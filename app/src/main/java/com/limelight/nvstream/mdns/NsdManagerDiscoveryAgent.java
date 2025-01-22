package com.limelight.nvstream.mdns;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import com.limelight.LimeLog;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class NsdManagerDiscoveryAgent extends MdnsDiscoveryAgent {
    private static final String SERVICE_TYPE = "_nvstream._tcp";
    private final NsdManager nsdManager;
    private final Object listenerLock = new Object();
    private NsdManager.DiscoveryListener pendingListener;
    private NsdManager.DiscoveryListener activeListener;
    private final HashMap<String, NsdManager.ServiceInfoCallback> serviceCallbacks = new HashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    private NsdManager.DiscoveryListener createDiscoveryListener() {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                LimeLog.severe("NSD: Service discovery start failed: " + errorCode);

                // This listener is no longer pending after this failure
                synchronized (listenerLock) {
                    if (pendingListener != this) {
                        return;
                    }

                    pendingListener = null;
                }

                listener.notifyDiscoveryFailure(new RuntimeException("onStartDiscoveryFailed(): " + errorCode));
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                LimeLog.severe("NSD: Service discovery stop failed: " + errorCode);

                // This listener is no longer active after this failure
                synchronized (listenerLock) {
                    if (activeListener != this) {
                        return;
                    }

                    activeListener = null;
                }
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                LimeLog.info("NSD: Service discovery started");

                synchronized (listenerLock) {
                    if (pendingListener != this) {
                        // If we registered another discovery listener in the meantime, stop this one
                        nsdManager.stopServiceDiscovery(this);
                        return;
                    }

                    pendingListener = null;
                    activeListener = this;
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                LimeLog.info("NSD: Service discovery stopped");

                synchronized (listenerLock) {
                    if (activeListener != this) {
                        return;
                    }

                    activeListener = null;
                }
            }

            @Override
            public void onServiceFound(NsdServiceInfo nsdServiceInfo) {
                // Protect against racing stopDiscovery() call
                synchronized (listenerLock) {
                    // Ignore callbacks if we're not the active listener
                    if (activeListener != this) {
                        return;
                    }

                    LimeLog.info("NSD: Machine appeared: " + nsdServiceInfo.getServiceName());

                    NsdManager.ServiceInfoCallback serviceInfoCallback = new NsdManager.ServiceInfoCallback() {
                        @Override
                        public void onServiceInfoCallbackRegistrationFailed(int errorCode) {
                            LimeLog.severe("NSD: Service info callback registration failed: " + errorCode);
                            listener.notifyDiscoveryFailure(new RuntimeException("onServiceInfoCallbackRegistrationFailed(): " + errorCode));
                        }

                        @Override
                        public void onServiceUpdated(NsdServiceInfo nsdServiceInfo) {
                            LimeLog.info("NSD: Machine resolved: " + nsdServiceInfo.getServiceName());
                            reportNewComputer(nsdServiceInfo.getServiceName(), nsdServiceInfo.getPort(),
                                    getV4Addrs(nsdServiceInfo.getHostAddresses()),
                                    getV6Addrs(nsdServiceInfo.getHostAddresses()));
                        }

                        @Override
                        public void onServiceLost() {
                        }

                        @Override
                        public void onServiceInfoCallbackUnregistered() {
                        }
                    };

                    nsdManager.registerServiceInfoCallback(nsdServiceInfo, executor, serviceInfoCallback);
                    serviceCallbacks.put(nsdServiceInfo.getServiceName(), serviceInfoCallback);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo nsdServiceInfo) {
                // Protect against racing stopDiscovery() call
                synchronized (listenerLock) {
                    // Ignore callbacks if we're not the active listener
                    if (activeListener != this) {
                        return;
                    }

                    LimeLog.info("NSD: Machine lost: " + nsdServiceInfo.getServiceName());

                    NsdManager.ServiceInfoCallback serviceInfoCallback = serviceCallbacks.remove(nsdServiceInfo.getServiceName());
                    if (serviceInfoCallback != null) {
                        nsdManager.unregisterServiceInfoCallback(serviceInfoCallback);
                    }
                }
            }
        };
    }

    public NsdManagerDiscoveryAgent(Context context, MdnsDiscoveryListener listener) {
        super(listener);
        this.nsdManager = context.getSystemService(NsdManager.class);
    }

    @Override
    public void startDiscovery(int discoveryIntervalMs) {
        synchronized (listenerLock) {
            // Register a new service discovery listener if there's not already one starting or running
            if (pendingListener == null && activeListener == null) {
                pendingListener = createDiscoveryListener();
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, pendingListener);
            }
        }
    }

    @Override
    public void stopDiscovery() {
        // Protect against racing ServiceInfoCallback and DiscoveryListener callbacks
        synchronized (listenerLock) {
            // Clear any pending listener to ensure the discoverStarted() callback
            // will realize it's gone and stop itself.
            pendingListener = null;

            // Unregister the service discovery listener
            if (activeListener != null) {
                nsdManager.stopServiceDiscovery(activeListener);

                // Even though listener stoppage is asynchronous, the listener is gone as far as
                // we're concerned. We null this right now to ensure pending callbacks know it's
                // stopped and startDiscovery() can immediately create a new listener. If we left
                // it until onDiscoveryStopped() was called, startDiscovery() would get confused
                // and assume a listener was already running, even though it's stopping.
                activeListener = null;
            }

            // Unregister all service info callbacks
            for (NsdManager.ServiceInfoCallback callback : serviceCallbacks.values()) {
                nsdManager.unregisterServiceInfoCallback(callback);
            }
            serviceCallbacks.clear();
        }
    }

    private static Inet4Address[] getV4Addrs(List<InetAddress> addrs) {
        int matchCount = 0;
        for (InetAddress addr : addrs) {
            if (addr instanceof Inet4Address) {
                matchCount++;
            }
        }

        Inet4Address[] matching = new Inet4Address[matchCount];

        int i = 0;
        for (InetAddress addr : addrs) {
            if (addr instanceof Inet4Address) {
                matching[i++] = (Inet4Address) addr;
            }
        }

        return matching;
    }

    private static Inet6Address[] getV6Addrs(List<InetAddress> addrs) {
        int matchCount = 0;
        for (InetAddress addr : addrs) {
            if (addr instanceof Inet6Address) {
                matchCount++;
            }
        }

        Inet6Address[] matching = new Inet6Address[matchCount];

        int i = 0;
        for (InetAddress addr : addrs) {
            if (addr instanceof Inet6Address) {
                matching[i++] = (Inet6Address) addr;
            }
        }

        return matching;
    }
}
