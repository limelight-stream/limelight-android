package com.limelight.binding.input;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.hardware.BatteryState;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.driver.AbstractController;
import com.limelight.binding.input.driver.UsbDriverListener;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;
import com.limelight.utils.Vector2d;

import org.cgutman.shieldcontrollerextensions.SceChargingState;
import org.cgutman.shieldcontrollerextensions.SceConnectionType;
import org.cgutman.shieldcontrollerextensions.SceManager;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

public class ControllerHandler implements InputManager.InputDeviceListener, UsbDriverListener {

    private static final int MAXIMUM_BUMPER_UP_DELAY_MS = 100;

    private static final int START_DOWN_TIME_MOUSE_MODE_MS = 750;

    private static final int MINIMUM_BUTTON_DOWN_TIME_MS = 25;

    private static final int EMULATING_SPECIAL = 0x1;
    private static final int EMULATING_SELECT = 0x2;
    private static final int EMULATING_TOUCHPAD = 0x4;

    private static final short MAX_GAMEPADS = 16; // Limited by bits in activeGamepadMask

    private static final int BATTERY_RECHECK_INTERVAL_MS = 120 * 1000;

    private static final Map<Integer, Integer> ANDROID_TO_LI_BUTTON_MAP = Map.ofEntries(
            Map.entry(KeyEvent.KEYCODE_BUTTON_A, ControllerPacket.A_FLAG),
            Map.entry(KeyEvent.KEYCODE_BUTTON_B, ControllerPacket.B_FLAG),
            Map.entry(KeyEvent.KEYCODE_BUTTON_X, ControllerPacket.X_FLAG),
            Map.entry(KeyEvent.KEYCODE_BUTTON_Y, ControllerPacket.Y_FLAG),
            Map.entry(KeyEvent.KEYCODE_BUTTON_L1, ControllerPacket.LB_FLAG),
            Map.entry(KeyEvent.KEYCODE_BUTTON_R1, ControllerPacket.RB_FLAG),
            Map.entry(KeyEvent.KEYCODE_BUTTON_THUMBL, ControllerPacket.LS_CLK_FLAG),
            Map.entry(KeyEvent.KEYCODE_BUTTON_THUMBR, ControllerPacket.RS_CLK_FLAG),
            Map.entry(KeyEvent.KEYCODE_BUTTON_START, ControllerPacket.PLAY_FLAG),
            Map.entry(KeyEvent.KEYCODE_MENU, ControllerPacket.PLAY_FLAG),
            Map.entry(KeyEvent.KEYCODE_BUTTON_SELECT, ControllerPacket.BACK_FLAG),
            Map.entry(KeyEvent.KEYCODE_BACK, ControllerPacket.BACK_FLAG),
            Map.entry(KeyEvent.KEYCODE_BUTTON_MODE, ControllerPacket.SPECIAL_BUTTON_FLAG),

            // This is the Xbox Series X Share button
            Map.entry(KeyEvent.KEYCODE_MEDIA_RECORD, ControllerPacket.MISC_FLAG),

            // This is a weird one, but it's what Android does prior to 4.10 kernels
            // where DualShock/DualSense touchpads weren't mapped as separate devices.
            // https://android.googlesource.com/platform/frameworks/base/+/master/data/keyboards/Vendor_054c_Product_0ce6_fallback.kl
            // https://android.googlesource.com/platform/frameworks/base/+/master/data/keyboards/Vendor_054c_Product_09cc.kl
            Map.entry(KeyEvent.KEYCODE_BUTTON_1, ControllerPacket.TOUCHPAD_FLAG)

            // FIXME: Paddles?
    );

    private final Vector2d inputVector = new Vector2d();

    private final SparseArray<InputDeviceContext> inputDeviceContexts = new SparseArray<>();
    private final SparseArray<UsbDeviceContext> usbDeviceContexts = new SparseArray<>();

    private final NvConnection conn;
    private final Activity activityContext;
    private final double stickDeadzone;
    private final InputDeviceContext defaultContext = new InputDeviceContext();
    private final GameGestures gestures;
    private final InputManager inputManager;
    private final Vibrator deviceVibrator;
    private final VibratorManager deviceVibratorManager;
    private final SensorManager deviceSensorManager;
    private final SceManager sceManager;
    private final Handler mainThreadHandler;
    private final HandlerThread backgroundHandlerThread;
    private final Handler backgroundThreadHandler;
    private boolean hasGameController;
    private boolean stopped = false;

    private final PreferenceConfiguration prefConfig;
    private short currentControllers, initialControllers;

