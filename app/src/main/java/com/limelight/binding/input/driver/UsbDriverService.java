package com.limelight.binding.input.driver;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.view.InputDevice;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import java.io.File;
import java.util.ArrayList;

public class UsbDriverService extends Service implements UsbDriverListener {

    private static final String ACTION_USB_PERMISSION =
            "com.limelight.USB_PERMISSION";

    private UsbManager usbManager;
    private PreferenceConfiguration prefConfig;
    private boolean started;

    private final UsbEventReceiver receiver = new UsbEventReceiver();
    private final UsbDriverBinder binder = new UsbDriverBinder();

    private final ArrayList<AbstractController> controllers = new ArrayList<>();

    private UsbDriverListener listener;
    private UsbDriverStateListener stateListener;
    private int nextDeviceId;

    private final ArrayList<XboxWirelessDongle> xboxWirelessDongles = new ArrayList<>();

    @Override
    public void reportControllerState(int controllerId, int buttonFlags, float leftStickX, float leftStickY,
                                      float rightStickX, float rightStickY, float leftTrigger, float rightTrigger) {
        // Call through to the client's listener
        if (listener != null) {
            listener.reportControllerState(controllerId, buttonFlags, leftStickX, leftStickY, rightStickX, rightStickY, leftTrigger, rightTrigger);
        }
    }

    @Override
    public void deviceRemoved(AbstractController controller) {
        // Remove the the controller from our list (if not removed already)
        controllers.remove(controller);

        // Call through to the client's listener
        if (listener != null) {
            listener.deviceRemoved(controller);
        }
    }

    @Override
    public void deviceAdded(AbstractController controller) {
        // Call through to the client's listener
        if (listener != null) {
            listener.deviceAdded(controller);
        }
    }

