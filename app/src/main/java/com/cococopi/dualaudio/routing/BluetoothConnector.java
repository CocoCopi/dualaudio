package com.cococopi.dualaudio.routing;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional one-tap connect helper. It runs in the Shizuku shell process, where
 * BLUETOOTH_PRIVILEGED is available and the non-SDK profile connect() methods
 * can be called. If the process has no usable Bluetooth context (or the ROM
 * rejects the call), the app falls back to opening Bluetooth settings.
 */
final class BluetoothConnector {

    private static final String TAG = "DualAudioBt";
    private static final long PROXY_TIMEOUT_SECONDS = 5;

    private final Context context;
    private final BluetoothAdapter adapter;

    BluetoothConnector(Context context) {
        this.context = context;
        BluetoothAdapter found = null;
        if (context != null) {
            try {
                BluetoothManager manager =
                        (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                if (manager != null) found = manager.getAdapter();
            } catch (Throwable t) {
                Log.w(TAG, "BluetoothManager unavailable", t);
            }
        }
        if (found == null) {
            try {
                found = BluetoothAdapter.getDefaultAdapter();
            } catch (Throwable ignored) {
                // no adapter
            }
        }
        adapter = found;
    }

    String connect(String address) throws Exception {
        return setConnected(address, true);
    }

    String disconnect(String address) throws Exception {
        return setConnected(address, false);
    }

    private String setConnected(String address, boolean connect) throws Exception {
        if (adapter == null) throw new IllegalStateException("no Bluetooth adapter");
        if (!adapter.isEnabled()) throw new IllegalStateException("Bluetooth is off");

        BluetoothDevice device = adapter.getRemoteDevice(address);
        Exception last = null;

        // Classic audio devices use A2DP. LE Audio devices use the LE Audio profile.
        try {
            boolean result = callConnection(BluetoothProfile.A2DP, device, connect);
            if (result) return "A2DP " + (connect ? "connect" : "disconnect") + " requested";
        } catch (Throwable t) {
            last = asException(t);
            Log.w(TAG, "A2DP operation failed", t);
        }

        try {
            boolean result = callConnection(BluetoothProfile.LE_AUDIO, device, connect);
            if (result) return "LE_AUDIO " + (connect ? "connect" : "disconnect") + " requested";
        } catch (Throwable t) {
            last = asException(t);
            Log.w(TAG, "LE Audio operation failed", t);
        }

        if (last != null) throw last;
        throw new IllegalStateException("the Bluetooth stack rejected the request");
    }

    private boolean callConnection(int profile, BluetoothDevice device, boolean connect)
            throws Exception {
        BluetoothProfile proxy = getProfile(profile);
        try {
            String methodName = connect ? "connect" : "disconnect";
            Method method = proxy.getClass().getMethod(methodName, BluetoothDevice.class);
            method.setAccessible(true);
            Object result = method.invoke(proxy, device);
            return result instanceof Boolean && (Boolean) result;
        } finally {
            try {
                adapter.closeProfileProxy(profile, proxy);
            } catch (Throwable ignored) {
            }
        }
    }

    private BluetoothProfile getProfile(int profile) throws Exception {
        if (context == null) throw new IllegalStateException("no context in user service");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BluetoothProfile> proxyRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();

        boolean requested = adapter.getProfileProxy(context,
                new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profileId, BluetoothProfile proxy) {
                        proxyRef.set(proxy);
                        latch.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(int profileId) {
                        // Nothing to do; the caller closes the proxy.
                    }
                }, profile);

        if (!requested) {
            throw new IllegalStateException("could not request Bluetooth profile " + profile);
        }
        if (!latch.await(PROXY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            errorRef.set("timed out waiting for Bluetooth profile " + profile);
            throw new IllegalStateException(errorRef.get());
        }
        BluetoothProfile proxy = proxyRef.get();
        if (proxy == null) {
            throw new IllegalStateException("Bluetooth profile " + profile + " unavailable");
        }
        return proxy;
    }

    private static Exception asException(Throwable t) {
        if (t instanceof Exception) return (Exception) t;
        return new Exception(t);
    }
}
