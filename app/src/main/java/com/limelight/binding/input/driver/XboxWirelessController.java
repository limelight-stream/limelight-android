package com.limelight.binding.input.driver;

public class XboxWirelessController extends AbstractController{
    static {
        System.loadLibrary("xow-driver");
    }

    private final long handle;

    public XboxWirelessController(int deviceId, UsbDriverListener listener, int vendorId, int productId, long handle) {
        super(deviceId, listener, vendorId, productId);
        this.handle = handle;
        registerNative(this.handle);
    }

    @Override
    public boolean start() {
        // do nothing since mt driver will handle it.
        return true;
    }

    @Override
    public void stop() {
        // do nothing since mt driver will handle it.
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        sendRumble(handle, lowFreqMotor, highFreqMotor);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        sendrumbleTriggers(handle, leftTrigger, rightTrigger);
    }

    public void updateInput(int buttons,short triggerLeft, short triggerRight,
                            short stickLeftX, short stickLeftY,
                            short stickRightX, short stickRightY) {
        buttonFlags = buttons;
        leftTrigger = triggerLeft / 1023.0f;
        rightTrigger = triggerRight / 1023.0f;
        leftStickX = stickLeftX / 32767.0f;
        leftStickY = stickLeftY / -32767.0f;
        rightStickX = stickRightX / 32767.0f;
        rightStickY = stickRightY / -32767.0f;

        reportInput();
    }

    native void registerNative(long handle);
    native void sendRumble(long handle, short lowFreqMotor, short highFreqMotor);
    native void sendrumbleTriggers(long handle, short leftTrigger, short rightTrigger);

}
