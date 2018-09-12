package com.github.rmtmckenzie.qrmobilevision;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO;
import static android.hardware.camera2.CameraMetadata.LENS_FACING_BACK;

/**
 * Implements QrCamera using Camera2 API
 */
@TargetApi(21)
class QrCameraC2 implements QrCamera {

    private static final String TAG = "c.g.r.QrCameraC2";
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private final int targetWidth;
    private final int targetHeight;
    private final Context context;
    private final SurfaceTexture texture;
    private Size size;
    private ImageReader reader;
    private CaptureRequest.Builder previewBuilder;
    private CameraCaptureSession previewSession;
    private Size jpegSizes[] = null;
    private QrDetector detector;
    private int orientation;
    private CameraDevice cameraDevice;

    private Integer mLastAfState = null;
    private static final long LOCK_FOCUS_DELAY_ON_FOCUSED = 2000;
    private static final long LOCK_FOCUS_DELAY_ON_UNFOCUSED = 1000;
    private Handler mUiHandler = new Handler(); // UI handler
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private Runnable mLockAutoFocusRunnable = new Runnable() {
        @Override
        public void run() {
            lockAutoFocus();
        }
    };
    private Runnable mBackgroundHandlerRunnable = new Runnable() {
        @Override
        public void run() {
            try (Image image = reader.acquireLatestImage()) {
                if (image == null)
                    return;
                Image.Plane[] planes = image.getPlanes();

//                    ByteBuffer b1 = planes[0].getBuffer(),
//                            b2 = planes[1].getBuffer(),
//                            b3 = planes[2].getBuffer();

                ByteBuffer buffer = planes[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);

//                    ByteBuffer bAll = ByteBuffer.allocateDirect(b1.remaining() + b2.remaining() + b3.remaining());
//                    bAll.put(b1);
//                    bAll.put(b3);
//                    bAll.put(b2);

                detector.detect(bytes);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    };

    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
            if (afState != null && !afState.equals(mLastAfState)) {
                switch (afState) {
                    case CaptureResult.CONTROL_AF_STATE_INACTIVE:
                        Log.d(TAG, "CaptureResult.CONTROL_AF_STATE_INACTIVE");
                        lockAutoFocus();
                        break;
                    case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN:
                        Log.d(TAG, "CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN");
                        break;
                    case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:
                        Log.d(TAG, "CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED");
                        mUiHandler.removeCallbacks(mLockAutoFocusRunnable);
                        mUiHandler.postDelayed(mLockAutoFocusRunnable, LOCK_FOCUS_DELAY_ON_FOCUSED);
                        break;
                    case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:
                        mUiHandler.removeCallbacks(mLockAutoFocusRunnable);
                        mUiHandler.postDelayed(mLockAutoFocusRunnable, LOCK_FOCUS_DELAY_ON_UNFOCUSED);
                        Log.d(TAG, "CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED");
                        break;
                    case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED:
                        mUiHandler.removeCallbacks(mLockAutoFocusRunnable);
                        //mUiHandler.postDelayed(mLockAutoFocusRunnable, LOCK_FOCUS_DELAY_ON_UNFOCUSED);
                        Log.d(TAG, "CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED");
                        break;
                    case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN:
                        Log.d(TAG, "CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN");
                        break;
                    case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED:
                        mUiHandler.removeCallbacks(mLockAutoFocusRunnable);
                        //mUiHandler.postDelayed(mLockAutoFocusRunnable, LOCK_FOCUS_DELAY_ON_FOCUSED);
                        Log.d(TAG, "CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED");
                        break;
                }
            }
            mLastAfState = afState;
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }
    };

    QrCameraC2(int width, int height, Context context, SurfaceTexture texture, QrDetector detector) {
        this.targetWidth = width;
        this.targetHeight = height;
        this.context = context;
        this.texture = texture;
        this.detector = detector;
    }

    @Override
    public int getWidth() {
        return size.getWidth();
    }

    @Override
    public int getHeight() {
        return size.getHeight();
    }

    @Override
    public int getOrientation() {
        return orientation;
    }