    public class UsbEventReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // Initial attachment broadcast
            if (action.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                // shouldClaimDevice() looks at the kernel's enumerated input
                // devices to make its decision about whether to prompt to take
                // control of the device. The kernel bringing up the input stack
                // may race with this callback and cause us to prompt when the
                // kernel is capable of running the device. Let's post a delayed
                // message to process this state change to allow the kernel
                // some time to bring up the stack.
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // Continue the state machine
                        handleUsbDeviceState(device);
                    }
                }, 1000);
            }
            // Subsequent permission dialog completion intent
            else if (action.equals(ACTION_USB_PERMISSION)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                // Permission dialog is now closed
                if (stateListener != null) {
                    stateListener.onUsbPermissionPromptCompleted();
                }

                // If we got this far, we've already found we're able to handle this device
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    handleUsbDeviceState(device);
                }
            }
        }
    }

    public class UsbDriverBinder extends Binder {
        public void setListener(UsbDriverListener listener) {
            UsbDriverService.this.listener = listener;

            // Report all controllerMap that already exist
            if (listener != null) {
                for (AbstractController controller : controllers) {
                    listener.deviceAdded(controller);
                }
            }
        }

        public void setStateListener(UsbDriverStateListener stateListener) {
            UsbDriverService.this.stateListener = stateListener;
        }

        public void start() {
            UsbDriverService.this.start();
        }

        public void stop() {
            UsbDriverService.this.stop();
        }
    }

    private void handleUsbDeviceState(UsbDevice device) {
        // Are we able to operate it?
        if (shouldClaimDevice(device, prefConfig.bindAllUsb)) {
            // Do we have permission yet?
            if (!usbManager.hasPermission(device)) {
                // Let's ask for permission
                try {
                    // Tell the state listener that we're about to display a permission dialog
                    if (stateListener != null) {
                        stateListener.onUsbPermissionPromptStarting();
                    }

                    int intentFlags = 0;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // This PendingIntent must be mutable to allow the framework to populate EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED.
                        intentFlags |= PendingIntent.FLAG_MUTABLE;
                    }

                    // This function is not documented as throwing any exceptions (denying access
                    // is indicated by calling the PendingIntent with a false result). However,
                    // Samsung Knox has some policies which block this request, but rather than
                    // just returning a false result or returning 0 enumerated devices,
                    // they throw an undocumented SecurityException from this call, crashing
                    // the whole app. :(

                    // Use an explicit intent to activate our unexported broadcast receiver, as required on Android 14+
                    Intent i = new Intent(ACTION_USB_PERMISSION);
                    i.setPackage(getPackageName());

                    usbManager.requestPermission(device, PendingIntent.getBroadcast(UsbDriverService.this, 0, i, intentFlags));
                } catch (SecurityException e) {
                    Toast.makeText(this, this.getText(R.string.error_usb_prohibited), Toast.LENGTH_LONG).show();
                    if (stateListener != null) {
                        stateListener.onUsbPermissionPromptCompleted();
                    }
                }
                return;
            }

            // Open the device
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                LimeLog.warning("Unable to open USB device: "+device.getDeviceName());
                return;
            }

            if (XboxWirelessDongle.canClaimDevice(device)) {
                var dongle = new XboxWirelessDongle(device, connection, this);
                if(!dongle.start()) {
                    connection.close();
                    return;
                }
                xboxWirelessDongles.add(dongle);
                return;
            }

            AbstractController controller;

            if (XboxOneController.canClaimDevice(device)) {
                controller = new XboxOneController(device, connection, nextDeviceId++, this);
            }
            else if (Xbox360Controller.canClaimDevice(device)) {
                controller = new Xbox360Controller(device, connection, nextDeviceId++, this);
            }
            else if (Xbox360WirelessDongle.canClaimDevice(device)) {
                controller = new Xbox360WirelessDongle(device, connection, nextDeviceId++, this);
            }
            else {
                // Unreachable
                return;
            }

            // Start the controller
            if (!controller.start()) {
                connection.close();
                return;
            }

            // Add this controller to the list
            controllers.add(controller);
        }
    }

    public static boolean isRecognizedInputDevice(UsbDevice device) {
        // Determine if this VID and PID combo matches an existing input device
        // and defer to the built-in controller support in that case.
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice inputDev = InputDevice.getDevice(id);
            if (inputDev == null) {
                // Device was removed while looping
                continue;
            }

            if (inputDev.getVendorId() == device.getVendorId() &&
                    inputDev.getProductId() == device.getProductId()) {
                return true;
            }
        }

        return false;
    }

    public static boolean kernelSupportsXboxOne() {
        String kernelVersion = System.getProperty("os.version");
        LimeLog.info("Kernel Version: "+kernelVersion);

        if (kernelVersion == null) {
            // We'll assume this is some newer version of Android
            // that doesn't let you read the kernel version this way.
            return true;
        }
        else if (kernelVersion.startsWith("2.") || kernelVersion.startsWith("3.")) {
            // These are old kernels that definitely don't support Xbox One controllers properly
            return false;
        }
        else if (kernelVersion.startsWith("4.4.") || kernelVersion.startsWith("4.9.")) {
            // These aren't guaranteed to have backported kernel patches for proper Xbox One
            // support (though some devices will).
            return false;
        }
        else {
            // The next AOSP common kernel is 4.14 which has working Xbox One controller support
            return true;
        }
    }

    public static boolean kernelSupportsXbox360W() {
        // Check if this kernel is 4.2+ to see if the xpad driver sets Xbox 360 wireless LEDs
        // https://github.com/torvalds/linux/commit/75b7f05d2798ee3a1cc5bbdd54acd0e318a80396
        String kernelVersion = System.getProperty("os.version");
        if (kernelVersion != null) {
            if (kernelVersion.startsWith("2.") || kernelVersion.startsWith("3.") ||
                    kernelVersion.startsWith("4.0.") || kernelVersion.startsWith("4.1.")) {
                // Even if LED devices are present, the driver won't set the initial LED state.
                return false;
            }
        }

        // We know we have a kernel that should set Xbox 360 wireless LEDs, but we still don't
        // know if CONFIG_JOYSTICK_XPAD_LEDS was enabled during the kernel build. Unfortunately
        // it's not possible to detect this reliably due to Android's app sandboxing. Reading
        // /proc/config.gz and enumerating /sys/class/leds are both blocked by SELinux on any
        // relatively modern device. We will assume that CONFIG_JOYSTICK_XPAD_LEDS=y on these
        // kernels and users can override by using the settings option to claim all devices.
        return true;
    }

    public static boolean shouldClaimDevice(UsbDevice device, boolean claimAllAvailable) {
        return ((!kernelSupportsXboxOne() || !isRecognizedInputDevice(device) || claimAllAvailable) && XboxOneController.canClaimDevice(device)) ||
                ((!isRecognizedInputDevice(device) || claimAllAvailable) && Xbox360Controller.canClaimDevice(device)) ||
                // We must not call isRecognizedInputDevice() because wireless controllers don't share the same product ID as the dongle
                ((!kernelSupportsXbox360W() || claimAllAvailable) && Xbox360WirelessDongle.canClaimDevice(device) ||
                        XboxWirelessDongle.canClaimDevice(device));
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void start() {
        if (started || usbManager == null) {
            return;
        }

        started = true;

        // Register for USB attach broadcasts and permission completions
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        }
        else {
            registerReceiver(receiver, filter);
        }

        // Enumerate existing devices
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            if (shouldClaimDevice(dev, prefConfig.bindAllUsb)) {
                // Start the process of claiming this device
                handleUsbDeviceState(dev);
            }
        }
    }

    private void stop() {
        if (!started) {
            return;
        }

        started = false;

        // Stop the attachment receiver
        unregisterReceiver(receiver);

        // Stop all dongles
        while (xboxWirelessDongles.size() > 0) {
            // Stop and remove the dongle
            xboxWirelessDongles.remove(0).stop();
        }

        // Stop all controllers
        while (controllers.size() > 0) {
            // Stop and remove the controller
            controllers.remove(0).stop();
        }
    }

    @Override
    public void onCreate() {
        this.usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        this.prefConfig = PreferenceConfiguration.readPreferences(this);
    }

    @Override
    public void onDestroy() {
        stop();

        // Remove listeners
        listener = null;
        stateListener = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public interface UsbDriverStateListener {
        void onUsbPermissionPromptStarting();
        void onUsbPermissionPromptCompleted();
    }
}
