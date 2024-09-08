package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Pair;

import com.limelight.LimeLog;
import com.limelight.binding.input.driver.UsbDriverListener;

import java.util.HashMap;
import java.util.Map;

public class XboxWirelessDongle {
    private UsbDriverListener listener;
    protected final UsbDevice device;
    protected final UsbDeviceConnection connection;

    private long driverHandle;

    private Map<Integer, AbstractController> controllers = new HashMap<>();

    static {
        System.loadLibrary("xow-driver");
    }

    public XboxWirelessDongle(UsbDevice device, UsbDeviceConnection connection, UsbDriverListener listener) {
        this.device = device;
        this.connection = connection;
        this.listener = listener;
        this.driverHandle = -1;
    }

    public boolean start() {
        if(this.driverHandle != -1) {
            return false; //we already started;
        }
        this.driverHandle = createDriver(connection.getFileDescriptor());
        boolean ok = startDriver(this.driverHandle, "");
        if(!ok) {
            LimeLog.info("xbox wireless dongle driver failed to start");
            destroyDriver(this.driverHandle);
            this.driverHandle = -1;
            return false;
        }
        return true;
    }

    public void stop() {
        if(this.driverHandle == -1) {
            return; //we already cleaned;
        }
        stopDriver(this.driverHandle);
        destroyDriver(this.driverHandle);
        for(var i: controllers.keySet()) {
            this.listener.deviceRemoved(controllers.remove(i));
        }
    }

    public static boolean canClaimDevice(UsbDevice device) {
        if (device.getVendorId() != 0x045e) {
            return false;
        }
        if (device.getProductId() != 0x02e6 &&  // Older one
                device.getProductId() != 0x02fe // new one
        ) {
            return false;
        }

        return true;
    }

    public void addNewController(int id, long handle, short vid, short pid){
        var controller = new XboxWirelessController(id + 0x045e0000, listener, vid, pid, handle);
        controllers.put(id, controller);
        this.listener.deviceAdded(controller);
    }

    public void removeController(int id) {
        var controller = controllers.get(id);
        if(controller == null) {
            return;
        }
        controllers.remove(id);
        this.listener.deviceRemoved(controller);
    }

    private native long createDriver(int fd);
    private native boolean startDriver(long handle, String fwPath);
    private native void stopDriver(long handle);
    private native void destroyDriver(long handle);
}