    @Override
    public void start() throws QrReader.Exception {
        startBackgroundThread();
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        String cameraId = null;
        try {
            String[] cameraIdList = manager.getCameraIdList();
            for (String id : cameraIdList) {
                CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics(id);
                Integer integer = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (integer != null && integer == LENS_FACING_BACK) {
                    cameraId = id;

                }
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "Error getting back camera.", e);
            throw new RuntimeException(e);
        }


        if (cameraId == null) {
            throw new QrReader.Exception(QrReader.Exception.Reason.noBackCamera);
        }

        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            // it seems as though the orientation is already corrected, so setting to 0
            // orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            orientation = 0;

            size = getAppropriateSize(map.getOutputSizes(SurfaceTexture.class));
            //size = map.getOutputSizes(SurfaceTexture.class)[0];
            jpegSizes = map.getOutputSizes(ImageFormat.JPEG);

            final boolean finalSupportsAutoFocus = isAutoFocusSupported(cameraId);
            final String cameraNewId = cameraId;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    startCamera(finalSupportsAutoFocus, cameraNewId);
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    Log.w(TAG, "Error opening camera: " + error);
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.w(TAG, "Error getting camera configuration.", e);
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        if (mBackgroundThread == null) return;
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void lockAutoFocus() {
        if (previewBuilder == null) return;
        try {
            // This is how to tell the camera to lock focus.
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            CaptureRequest captureRequest = previewBuilder.build();
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null); // prevent CONTROL_AF_TRIGGER_START from calling over and over again
            previewSession.capture(captureRequest, mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * @return
     */
    private float getMinimumFocusDistance(String cameraId) {
        if (cameraId == null)
            return 0;

        Float minimumLens = null;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
            minimumLens = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        } catch (Exception e) {
            Log.e(TAG, "isHardwareLevelSupported Error", e);
        }
        if (minimumLens != null)
            return minimumLens;
        return 0;
    }

    /**
     * @return
     */
    private boolean isAutoFocusSupported(String cameraId) {
        return isHardwareLevelSupported(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY, cameraId) || getMinimumFocusDistance(cameraId) > 0;
    }

    // Returns true if the device supports the required hardware level, or better.
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private boolean isHardwareLevelSupported(int requiredLevel, String cameraId) {
        boolean res = false;
        if (cameraId == null)
            return res;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics(cameraId);

            int deviceLevel = cameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            switch (deviceLevel) {
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                    Log.d(TAG, "Camera support level: INFO_SUPPORTED_HARDWARE_LEVEL_3");
                    break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                    Log.d(TAG, "Camera support level: INFO_SUPPORTED_HARDWARE_LEVEL_FULL");
                    break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                    Log.d(TAG, "Camera support level: INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY");
                    break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                    Log.d(TAG, "Camera support level: INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED");
                    break;
                default:
                    Log.d(TAG, "Unknown INFO_SUPPORTED_HARDWARE_LEVEL: " + deviceLevel);
                    break;
            }


            if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                res = requiredLevel == deviceLevel;
            } else {
                // deviceLevel is not LEGACY, can use numerical sort
                res = requiredLevel <= deviceLevel;
            }

        } catch (Exception e) {
            Log.e(TAG, "isHardwareLevelSupported Error", e);
        }
        return res;
    }

    private void startCamera(boolean supportsAutofocus, String cameraId) {
        List<Surface> list = new ArrayList<>();

        Size jpegSize = getAppropriateSize(jpegSizes);

        int width = jpegSize.getWidth(), height = jpegSize.getHeight();

        reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
        list.add(reader.getSurface());


        ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {

            @Override
            public void onImageAvailable(ImageReader reader) {
                mBackgroundHandler.post(mBackgroundHandlerRunnable);
            }
        };

        reader.setOnImageAvailableListener(imageAvailableListener, null);
        texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        list.add(new Surface(texture));
        try {
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(list.get(0));
            previewBuilder.addTarget(list.get(1));

            if (supportsAutofocus) {
                previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            }
            previewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
//            previewBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(orientation));
        } catch (java.lang.Exception e) {
            e.printStackTrace();
            return;
        }


        try {
            cameraDevice.createCaptureSession(list, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    previewSession = session;
                    startPreview();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    System.out.println("### Configuration Fail ###");
                }
            }, null);
        } catch (Throwable t) {
            t.printStackTrace();

        }
    }

    private void startPreview() {
        if (cameraDevice == null) return;
        if (previewSession == null) return;
        try {
            previewSession.setRepeatingRequest(previewBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (java.lang.Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stop() {
        stopBackgroundThread();
        if (cameraDevice != null) {
            cameraDevice.close();
        }
        if (previewBuilder != null) {
            previewBuilder = null;
        }
        if (previewSession != null) {
            previewSession.close();
            previewSession = null;
        }
        if (reader != null) {
            reader.close();
        }
    }

    private Size getAppropriateSize(Size[] sizes) {
        // assume sizes is never 0
        if (sizes.length == 1) {
            return sizes[0];
        }

        Size s = sizes[0];
        Size s1 = sizes[1];

        if (s1.getWidth() > s.getWidth() || s1.getHeight() > s.getHeight()) {
            // ascending
            if (orientation % 180 == 0) {
                for (Size size : sizes) {
                    s = size;
                    if (size.getHeight() > targetHeight && size.getWidth() > targetWidth) {
                        break;
                    }
                }
            } else {
                for (Size size : sizes) {
                    s = size;
                    if (size.getHeight() > targetWidth && size.getWidth() > targetHeight) {
                        break;
                    }
                }
            }
        } else {
            // descending
            if (orientation % 180 == 0) {
                for (Size size : sizes) {
                    if (size.getHeight() < targetHeight || size.getWidth() < targetWidth) {
                        break;
                    }
                    s = size;
                }
            } else {
                for (Size size : sizes) {
                    if (size.getHeight() < targetWidth || size.getWidth() < targetHeight) {
                        break;
                    }
                    s = size;
                }
            }
        }
        return s;
    }
}
