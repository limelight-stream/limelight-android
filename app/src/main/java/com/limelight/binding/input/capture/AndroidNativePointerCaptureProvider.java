package com.limelight.binding.input.capture;

import android.annotation.TargetApi;
import android.app.Activity;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;


// We extend AndroidPointerIconCaptureProvider because we want to also get the
// pointer icon hiding behavior over our stream view just in case pointer capture
// is unavailable on this system (ex: DeX, ChromeOS)
@TargetApi(Build.VERSION_CODES.O)
public class AndroidNativePointerCaptureProvider extends AndroidPointerIconCaptureProvider implements InputManager.InputDeviceListener {
    private final InputManager inputManager;
    private final View targetView;

    public AndroidNativePointerCaptureProvider(Activity activity, View targetView) {
        super(activity, targetView);
        this.inputManager = activity.getSystemService(InputManager.class);
        this.targetView = targetView;
    }

    public static boolean isCaptureProviderSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    // We only capture the pointer if we have a compatible InputDevice
    // present. This is a workaround for an Android 12 regression causing
    // incorrect mouse input when using the SPen.
    // https://github.com/moonlight-stream/moonlight-android/issues/1030
    private boolean hasCaptureCompatibleInputDevice() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) {
                continue;
            }

            // Skip touchscreens when considering compatible capture devices.
            // Samsung devices on Android 12 will report a sec_touchpad device
            // with SOURCE_TOUCHSCREEN, SOURCE_KEYBOARD, and SOURCE_MOUSE.
            // Upon enabling pointer capture, that device will switch to
            // SOURCE_KEYBOARD and SOURCE_TOUCHPAD.
            // Only skip on non ChromeOS devices cause the ChromeOS pointer else
            // gets disabled removing relative mouse capabilities
            // on Chromebooks with touchscreens
            if (device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) && !targetView.getContext().getPackageManager().hasSystemFeature("org.chromium.arc.device_management")) {
                continue;
            }

            if (device.supportsSource(InputDevice.SOURCE_MOUSE) ||
                    device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                    device.supportsSource(InputDevice.SOURCE_TOUCHPAD)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void showCursor() {
        super.showCursor();

        // It is important to unregister the listener *before* releasing pointer capture,
        // because releasing pointer capture can cause an onInputDeviceChanged() callback
        // for devices with a touchpad (like a DS4 controller).
        inputManager.unregisterInputDeviceListener(this);
        targetView.releasePointerCapture();
    }

    @Override
    public void hideCursor() {
        super.hideCursor();

        // Listen for device events to enable/disable capture
        inputManager.registerInputDeviceListener(this, null);

        // Capture now if we have a capture-capable device
        if (hasCaptureCompatibleInputDevice()) {
            targetView.requestPointerCapture();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focusActive) {
        // NB: We have to check cursor visibility here because Android pointer capture
        // doesn't support capturing the cursor while it's visible. Enabling pointer
        // capture implicitly hides the cursor.
        if (!focusActive || !isCapturing || isCursorVisible) {
            return;
        }

        // Recapture the pointer if focus was regained. On Android Q,
        // we have to delay a bit before requesting capture because otherwise
        // we'll hit the "requestPointerCapture called for a window that has no focus"
        // error and it will not actually capture the cursor.
        Handler h = new Handler();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (hasCaptureCompatibleInputDevice()) {
                    targetView.requestPointerCapture();
                }
            }
        }, 500);
    }

    @Override
    public boolean eventHasRelativeMouseAxes(MotionEvent event) {
        // SOURCE_MOUSE_RELATIVE is how SOURCE_MOUSE appears when our view has pointer capture.
        // SOURCE_TOUCHPAD will have relative axes populated iff our view has pointer capture.
        // See https://developer.android.com/reference/android/view/View#requestPointerCapture()
        int eventSource = event.getSource();
        return (eventSource == InputDevice.SOURCE_MOUSE_RELATIVE && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) ||
                (eventSource == InputDevice.SOURCE_TOUCHPAD && targetView.hasPointerCapture());
    }

    @Override
    public float getRelativeAxisX(MotionEvent event) {
        int axis = (event.getSource() == InputDevice.SOURCE_MOUSE_RELATIVE) ?
                MotionEvent.AXIS_X : MotionEvent.AXIS_RELATIVE_X;
        float x = event.getAxisValue(axis);
        for (int i = 0; i < event.getHistorySize(); i++) {
            x += event.getHistoricalAxisValue(axis, i);
        }
        return x;
    }

    @Override
    public float getRelativeAxisY(MotionEvent event) {
        int axis = (event.getSource() == InputDevice.SOURCE_MOUSE_RELATIVE) ?
                MotionEvent.AXIS_Y : MotionEvent.AXIS_RELATIVE_Y;
        float y = event.getAxisValue(axis);
        for (int i = 0; i < event.getHistorySize(); i++) {
            y += event.getHistoricalAxisValue(axis, i);
        }
        return y;
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        // Check if we've added a capture-compatible device
        if (!targetView.hasPointerCapture() && hasCaptureCompatibleInputDevice()) {
            targetView.requestPointerCapture();
        }
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        // Check if the capture-compatible device was removed
        if (targetView.hasPointerCapture() && !hasCaptureCompatibleInputDevice()) {
            targetView.releasePointerCapture();
        }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        // Emulating a remove+add should be sufficient for our purposes.
        //
        // Note: This callback must be handled carefully because it can happen as a result of
        // calling requestPointerCapture(). This can cause trackpad devices to gain SOURCE_MOUSE_RELATIVE
        // and re-enter this callback.
        onInputDeviceRemoved(deviceId);
        onInputDeviceAdded(deviceId);
    }
}
