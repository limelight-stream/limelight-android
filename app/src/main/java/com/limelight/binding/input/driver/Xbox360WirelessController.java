package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;

import java.nio.ByteBuffer;

public class Xbox360WirelessController extends AbstractXboxController {
    private static final int XB360W_IFACE_SUBCLASS = 93;
    private static final int XB360W_IFACE_PROTOCOL = 129; // Wireless only

    private static final int[] SUPPORTED_VENDORS = {
            0x045e, // Microsoft
    };

    public static boolean canClaimDevice(UsbDevice device) {
        for (int supportedVid : SUPPORTED_VENDORS) {
            if (device.getVendorId() == supportedVid &&
                    device.getInterfaceCount() >= 1 &&
                    device.getInterface(0).getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    device.getInterface(0).getInterfaceSubclass() == XB360W_IFACE_SUBCLASS &&
                    device.getInterface(0).getInterfaceProtocol() == XB360W_IFACE_PROTOCOL) {
                return true;
            }
        }

        return false;
    }

    public Xbox360WirelessController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(device, connection, deviceId, listener);
    }

    private int unsignByte(byte b) {
        if (b < 0) {
            return b + 256;
        }
        else {
            return b;
        }
    }

    @Override
    protected boolean handleRead(ByteBuffer buffer) {
        if (buffer.remaining() < 14) {
            LimeLog.severe("Read too small: "+buffer.remaining());
            return false;
        }

        // Skip first short
        buffer.position(buffer.position() + 2);

        // DPAD
        byte b = buffer.get();
        setButtonFlag(ControllerPacket.LEFT_FLAG, b & 0x04);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, b & 0x08);
        setButtonFlag(ControllerPacket.UP_FLAG, b & 0x01);
        setButtonFlag(ControllerPacket.DOWN_FLAG, b & 0x02);

        // Start/Select
        setButtonFlag(ControllerPacket.PLAY_FLAG, b & 0x10);
        setButtonFlag(ControllerPacket.BACK_FLAG, b & 0x20);

        // LS/RS
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b & 0x40);
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b & 0x80);

        // ABXY buttons
        b = buffer.get();
        setButtonFlag(ControllerPacket.A_FLAG, b & 0x10);
        setButtonFlag(ControllerPacket.B_FLAG, b & 0x20);
        setButtonFlag(ControllerPacket.X_FLAG, b & 0x40);
        setButtonFlag(ControllerPacket.Y_FLAG, b & 0x80);

        // LB/RB
        setButtonFlag(ControllerPacket.LB_FLAG, b & 0x01);
        setButtonFlag(ControllerPacket.RB_FLAG, b & 0x02);

        // Xbox button
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b & 0x04);

        // Triggers
        leftTrigger = unsignByte(buffer.get()) / 255.0f;
        rightTrigger = unsignByte(buffer.get()) / 255.0f;

        // Left stick
        leftStickX = buffer.getShort() / 32767.0f;
        leftStickY = ~buffer.getShort() / 32767.0f;

        // Right stick
        rightStickX = buffer.getShort() / 32767.0f;
        rightStickY = ~buffer.getShort() / 32767.0f;

        // Return true to send input
        return true;
    }

    private boolean sendLedCommand(byte command) {
        byte[] commandBuffer = {
                0x00,
                0x00,
                0x08,
                (byte) (0x40 + (byte)(2 + (getControllerId() % 4))),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00};

        int res = connection.bulkTransfer(outEndpt, commandBuffer, commandBuffer.length, 3000);
        if (res != commandBuffer.length) {
            LimeLog.warning("LED set transfer failed: "+res);
            return false;
        }

        return true;
    }

    @Override
    protected boolean doInit() {
        // Turn the LED on corresponding to our device ID
        sendLedCommand((byte)(2 + (getControllerId() % 4)));

        connection.releaseInterface(device.getInterface(0));

        // Return control to the kernel.
        return false;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        byte[] data = {
                0x00, 0x08, 0x00,
                (byte)(lowFreqMotor >> 8), (byte)(highFreqMotor >> 8),
                0x00, 0x00, 0x00
        };
        int res = connection.bulkTransfer(outEndpt, data, data.length, 100);
        if (res != data.length) {
            LimeLog.warning("Rumble transfer failed: "+res);
        }
    }
}
