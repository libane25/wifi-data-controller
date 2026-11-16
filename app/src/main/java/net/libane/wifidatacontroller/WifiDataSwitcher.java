package net.libane.wifidatacontroller;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs inside system_server. Watches Wi-Fi networks and sets the user mobile-data
 * switch: off while any Wi-Fi network is up, on when the last one is gone.
 * Uses the same user-data reason as Quick Settings, so IMS/voice is left alone.
 */
public final class WifiDataSwitcher {
    public interface LogSink {
        void log(int priority, String message, Throwable error);
    }

    private static final String TAG = "WiFiDataController";
    private static final long DEBOUNCE_MS = 400;
    private static final int MAX_FAILURES = 8;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Set<Network> wifiNetworks = new LinkedHashSet<>();
    private final HandlerThread callbackThread;
    private final Handler callbackHandler;
    private final ExecutorService dataWorker;
    private final Runnable dispatch;

    private LogSink logSink = (priority, message, error) -> Log.println(priority, TAG, message);
    private Context appContext;
    private volatile boolean wifiConnected;
    private Boolean appliedEnabled;
    private int consecutiveFailures;

    public WifiDataSwitcher() {
        callbackThread = new HandlerThread("WiFiDataController-net");
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());
        dataWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WiFiDataController-data");
            thread.setDaemon(false);
            return thread;
        });
        dispatch = () -> {
            wifiConnected = !wifiNetworks.isEmpty();
            dataWorker.execute(this::applyOnWorker);
        };
    }

    public void start(LogSink sink) {
        if (sink != null) logSink = sink;
        if (!started.compareAndSet(false, true)) return;
        Thread init = new Thread(this::awaitAndRegister, "WiFiDataController-init");
        init.setDaemon(true);
        init.start();
    }

    private void awaitAndRegister() {
        for (int attempt = 1; attempt <= 90; attempt++) {
            Context context = systemContext();
            if (context != null) {
                try {
                    register(context);
                    log(Log.INFO, "Watching Wi-Fi in system_server (attempt " + attempt + ").", null);
                    return;
                } catch (Throwable error) {
                    log(Log.WARN, "Could not register the Wi-Fi callback.", error);
                }
            } else {
                log(Log.INFO, "System context is not ready (attempt " + attempt + ").", null);
            }
            sleep(1000);
        }
        log(Log.ERROR, "Gave up waiting for a system context; mobile data will not follow Wi-Fi.", null);
    }

    private void register(Context context) {
        Context app = context.getApplicationContext();
        appContext = app != null ? app : context;
        ConnectivityManager connectivity = appContext.getSystemService(ConnectivityManager.class);
        if (connectivity == null) throw new IllegalStateException("ConnectivityManager missing");

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .build();

        NetworkCallback callback = new NetworkCallback(connectivity);
        CountDownLatch registered = new CountDownLatch(1);
        callbackHandler.post(() -> {
            try {
                connectivity.registerNetworkCallback(request, callback, callbackHandler);
                for (Network network : connectivity.getAllNetworks()) {
                    remember(connectivity, network);
                }
                callbackHandler.post(dispatch);
            } catch (Throwable error) {
                log(Log.ERROR, "registerNetworkCallback failed.", error);
            } finally {
                registered.countDown();
            }
        });
        try {
            if (!registered.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out registering the Wi-Fi callback");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private void remember(ConnectivityManager connectivity, Network network) {
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            wifiNetworks.add(network);
        }
    }

    private void schedule(String reason) {
        consecutiveFailures = 0;
        log(Log.INFO, reason + "; wifiNetworks=" + wifiNetworks.size(), null);
        callbackHandler.removeCallbacks(dispatch);
        callbackHandler.postDelayed(dispatch, DEBOUNCE_MS);
    }

    private void applyOnWorker() {
        boolean wantData = !wifiConnected;
        Context context = appContext;
        if (context == null) return;
        if (appliedEnabled != null && appliedEnabled == wantData) {
            log(Log.INFO, "Mobile data already " + (wantData ? "on" : "off")
                    + " (wifi " + (wifiConnected ? "up" : "down") + ").", null);
            return;
        }
        boolean ok = setMobileData(context, wantData);
        if (ok) {
            appliedEnabled = wantData;
            consecutiveFailures = 0;
            log(Log.INFO, "Mobile data turned " + (wantData ? "on" : "off")
                    + " because Wi-Fi is " + (wifiConnected ? "connected" : "disconnected") + ".", null);
            return;
        }
        consecutiveFailures++;
        log(Log.WARN, "Failed to turn mobile data " + (wantData ? "on" : "off")
                + " (failure " + consecutiveFailures + ").", null);
        if (consecutiveFailures <= MAX_FAILURES) {
            callbackHandler.postDelayed(dispatch, 2000);
        }
    }

    private boolean setMobileData(Context context, boolean enabled) {
        TelephonyManager base = context.getSystemService(TelephonyManager.class);
        if (base == null) return false;
        boolean any = false;
        for (int subId : dataSubscriptions(context)) {
            TelephonyManager telephony = subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    ? base
                    : base.createForSubscriptionId(subId);
            try {
                if (telephony.isDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER) == enabled) {
                    any = true;
                    continue;
                }
            } catch (Throwable ignored) {
                // The write below is the source of truth if the read is blocked.
            }
            try {
                telephony.setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, enabled);
                any = true;
                log(Log.INFO, "setDataEnabledForReason sub=" + subId + " enabled=" + enabled, null);
            } catch (Throwable error) {
                log(Log.WARN, "setDataEnabledForReason failed for sub " + subId + "; writing the setting.", error);
                if (writeDataSetting(context, subId, enabled)) any = true;
            }
        }
        return any;
    }

    private static int[] dataSubscriptions(Context context) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        int defaultData = SubscriptionManager.getDefaultDataSubscriptionId();
        if (defaultData != SubscriptionManager.INVALID_SUBSCRIPTION_ID) ids.add(defaultData);
        SubscriptionManager subscriptions = context.getSystemService(SubscriptionManager.class);
        if (subscriptions != null) {
            try {
                List<SubscriptionInfo> active = subscriptions.getActiveSubscriptionInfoList();
                if (active != null) {
                    for (SubscriptionInfo info : active) {
                        if (info == null) continue;
                        int subId = info.getSubscriptionId();
                        if (subId == defaultData || info.getSimSlotIndex() >= 0) ids.add(subId);
                    }
                }
            } catch (Throwable ignored) {
                // Default data sub is enough when the list is not readable yet.
            }
        }
        if (ids.isEmpty()) ids.add(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        int[] out = new int[ids.size()];
        int index = 0;
        for (int id : ids) out[index++] = id;
        return out;
    }

    private static boolean writeDataSetting(Context context, int subId, boolean enabled) {
        String key = subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                ? "mobile_data"
                : "mobile_data" + subId;
        return Settings.Global.putInt(context.getContentResolver(), key, enabled ? 1 : 0);
    }

    private static Context systemContext() {
        Looper main = Looper.getMainLooper();
        if (main == null) return null;
        Context[] holder = new Context[1];
        CountDownLatch ready = new CountDownLatch(1);
        new Handler(main).post(() -> {
            holder[0] = readSystemContext();
            ready.countDown();
        });
        try {
            ready.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return holder[0];
    }

    private static Context readSystemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            if (system != null) return system;
            return (Context) activityThread.getMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void log(int priority, String message, Throwable error) {
        if (error != null) Log.println(priority, TAG, message + ": " + error);
        else Log.println(priority, TAG, message);
        try {
            logSink.log(priority, message, error);
        } catch (Throwable ignored) {
            // Logging must not break the switch.
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private final class NetworkCallback extends ConnectivityManager.NetworkCallback {
        private final ConnectivityManager connectivity;

        NetworkCallback(ConnectivityManager connectivity) {
            this.connectivity = connectivity;
        }

        @Override
        public void onAvailable(Network network) {
            try {
                remember(connectivity, network);
                schedule("Wi-Fi network available " + network);
            } catch (Throwable error) {
                log(Log.ERROR, "onAvailable failed.", error);
            }
        }

        @Override
        public void onLost(Network network) {
            try {
                wifiNetworks.remove(network);
                schedule("Wi-Fi network lost " + network);
            } catch (Throwable error) {
                log(Log.ERROR, "onLost failed.", error);
            }
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            try {
                boolean wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                boolean changed = wifi ? wifiNetworks.add(network) : wifiNetworks.remove(network);
                if (changed) schedule("Wi-Fi capabilities changed " + network);
            } catch (Throwable error) {
                log(Log.ERROR, "onCapabilitiesChanged failed.", error);
            }
        }
    }
}
