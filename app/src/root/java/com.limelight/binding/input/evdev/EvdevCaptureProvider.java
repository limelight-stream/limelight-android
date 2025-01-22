package com.limelight.binding.input.evdev;

import android.app.Activity;
import android.os.Build;
import android.os.Looper;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.binding.input.capture.InputCaptureProvider;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class EvdevCaptureProvider extends InputCaptureProvider {

    private final EvdevListener listener;
    private final String libraryPath;

    private boolean shutdown = false;
    private InputStream evdevIn;
    private OutputStream evdevOut;
    private Process su;
    private ServerSocket servSock;
    private Socket evdevSock;
    private Activity activity;
    private boolean started = false;

    private static final byte UNGRAB_REQUEST = 1;
    private static final byte REGRAB_REQUEST = 2;

    private final Thread handlerThread = new Thread() {
        @Override
        public void run() {
            int deltaX = 0;
            int deltaY = 0;
            byte deltaVScroll = 0;
            byte deltaHScroll = 0;

            // Bind a local listening socket for evdevreader to connect to
            try {
                servSock = new ServerSocket(0, 1);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            final String evdevReaderCmd = libraryPath+File.separatorChar+"libevdev_reader.so "+servSock.getLocalPort();

            // On Nougat and later, we'll need to pass the command directly to SU.
            // Writing to SU's input stream after it has started doesn't seem to work anymore.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Launch evdev_reader directly via SU
                try {
                    su = new ProcessBuilder("su", "-c", evdevReaderCmd).start();
                } catch (IOException e) {
                    reportDeviceNotRooted();
                    e.printStackTrace();
                    return;
                }
            }
            else {
                // Launch a SU shell on Marshmallow and earlier
                ProcessBuilder builder = new ProcessBuilder("su");
                builder.redirectErrorStream(true);

                try {
                    su = builder.start();
                } catch (IOException e) {
                    reportDeviceNotRooted();
                    e.printStackTrace();
                    return;
                }

                // Start evdevreader
                DataOutputStream suOut = new DataOutputStream(su.getOutputStream());
                try {
                    suOut.writeChars(evdevReaderCmd+"\n");
                } catch (IOException e) {
                    reportDeviceNotRooted();
                    e.printStackTrace();
                    return;
                }
            }

            // Wait for evdevreader's connection
            LimeLog.info("Waiting for EvdevReader connection to port "+servSock.getLocalPort());
            try {
                evdevSock = servSock.accept();
                evdevIn = evdevSock.getInputStream();
                evdevOut = evdevSock.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            LimeLog.info("EvdevReader connected from port "+evdevSock.getPort());

            while (!isInterrupted() && !shutdown) {
                EvdevEvent event;
                try {
                    event = EvdevReader.read(evdevIn);
                } catch (IOException e) {
                    event = null;
                }
                if (event == null) {
                    break;
                }

                // Note: The EvdevReader process already filters input events when grabbing
                // is not enabled, so we don't need to that here.

                switch (event.type) {
                    case EvdevEvent.EV_SYN:
                        if (deltaX != 0 || deltaY != 0) {
                            listener.mouseMove(deltaX, deltaY);
                            deltaX = deltaY = 0;
                        }
                        if (deltaVScroll != 0) {
                            listener.mouseVScroll(deltaVScroll);
                            deltaVScroll = 0;
                        }
                        if (deltaHScroll != 0) {
                            listener.mouseHScroll(deltaHScroll);
                            deltaHScroll = 0;
                        }
                        break;

                    case EvdevEvent.EV_REL:
                        switch (event.code) {
                            case EvdevEvent.REL_X:
                                deltaX = event.value;
                                break;
                            case EvdevEvent.REL_Y:
                                deltaY = event.value;
                                break;
                            case EvdevEvent.REL_HWHEEL:
                                deltaHScroll = (byte) event.value;
                                break;
                            case EvdevEvent.REL_WHEEL:
                                deltaVScroll = (byte) event.value;
                                break;
                        }
                        break;

                    case EvdevEvent.EV_KEY:
                        switch (event.code) {
                            case EvdevEvent.BTN_LEFT:
                                listener.mouseButtonEvent(EvdevListener.BUTTON_LEFT,
                                        event.value != 0);
                                break;
                            case EvdevEvent.BTN_MIDDLE:
                                listener.mouseButtonEvent(EvdevListener.BUTTON_MIDDLE,
                                        event.value != 0);
                                break;
                            case EvdevEvent.BTN_RIGHT:
                                listener.mouseButtonEvent(EvdevListener.BUTTON_RIGHT,
                                        event.value != 0);
                                break;

                            case EvdevEvent.BTN_SIDE:
                                listener.mouseButtonEvent(EvdevListener.BUTTON_X1,
                                        event.value != 0);
                                break;

                            case EvdevEvent.BTN_EXTRA:
                                listener.mouseButtonEvent(EvdevListener.BUTTON_X2,
                                        event.value != 0);
                                break;

                            case EvdevEvent.BTN_FORWARD:
                            case EvdevEvent.BTN_BACK:
                            case EvdevEvent.BTN_TASK:
                                // Other unhandled mouse buttons
                                break;

                            default:
                                // We got some unrecognized button. This means
                                // someone is trying to use the other device in this
                                // "combination" input device. We'll try to handle
                                // it via keyboard, but we're not going to disconnect
                                // if we can't
                                short keyCode = EvdevTranslator.translateEvdevKeyCode(event.code);
                                if (keyCode != 0) {
                                    listener.keyboardEvent(event.value != 0, keyCode);
                                }
                                break;
                        }
                        break;

                    case EvdevEvent.EV_MSC:
                        break;
                }
            }
        }
    };

    public EvdevCaptureProvider(Activity activity, EvdevListener listener) {
        this.listener = listener;
        this.activity = activity;
        this.libraryPath = activity.getApplicationInfo().nativeLibraryDir;
    }

    private void reportDeviceNotRooted() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, "This device is not rooted - Mouse capture is unavailable", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void runInNetworkSafeContextSynchronously(Runnable runnable) {
        // This function is used to avoid Android's strict NetworkOnMainThreadException.
        // For our usage, it is highly unlikely to cause problems since we only do
        // write operations and only to localhost sockets.
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            Thread t = new Thread(runnable);
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                // The main thread should never be interrupted
                e.printStackTrace();
            }
        }
        else {
            // Run the runnable directly
            runnable.run();
        }
    }

    @Override
    public void showCursor() {
        super.showCursor();
        // This may be called on the main thread
        runInNetworkSafeContextSynchronously(new Runnable() {
            @Override
            public void run() {
                if (started && !shutdown && evdevOut != null) {
                    try {
                        evdevOut.write(UNGRAB_REQUEST);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    @Override
    public void hideCursor() {
        super.hideCursor();
        // This may be called on the main thread
        runInNetworkSafeContextSynchronously(new Runnable() {
            @Override
            public void run() {
                // Send a request to regrab if we're already capturing
                if (started && !shutdown && evdevOut != null) {
                    try {
                        evdevOut.write(REGRAB_REQUEST);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    @Override
    public void enableCapture() {
        if (!started) {
            // Start the handler thread if it's our first time
            // capturing
            handlerThread.start();
            started = true;
        }

        // Call the superclass only after we've started the handler thread.
        // It will invoke hideCursor() when we call it.
        super.enableCapture();
    }

    @Override
    public void destroy() {
        // We need to stop the process in this context otherwise
        // we could get stuck waiting on output from the process
        // in order to terminate it.
        //
        // This may be called on the main thread.

        if (!started) {
            return;
        }

        shutdown = true;
        handlerThread.interrupt();

        runInNetworkSafeContextSynchronously(new Runnable() {
            @Override
            public void run() {
                if (servSock != null) {
                    try {
                        servSock.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                if (evdevSock != null) {
                    try {
                        evdevSock.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                if (evdevIn != null) {
                    try {
                        evdevIn.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                if (evdevOut != null) {
                    try {
                        evdevOut.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        if (su != null) {
            su.destroy();
        }

        try {
            handlerThread.join();
        } catch (InterruptedException ignored) {}
    }
}
