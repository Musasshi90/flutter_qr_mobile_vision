package com.github.rmtmckenzie.qrmobilevision;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.util.Log;

import java.io.IOException;
import java.util.List;

/**
 * Implements QrCamera using Deprecated Camera API
 */
@TargetApi(16)
@SuppressWarnings("deprecation")
class QrCameraC1 implements QrCamera {

    private static final String TAG = "c.g.r.QrCameraC1";
    private final SurfaceTexture texture;
    private final QrDetector detector;
    private Camera.CameraInfo info = new Camera.CameraInfo();
    private int targetWidth, targetHeight;
    private Camera camera = null;

    QrCameraC1(int width, int height, SurfaceTexture texture, QrDetector detector) {
        this.texture = texture;
        targetHeight = height;
        targetWidth = width;
        this.detector = detector;
    }

    @Override
    public void start() throws QrReader.Exception {
        int numberOfCameras = Camera.getNumberOfCameras();
        info = new Camera.CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                camera = Camera.open(i);
            }
        }

        if (camera == null) {
            throw new QrReader.Exception(QrReader.Exception.Reason.noBackCamera);
        }

        Camera.Parameters parameters = camera.getParameters();

        List<String> focusModes = parameters.getSupportedFocusModes();
        if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            Log.i(TAG, "Initializing with autofocus on.");
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        } else {
            Log.i(TAG, "Initializing with autofocus off as not supported.");
        }

        List<Size> supportedSizes = parameters.getSupportedPreviewSizes();
        Size size = getAppropriateSize(supportedSizes);

        parameters.setPreviewSize(size.width, size.height);

        texture.setDefaultBufferSize(size.width, size.height);

        detector.useNV21(size.width, size.height);

        try {
            camera.setPreviewCallback(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    if (data != null) detector.detect(data);
                    else System.out.println("It's NULL!");
                }
            });
            camera.setPreviewTexture(texture);
            camera.startPreview();
            camera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public int getWidth() {
        return camera.getParameters().getPreviewSize().width;
    }

    @Override
    public int getHeight() {
        return camera.getParameters().getPreviewSize().height;
    }

    @Override
    public int getOrientation() {
        return info.orientation;
    }

    @Override
    public void stop() {
        camera.stopPreview();
        camera.setPreviewCallback(null);
        camera.release();
    }

    //Size here is Camera.Size, not android.util.Size as in the QrCameraC2 version of this method
    private Size getAppropriateSize(List<Size> sizes) {
        final int MAX_WIDTH = 1280;
        final float TARGET_ASPECT = 16.f / 9.f;
        final float ASPECT_TOLERANCE = 0.1f;

        Size outputSize = sizes.get(0);
        float outputAspect = (float) outputSize.width / outputSize.height;
        for (Size candidateSize : sizes) {
            if (candidateSize.width > MAX_WIDTH) continue;
            float candidateAspect = (float) candidateSize.width / candidateSize.height;
            boolean goodCandidateAspect =
                Math.abs(candidateAspect - TARGET_ASPECT) < ASPECT_TOLERANCE;
            boolean goodOutputAspect =
                Math.abs(outputAspect - TARGET_ASPECT) < ASPECT_TOLERANCE;
            if ((goodCandidateAspect && !goodOutputAspect) ||
                candidateSize.width > outputSize.height) {
                outputSize = candidateSize;
                outputAspect = candidateAspect;
            }
        }
        Log.i(TAG, "Resolution chosen: " + outputSize);
        return outputSize;
    }
}