    public ControllerHandler(Activity activityContext, NvConnection conn, GameGestures gestures, PreferenceConfiguration prefConfig) {
        this.activityContext = activityContext;
        this.conn = conn;
        this.gestures = gestures;
        this.prefConfig = prefConfig;
        this.deviceVibrator = (Vibrator) activityContext.getSystemService(Context.VIBRATOR_SERVICE);
        this.deviceSensorManager = (SensorManager) activityContext.getSystemService(Context.SENSOR_SERVICE);
        this.inputManager = (InputManager) activityContext.getSystemService(Context.INPUT_SERVICE);
        this.mainThreadHandler = new Handler(Looper.getMainLooper());

        // Create a HandlerThread to process battery state updates. These can be slow enough
        // that they lead to ANRs if we do them on the main thread.
        this.backgroundHandlerThread = new HandlerThread("ControllerHandler");
        this.backgroundHandlerThread.start();
        this.backgroundThreadHandler = new Handler(backgroundHandlerThread.getLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.deviceVibratorManager = (VibratorManager) activityContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        }
        else {
            this.deviceVibratorManager = null;
        }

        this.sceManager = new SceManager(activityContext);
        this.sceManager.start();

        int deadzonePercentage = prefConfig.deadzonePercentage;

        int[] ids = InputDevice.getDeviceIds();
        for (int id : ids) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null) {
                // This device was removed during enumeration
                continue;
            }
            if ((dev.getSources() & InputDevice.SOURCE_JOYSTICK) != 0 ||
                    (dev.getSources() & InputDevice.SOURCE_GAMEPAD) != 0) {
                // This looks like a gamepad, but we'll check X and Y to be sure
                if (getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_X) != null &&
                    getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Y) != null) {
                    // This is a gamepad
                    hasGameController = true;
                }
            }
        }

        // 1% is the lowest possible deadzone we support
        if (deadzonePercentage <= 0) {
            deadzonePercentage = 1;
        }

        this.stickDeadzone = (double)deadzonePercentage / 100.0;

        // Initialize the default context for events with no device
        defaultContext.leftStickXAxis = MotionEvent.AXIS_X;
        defaultContext.leftStickYAxis = MotionEvent.AXIS_Y;
        defaultContext.leftStickDeadzoneRadius = (float) stickDeadzone;
        defaultContext.rightStickXAxis = MotionEvent.AXIS_Z;
        defaultContext.rightStickYAxis = MotionEvent.AXIS_RZ;
        defaultContext.rightStickDeadzoneRadius = (float) stickDeadzone;
        defaultContext.leftTriggerAxis = MotionEvent.AXIS_BRAKE;
        defaultContext.rightTriggerAxis = MotionEvent.AXIS_GAS;
        defaultContext.hatXAxis = MotionEvent.AXIS_HAT_X;
        defaultContext.hatYAxis = MotionEvent.AXIS_HAT_Y;
        defaultContext.controllerNumber = (short) 0;
        defaultContext.assignedControllerNumber = true;
        defaultContext.external = false;

        // Some devices (GPD XD) have a back button which sends input events
        // with device ID == 0. This hits the default context which would normally
        // consume these. Instead, let's ignore them since that's probably the
        // most likely case.
        defaultContext.ignoreBack = true;

        // Get the initially attached set of gamepads. As each gamepad receives
        // its initial InputEvent, we will move these from this set onto the
        // currentControllers set which will allow them to properly unplug
        // if they are removed.
        initialControllers = getAttachedControllerMask(activityContext);

        // Register ourselves for input device notifications
        inputManager.registerInputDeviceListener(this, null);
    }

    private static InputDevice.MotionRange getMotionRangeForJoystickAxis(InputDevice dev, int axis) {
        InputDevice.MotionRange range;

        // First get the axis for SOURCE_JOYSTICK
        range = dev.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK);
        if (range == null) {
            // Now try the axis for SOURCE_GAMEPAD
            range = dev.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD);
        }

        return range;
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        // Nothing happening here yet
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        InputDeviceContext context = inputDeviceContexts.get(deviceId);
        if (context != null) {
            LimeLog.info("Removed controller: "+context.name+" ("+deviceId+")");
            releaseControllerNumber(context);
            context.destroy();
            inputDeviceContexts.remove(deviceId);
        }
    }

    // This can happen when gaining/losing input focus with some devices.
    // Input devices that have a trackpad may gain/lose AXIS_RELATIVE_X/Y.
    @Override
    public void onInputDeviceChanged(int deviceId) {
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null) {
            return;
        }

        // If we don't have a context for this device, we don't need to update anything
        InputDeviceContext existingContext = inputDeviceContexts.get(deviceId);
        if (existingContext == null) {
            return;
        }

        LimeLog.info("Device changed: "+existingContext.name+" ("+deviceId+")");

        // Migrate the existing context into this new one by moving any stateful elements
        InputDeviceContext newContext = createInputDeviceContextForDevice(device);
        newContext.migrateContext(existingContext);
        inputDeviceContexts.put(deviceId, newContext);
    }

    public void stop() {
        if (stopped) {
            return;
        }

        // Stop new device contexts from being created or used
        stopped = true;

        // Unregister our input device callbacks
        inputManager.unregisterInputDeviceListener(this);

        for (int i = 0; i < inputDeviceContexts.size(); i++) {
            InputDeviceContext deviceContext = inputDeviceContexts.valueAt(i);
            deviceContext.destroy();
        }

        for (int i = 0; i < usbDeviceContexts.size(); i++) {
            UsbDeviceContext deviceContext = usbDeviceContexts.valueAt(i);
            deviceContext.destroy();
        }

        deviceVibrator.cancel();
    }

    public void destroy() {
        if (!stopped) {
            stop();
        }

        sceManager.stop();
        backgroundHandlerThread.quit();
    }

    public void disableSensors() {
        for (int i = 0; i < inputDeviceContexts.size(); i++) {
            InputDeviceContext deviceContext = inputDeviceContexts.valueAt(i);
            deviceContext.disableSensors();
        }
    }

    public void enableSensors() {
        if (stopped) {
            return;
        }

        for (int i = 0; i < inputDeviceContexts.size(); i++) {
            InputDeviceContext deviceContext = inputDeviceContexts.valueAt(i);
            deviceContext.enableSensors();
        }
    }

    private static boolean hasJoystickAxes(InputDevice device) {
        return (device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
                getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_X) != null &&
                getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_Y) != null;
    }

    private static boolean hasGamepadButtons(InputDevice device) {
        return (device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    public static boolean isGameControllerDevice(InputDevice device) {
        if (device == null) {
            return true;
        }

        if (hasJoystickAxes(device) || hasGamepadButtons(device)) {
            // Has real joystick axes or gamepad buttons
            return true;
        }

        // HACK for https://issuetracker.google.com/issues/163120692
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            if (device.getId() == -1) {
                // This "virtual" device could be input from any of the attached devices.
                // Look to see if any gamepads are connected.
                int[] ids = InputDevice.getDeviceIds();
                for (int id : ids) {
                    InputDevice dev = InputDevice.getDevice(id);
                    if (dev == null) {
                        // This device was removed during enumeration
                        continue;
                    }

                    // If there are any gamepad devices connected, we'll
                    // report that this virtual device is a gamepad.
                    if (hasJoystickAxes(dev) || hasGamepadButtons(dev)) {
                        return true;
                    }
                }
            }
        }

        //Check if the device is a TV remote control
        boolean isDigitalPad = (device.getSources() & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;

        // Otherwise, we'll try anything that claims to be a non-alphabetic keyboard
        return !isDigitalPad && device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    public static short getAttachedControllerMask(Context context) {
        int count = 0;
        short mask = 0;

        // Count all input devices that are gamepads
        InputManager im = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        for (int id : im.getInputDeviceIds()) {
            InputDevice dev = im.getInputDevice(id);
            if (dev == null) {
                continue;
            }

            if (hasJoystickAxes(dev)) {
                LimeLog.info("Counting InputDevice: "+dev.getName());
                mask |= 1 << count++;
            }
        }

        // Count all USB devices that match our drivers
        if (PreferenceConfiguration.readPreferences(context).usbDriver) {
            UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (usbManager != null) {
                for (UsbDevice dev : usbManager.getDeviceList().values()) {
                    // We explicitly check not to claim devices that appear as InputDevices
                    // otherwise we will double count them.
                    if (UsbDriverService.shouldClaimDevice(dev, false) &&
                            !UsbDriverService.isRecognizedInputDevice(dev)) {
                        LimeLog.info("Counting UsbDevice: "+dev.getDeviceName());
                        mask |= 1 << count++;
                    }
                }
            }
        }

        if (PreferenceConfiguration.readPreferences(context).onscreenController) {
            LimeLog.info("Counting OSC gamepad");
            mask |= 1;
        }

        LimeLog.info("Enumerated "+count+" gamepads");
        return mask;
    }

    private void releaseControllerNumber(GenericControllerContext context) {
        // If we reserved a controller number, remove that reservation
        if (context.reservedControllerNumber) {
            LimeLog.info("Controller number "+context.controllerNumber+" is now available");
            currentControllers &= ~(1 << context.controllerNumber);
        }

        // If this device sent data as a gamepad, zero the values before removing.
        // We must do this after clearing the currentControllers entry so this
        // causes the device to be removed on the server PC.
        if (context.assignedControllerNumber) {
            conn.sendControllerInput(context.controllerNumber, getActiveControllerMask(),
                    (short) 0,
                    (byte) 0, (byte) 0,
                    (short) 0, (short) 0,
                    (short) 0, (short) 0);
        }
    }

    private boolean isAssociatedJoystick(InputDevice originalDevice, InputDevice possibleAssociatedJoystick) {
        if (possibleAssociatedJoystick == null) {
            return false;
        }

        // This can't be an associated joystick if it's not a joystick
        if ((possibleAssociatedJoystick.getSources() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return false;
        }

        // Make sure the device names *don't* match in order to prevent us from accidentally matching
        // on another of the exact same device.
        if (possibleAssociatedJoystick.getName().equals(originalDevice.getName())) {
            return false;
        }

        // Make sure the descriptor matches. This can match in cases where two of the exact same
        // input device are connected, so we perform the name check to exclude that case.
        if (!possibleAssociatedJoystick.getDescriptor().equals(originalDevice.getDescriptor())) {
            return false;
        }

        return true;
    }

    // Called before sending input but after we've determined that this
    // is definitely a controller (not a keyboard, mouse, or something else)
    private void assignControllerNumberIfNeeded(GenericControllerContext context) {
        if (context.assignedControllerNumber) {
            return;
        }

        if (context instanceof InputDeviceContext) {
            InputDeviceContext devContext = (InputDeviceContext) context;

            LimeLog.info(devContext.name+" ("+context.id+") needs a controller number assigned");
            if (!devContext.external) {
                LimeLog.info("Built-in buttons hardcoded as controller 0");
                context.controllerNumber = 0;
            }
            else if (prefConfig.multiController && devContext.hasJoystickAxes) {
                context.controllerNumber = 0;

                LimeLog.info("Reserving the next available controller number");
                for (short i = 0; i < MAX_GAMEPADS; i++) {
                    if ((currentControllers & (1 << i)) == 0) {
                        // Found an unused controller value
                        currentControllers |= (1 << i);

                        // Take this value out of the initial gamepad set
                        initialControllers &= ~(1 << i);

                        context.controllerNumber = i;
                        context.reservedControllerNumber = true;
                        break;
                    }
                }
            }
            else if (!devContext.hasJoystickAxes) {
                // If this device doesn't have joystick axes, it may be an input device associated
                // with another joystick (like a PS4 touchpad). We'll propagate that joystick's
                // controller number to this associated device.

                context.controllerNumber = 0;

                // For the DS4 case, the associated joystick is the next device after the touchpad.
                // We'll try the opposite case too, just to be a little future-proof.
                InputDevice associatedDevice = InputDevice.getDevice(devContext.id + 1);
                if (!isAssociatedJoystick(devContext.inputDevice, associatedDevice)) {
                    associatedDevice = InputDevice.getDevice(devContext.id - 1);
                    if (!isAssociatedJoystick(devContext.inputDevice, associatedDevice)) {
                        LimeLog.info("No associated joystick device found");
                        associatedDevice = null;
                    }
                }

                if (associatedDevice != null) {
                    InputDeviceContext associatedDeviceContext = inputDeviceContexts.get(associatedDevice.getId());

                    // Create a new context for the associated device if one doesn't exist
                    if (associatedDeviceContext == null) {
                        associatedDeviceContext = createInputDeviceContextForDevice(associatedDevice);
                        inputDeviceContexts.put(associatedDevice.getId(), associatedDeviceContext);
                    }

                    // Assign a controller number for the associated device if one isn't assigned
                    if (!associatedDeviceContext.assignedControllerNumber) {
                        assignControllerNumberIfNeeded(associatedDeviceContext);
                    }

                    // Propagate the associated controller number
                    context.controllerNumber = associatedDeviceContext.controllerNumber;

                    LimeLog.info("Propagated controller number from "+associatedDeviceContext.name);
                }
            }
            else {
                LimeLog.info("Not reserving a controller number");
                context.controllerNumber = 0;
            }

            // If the gamepad doesn't have motion sensors, use the on-device sensors as a fallback for player 1
            if (prefConfig.gamepadMotionSensorsFallbackToDevice && context.controllerNumber == 0 && devContext.sensorManager == null) {
                devContext.sensorManager = deviceSensorManager;
            }
        }
        else {
            if (prefConfig.multiController) {
                context.controllerNumber = 0;

                LimeLog.info("Reserving the next available controller number");
                for (short i = 0; i < MAX_GAMEPADS; i++) {
                    if ((currentControllers & (1 << i)) == 0) {
                        // Found an unused controller value
                        currentControllers |= (1 << i);

                        // Take this value out of the initial gamepad set
                        initialControllers &= ~(1 << i);

                        context.controllerNumber = i;
                        context.reservedControllerNumber = true;
                        break;
                    }
                }
            }
            else {
                LimeLog.info("Not reserving a controller number");
                context.controllerNumber = 0;
            }
        }

        LimeLog.info("Assigned as controller "+context.controllerNumber);
        context.assignedControllerNumber = true;

        // Report attributes of this new controller to the host
        context.sendControllerArrival();
    }

    private UsbDeviceContext createUsbDeviceContextForDevice(AbstractController device) {
        UsbDeviceContext context = new UsbDeviceContext();

        context.id = device.getControllerId();
        context.device = device;
        context.external = true;

        context.vendorId = device.getVendorId();
        context.productId = device.getProductId();

        context.leftStickDeadzoneRadius = (float) stickDeadzone;
        context.rightStickDeadzoneRadius = (float) stickDeadzone;
        context.triggerDeadzone = 0.13f;

        return context;
    }

    private static boolean hasButtonUnderTouchpad(InputDevice dev, byte type) {
        // It has to have a touchpad to have a button under it
        if ((dev.getSources() & InputDevice.SOURCE_TOUCHPAD) != InputDevice.SOURCE_TOUCHPAD) {
            return false;
        }

        // Landroid/view/InputDevice;->hasButtonUnderPad()Z is blocked after O
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O) {
            try {
                return (Boolean) dev.getClass().getMethod("hasButtonUnderPad").invoke(dev);
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            } catch (ClassCastException e) {
                e.printStackTrace();
            }
        }

        // We can't use the platform API, so we'll have to just guess based on the gamepad type.
        // If this is a PlayStation controller with a touchpad, we know it has a clickpad.
        return type == MoonBridge.LI_CTYPE_PS;
    }

    private static boolean isExternal(InputDevice dev) {
        // The ASUS Tinker Board inaccurately reports Bluetooth gamepads as internal,
        // causing shouldIgnoreBack() to believe it should pass through back as a
        // navigation event for any attached gamepads.
        if (Build.MODEL.equals("Tinker Board")) {
            return true;
        }

        String deviceName = dev.getName();
        if (deviceName.contains("gpio") || // This is the back button on Shield portable consoles
                deviceName.contains("joy_key") || // These are the gamepad buttons on the Archos Gamepad 2
                deviceName.contains("keypad") || // These are gamepad buttons on the XPERIA Play
                deviceName.equalsIgnoreCase("NVIDIA Corporation NVIDIA Controller v01.01") || // Gamepad on Shield Portable
                deviceName.equalsIgnoreCase("NVIDIA Corporation NVIDIA Controller v01.02") || // Gamepad on Shield Portable (?)
                deviceName.equalsIgnoreCase("GR0006") // Gamepad on Logitech G Cloud
        )
        {
            LimeLog.info(dev.getName()+" is internal by hardcoded mapping");
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Landroid/view/InputDevice;->isExternal()Z is officially public on Android Q
            return dev.isExternal();
        }
        else {
            try {
                // Landroid/view/InputDevice;->isExternal()Z is on the light graylist in Android P
                return (Boolean)dev.getClass().getMethod("isExternal").invoke(dev);
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            } catch (ClassCastException e) {
                e.printStackTrace();
            }
        }

        // Answer true if we don't know
        return true;
    }

    private boolean shouldIgnoreBack(InputDevice dev) {
        String devName = dev.getName();

        // The Serval has a Select button but the framework doesn't
        // know about that because it uses a non-standard scancode.
        if (devName.contains("Razer Serval")) {
            return true;
        }

        // Classify this device as a remote by name if it has no joystick axes
        if (!hasJoystickAxes(dev) && devName.toLowerCase().contains("remote")) {
            return true;
        }

        // Otherwise, dynamically try to determine whether we should allow this
        // back button to function for navigation.
        //
        // First, check if this is an internal device we're being called on.
        if (!isExternal(dev)) {
            InputManager im = (InputManager) activityContext.getSystemService(Context.INPUT_SERVICE);

            boolean foundInternalGamepad = false;
            boolean foundInternalSelect = false;
            for (int id : im.getInputDeviceIds()) {
                InputDevice currentDev = im.getInputDevice(id);

                // Ignore external devices
                if (currentDev == null || isExternal(currentDev)) {
                    continue;
                }

                // Note that we are explicitly NOT excluding the current device we're examining here,
                // since the other gamepad buttons may be on our current device and that's fine.
                if (currentDev.hasKeys(KeyEvent.KEYCODE_BUTTON_SELECT)[0]) {
                    foundInternalSelect = true;
                }

                // We don't check KEYCODE_BUTTON_A here, since the Shield Android TV has a
                // virtual mouse device that claims to have KEYCODE_BUTTON_A. Instead, we rely
                // on the SOURCE_GAMEPAD flag to be set on gamepad devices.
                if (hasGamepadButtons(currentDev)) {
                    foundInternalGamepad = true;
                }
            }

            // Allow the back button to function for navigation if we either:
            // a) have no internal gamepad (most phones)
            // b) have an internal gamepad but also have an internal select button (GPD XD)
            // but not:
            // c) have an internal gamepad but no internal select button (NVIDIA SHIELD Portable)
            return !foundInternalGamepad || foundInternalSelect;
        }
        else {
            // For external devices, we want to pass through the back button if the device
            // has no gamepad axes or gamepad buttons.
            return !hasJoystickAxes(dev) && !hasGamepadButtons(dev);
        }
    }

    private InputDeviceContext createInputDeviceContextForDevice(InputDevice dev) {
        InputDeviceContext context = new InputDeviceContext();
        String devName = dev.getName();

        LimeLog.info("Creating controller context for device: "+devName);
        LimeLog.info("Vendor ID: " + dev.getVendorId());
        LimeLog.info("Product ID: "+dev.getProductId());
        LimeLog.info(dev.toString());

        context.inputDevice = dev;
        context.name = devName;
        context.id = dev.getId();
        context.external = isExternal(dev);

        context.vendorId = dev.getVendorId();
        context.productId = dev.getProductId();

        // These aren't always present in the Android key layout files, so they won't show up
        // in our normal InputDevice.hasKeys() probing.
        context.hasPaddles = MoonBridge.guessControllerHasPaddles(context.vendorId, context.productId);
        context.hasShare = MoonBridge.guessControllerHasShareButton(context.vendorId, context.productId);

        // Try to use the InputDevice's associated vibrators first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasQuadAmplitudeControlledRumbleVibrators(dev.getVibratorManager())) {
            context.vibratorManager = dev.getVibratorManager();
            context.quadVibrators = true;
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasDualAmplitudeControlledRumbleVibrators(dev.getVibratorManager())) {
            context.vibratorManager = dev.getVibratorManager();
            context.quadVibrators = false;
        }
        else if (dev.getVibrator().hasVibrator()) {
            context.vibrator = dev.getVibrator();
        }
        else if (!context.external) {
            // If this is an internal controller, try to use the device's vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasQuadAmplitudeControlledRumbleVibrators(deviceVibratorManager)) {
                context.vibratorManager = deviceVibratorManager;
                context.quadVibrators = true;
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasDualAmplitudeControlledRumbleVibrators(deviceVibratorManager)) {
                context.vibratorManager = deviceVibratorManager;
                context.quadVibrators = false;
            }
            else if (deviceVibrator.hasVibrator()) {
                context.vibrator = deviceVibrator;
            }
        }

        // On Android 12, we can try to use the InputDevice's sensors. This may not work if the
        // Linux kernel version doesn't have motion sensor support, which is common for third-party
        // gamepads.
        //
        // Android 12 has a bug that causes InputDeviceSensorManager to cause a NPE on a background
        // thread due to bad error checking in InputListener callbacks. InputDeviceSensorManager is
        // created upon the first call to InputDevice.getSensorManager(), so we avoid calling this
        // on Android 12 unless we have a gamepad that could plausibly have motion sensors.
        // https://cs.android.com/android/_/android/platform/frameworks/base/+/8970010a5e9f3dc5c069f56b4147552accfcbbeb
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                (Build.VERSION.SDK_INT == Build.VERSION_CODES.S &&
                        (context.vendorId == 0x054c || context.vendorId == 0x057e))) && // Sony or Nintendo
                prefConfig.gamepadMotionSensors) {
            if (dev.getSensorManager().getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null || dev.getSensorManager().getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                context.sensorManager = dev.getSensorManager();
            }
        }

        // Check if this device has a usable RGB LED and cache that result
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (Light light : dev.getLightsManager().getLights()) {
                if (light.hasRgbControl()) {
                    context.hasRgbLed = true;
                    break;
                }
            }
        }

        // Detect if the gamepad has Mode and Select buttons according to the Android key layouts.
        // We do this first because other codepaths below may override these defaults.
        boolean[] buttons = dev.hasKeys(KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK, 0);
        context.hasMode = buttons[0];
        context.hasSelect = buttons[1] || buttons[2];

        context.touchpadXRange = dev.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD);
        context.touchpadYRange = dev.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD);
        context.touchpadPressureRange = dev.getMotionRange(MotionEvent.AXIS_PRESSURE, InputDevice.SOURCE_TOUCHPAD);

        context.leftStickXAxis = MotionEvent.AXIS_X;
        context.leftStickYAxis = MotionEvent.AXIS_Y;
        if (getMotionRangeForJoystickAxis(dev, context.leftStickXAxis) != null &&
                getMotionRangeForJoystickAxis(dev, context.leftStickYAxis) != null) {
            // This is a gamepad
            hasGameController = true;
            context.hasJoystickAxes = true;
        }

        // This is hack to deal with the Nvidia Shield's modifications that causes the DS4 clickpad
        // to work as a duplicate Select button instead of a unique button we can handle separately.
        context.isDualShockStandaloneTouchpad =
                context.vendorId == 0x054c && // Sony
                devName.endsWith(" Touchpad") &&
                dev.getSources() == (InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_MOUSE);

        InputDevice.MotionRange leftTriggerRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_LTRIGGER);
        InputDevice.MotionRange rightTriggerRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RTRIGGER);
        InputDevice.MotionRange brakeRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_BRAKE);
        InputDevice.MotionRange gasRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_GAS);
        InputDevice.MotionRange throttleRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_THROTTLE);
        if (leftTriggerRange != null && rightTriggerRange != null)
        {
            // Some controllers use LTRIGGER and RTRIGGER (like Ouya)
            context.leftTriggerAxis = MotionEvent.AXIS_LTRIGGER;
            context.rightTriggerAxis = MotionEvent.AXIS_RTRIGGER;
        }
        else if (brakeRange != null && gasRange != null)
        {
            // Others use GAS and BRAKE (like Moga)
            context.leftTriggerAxis = MotionEvent.AXIS_BRAKE;
            context.rightTriggerAxis = MotionEvent.AXIS_GAS;
        }
        else if (brakeRange != null && throttleRange != null)
        {
            // Others use THROTTLE and BRAKE (like Xiaomi)
            context.leftTriggerAxis = MotionEvent.AXIS_BRAKE;
            context.rightTriggerAxis = MotionEvent.AXIS_THROTTLE;
        }
        else
        {
            InputDevice.MotionRange rxRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RX);
            InputDevice.MotionRange ryRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RY);
            if (rxRange != null && ryRange != null && devName != null) {
                if (dev.getVendorId() == 0x054c) { // Sony
                    if (dev.hasKeys(KeyEvent.KEYCODE_BUTTON_C)[0]) {
                        LimeLog.info("Detected non-standard DualShock 4 mapping");
                        context.isNonStandardDualShock4 = true;
                    } else {
                        LimeLog.info("Detected DualShock 4 (Linux standard mapping)");
                        context.usesLinuxGamepadStandardFaceButtons = true;
                    }
                }

                if (context.isNonStandardDualShock4) {
                    // The old DS4 driver uses RX and RY for triggers
                    context.leftTriggerAxis = MotionEvent.AXIS_RX;
                    context.rightTriggerAxis = MotionEvent.AXIS_RY;

                    // DS4 has Select and Mode buttons (possibly mapped non-standard)
                    context.hasSelect = true;
                    context.hasMode = true;
                }
                else {
                    // If it's not a non-standard DS4 controller, it's probably an Xbox controller or
                    // other sane controller that uses RX and RY for right stick and Z and RZ for triggers.
                    context.rightStickXAxis = MotionEvent.AXIS_RX;
                    context.rightStickYAxis = MotionEvent.AXIS_RY;

                    // While it's likely that Z and RZ are triggers, we may have digital trigger buttons
                    // instead. We must check that we actually have Z and RZ axes before assigning them.
                    if (getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Z) != null &&
                            getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RZ) != null) {
                        context.leftTriggerAxis = MotionEvent.AXIS_Z;
                        context.rightTriggerAxis = MotionEvent.AXIS_RZ;
                    }
                }

                // Triggers always idle negative on axes that are centered at zero
                context.triggersIdleNegative = true;
            }
        }

        if (context.rightStickXAxis == -1 && context.rightStickYAxis == -1) {
            InputDevice.MotionRange zRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Z);
            InputDevice.MotionRange rzRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RZ);

            // Most other controllers use Z and RZ for the right stick
            if (zRange != null && rzRange != null) {
                context.rightStickXAxis = MotionEvent.AXIS_Z;
                context.rightStickYAxis = MotionEvent.AXIS_RZ;
            }
            else {
                InputDevice.MotionRange rxRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RX);
                InputDevice.MotionRange ryRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RY);

                // Try RX and RY now
                if (rxRange != null && ryRange != null) {
                    context.rightStickXAxis = MotionEvent.AXIS_RX;
                    context.rightStickYAxis = MotionEvent.AXIS_RY;
                }
            }
        }

        // Some devices have "hats" for d-pads
        InputDevice.MotionRange hatXRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_HAT_X);
        InputDevice.MotionRange hatYRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_HAT_Y);
        if (hatXRange != null && hatYRange != null) {
            context.hatXAxis = MotionEvent.AXIS_HAT_X;
            context.hatYAxis = MotionEvent.AXIS_HAT_Y;
        }

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            context.leftStickDeadzoneRadius = (float) stickDeadzone;
        }

        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            context.rightStickDeadzoneRadius = (float) stickDeadzone;
        }

        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            InputDevice.MotionRange ltRange = getMotionRangeForJoystickAxis(dev, context.leftTriggerAxis);
            InputDevice.MotionRange rtRange = getMotionRangeForJoystickAxis(dev, context.rightTriggerAxis);

            // It's important to have a valid deadzone so controller packet batching works properly
            context.triggerDeadzone = Math.max(Math.abs(ltRange.getFlat()), Math.abs(rtRange.getFlat()));

            // For triggers without (valid) deadzones, we'll use 13% (around XInput's default)
            if (context.triggerDeadzone < 0.13f ||
                context.triggerDeadzone > 0.30f)
            {
                context.triggerDeadzone = 0.13f;
            }
        }

        // The ADT-1 controller needs a similar fixup to the ASUS Gamepad
        if (dev.getVendorId() == 0x18d1 && dev.getProductId() == 0x2c40) {
            context.backIsStart = true;
            context.modeIsSelect = true;
            context.triggerDeadzone = 0.30f;
            context.hasSelect = true;
            context.hasMode = false;
        }

        context.ignoreBack = shouldIgnoreBack(dev);

        if (devName != null) {
            // For the Nexus Player (and probably other ATV devices), we should
            // use the back button as start since it doesn't have a start/menu button
            // on the controller
            if (devName.contains("ASUS Gamepad")) {
                boolean[] hasStartKey = dev.hasKeys(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU, 0);
                if (!hasStartKey[0] && !hasStartKey[1]) {
                    context.backIsStart = true;
                    context.modeIsSelect = true;
                    context.hasSelect = true;
                    context.hasMode = false;
                }

                // The ASUS Gamepad has triggers that sit far forward and are prone to false presses
                // so we increase the deadzone on them to minimize this
                context.triggerDeadzone = 0.30f;
            }
            // SHIELD controllers will use small stick deadzones
            else if (devName.contains("SHIELD") || devName.contains("NVIDIA Controller")) {
                // The big Nvidia button on the Shield controllers acts like a Search button. It
                // summons the Google Assistant on the Shield TV. On my Pixel 4, it seems to do
                // nothing, so we can hijack it to act like a mode button.
                if (devName.contains("NVIDIA Controller v01.03") || devName.contains("NVIDIA Controller v01.04")) {
                    context.searchIsMode = true;
                    context.hasMode = true;
                }
            }
            // The Serval has a couple of unknown buttons that are start and select. It also has
            // a back button which we want to ignore since there's already a select button.
            else if (devName.contains("Razer Serval")) {
                context.isServal = true;

                // Serval has Select and Mode buttons (possibly mapped non-standard)
                context.hasMode = true;
                context.hasSelect = true;
            }
            // The Xbox One S Bluetooth controller has some mappings that need fixing up.
            // However, Microsoft released a firmware update with no change to VID/PID
            // or device name that fixed the mappings for Android. Since there's
            // no good way to detect this, we'll use the presence of GAS/BRAKE axes
            // that were added in the latest firmware. If those are present, the only
            // required fixup is ignoring the select button.
            else if (devName.equals("Xbox Wireless Controller")) {
                if (gasRange == null) {
                    context.isNonStandardXboxBtController = true;

                    // Xbox One S has Select and Mode buttons (possibly mapped non-standard)
                    context.hasMode = true;
                    context.hasSelect = true;
                }
            }
        }

        // Thrustmaster Score A gamepad home button reports directly to android as
        // KEY_HOMEPAGE event on another event channel
        if (dev.getVendorId() == 0x044f && dev.getProductId() == 0xb328) {
            context.hasMode = false;
        }

        LimeLog.info("Analog stick deadzone: "+context.leftStickDeadzoneRadius+" "+context.rightStickDeadzoneRadius);
        LimeLog.info("Trigger deadzone: "+context.triggerDeadzone);

        return context;
    }

    private InputDeviceContext getContextForEvent(InputEvent event) {
        // Don't return a context if we're stopped
        if (stopped) {
            return null;
        }
        else if (event.getDeviceId() == 0) {
            // Unknown devices use the default context
            return defaultContext;
        }
        else if (event.getDevice() == null) {
            // During device removal, sometimes we can get events after the
            // input device has been destroyed. In this case we'll see a
            // != 0 device ID but no device attached.
            return null;
        }

        // HACK for https://issuetracker.google.com/issues/163120692
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            if (event.getDeviceId() == -1) {
                return defaultContext;
            }
        }

        // Return the existing context if it exists
        InputDeviceContext context = inputDeviceContexts.get(event.getDeviceId());
        if (context != null) {
            return context;
        }

        // Otherwise create a new context
        context = createInputDeviceContextForDevice(event.getDevice());
        inputDeviceContexts.put(event.getDeviceId(), context);

        return context;
    }

    private byte maxByMagnitude(byte a, byte b) {
        int absA = Math.abs(a);
        int absB = Math.abs(b);
        if (absA > absB) {
            return a;
        }
        else {
            return b;
        }
    }

    private short maxByMagnitude(short a, short b) {
        int absA = Math.abs(a);
        int absB = Math.abs(b);
        if (absA > absB) {
            return a;
        }
        else {
            return b;
        }
    }

    private short getActiveControllerMask() {
        if (prefConfig.multiController) {
            return (short)(currentControllers | initialControllers | (prefConfig.onscreenController ? 1 : 0));
        }
        else {
            // Only Player 1 is active with multi-controller disabled
            return 1;
        }
    }

    private static boolean areBatteryCapacitiesEqual(float first, float second) {
        // With no NaNs involved, it is a simple equality comparison.
        if (!Float.isNaN(first) && !Float.isNaN(second)) {
            return first == second;
        }
        else {
            // If we have a NaN in one or both positions, compare NaN-ness instead.
            // Equality comparisons will always return false for NaN.
            return Float.isNaN(first) == Float.isNaN(second);
        }
    }

    // This must not be called on the main thread due to risk of ANRs!
    private void sendControllerBatteryPacket(InputDeviceContext context) {
        int currentBatteryStatus;
        float currentBatteryCapacity;

        // Use the BatteryState object introduced in Android S, if it's available and present.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.inputDevice.getBatteryState().isPresent()) {
            currentBatteryStatus = context.inputDevice.getBatteryState().getStatus();
            currentBatteryCapacity = context.inputDevice.getBatteryState().getCapacity();
        }
        else if (sceManager.isRecognizedDevice(context.inputDevice)) {
            // On the SHIELD Android TV, we can use a proprietary API to access battery/charge state.
            // We will convert it to the same form used by BatteryState to share code.
            int batteryPercentage = sceManager.getBatteryPercentage(context.inputDevice);
            if (batteryPercentage < 0) {
                currentBatteryCapacity = Float.NaN;
            }
            else {
                currentBatteryCapacity = batteryPercentage / 100.f;
            }

            SceConnectionType connectionType = sceManager.getConnectionType(context.inputDevice);
            SceChargingState chargingState = sceManager.getChargingState(context.inputDevice);

            // We can make some assumptions about charge state based on the connection type
            if (connectionType == SceConnectionType.WIRED || connectionType == SceConnectionType.BOTH) {
                if (batteryPercentage == 100) {
                    currentBatteryStatus = BatteryState.STATUS_FULL;
                }
                else if (chargingState == SceChargingState.NOT_CHARGING) {
                    currentBatteryStatus = BatteryState.STATUS_NOT_CHARGING;
                }
                else {
                    currentBatteryStatus = BatteryState.STATUS_CHARGING;
                }
            }
            else if (connectionType == SceConnectionType.WIRELESS) {
                if (chargingState == SceChargingState.CHARGING) {
                    currentBatteryStatus = BatteryState.STATUS_CHARGING;
                }
                else {
                    currentBatteryStatus = BatteryState.STATUS_DISCHARGING;
                }
            }
            else {
                // If connection type is unknown, just use the charge state
                if (batteryPercentage == 100) {
                    currentBatteryStatus = BatteryState.STATUS_FULL;
                }
                else if (chargingState == SceChargingState.NOT_CHARGING) {
                    currentBatteryStatus = BatteryState.STATUS_DISCHARGING;
                }
                else if (chargingState == SceChargingState.CHARGING) {
                    currentBatteryStatus = BatteryState.STATUS_CHARGING;
                }
                else {
                    currentBatteryStatus = BatteryState.STATUS_UNKNOWN;
                }
            }
        }
        else {
            return;
        }

        if (currentBatteryStatus != context.lastReportedBatteryStatus ||
                !areBatteryCapacitiesEqual(currentBatteryCapacity, context.lastReportedBatteryCapacity)) {
            byte state;
            byte percentage;

            switch (currentBatteryStatus) {
                case BatteryState.STATUS_UNKNOWN:
                    state = MoonBridge.LI_BATTERY_STATE_UNKNOWN;
                    break;

                case BatteryState.STATUS_CHARGING:
                    state = MoonBridge.LI_BATTERY_STATE_CHARGING;
                    break;

                case BatteryState.STATUS_DISCHARGING:
                    state = MoonBridge.LI_BATTERY_STATE_DISCHARGING;
                    break;

                case BatteryState.STATUS_NOT_CHARGING:
                    state = MoonBridge.LI_BATTERY_STATE_NOT_CHARGING;
                    break;

                case BatteryState.STATUS_FULL:
                    state = MoonBridge.LI_BATTERY_STATE_FULL;
                    break;

                default:
                    return;
            }

            if (Float.isNaN(currentBatteryCapacity)) {
                percentage = MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN;
            }
            else {
                percentage = (byte)(currentBatteryCapacity * 100);
            }

            conn.sendControllerBatteryEvent((byte)context.controllerNumber, state, percentage);

            context.lastReportedBatteryStatus = currentBatteryStatus;
            context.lastReportedBatteryCapacity = currentBatteryCapacity;
        }
    }

    private void sendControllerInputPacket(GenericControllerContext originalContext) {
        assignControllerNumberIfNeeded(originalContext);

        // Take the context's controller number and fuse all inputs with the same number
        short controllerNumber = originalContext.controllerNumber;
        int inputMap = 0;
        byte leftTrigger = 0;
        byte rightTrigger = 0;
        short leftStickX = 0;
        short leftStickY = 0;
        short rightStickX = 0;
        short rightStickY = 0;

        // In order to properly handle controllers that are split into multiple devices,
        // we must aggregate all controllers with the same controller number into a single
        // device before we send it.
        for (int i = 0; i < inputDeviceContexts.size(); i++) {
            GenericControllerContext context = inputDeviceContexts.valueAt(i);
            if (context.assignedControllerNumber &&
                    context.controllerNumber == controllerNumber &&
                    context.mouseEmulationActive == originalContext.mouseEmulationActive) {
                inputMap |= context.inputMap;
                leftTrigger |= maxByMagnitude(leftTrigger, context.leftTrigger);
                rightTrigger |= maxByMagnitude(rightTrigger, context.rightTrigger);
                leftStickX |= maxByMagnitude(leftStickX, context.leftStickX);
                leftStickY |= maxByMagnitude(leftStickY, context.leftStickY);
                rightStickX |= maxByMagnitude(rightStickX, context.rightStickX);
                rightStickY |= maxByMagnitude(rightStickY, context.rightStickY);
            }
        }
        for (int i = 0; i < usbDeviceContexts.size(); i++) {
            GenericControllerContext context = usbDeviceContexts.valueAt(i);
            if (context.assignedControllerNumber &&
                    context.controllerNumber == controllerNumber &&
                    context.mouseEmulationActive == originalContext.mouseEmulationActive) {
                inputMap |= context.inputMap;
                leftTrigger |= maxByMagnitude(leftTrigger, context.leftTrigger);
                rightTrigger |= maxByMagnitude(rightTrigger, context.rightTrigger);
                leftStickX |= maxByMagnitude(leftStickX, context.leftStickX);
                leftStickY |= maxByMagnitude(leftStickY, context.leftStickY);
                rightStickX |= maxByMagnitude(rightStickX, context.rightStickX);
                rightStickY |= maxByMagnitude(rightStickY, context.rightStickY);
            }
        }
        if (defaultContext.controllerNumber == controllerNumber) {
            inputMap |= defaultContext.inputMap;
            leftTrigger |= maxByMagnitude(leftTrigger, defaultContext.leftTrigger);
            rightTrigger |= maxByMagnitude(rightTrigger, defaultContext.rightTrigger);
            leftStickX |= maxByMagnitude(leftStickX, defaultContext.leftStickX);
            leftStickY |= maxByMagnitude(leftStickY, defaultContext.leftStickY);
            rightStickX |= maxByMagnitude(rightStickX, defaultContext.rightStickX);
            rightStickY |= maxByMagnitude(rightStickY, defaultContext.rightStickY);
        }

        if (originalContext.mouseEmulationActive) {
            int changedMask = inputMap ^  originalContext.mouseEmulationLastInputMap;

            boolean aDown = (inputMap & ControllerPacket.A_FLAG) != 0;
            boolean bDown = (inputMap & ControllerPacket.B_FLAG) != 0;

            originalContext.mouseEmulationLastInputMap = inputMap;

            if ((changedMask & ControllerPacket.A_FLAG) != 0) {
                if (aDown) {
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                }
                else {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                }
            }
            if ((changedMask & ControllerPacket.B_FLAG) != 0) {
                if (bDown) {
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                }
                else {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                }
            }
            if ((changedMask & ControllerPacket.UP_FLAG) != 0) {
                if ((inputMap & ControllerPacket.UP_FLAG) != 0) {
                    conn.sendMouseScroll((byte) 1);
                }
            }
            if ((changedMask & ControllerPacket.DOWN_FLAG) != 0) {
                if ((inputMap & ControllerPacket.DOWN_FLAG) != 0) {
                    conn.sendMouseScroll((byte) -1);
                }
            }
            if ((changedMask & ControllerPacket.RIGHT_FLAG) != 0) {
                if ((inputMap & ControllerPacket.RIGHT_FLAG) != 0) {
                    conn.sendMouseHScroll((byte) 1);
                }
            }
            if ((changedMask & ControllerPacket.LEFT_FLAG) != 0) {
                if ((inputMap & ControllerPacket.LEFT_FLAG) != 0) {
                    conn.sendMouseHScroll((byte) -1);
                }
            }

            conn.sendControllerInput(controllerNumber, getActiveControllerMask(),
                    (short)0, (byte)0, (byte)0, (short)0, (short)0, (short)0, (short)0);
        }
        else {
            conn.sendControllerInput(controllerNumber, getActiveControllerMask(),
                    inputMap,
                    leftTrigger, rightTrigger,
                    leftStickX, leftStickY,
                    rightStickX, rightStickY);
        }
    }

    private final int REMAP_IGNORE = -1;
    private final int REMAP_CONSUME = -2;

    // Return a valid keycode, -2 to consume, or -1 to not consume the event
    // Device MAY BE NULL
    private int handleRemapping(InputDeviceContext context, KeyEvent event) {
        // Don't capture the back button if configured
        if (context.ignoreBack) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                return REMAP_IGNORE;
            }
        }

        // If we know this gamepad has a share button and receive an unmapped
        // KEY_RECORD event, report that as a share button press.
        if (context.hasShare) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN &&
                    event.getScanCode() == 167) {
                return KeyEvent.KEYCODE_MEDIA_RECORD;
            }
        }

        // The Shield's key layout files map the DualShock 4 clickpad button to
        // BUTTON_SELECT instead of something sane like BUTTON_1 as the standard AOSP
        // mapping does. If we get a button from a Sony device reported as BUTTON_SELECT
        // that matches the keycode used by hid-sony for the clickpad or it's from the
        // separate touchpad input device, remap it to BUTTON_1 to match the current AOSP
        // layout and trigger our touchpad button logic.
        if (context.vendorId == 0x054c &&
                event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_SELECT &&
                (event.getScanCode() == 317 || context.isDualShockStandaloneTouchpad)) {
            return KeyEvent.KEYCODE_BUTTON_1;
        }

        // Override mode button for 8BitDo controllers
        if (context.vendorId == 0x2dc8 && event.getScanCode() == 306) {
            return KeyEvent.KEYCODE_BUTTON_MODE;
        }

        // This mapping was adding in Android 10, then changed based on
        // kernel changes (adding hid-nintendo) in Android 11. If we're
        // on anything newer than Pie, just use the built-in mapping.
        if ((context.vendorId == 0x057e && context.productId == 0x2009 && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) || // Switch Pro controller
                (context.vendorId == 0x0f0d && context.productId == 0x00c1)) { // HORIPAD for Switch
            switch (event.getScanCode()) {
                case 0x130:
                    return KeyEvent.KEYCODE_BUTTON_A;
                case 0x131:
                    return KeyEvent.KEYCODE_BUTTON_B;
                case 0x132:
                    return KeyEvent.KEYCODE_BUTTON_X;
                case 0x133:
                    return KeyEvent.KEYCODE_BUTTON_Y;
                case 0x134:
                    return KeyEvent.KEYCODE_BUTTON_L1;
                case 0x135:
                    return KeyEvent.KEYCODE_BUTTON_R1;
                case 0x136:
                    return KeyEvent.KEYCODE_BUTTON_L2;
                case 0x137:
                    return KeyEvent.KEYCODE_BUTTON_R2;
                case 0x138:
                    return KeyEvent.KEYCODE_BUTTON_SELECT;
                case 0x139:
                    return KeyEvent.KEYCODE_BUTTON_START;
                case 0x13A:
                    return KeyEvent.KEYCODE_BUTTON_THUMBL;
                case 0x13B:
                    return KeyEvent.KEYCODE_BUTTON_THUMBR;
                case 0x13D:
                    return KeyEvent.KEYCODE_BUTTON_MODE;
            }
        }

        if (context.usesLinuxGamepadStandardFaceButtons) {
            // Android's Generic.kl swaps BTN_NORTH and BTN_WEST
            switch (event.getScanCode()) {
                case 304:
                    return KeyEvent.KEYCODE_BUTTON_A;
                case 305:
                    return KeyEvent.KEYCODE_BUTTON_B;
                case 307:
                    return KeyEvent.KEYCODE_BUTTON_Y;
                case 308:
                    return KeyEvent.KEYCODE_BUTTON_X;
            }
        }

        if (context.isNonStandardDualShock4) {
            switch (event.getScanCode()) {
                case 304:
                    return KeyEvent.KEYCODE_BUTTON_X;
                case 305:
                    return KeyEvent.KEYCODE_BUTTON_A;
                case 306:
                    return KeyEvent.KEYCODE_BUTTON_B;
                case 307:
                    return KeyEvent.KEYCODE_BUTTON_Y;
                case 308:
                    return KeyEvent.KEYCODE_BUTTON_L1;
                case 309:
                    return KeyEvent.KEYCODE_BUTTON_R1;
                /*
                **** Using analog triggers instead ****
                case 310:
                    return KeyEvent.KEYCODE_BUTTON_L2;
                case 311:
                    return KeyEvent.KEYCODE_BUTTON_R2;
                */
                case 312:
                    return KeyEvent.KEYCODE_BUTTON_SELECT;
                case 313:
                    return KeyEvent.KEYCODE_BUTTON_START;
                case 314:
                    return KeyEvent.KEYCODE_BUTTON_THUMBL;
                case 315:
                    return KeyEvent.KEYCODE_BUTTON_THUMBR;
                case 316:
                    return KeyEvent.KEYCODE_BUTTON_MODE;
                default:
                    return REMAP_CONSUME;
            }
        }
        // If this is a Serval controller sending an unknown key code, it's probably
        // the start and select buttons
        else if (context.isServal && event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN) {
            switch (event.getScanCode())  {
                case 314:
                    return KeyEvent.KEYCODE_BUTTON_SELECT;
                case 315:
                    return KeyEvent.KEYCODE_BUTTON_START;
            }
        }
        else if (context.isNonStandardXboxBtController) {
            switch (event.getScanCode()) {
                case 306:
                    return KeyEvent.KEYCODE_BUTTON_X;
                case 307:
                    return KeyEvent.KEYCODE_BUTTON_Y;
                case 308:
                    return KeyEvent.KEYCODE_BUTTON_L1;
                case 309:
                    return KeyEvent.KEYCODE_BUTTON_R1;
                case 310:
                    return KeyEvent.KEYCODE_BUTTON_SELECT;
                case 311:
                    return KeyEvent.KEYCODE_BUTTON_START;
                case 312:
                    return KeyEvent.KEYCODE_BUTTON_THUMBL;
                case 313:
                    return KeyEvent.KEYCODE_BUTTON_THUMBR;
                case 139:
                    return KeyEvent.KEYCODE_BUTTON_MODE;
                default:
                    // Other buttons are mapped correctly
            }

            // The Xbox button is sent as MENU
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                return KeyEvent.KEYCODE_BUTTON_MODE;
            }
        }
        else if (context.vendorId == 0x0b05 && // ASUS
                     (context.productId == 0x7900 || // Kunai - USB
                      context.productId == 0x7902)) // Kunai - Bluetooth
        {
            // ROG Kunai has special M1-M4 buttons that are accessible via the
            // joycon-style detachable controllers that we should map to Start
            // and Select.
            switch (event.getScanCode()) {
                case 264:
                case 266:
                    return KeyEvent.KEYCODE_BUTTON_START;

                case 265:
                case 267:
                    return KeyEvent.KEYCODE_BUTTON_SELECT;
            }
        }

        // Past here we can fixup the keycode and potentially trigger
        // another special case so we need to remember what keycode we're using
        int keyCode = event.getKeyCode();

        // This is a hack for (at least) the "Tablet Remote" app
        // which sends BACK with META_ALT_ON instead of KEYCODE_BUTTON_B
        if (keyCode == KeyEvent.KEYCODE_BACK &&
                !event.hasNoModifiers() &&
                (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0)
        {
            keyCode = KeyEvent.KEYCODE_BUTTON_B;
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_START ||
                keyCode == KeyEvent.KEYCODE_MENU) {
            // Ensure that we never use back as start if we have a real start
            context.backIsStart = false;
        }
        else if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            // Don't use mode as select if we have a select
            context.modeIsSelect = false;
        }
        else if (context.backIsStart && keyCode == KeyEvent.KEYCODE_BACK) {
            // Emulate the start button with back
            return KeyEvent.KEYCODE_BUTTON_START;
        }
        else if (context.modeIsSelect && keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            // Emulate the select button with mode
            return KeyEvent.KEYCODE_BUTTON_SELECT;
        }
        else if (context.searchIsMode && keyCode == KeyEvent.KEYCODE_SEARCH) {
            // Emulate the mode button with search
            return KeyEvent.KEYCODE_BUTTON_MODE;
        }

        return keyCode;
    }

    private int handleFlipFaceButtons(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return KeyEvent.KEYCODE_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_B:
                return KeyEvent.KEYCODE_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_X:
                return KeyEvent.KEYCODE_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return KeyEvent.KEYCODE_BUTTON_X;
            default:
                return keyCode;
        }
    }

    private Vector2d populateCachedVector(float x, float y) {
        // Reinitialize our cached Vector2d object
        inputVector.initialize(x, y);
        return inputVector;
    }

    private void handleDeadZone(Vector2d stickVector, float deadzoneRadius) {
        if (stickVector.getMagnitude() <= deadzoneRadius) {
            // Deadzone
            stickVector.initialize(0, 0);
        }

        // We're not normalizing here because we let the computer handle the deadzones.
        // Normalizing can make the deadzones larger than they should be after the computer also
        // evaluates the deadzone.
    }

    private void handleAxisSet(InputDeviceContext context, float lsX, float lsY, float rsX,
                               float rsY, float lt, float rt, float hatX, float hatY) {

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            Vector2d leftStickVector = populateCachedVector(lsX, lsY);

            handleDeadZone(leftStickVector, context.leftStickDeadzoneRadius);

            context.leftStickX = (short) (leftStickVector.getX() * 0x7FFE);
            context.leftStickY = (short) (-leftStickVector.getY() * 0x7FFE);
        }

        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            Vector2d rightStickVector = populateCachedVector(rsX, rsY);

            handleDeadZone(rightStickVector, context.rightStickDeadzoneRadius);

            context.rightStickX = (short) (rightStickVector.getX() * 0x7FFE);
            context.rightStickY = (short) (-rightStickVector.getY() * 0x7FFE);
        }

        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            // Android sends an initial 0 value for trigger axes even if the trigger
            // should be negative when idle. After the first touch, the axes will go back
            // to normal behavior, so ignore triggersIdleNegative for each trigger until
            // first touch.
            if (lt != 0) {
                context.leftTriggerAxisUsed = true;
            }
            if (rt != 0) {
                context.rightTriggerAxisUsed = true;
            }
            if (context.triggersIdleNegative) {
                if (context.leftTriggerAxisUsed) {
                    lt = (lt + 1) / 2;
                }
                if (context.rightTriggerAxisUsed) {
                    rt = (rt + 1) / 2;
                }
            }

            if (lt <= context.triggerDeadzone) {
                lt = 0;
            }
            if (rt <= context.triggerDeadzone) {
                rt = 0;
            }

            context.leftTrigger = (byte)(lt * 0xFF);
            context.rightTrigger = (byte)(rt * 0xFF);
        }

        if (context.hatXAxis != -1 && context.hatYAxis != -1) {
            context.inputMap &= ~(ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG);
            if (hatX < -0.5) {
                context.inputMap |= ControllerPacket.LEFT_FLAG;
                context.hatXAxisUsed = true;
            }
            else if (hatX > 0.5) {
                context.inputMap |= ControllerPacket.RIGHT_FLAG;
                context.hatXAxisUsed = true;
            }

            context.inputMap &= ~(ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG);
            if (hatY < -0.5) {
                context.inputMap |= ControllerPacket.UP_FLAG;
                context.hatYAxisUsed = true;
            }
            else if (hatY > 0.5) {
                context.inputMap |= ControllerPacket.DOWN_FLAG;
                context.hatYAxisUsed = true;
            }
        }

        sendControllerInputPacket(context);
    }

    // Normalize the given raw float value into a 0.0-1.0f range
    private float normalizeRawValueWithRange(float value, InputDevice.MotionRange range) {
        value = Math.max(value, range.getMin());
        value = Math.min(value, range.getMax());

        value -= range.getMin();

        return value / range.getRange();
    }

    private boolean sendTouchpadEventForPointer(InputDeviceContext context, MotionEvent event, byte touchType, int pointerIndex) {
        float normalizedX = normalizeRawValueWithRange(event.getX(pointerIndex), context.touchpadXRange);
        float normalizedY = normalizeRawValueWithRange(event.getY(pointerIndex), context.touchpadYRange);
        float normalizedPressure = context.touchpadPressureRange != null ?
                normalizeRawValueWithRange(event.getPressure(pointerIndex), context.touchpadPressureRange)
                : 0;

        return conn.sendControllerTouchEvent((byte)context.controllerNumber, touchType,
                event.getPointerId(pointerIndex),
                normalizedX, normalizedY, normalizedPressure) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    public boolean tryHandleTouchpadEvent(MotionEvent event) {
        // Bail if this is not a touchpad or mouse event
        if (event.getSource() != InputDevice.SOURCE_TOUCHPAD &&
                event.getSource() != InputDevice.SOURCE_MOUSE) {
            return false;
        }

        // Only get a context if one already exists. We want to ensure we don't report non-gamepads.
        InputDeviceContext context = inputDeviceContexts.get(event.getDeviceId());
        if (context == null) {
            return false;
        }

        // When we're working with a mouse source instead of a touchpad, we're quite limited in
        // what useful input we can provide via the controller API. The ABS_X/ABS_Y values are
        // screen coordinates rather than touchpad coordinates. For now, we will just support
        // the clickpad button and nothing else.
        if (event.getSource() == InputDevice.SOURCE_MOUSE) {
            // Unlike the touchpad where down and up refer to individual touches on the touchpad,
            // down and up on a mouse indicates the state of the left mouse button.
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    context.inputMap |= ControllerPacket.TOUCHPAD_FLAG;
                    sendControllerInputPacket(context);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    context.inputMap &= ~ControllerPacket.TOUCHPAD_FLAG;
                    sendControllerInputPacket(context);
                    break;
                default:
                    break;
            }

            return !prefConfig.gamepadTouchpadAsMouse;
        }

        byte touchType;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                touchType = MoonBridge.LI_TOUCH_EVENT_DOWN;
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if ((event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    touchType = MoonBridge.LI_TOUCH_EVENT_CANCEL;
                }
                else {
                    touchType = MoonBridge.LI_TOUCH_EVENT_UP;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                touchType = MoonBridge.LI_TOUCH_EVENT_MOVE;
                break;

            case MotionEvent.ACTION_CANCEL:
                // ACTION_CANCEL applies to *all* pointers in the gesture, so it maps to CANCEL_ALL
                // rather than CANCEL. For a single pointer cancellation, that's indicated via
                // FLAG_CANCELED on a ACTION_POINTER_UP.
                // https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi
                touchType = MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL;
                break;

            case MotionEvent.ACTION_BUTTON_PRESS:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && event.getActionButton() == MotionEvent.BUTTON_PRIMARY) {
                    context.inputMap |= ControllerPacket.TOUCHPAD_FLAG;
                    sendControllerInputPacket(context);
                    return !prefConfig.gamepadTouchpadAsMouse; // Report as unhandled event to trigger mouse handling
                }
                return false;

            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && event.getActionButton() == MotionEvent.BUTTON_PRIMARY) {
                    context.inputMap &= ~ControllerPacket.TOUCHPAD_FLAG;
                    sendControllerInputPacket(context);
                    return !prefConfig.gamepadTouchpadAsMouse; // Report as unhandled event to trigger mouse handling
                }
                return false;

            default:
                return false;
        }

        // Bail if the user wants gamepad touchpads to control the mouse
        //
        // NB: We do this after processing ACTION_BUTTON_PRESS and ACTION_BUTTON_RELEASE
        // because we want to still send the touchpad button via the gamepad even when
        // configured to use the touchpad for mouse control.
        if (prefConfig.gamepadTouchpadAsMouse) {
            return false;
        }

        // If we don't have X and Y ranges, we can't process this event
        if (context.touchpadXRange == null || context.touchpadYRange == null) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (!sendTouchpadEventForPointer(context, event, touchType, i)) {
                    // Controller touch events are not supported by the host
                    return false;
                }
            }
            return true;
        }
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendControllerTouchEvent((byte)context.controllerNumber, MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL,
                    0, 0, 0, 0) != MoonBridge.LI_ERR_UNSUPPORTED;
        }
        else {
            // Down and Up events impact the action index pointer
            return sendTouchpadEventForPointer(context, event, touchType, event.getActionIndex());
        }
    }

    public boolean handleMotionEvent(MotionEvent event) {
        InputDeviceContext context = getContextForEvent(event);
        if (context == null) {
            return true;
        }

        float lsX = 0, lsY = 0, rsX = 0, rsY = 0, rt = 0, lt = 0, hatX = 0, hatY = 0;

        // We purposefully ignore the historical values in the motion event as it makes
        // the controller feel sluggish for some users.

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            lsX = event.getAxisValue(context.leftStickXAxis);
            lsY = event.getAxisValue(context.leftStickYAxis);
        }

        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            rsX = event.getAxisValue(context.rightStickXAxis);
            rsY = event.getAxisValue(context.rightStickYAxis);
        }

        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            lt = event.getAxisValue(context.leftTriggerAxis);
            rt = event.getAxisValue(context.rightTriggerAxis);
        }

        if (context.hatXAxis != -1 && context.hatYAxis != -1) {
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        }

        handleAxisSet(context, lsX, lsY, rsX, rsY, lt, rt, hatX, hatY);

        return true;
    }

    private Vector2d convertRawStickAxisToPixelMovement(short stickX, short stickY) {
        Vector2d vector = new Vector2d();
        vector.initialize(stickX, stickY);
        vector.scalarMultiply(1 / 32766.0f);
        vector.scalarMultiply(4);
        if (vector.getMagnitude() > 0) {
            // Move faster as the stick is pressed further from center
            vector.scalarMultiply(Math.pow(vector.getMagnitude(), 2));
        }
        return vector;
    }

    private void sendEmulatedMouseMove(short x, short y) {
        Vector2d vector = convertRawStickAxisToPixelMovement(x, y);
        if (vector.getMagnitude() >= 1) {
            conn.sendMouseMove((short)vector.getX(), (short)-vector.getY());
        }
    }

    private void sendEmulatedMouseScroll(short x, short y) {
        Vector2d vector = convertRawStickAxisToPixelMovement(x, y);
        if (vector.getMagnitude() >= 1) {
            conn.sendMouseHighResScroll((short)vector.getY());
            conn.sendMouseHighResHScroll((short)vector.getX());
        }
    }

    @TargetApi(31)
    private boolean hasDualAmplitudeControlledRumbleVibrators(VibratorManager vm) {
        int[] vibratorIds = vm.getVibratorIds();

        // There must be exactly 2 vibrators on this device
        if (vibratorIds.length != 2) {
            return false;
        }

        // Both vibrators must have amplitude control
        for (int vid : vibratorIds) {
            if (!vm.getVibrator(vid).hasAmplitudeControl()) {
                return false;
            }
        }

        return true;
    }

    // This must only be called if hasDualAmplitudeControlledRumbleVibrators() is true!
    @TargetApi(31)
    private void rumbleDualVibrators(VibratorManager vm, short lowFreqMotor, short highFreqMotor) {
        // Normalize motor values to 0-255 amplitudes for VibrationManager
        highFreqMotor = (short)((highFreqMotor >> 8) & 0xFF);
        lowFreqMotor = (short)((lowFreqMotor >> 8) & 0xFF);

        // If they're both zero, we can just call cancel().
        if (lowFreqMotor == 0 && highFreqMotor == 0) {
            vm.cancel();
            return;
        }

        // There's no documentation that states that vibrators for FF_RUMBLE input devices will
        // always be enumerated in this order, but it seems consistent between Xbox Series X (USB),
        // PS3 (USB), and PS4 (USB+BT) controllers on Android 12 Beta 3.
        int[] vibratorIds = vm.getVibratorIds();
        int[] vibratorAmplitudes = new int[] { highFreqMotor, lowFreqMotor };

        CombinedVibration.ParallelCombination combo = CombinedVibration.startParallel();

        for (int i = 0; i < vibratorIds.length; i++) {
            // It's illegal to create a VibrationEffect with an amplitude of 0.
            // Simply excluding that vibrator from our ParallelCombination will turn it off.
            if (vibratorAmplitudes[i] != 0) {
                combo.addVibrator(vibratorIds[i], VibrationEffect.createOneShot(60000, vibratorAmplitudes[i]));
            }
        }

        VibrationAttributes.Builder vibrationAttributes = new VibrationAttributes.Builder();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrationAttributes.setUsage(VibrationAttributes.USAGE_MEDIA);
        }

        vm.vibrate(combo.combine(), vibrationAttributes.build());
    }

    @TargetApi(31)
    private boolean hasQuadAmplitudeControlledRumbleVibrators(VibratorManager vm) {
        int[] vibratorIds = vm.getVibratorIds();

        // There must be exactly 4 vibrators on this device
        if (vibratorIds.length != 4) {
            return false;
        }

        // All vibrators must have amplitude control
        for (int vid : vibratorIds) {
            if (!vm.getVibrator(vid).hasAmplitudeControl()) {
                return false;
            }
        }

        return true;
    }

    // This must only be called if hasQuadAmplitudeControlledRumbleVibrators() is true!
    @TargetApi(31)
    private void rumbleQuadVibrators(VibratorManager vm, short lowFreqMotor, short highFreqMotor, short leftTrigger, short rightTrigger) {
        // Normalize motor values to 0-255 amplitudes for VibrationManager
        highFreqMotor = (short)((highFreqMotor >> 8) & 0xFF);
        lowFreqMotor = (short)((lowFreqMotor >> 8) & 0xFF);
        leftTrigger = (short)((leftTrigger >> 8) & 0xFF);
        rightTrigger = (short)((rightTrigger >> 8) & 0xFF);

        // If they're all zero, we can just call cancel().
        if (lowFreqMotor == 0 && highFreqMotor == 0 && leftTrigger == 0 && rightTrigger == 0) {
            vm.cancel();
            return;
        }

        // This is a guess based upon the behavior of FF_RUMBLE, but untested due to lack of Linux
        // support for trigger rumble!
        int[] vibratorIds = vm.getVibratorIds();
        int[] vibratorAmplitudes = new int[] { highFreqMotor, lowFreqMotor, leftTrigger, rightTrigger };

        CombinedVibration.ParallelCombination combo = CombinedVibration.startParallel();

        for (int i = 0; i < vibratorIds.length; i++) {
            // It's illegal to create a VibrationEffect with an amplitude of 0.
            // Simply excluding that vibrator from our ParallelCombination will turn it off.
            if (vibratorAmplitudes[i] != 0) {
                combo.addVibrator(vibratorIds[i], VibrationEffect.createOneShot(60000, vibratorAmplitudes[i]));
            }
        }

        VibrationAttributes.Builder vibrationAttributes = new VibrationAttributes.Builder();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrationAttributes.setUsage(VibrationAttributes.USAGE_MEDIA);
        }

        vm.vibrate(combo.combine(), vibrationAttributes.build());
    }

    private void rumbleSingleVibrator(Vibrator vibrator, short lowFreqMotor, short highFreqMotor) {
        // Since we can only use a single amplitude value, compute the desired amplitude
        // by taking 80% of the big motor and 33% of the small motor, then capping to 255.
        // NB: This value is now 0-255 as required by VibrationEffect.
        short lowFreqMotorMSB = (short)((lowFreqMotor >> 8) & 0xFF);
        short highFreqMotorMSB = (short)((highFreqMotor >> 8) & 0xFF);
        int simulatedAmplitude = Math.min(255, (int)((lowFreqMotorMSB * 0.80) + (highFreqMotorMSB * 0.33)));

        if (simulatedAmplitude == 0) {
            // This case is easy - just cancel the current effect and get out.
            // NB: We cannot simply check lowFreqMotor == highFreqMotor == 0
            // because our simulatedAmplitude could be 0 even though our inputs
            // are not (ex: lowFreqMotor == 0 && highFreqMotor == 1).
            vibrator.cancel();
            return;
        }

        // Attempt to use amplitude-based control if we're on Oreo and the device
        // supports amplitude-based vibration control.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (vibrator.hasAmplitudeControl()) {
                VibrationEffect effect = VibrationEffect.createOneShot(60000, simulatedAmplitude);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    VibrationAttributes vibrationAttributes = new VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_MEDIA)
                            .build();
                    vibrator.vibrate(effect, vibrationAttributes);
                }
                else {
                    AudioAttributes audioAttributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .build();
                    vibrator.vibrate(effect, audioAttributes);
                }
                return;
            }
        }

        // If we reach this point, we don't have amplitude controls available, so
        // we must emulate it by PWMing the vibration. Ick.
        long pwmPeriod = 20;
        long onTime = (long)((simulatedAmplitude / 255.0) * pwmPeriod);
        long offTime = pwmPeriod - onTime;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes vibrationAttributes = new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build();
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, onTime, offTime}, 0), vibrationAttributes);
        }
        else {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .build();
            vibrator.vibrate(new long[]{0, onTime, offTime}, 0, audioAttributes);
        }
    }

    public void handleRumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        boolean foundMatchingDevice = false;
        boolean vibrated = false;

        if (stopped) {
            return;
        }

        for (int i = 0; i < inputDeviceContexts.size(); i++) {
            InputDeviceContext deviceContext = inputDeviceContexts.valueAt(i);

            if (deviceContext.controllerNumber == controllerNumber) {
                foundMatchingDevice = true;

                deviceContext.lowFreqMotor = lowFreqMotor;
                deviceContext.highFreqMotor = highFreqMotor;

                // Prefer the documented Android 12 rumble API which can handle dual vibrators on PS/Xbox controllers
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && deviceContext.vibratorManager != null) {
                    vibrated = true;
                    if (deviceContext.quadVibrators) {
                        rumbleQuadVibrators(deviceContext.vibratorManager,
                                deviceContext.lowFreqMotor, deviceContext.highFreqMotor,
                                deviceContext.leftTriggerMotor, deviceContext.rightTriggerMotor);
                    }
                    else {
                        rumbleDualVibrators(deviceContext.vibratorManager,
                                deviceContext.lowFreqMotor, deviceContext.highFreqMotor);
                    }
                }
                // On Shield devices, we can use their special API to rumble Shield controllers
                else if (sceManager.rumble(deviceContext.inputDevice, deviceContext.lowFreqMotor, deviceContext.highFreqMotor)) {
                    vibrated = true;
                }
                // If all else fails, we have to try the old Vibrator API
                else if (deviceContext.vibrator != null) {
                    vibrated = true;
                    rumbleSingleVibrator(deviceContext.vibrator, deviceContext.lowFreqMotor, deviceContext.highFreqMotor);
                }
            }
        }

        for (int i = 0; i < usbDeviceContexts.size(); i++) {
            UsbDeviceContext deviceContext = usbDeviceContexts.valueAt(i);

            if (deviceContext.controllerNumber == controllerNumber) {
                foundMatchingDevice = vibrated = true;
                deviceContext.device.rumble(lowFreqMotor, highFreqMotor);
            }
        }

        // We may decide to rumble the device for player 1
        if (controllerNumber == 0) {
            // If we didn't find a matching device, it must be the on-screen
            // controls that triggered the rumble. Vibrate the device if
            // the user has requested that behavior.
            if (!foundMatchingDevice && prefConfig.onscreenController && !prefConfig.onlyL3R3 && prefConfig.vibrateOsc) {
                rumbleSingleVibrator(deviceVibrator, lowFreqMotor, highFreqMotor);
            }
            else if (foundMatchingDevice && !vibrated && prefConfig.vibrateFallbackToDevice) {
                // We found a device to vibrate but it didn't have rumble support. The user
                // has requested us to vibrate the device in this case.

                // We cast the unsigned short value to a signed int before multiplying by
                // the preferred strength. The resulting value is capped at 65534 before
                // we cast it back to a short so it doesn't go above 100%.
                short lowFreqMotorAdjusted = (short)(Math.min((((lowFreqMotor & 0xffff)
                        * prefConfig.vibrateFallbackToDeviceStrength) / 100), Short.MAX_VALUE*2));
                short highFreqMotorAdjusted = (short)(Math.min((((highFreqMotor & 0xffff)
                        * prefConfig.vibrateFallbackToDeviceStrength) / 100), Short.MAX_VALUE*2));

                rumbleSingleVibrator(deviceVibrator, lowFreqMotorAdjusted, highFreqMotorAdjusted);
            }
        }
    }

    public void handleRumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        if (stopped) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (int i = 0; i < inputDeviceContexts.size(); i++) {
                InputDeviceContext deviceContext = inputDeviceContexts.valueAt(i);

                if (deviceContext.controllerNumber == controllerNumber) {
                    deviceContext.leftTriggerMotor = leftTrigger;
                    deviceContext.rightTriggerMotor = rightTrigger;

                    if (deviceContext.quadVibrators) {
                        rumbleQuadVibrators(deviceContext.vibratorManager,
                                deviceContext.lowFreqMotor, deviceContext.highFreqMotor,
                                deviceContext.leftTriggerMotor, deviceContext.rightTriggerMotor);
                    }
                }
            }
        }

        for (int i = 0; i < usbDeviceContexts.size(); i++) {
            UsbDeviceContext deviceContext = usbDeviceContexts.valueAt(i);

            if (deviceContext.controllerNumber == controllerNumber) {
                deviceContext.device.rumbleTriggers(leftTrigger, rightTrigger);
            }
        }
    }

    private SensorEventListener createSensorListener(final short controllerNumber, final byte motionType, final boolean needsDeviceOrientationCorrection) {
        return new SensorEventListener() {
            private float[] lastValues = new float[3];

            @Override
            public void onSensorChanged(SensorEvent sensorEvent) {
                // Android will invoke our callback any time we get a new reading,
                // even if the values are the same as last time. Don't report a
                // duplicate set of values to save bandwidth.
                if (sensorEvent.values[0] == lastValues[0] &&
                        sensorEvent.values[1] == lastValues[1] &&
                        sensorEvent.values[2] == lastValues[2]) {
                    return;
                }
                else {
                    lastValues[0] = sensorEvent.values[0];
                    lastValues[1] = sensorEvent.values[1];
                    lastValues[2] = sensorEvent.values[2];
                }

                int x = 0;
                int y = 1;
                int z = 2;
                int xFactor = 1;
                int yFactor = 1;
                int zFactor = 1;

                if (needsDeviceOrientationCorrection) {
                    int deviceRotation = activityContext.getWindowManager().getDefaultDisplay().getRotation();
                    switch (deviceRotation) {
                        case Surface.ROTATION_0:
                        case Surface.ROTATION_180:
                            x = 0;
                            y = 2;
                            z = 1;
                            break;

                        case Surface.ROTATION_90:
                        case Surface.ROTATION_270:
                            x = 1;
                            y = 2;
                            z = 0;
                            break;
                    }

                    switch (deviceRotation) {
                        case Surface.ROTATION_0:
                            zFactor = -1;
                            break;
                        case Surface.ROTATION_90:
                            xFactor = -1;
                            zFactor = -1;
                            break;
                        case Surface.ROTATION_180:
                            xFactor = -1;
                            break;
                        case Surface.ROTATION_270:
                            break;
                    }
                }

                if (motionType == MoonBridge.LI_MOTION_TYPE_GYRO) {
                    // Convert from rad/s to deg/s
                    conn.sendControllerMotionEvent((byte) controllerNumber,
                            motionType,
                            sensorEvent.values[x] * xFactor * 57.2957795f,
                            sensorEvent.values[y] * yFactor * 57.2957795f,
                            sensorEvent.values[z] * zFactor * 57.2957795f);
                }
                else {
                    // Pass m/s^2 directly without conversion
                    conn.sendControllerMotionEvent((byte) controllerNumber,
                            motionType,
                            sensorEvent.values[x] * xFactor,
                            sensorEvent.values[y] * yFactor,
                            sensorEvent.values[z] * zFactor);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
    }

    public void handleSetMotionEventState(final short controllerNumber, final byte motionType, short reportRateHz) {
        if (stopped) {
            return;
        }

        // Report rate is restricted to <= 200 Hz without the HIGH_SAMPLING_RATE_SENSORS permission
        reportRateHz = (short) Math.min(200, reportRateHz);

        for (int i = 0; i < inputDeviceContexts.size(); i++) {
            InputDeviceContext deviceContext = inputDeviceContexts.valueAt(i);

            if (deviceContext.controllerNumber == controllerNumber) {
                // Store the desired report rate even if we don't have sensors. In some cases,
                // input devices can be reconfigured at runtime which results in a change where
                // sensors disappear and reappear. By storing the desired report rate, we can
                // reapply the desired motion sensor configuration after they reappear.
                switch (motionType) {
                    case MoonBridge.LI_MOTION_TYPE_ACCEL:
                        deviceContext.accelReportRateHz = reportRateHz;
                        break;
                    case MoonBridge.LI_MOTION_TYPE_GYRO:
                        deviceContext.gyroReportRateHz = reportRateHz;
                        break;
                }

                backgroundThreadHandler.removeCallbacks(deviceContext.enableSensorRunnable);

                SensorManager sm = deviceContext.sensorManager;
                if (sm == null) {
                    continue;
                }

                switch (motionType) {
                    case MoonBridge.LI_MOTION_TYPE_ACCEL:
                        if (deviceContext.accelListener != null) {
                            sm.unregisterListener(deviceContext.accelListener);
                            deviceContext.accelListener = null;
                        }

                        // Enable the accelerometer if requested
                        Sensor accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                        if (reportRateHz != 0 && accelSensor != null) {
                            deviceContext.accelListener = createSensorListener(controllerNumber, motionType, sm == deviceSensorManager);
                            sm.registerListener(deviceContext.accelListener, accelSensor, 1000000 / reportRateHz);
                        }
                        break;
                    case MoonBridge.LI_MOTION_TYPE_GYRO:
                        if (deviceContext.gyroListener != null) {
                            sm.unregisterListener(deviceContext.gyroListener);
                            deviceContext.gyroListener = null;
                        }

                        // Enable the gyroscope if requested
                        Sensor gyroSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                        if (reportRateHz != 0 && gyroSensor != null) {
                            deviceContext.gyroListener = createSensorListener(controllerNumber, motionType, sm == deviceSensorManager);
                            sm.registerListener(deviceContext.gyroListener, gyroSensor, 1000000 / reportRateHz);
                        }
                        break;
                }
                break;
            }
        }
    }

    public void handleSetControllerLED(short controllerNumber, byte r, byte g, byte b) {
        if (stopped) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (int i = 0; i < inputDeviceContexts.size(); i++) {
                InputDeviceContext deviceContext = inputDeviceContexts.valueAt(i);

                // Ignore input devices without an RGB LED
                if (deviceContext.controllerNumber == controllerNumber && deviceContext.hasRgbLed) {
                    // Create a new light session if one doesn't already exist
                    if (deviceContext.lightsSession == null) {
                        deviceContext.lightsSession = deviceContext.inputDevice.getLightsManager().openSession();
                    }

                    // Convert the RGB components into the integer value that LightState uses
                    int argbValue = 0xFF000000 | ((r << 16) & 0xFF0000) | ((g << 8) & 0xFF00) | (b & 0xFF);
                    LightState lightState = new LightState.Builder().setColor(argbValue).build();

                    // Set the RGB value for each RGB-controllable LED on the device
                    LightsRequest.Builder lightsRequestBuilder = new LightsRequest.Builder();
                    for (Light light : deviceContext.inputDevice.getLightsManager().getLights()) {
                        if (light.hasRgbControl()) {
                            lightsRequestBuilder.addLight(light, lightState);
                        }
                    }

                    // Apply the LED changes
                    deviceContext.lightsSession.requestLights(lightsRequestBuilder.build());
                }
            }
        }
    }

    public boolean handleButtonUp(KeyEvent event) {
        InputDeviceContext context = getContextForEvent(event);
        if (context == null) {
            return true;
        }

        int keyCode = handleRemapping(context, event);
        if (keyCode < 0) {
            return (keyCode == REMAP_CONSUME);
        }

        if (prefConfig.flipFaceButtons) {
            keyCode = handleFlipFaceButtons(keyCode);
        }

        // If the button hasn't been down long enough, sleep for a bit before sending the up event
        // This allows "instant" button presses (like OUYA's virtual menu button) to work. This
        // path should not be triggered during normal usage.
        int buttonDownTime = (int)(event.getEventTime() - event.getDownTime());
        if (buttonDownTime < ControllerHandler.MINIMUM_BUTTON_DOWN_TIME_MS)
        {
            // Since our sleep time is so short (<= 25 ms), it shouldn't cause a problem doing this
            // in the UI thread.
            try {
                Thread.sleep(ControllerHandler.MINIMUM_BUTTON_DOWN_TIME_MS - buttonDownTime);
            } catch (InterruptedException e) {
                e.printStackTrace();

                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();
            }
        }

        switch (keyCode) {
        case KeyEvent.KEYCODE_BUTTON_MODE:
            context.inputMap &= ~ControllerPacket.SPECIAL_BUTTON_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_START:
        case KeyEvent.KEYCODE_MENU:
            // Sometimes we'll get a spurious key up event on controller disconnect.
            // Make sure it's real by checking that the key is actually down before taking
            // any action.
            if ((context.inputMap & ControllerPacket.PLAY_FLAG) != 0 &&
                    event.getEventTime() - context.startDownTime > ControllerHandler.START_DOWN_TIME_MOUSE_MODE_MS &&
                    prefConfig.mouseEmulation) {
                context.toggleMouseEmulation();
            }
            context.inputMap &= ~ControllerPacket.PLAY_FLAG;
            break;
        case KeyEvent.KEYCODE_BACK:
        case KeyEvent.KEYCODE_BUTTON_SELECT:
            context.inputMap &= ~ControllerPacket.BACK_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_B:
            context.inputMap &= ~ControllerPacket.B_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_A:
            context.inputMap &= ~ControllerPacket.A_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_X:
            context.inputMap &= ~ControllerPacket.X_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_Y:
            context.inputMap &= ~ControllerPacket.Y_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_L1:
            context.inputMap &= ~ControllerPacket.LB_FLAG;
            context.lastLbUpTime = event.getEventTime();
            break;
        case KeyEvent.KEYCODE_BUTTON_R1:
            context.inputMap &= ~ControllerPacket.RB_FLAG;
            context.lastRbUpTime = event.getEventTime();
            break;
        case KeyEvent.KEYCODE_BUTTON_THUMBL:
            context.inputMap &= ~ControllerPacket.LS_CLK_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_THUMBR:
            context.inputMap &= ~ControllerPacket.RS_CLK_FLAG;
            break;
        case KeyEvent.KEYCODE_MEDIA_RECORD: // Xbox Series X Share button
            context.inputMap &= ~ControllerPacket.MISC_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_1: // PS4/PS5 touchpad button (prior to 4.10)
            context.inputMap &= ~ControllerPacket.TOUCHPAD_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_L2:
            if (context.leftTriggerAxisUsed) {
                // Suppress this digital event if an analog trigger is active
                return true;
            }
            context.leftTrigger = 0;
            break;
        case KeyEvent.KEYCODE_BUTTON_R2:
            if (context.rightTriggerAxisUsed) {
                // Suppress this digital event if an analog trigger is active
                return true;
            }
            context.rightTrigger = 0;
            break;
        case KeyEvent.KEYCODE_UNKNOWN:
            // Paddles aren't mapped in any of the Android key layout files,
            // so we need to handle the evdev key codes directly.
            if (context.hasPaddles) {
                switch (event.getScanCode()) {
                    case 0x2c4: // BTN_TRIGGER_HAPPY5
                        context.inputMap &= ~ControllerPacket.PADDLE1_FLAG;
                        break;
                    case 0x2c5: // BTN_TRIGGER_HAPPY6
                        context.inputMap &= ~ControllerPacket.PADDLE2_FLAG;
                        break;
                    case 0x2c6: // BTN_TRIGGER_HAPPY7
                        context.inputMap &= ~ControllerPacket.PADDLE3_FLAG;
                        break;
                    case 0x2c7: // BTN_TRIGGER_HAPPY8
                        context.inputMap &= ~ControllerPacket.PADDLE4_FLAG;
                        break;
                    default:
                        return false;
                }
            }
            else {
                return false;
            }
            break;
        default:
            return false;
        }

        // Check if we're emulating the select button
        if ((context.emulatingButtonFlags & ControllerHandler.EMULATING_SELECT) != 0)
        {
            // If either start or LB is up, select comes up too
            if ((context.inputMap & ControllerPacket.PLAY_FLAG) == 0 ||
                (context.inputMap & ControllerPacket.LB_FLAG) == 0)
            {
                context.inputMap &= ~ControllerPacket.BACK_FLAG;

                context.emulatingButtonFlags &= ~ControllerHandler.EMULATING_SELECT;
            }
        }

        // Check if we're emulating the special button
        if ((context.emulatingButtonFlags & ControllerHandler.EMULATING_SPECIAL) != 0)
        {
            // If either start or select and RB is up, the special button comes up too
            if ((context.inputMap & ControllerPacket.PLAY_FLAG) == 0 ||
                ((context.inputMap & ControllerPacket.BACK_FLAG) == 0 &&
                 (context.inputMap & ControllerPacket.RB_FLAG) == 0))
            {
                context.inputMap &= ~ControllerPacket.SPECIAL_BUTTON_FLAG;

                context.emulatingButtonFlags &= ~ControllerHandler.EMULATING_SPECIAL;
            }
        }

        // Check if we're emulating the touchpad button
        if ((context.emulatingButtonFlags & ControllerHandler.EMULATING_TOUCHPAD) != 0)
        {
            // If either select or LB is up, touchpad comes up too
            if ((context.inputMap & ControllerPacket.BACK_FLAG) == 0 ||
                    (context.inputMap & ControllerPacket.LB_FLAG) == 0)
            {
                context.inputMap &= ~ControllerPacket.TOUCHPAD_FLAG;

                context.emulatingButtonFlags &= ~ControllerHandler.EMULATING_TOUCHPAD;
            }
        }

        sendControllerInputPacket(context);

        if (context.pendingExit && context.inputMap == 0) {
            // All buttons from the quit combo are lifted. Finish the activity now.
            activityContext.finish();
        }

        return true;
    }

    public boolean handleButtonDown(KeyEvent event) {
        InputDeviceContext context = getContextForEvent(event);
        if (context == null) {
            return true;
        }

        int keyCode = handleRemapping(context, event);
        if (keyCode < 0) {
            return (keyCode == REMAP_CONSUME);
        }

        if (prefConfig.flipFaceButtons) {
            keyCode = handleFlipFaceButtons(keyCode);
        }

        switch (keyCode) {
        case KeyEvent.KEYCODE_BUTTON_MODE:
            context.hasMode = true;
            context.inputMap |= ControllerPacket.SPECIAL_BUTTON_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_START:
        case KeyEvent.KEYCODE_MENU:
            if (event.getRepeatCount() == 0) {
                context.startDownTime = event.getEventTime();
            }
            context.inputMap |= ControllerPacket.PLAY_FLAG;
            break;
        case KeyEvent.KEYCODE_BACK:
        case KeyEvent.KEYCODE_BUTTON_SELECT:
            context.hasSelect = true;
            context.inputMap |= ControllerPacket.BACK_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_B:
            context.inputMap |= ControllerPacket.B_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_A:
            context.inputMap |= ControllerPacket.A_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_X:
            context.inputMap |= ControllerPacket.X_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_Y:
            context.inputMap |= ControllerPacket.Y_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_L1:
            context.inputMap |= ControllerPacket.LB_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_R1:
            context.inputMap |= ControllerPacket.RB_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_THUMBL:
            context.inputMap |= ControllerPacket.LS_CLK_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_THUMBR:
            context.inputMap |= ControllerPacket.RS_CLK_FLAG;
            break;
        case KeyEvent.KEYCODE_MEDIA_RECORD: // Xbox Series X Share button
            context.inputMap |= ControllerPacket.MISC_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_1: // PS4/PS5 touchpad button (prior to 4.10)
            context.inputMap |= ControllerPacket.TOUCHPAD_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_L2:
            if (context.leftTriggerAxisUsed) {
                // Suppress this digital event if an analog trigger is active
                return true;
            }
            context.leftTrigger = (byte)0xFF;
            break;
        case KeyEvent.KEYCODE_BUTTON_R2:
            if (context.rightTriggerAxisUsed) {
                // Suppress this digital event if an analog trigger is active
                return true;
            }
            context.rightTrigger = (byte)0xFF;
            break;
        case KeyEvent.KEYCODE_UNKNOWN:
            // Paddles aren't mapped in any of the Android key layout files,
            // so we need to handle the evdev key codes directly.
            if (context.hasPaddles) {
                switch (event.getScanCode()) {
                    case 0x2c4: // BTN_TRIGGER_HAPPY5
                        context.inputMap |= ControllerPacket.PADDLE1_FLAG;
                        break;
                    case 0x2c5: // BTN_TRIGGER_HAPPY6
                        context.inputMap |= ControllerPacket.PADDLE2_FLAG;
                        break;
                    case 0x2c6: // BTN_TRIGGER_HAPPY7
                        context.inputMap |= ControllerPacket.PADDLE3_FLAG;
                        break;
                    case 0x2c7: // BTN_TRIGGER_HAPPY8
                        context.inputMap |= ControllerPacket.PADDLE4_FLAG;
                        break;
                    default:
                        return false;
                }
            }
            else {
                return false;
            }
            break;
        default:
            return false;
        }

        // Start+Back+LB+RB is the quit combo
        if (context.inputMap == (ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG |
                                 ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG)) {
            // Wait for the combo to lift and then finish the activity
            context.pendingExit = true;
        }

        // Start+LB acts like select for controllers with one button
        if (!context.hasSelect) {
            if (context.inputMap == (ControllerPacket.PLAY_FLAG | ControllerPacket.LB_FLAG) ||
                    (context.inputMap == ControllerPacket.PLAY_FLAG &&
                            event.getEventTime() - context.lastLbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS))
            {
                context.inputMap &= ~(ControllerPacket.PLAY_FLAG | ControllerPacket.LB_FLAG);
                context.inputMap |= ControllerPacket.BACK_FLAG;

                context.emulatingButtonFlags |= ControllerHandler.EMULATING_SELECT;
            }
        }
        else if (context.needsClickpadEmulation) {
            // Select+LB acts like the clickpad when we're faking a PS4 controller for motion support
            if (context.inputMap == (ControllerPacket.BACK_FLAG | ControllerPacket.LB_FLAG) ||
                    (context.inputMap == ControllerPacket.BACK_FLAG &&
                            event.getEventTime() - context.lastLbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS))
            {
                context.inputMap &= ~(ControllerPacket.BACK_FLAG | ControllerPacket.LB_FLAG);
                context.inputMap |= ControllerPacket.TOUCHPAD_FLAG;

                context.emulatingButtonFlags |= ControllerHandler.EMULATING_TOUCHPAD;
            }
        }

        // If there is a physical select button, we'll use Start+Select as the special button combo
        // otherwise we'll use Start+RB.
        if (!context.hasMode) {
            if (context.hasSelect) {
                if (context.inputMap == (ControllerPacket.PLAY_FLAG | ControllerPacket.BACK_FLAG)) {
                    context.inputMap &= ~(ControllerPacket.PLAY_FLAG | ControllerPacket.BACK_FLAG);
                    context.inputMap |= ControllerPacket.SPECIAL_BUTTON_FLAG;

                    context.emulatingButtonFlags |= ControllerHandler.EMULATING_SPECIAL;
                }
            }
            else {
                if (context.inputMap == (ControllerPacket.PLAY_FLAG | ControllerPacket.RB_FLAG) ||
                        (context.inputMap == ControllerPacket.PLAY_FLAG &&
                                event.getEventTime() - context.lastRbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS))
                {
                    context.inputMap &= ~(ControllerPacket.PLAY_FLAG | ControllerPacket.RB_FLAG);
                    context.inputMap |= ControllerPacket.SPECIAL_BUTTON_FLAG;

                    context.emulatingButtonFlags |= ControllerHandler.EMULATING_SPECIAL;
                }
            }
        }

        // We don't need to send repeat key down events, but the platform
        // sends us events that claim to be repeats but they're from different
        // devices, so we just send them all and deal with some duplicates.
        sendControllerInputPacket(context);
        return true;
    }

    public void reportOscState(int buttonFlags,
                               short leftStickX, short leftStickY,
                               short rightStickX, short rightStickY,
                               byte leftTrigger, byte rightTrigger) {
        defaultContext.leftStickX = leftStickX;
        defaultContext.leftStickY = leftStickY;

        defaultContext.rightStickX = rightStickX;
        defaultContext.rightStickY = rightStickY;

        defaultContext.leftTrigger = leftTrigger;
        defaultContext.rightTrigger = rightTrigger;

        defaultContext.inputMap = buttonFlags;

        sendControllerInputPacket(defaultContext);
    }

    @Override
    public void reportControllerState(int controllerId, int buttonFlags,
                                      float leftStickX, float leftStickY,
                                      float rightStickX, float rightStickY,
                                      float leftTrigger, float rightTrigger) {
        GenericControllerContext context = usbDeviceContexts.get(controllerId);
        if (context == null) {
            return;
        }

        Vector2d leftStickVector = populateCachedVector(leftStickX, leftStickY);

        handleDeadZone(leftStickVector, context.leftStickDeadzoneRadius);

        context.leftStickX = (short) (leftStickVector.getX() * 0x7FFE);
        context.leftStickY = (short) (-leftStickVector.getY() * 0x7FFE);

        Vector2d rightStickVector = populateCachedVector(rightStickX, rightStickY);

        handleDeadZone(rightStickVector, context.rightStickDeadzoneRadius);

        context.rightStickX = (short) (rightStickVector.getX() * 0x7FFE);
        context.rightStickY = (short) (-rightStickVector.getY() * 0x7FFE);

        if (leftTrigger <= context.triggerDeadzone) {
            leftTrigger = 0;
        }
        if (rightTrigger <= context.triggerDeadzone) {
            rightTrigger = 0;
        }

        context.leftTrigger = (byte)(leftTrigger * 0xFF);
        context.rightTrigger = (byte)(rightTrigger * 0xFF);

        context.inputMap = buttonFlags;

        sendControllerInputPacket(context);
    }

    @Override
    public void deviceRemoved(AbstractController controller) {
        UsbDeviceContext context = usbDeviceContexts.get(controller.getControllerId());
        if (context != null) {
            LimeLog.info("Removed controller: "+controller.getControllerId());
            releaseControllerNumber(context);
            context.destroy();
            usbDeviceContexts.remove(controller.getControllerId());
        }
    }

    @Override
    public void deviceAdded(AbstractController controller) {
        if (stopped) {
            return;
        }

        UsbDeviceContext context = createUsbDeviceContextForDevice(controller);
        usbDeviceContexts.put(controller.getControllerId(), context);
    }

    class GenericControllerContext {
        public int id;
        public boolean external;

        public int vendorId;
        public int productId;

        public float leftStickDeadzoneRadius;
        public float rightStickDeadzoneRadius;
        public float triggerDeadzone;

        public boolean assignedControllerNumber;
        public boolean reservedControllerNumber;
        public short controllerNumber;

        public int inputMap = 0;
        public byte leftTrigger = 0x00;
        public byte rightTrigger = 0x00;
        public short rightStickX = 0x0000;
        public short rightStickY = 0x0000;
        public short leftStickX = 0x0000;
        public short leftStickY = 0x0000;

        public boolean mouseEmulationActive;
        public int mouseEmulationLastInputMap;
        public final int mouseEmulationReportPeriod = 50;

        public final Runnable mouseEmulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mouseEmulationActive) {
                    return;
                }

                // Send mouse events from analog sticks
                if (prefConfig.analogStickForScrolling == PreferenceConfiguration.AnalogStickForScrolling.RIGHT) {
                    sendEmulatedMouseMove(leftStickX, leftStickY);
                    sendEmulatedMouseScroll(rightStickX, rightStickY);
                }
                else if (prefConfig.analogStickForScrolling == PreferenceConfiguration.AnalogStickForScrolling.LEFT) {
                    sendEmulatedMouseMove(rightStickX, rightStickY);
                    sendEmulatedMouseScroll(leftStickX, leftStickY);
                }
                else {
                    sendEmulatedMouseMove(leftStickX, leftStickY);
                    sendEmulatedMouseMove(rightStickX, rightStickY);
                }

                // Requeue the callback
                mainThreadHandler.postDelayed(this, mouseEmulationReportPeriod);
            }
        };

        public void toggleMouseEmulation() {
            mainThreadHandler.removeCallbacks(mouseEmulationRunnable);
            mouseEmulationActive = !mouseEmulationActive;
            Toast.makeText(activityContext, "Mouse emulation is: " + (mouseEmulationActive ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();

            if (mouseEmulationActive) {
                mainThreadHandler.postDelayed(mouseEmulationRunnable, mouseEmulationReportPeriod);
            }
        }

        public void destroy() {
            mouseEmulationActive = false;
            mainThreadHandler.removeCallbacks(mouseEmulationRunnable);
        }

        public void sendControllerArrival() {}
    }

    class InputDeviceContext extends GenericControllerContext {
        public String name;
        public VibratorManager vibratorManager;
        public Vibrator vibrator;
        public boolean quadVibrators;
        public short lowFreqMotor, highFreqMotor;
        public short leftTriggerMotor, rightTriggerMotor;

        public SensorManager sensorManager;
        public SensorEventListener gyroListener;
        public short gyroReportRateHz;
        public SensorEventListener accelListener;
        public short accelReportRateHz;

        public InputDevice inputDevice;

        public boolean hasRgbLed;
        public LightsManager.LightsSession lightsSession;

        // These are BatteryState values, not Moonlight values
        public int lastReportedBatteryStatus;
        public float lastReportedBatteryCapacity;

        public int leftStickXAxis = -1;
        public int leftStickYAxis = -1;

        public int rightStickXAxis = -1;
        public int rightStickYAxis = -1;

        public int leftTriggerAxis = -1;
        public int rightTriggerAxis = -1;
        public boolean triggersIdleNegative;
        public boolean leftTriggerAxisUsed, rightTriggerAxisUsed;

        public int hatXAxis = -1;
        public int hatYAxis = -1;
        public boolean hatXAxisUsed, hatYAxisUsed;

        InputDevice.MotionRange touchpadXRange;
        InputDevice.MotionRange touchpadYRange;
        InputDevice.MotionRange touchpadPressureRange;

        public boolean isNonStandardDualShock4;
        public boolean usesLinuxGamepadStandardFaceButtons;
        public boolean isNonStandardXboxBtController;
        public boolean isServal;
        public boolean backIsStart;
        public boolean modeIsSelect;
        public boolean searchIsMode;
        public boolean ignoreBack;
        public boolean hasJoystickAxes;
        public boolean pendingExit;
        public boolean isDualShockStandaloneTouchpad;

        public int emulatingButtonFlags = 0;
        public boolean hasSelect;
        public boolean hasMode;
        public boolean hasPaddles;
        public boolean hasShare;
        public boolean needsClickpadEmulation;

        // Used for OUYA bumper state tracking since they force all buttons
        // up when the OUYA button goes down. We watch the last time we get
        // a bumper up and compare that to our maximum delay when we receive
        // a Start button press to see if we should activate one of our
        // emulated button combos.
        public long lastLbUpTime = 0;
        public long lastRbUpTime = 0;

        public long startDownTime = 0;

        public final Runnable batteryStateUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                sendControllerBatteryPacket(InputDeviceContext.this);

                // Requeue the callback
                backgroundThreadHandler.postDelayed(this, BATTERY_RECHECK_INTERVAL_MS);
            }
        };

        public final Runnable enableSensorRunnable = new Runnable() {
            @Override
            public void run() {
                // Turn back on any sensors that should be reporting but are currently unregistered
                if (accelReportRateHz != 0 && accelListener == null) {
                    handleSetMotionEventState(controllerNumber, MoonBridge.LI_MOTION_TYPE_ACCEL, accelReportRateHz);
                }
                if (gyroReportRateHz != 0 && gyroListener == null) {
                    handleSetMotionEventState(controllerNumber, MoonBridge.LI_MOTION_TYPE_GYRO, gyroReportRateHz);
                }
            }
        };

        @Override
        public void destroy() {
            super.destroy();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibratorManager != null) {
                vibratorManager.cancel();
            }
            else if (vibrator != null) {
                vibrator.cancel();
            }

            backgroundThreadHandler.removeCallbacks(enableSensorRunnable);

            if (gyroListener != null) {
                sensorManager.unregisterListener(gyroListener);
            }
            if (accelListener != null) {
                sensorManager.unregisterListener(accelListener);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (lightsSession != null) {
                    lightsSession.close();
                }
            }

            backgroundThreadHandler.removeCallbacks(batteryStateUpdateRunnable);
        }

        @Override
        public void sendControllerArrival() {
            byte type;
            switch (inputDevice.getVendorId()) {
                case 0x045e: // Microsoft
                    type = MoonBridge.LI_CTYPE_XBOX;
                    break;
                case 0x054c: // Sony
                    type = MoonBridge.LI_CTYPE_PS;
                    break;
                case 0x057e: // Nintendo
                    type = MoonBridge.LI_CTYPE_NINTENDO;
                    break;
                default:
                    // Consult SDL's controller type list to see if it knows
                    type = MoonBridge.guessControllerType(inputDevice.getVendorId(), inputDevice.getProductId());
                    break;
            }

            int supportedButtonFlags = 0;
            for (Map.Entry<Integer, Integer> entry : ANDROID_TO_LI_BUTTON_MAP.entrySet()) {
                if (inputDevice.hasKeys(entry.getKey())[0]) {
                    supportedButtonFlags |= entry.getValue();
                }
            }

            // Add non-standard button flags that may not be mapped in the Android kl file
            if (hasPaddles) {
                supportedButtonFlags |=
                        ControllerPacket.PADDLE1_FLAG |
                        ControllerPacket.PADDLE2_FLAG |
                        ControllerPacket.PADDLE3_FLAG |
                        ControllerPacket.PADDLE4_FLAG;
            }
            if (hasShare) {
                supportedButtonFlags |= ControllerPacket.MISC_FLAG;
            }

            if (getMotionRangeForJoystickAxis(inputDevice, MotionEvent.AXIS_HAT_X) != null) {
                supportedButtonFlags |= ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG;
            }
            if (getMotionRangeForJoystickAxis(inputDevice, MotionEvent.AXIS_HAT_Y) != null) {
                supportedButtonFlags |= ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG;
            }

            short capabilities = 0;

            // Most of the advanced InputDevice capabilities came in Android S
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (quadVibrators) {
                    capabilities |= MoonBridge.LI_CCAP_RUMBLE | MoonBridge.LI_CCAP_TRIGGER_RUMBLE;
                }
                else if (vibratorManager != null || vibrator != null) {
                    capabilities |= MoonBridge.LI_CCAP_RUMBLE;
                }

                // Calling InputDevice.getBatteryState() to see if a battery is present
                // performs a Binder transaction that can cause ANRs on some devices.
                // To avoid this, we will just claim we can report battery state for all
                // external gamepad devices on Android S. If it turns out that no battery
                // is actually present, we'll just report unknown battery state to the host.
                if (external) {
                    capabilities |= MoonBridge.LI_CCAP_BATTERY_STATE;
                }

                // Light.hasRgbControl() was totally broken prior to Android 14.
                // It always returned true because LIGHT_CAPABILITY_RGB was defined as 0,
                // so we will just guess RGB is supported if it's a PlayStation controller.
                if (hasRgbLed && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || type == MoonBridge.LI_CTYPE_PS)) {
                    capabilities |= MoonBridge.LI_CCAP_RGB_LED;
                }
            }

            // Report analog triggers if we have at least one trigger axis
            if (leftTriggerAxis != -1 || rightTriggerAxis != -1) {
                capabilities |= MoonBridge.LI_CCAP_ANALOG_TRIGGERS;
            }

            // Report sensors if the input device has them or we're using built-in sensors for a built-in controller
            if (sensorManager != null && sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                capabilities |= MoonBridge.LI_CCAP_ACCEL;
            }
            if (sensorManager != null && sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                capabilities |= MoonBridge.LI_CCAP_GYRO;
            }

            byte reportedType;
            if (type != MoonBridge.LI_CTYPE_PS && sensorManager != null) {
                // Override the detected controller type if we're emulating motion sensors on an Xbox controller
                Toast.makeText(activityContext, activityContext.getResources().getText(R.string.toast_controller_type_changed), Toast.LENGTH_LONG).show();
                reportedType = MoonBridge.LI_CTYPE_UNKNOWN;

                // Remember that we should enable the clickpad emulation combo (Select+LB) for this device
                needsClickpadEmulation = true;
            }
            else {
                // Report the true type to the host PC if we're not emulating motion sensors
                reportedType = type;
            }

            // We can perform basic rumble with any vibrator
            if (vibrator != null) {
                capabilities |= MoonBridge.LI_CCAP_RUMBLE;
            }

            // Shield controllers use special APIs for rumble and battery state
            if (sceManager.isRecognizedDevice(inputDevice)) {
                capabilities |= MoonBridge.LI_CCAP_RUMBLE | MoonBridge.LI_CCAP_BATTERY_STATE;
            }

            if ((inputDevice.getSources() & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                capabilities |= MoonBridge.LI_CCAP_TOUCHPAD;

                // Use the platform API or internal heuristics to determine if this has a clickpad
                if (hasButtonUnderTouchpad(inputDevice, type)) {
                    supportedButtonFlags |= ControllerPacket.TOUCHPAD_FLAG;
                }
            }

            conn.sendControllerArrivalEvent((byte)controllerNumber, getActiveControllerMask(),
                    reportedType, supportedButtonFlags, capabilities);

            // After reporting arrival to the host, send initial battery state and begin monitoring
            backgroundThreadHandler.post(batteryStateUpdateRunnable);
        }

        public void migrateContext(InputDeviceContext oldContext) {
            // Take ownership of the sensor and light sessions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.lightsSession = oldContext.lightsSession;
                oldContext.lightsSession = null;
            }
            this.gyroReportRateHz = oldContext.gyroReportRateHz;
            this.accelReportRateHz = oldContext.accelReportRateHz;

            // Don't release the controller number, because we will carry it over if it is present.
            // We also want to make sure the change is invisible to the host PC to avoid an add/remove
            // cycle for the gamepad which may break some games.
            oldContext.destroy();

            // Copy over existing controller number state
            this.assignedControllerNumber = oldContext.assignedControllerNumber;
            this.reservedControllerNumber = oldContext.reservedControllerNumber;
            this.controllerNumber = oldContext.controllerNumber;

            // We may have set this device to use the built-in sensor manager. If so, do that again.
            if (oldContext.sensorManager == deviceSensorManager) {
                this.sensorManager = deviceSensorManager;
            }

            // Copy state initialized in reportControllerArrival()
            this.needsClickpadEmulation = oldContext.needsClickpadEmulation;

            // Re-enable sensors on the new context
            enableSensors();

            // Refresh battery state and start the battery state polling again
            backgroundThreadHandler.post(batteryStateUpdateRunnable);
        }

        public void disableSensors() {
            // Stop any pending enablement
            backgroundThreadHandler.removeCallbacks(enableSensorRunnable);

            // Unregister all sensor listeners
            if (gyroListener != null) {
                sensorManager.unregisterListener(gyroListener);
                gyroListener = null;

                // Send a gyro event to ensure the virtual controller is stationary
                conn.sendControllerMotionEvent((byte) controllerNumber, MoonBridge.LI_MOTION_TYPE_GYRO, 0.f, 0.f, 0.f);
            }
            if (accelListener != null) {
                sensorManager.unregisterListener(accelListener);
                accelListener = null;

                // We leave the acceleration as-is to preserve the attitude of the controller
            }
        }

        public void enableSensors() {
            // We allow 1 second for the input device to settle before re-enabling sensors.
            // Pointer capture can cause the input device to change, which can cause
            // InputDeviceSensorManager to crash due to missing null checks on the InputDevice.
            backgroundThreadHandler.postDelayed(enableSensorRunnable, 1000);
        }
    }

    class UsbDeviceContext extends GenericControllerContext {
        public AbstractController device;

        @Override
        public void destroy() {
            super.destroy();

            // Nothing for now
        }

        @Override
        public void sendControllerArrival() {
            conn.sendControllerArrivalEvent((byte)controllerNumber, getActiveControllerMask(),
                    device.getType(), device.getSupportedButtonFlags(), device.getCapabilities());
        }
    }
}
