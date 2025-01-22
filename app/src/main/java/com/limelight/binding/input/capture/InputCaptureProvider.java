package com.limelight.binding.input.capture;

import android.view.MotionEvent;

public abstract class InputCaptureProvider {
    protected boolean isCapturing;
    protected boolean isCursorVisible;

    public void enableCapture() {
        isCapturing = true;
        hideCursor();
    }
    public void disableCapture() {
        isCapturing = false;
        showCursor();
    }

    public void destroy() {}

    public boolean isCapturingEnabled() {
        return isCapturing;
    }

    public boolean isCapturingActive() {
        return isCapturing;
    }

    public void showCursor() {
        isCursorVisible = true;
    }

    public void hideCursor() {
        isCursorVisible = false;
    }

    public boolean eventHasRelativeMouseAxes(MotionEvent event) {
        return false;
    }

    public float getRelativeAxisX(MotionEvent event) {
        return 0;
    }

    public float getRelativeAxisY(MotionEvent event) {
        return 0;
    }

    public void onWindowFocusChanged(boolean focusActive) {}
}
