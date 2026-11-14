package com.cococopi.dualaudio.routing;

import android.content.Context;
import android.util.Log;

/**
 * Runs inside a Shizuku app_process as the ADB shell user (uid 2000) or root.
 * It owns the combined audio routing and an optional watcher that reapplies
 * the preference after a device reconnects or the audio policy resets.
 */
public class AudioRoutingUserService extends IAudioRoutingService.Stub {

    private static final String TAG = "DualAudioUserService";
    private static final long WATCH_INTERVAL_MS = 3000L;

    private final AudioRouter router;
    private final BluetoothConnector bluetooth;
    private volatile String[] addresses;
    private volatile boolean enabled;
    private Thread watcher;

    /** Used by Shizuku versions before v13. */
    public AudioRoutingUserService() {
        this(null);
    }

    /** Shizuku v13 and newer try this constructor first. */
    public AudioRoutingUserService(Context context) {
        try {
            router = new AudioRouter();
        } catch (Throwable t) {
            throw new IllegalStateException("could not access AudioService: " + rootMessage(t), t);
        }
        bluetooth = context != null ? new BluetoothConnector(context) : null;
    }

    @Override
    public synchronized String apply(String[] requested) {
        if (requested == null || requested.length < 2) {
            return "ERROR: two device addresses are required";
        }
        addresses = requested.clone();
        enabled = true;
        String result;
        try {
            result = router.apply(addresses);
        } catch (Throwable t) {
            result = "ERROR: " + rootMessage(t);
        }
        startWatcher();
        return result;
    }

    @Override
    public synchronized String clear() {
        enabled = false;
        interruptWatcher();
        try {
            return router.clear();
        } catch (Throwable t) {
            return "ERROR: " + rootMessage(t);
        }
    }

    @Override
    public String status() {
        try {
            return router.status()
                    + "\nauto-reapply: " + (enabled ? "on" : "off")
                    + "\nwatch addresses: " + describeAddresses(addresses);
        } catch (Throwable t) {
            return "ERROR: " + rootMessage(t);
        }
    }

    @Override
    public boolean isApplied(String[] requested) {
        if (requested == null) return false;
        try {
            return router.isApplied(requested);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String connectDevice(String address) {
        if (bluetooth == null) return "ERROR: no Bluetooth context in user service";
        try {
            return "OK: " + bluetooth.connect(address);
        } catch (Throwable t) {
            return "ERROR: " + rootMessage(t);
        }
    }

    @Override
    public String disconnectDevice(String address) {
        if (bluetooth == null) return "ERROR: no Bluetooth context in user service";
        try {
            return "OK: " + bluetooth.disconnect(address);
        } catch (Throwable t) {
            return "ERROR: " + rootMessage(t);
        }
    }

    @Override
    public void destroy() {
        enabled = false;
        interruptWatcher();
        try {
            router.clear();
        } catch (Throwable ignored) {
        }
        System.exit(0);
    }

    private synchronized void startWatcher() {
        if (watcher != null) return;
        watcher = new Thread(() -> {
            Log.i(TAG, "routing watcher started");
            while (enabled && !Thread.currentThread().isInterrupted()) {
                try {
                    String[] current = addresses;
                    if (current != null && current.length >= 2
                            && router.bothConnected(current)
                            && !router.isApplied(current)) {
                        Log.i(TAG, "reapplying routing -> " + router.apply(current));
                    }
                    Thread.sleep(WATCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "reapply failed", t);
                }
            }
            Log.i(TAG, "routing watcher stopped");
        }, "dual-audio-routing-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private synchronized void interruptWatcher() {
        if (watcher != null) {
            watcher.interrupt();
            watcher = null;
        }
    }

    private static String describeAddresses(String[] addresses) {
        if (addresses == null) return "none";
        StringBuilder sb = new StringBuilder();
        for (String address : addresses) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(address);
        }
        return sb.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
