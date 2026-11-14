package com.cococopi.dualaudio.routing;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Routes the media audio strategy to several output devices at once.
 *
 * <p>This file intentionally uses reflection and only public SDK types: it must
 * compile in the normal app module, but it is executed in a Shizuku user
 * service process (ADB shell or root) where hidden SDK interfaces are not
 * restricted. In a regular app process the calls fail with a SecurityException
 * because {@code MODIFY_AUDIO_ROUTING} is a privileged permission.
 *
 * <p>Android 12 added combined audio device routing. The framework mixes the
 * stream once and duplicates it to all preferred outputs. Whether two A2DP
 * devices can actually be connected at the same time is still a property of the
 * Bluetooth stack, not of this class.
 */
final class AudioRouter {

    private static final String TAG = "DualAudioRouter";

    private static final int ROLE_OUTPUT = 2; // AudioDeviceAttributes.ROLE_OUTPUT
    private static final int GET_DEVICES_OUTPUTS = 2;
    private static final int FALLBACK_MEDIA_STRATEGY_ID = 0;

    private final Object audioService;
    private final int strategyId;

    AudioRouter() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "audio");
        if (binder == null) {
            throw new IllegalStateException("audio service is not available");
        }
        audioService = Class.forName("android.media.IAudioService$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
        strategyId = findMediaStrategyId();
    }

    int getStrategyId() {
        return strategyId;
    }

    /**
     * Sets the media strategy's preferred outputs to the given Bluetooth
     * addresses. At least two distinct connected outputs are required.
     */
    String apply(String[] addresses) throws Exception {
        List<AudioDeviceInfo> targets = findTargets(addresses, true);
        List<Object> attributes = new ArrayList<>(targets.size());
        for (AudioDeviceInfo info : targets) {
            attributes.add(toAttributes(info));
        }

        Object result = invoke(
                new String[]{"setPreferredDevicesForStrategy"},
                strategyId,
                attributes);
        int code = result instanceof Integer ? (Integer) result : 0;
        String summary = "setPreferredDevicesForStrategy -> " + code
                + " (" + describeDevices(targets) + ")";
        Log.i(TAG, summary);
        return summary;
    }

    /** Removes any preference for the media strategy, restoring normal routing. */
    String clear() throws Exception {
        Object result = invoke(
                new String[]{
                        "removePreferredDevicesForStrategy",
                        "clearPreferredDevicesForStrategy",
                        "removePreferredDeviceForStrategy"},
                strategyId);
        int code = result instanceof Integer ? (Integer) result : 0;
        String summary = "removePreferredDevicesForStrategy -> " + code;
        Log.i(TAG, summary);
        return summary;
    }

    /** True when the preferred outputs are exactly the given addresses. */
    boolean isApplied(String[] addresses) {
        try {
            List<AudioDeviceInfo> targets = findTargets(addresses, false);
            if (targets.size() < 2) return false;

            List<?> preferred = getPreferred();
            if (preferred == null || preferred.size() != targets.size()) return false;

            Set<String> wanted = new LinkedHashSet<>();
            for (AudioDeviceInfo info : targets) {
                wanted.add(key(info.getType(), info.getAddress()));
            }
            Set<String> actual = new LinkedHashSet<>();
            for (Object device : preferred) {
                actual.add(key(
                        (int) call(device, "getType"),
                        (String) call(device, "getAddress")));
            }
            return wanted.equals(actual);
        } catch (Throwable t) {
            Log.w(TAG, "isApplied failed", t);
            return false;
        }
    }

    /** True when both addresses are currently visible as Bluetooth outputs. */
    boolean bothConnected(String[] addresses) {
        try {
            return findTargets(addresses, false).size() >= 2;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Human-readable state for the app and for bug reports. */
    String status() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("STRATEGY_MEDIA id=").append(strategyId).append('\n');
        sb.append("connected outputs:\n");
        List<AudioDeviceInfo> outputs = getOutputDevices();
        if (outputs.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (AudioDeviceInfo info : outputs) {
                sb.append("  ").append(typeName(info.getType()))
                        .append(" name=").append(info.getProductName())
                        .append(" addr=").append(info.getAddress())
                        .append('\n');
            }
        }
        sb.append("preferred: ").append(getPreferred());
        return sb.toString();
    }

    /** A compact list of Bluetooth outputs, for the UI's diagnostics screen. */
    String bluetoothOutputs() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (AudioDeviceInfo info : getOutputDevices()) {
            if (isBluetoothOutputType(info.getType())) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(typeName(info.getType()))
                        .append(" | ").append(info.getProductName())
                        .append(" | ").append(info.getAddress());
            }
        }
        return sb.toString();
    }

    private List<AudioDeviceInfo> findTargets(String[] addresses, boolean requireTwo)
            throws Exception {
        if (addresses == null) {
            throw new IllegalArgumentException("no addresses supplied");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String address : addresses) {
            if (address != null && !address.trim().isEmpty()) {
                unique.add(address.trim());
            }
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("no device addresses supplied");
        }

        List<AudioDeviceInfo> found = new ArrayList<>();
        for (String address : unique) {
            AudioDeviceInfo match = findOutputByAddress(address);
            if (match == null) {
                throw new IllegalStateException(
                        "Bluetooth output is not connected: " + address);
            }
            found.add(match);
        }
        if (requireTwo && found.size() < 2) {
            throw new IllegalStateException("two connected Bluetooth outputs are required");
        }
        return found;
    }

    private AudioDeviceInfo findOutputByAddress(String address) throws Exception {
        for (AudioDeviceInfo info : getOutputDevices()) {
            if (!isBluetoothOutputType(info.getType())) continue;
            if (address.equalsIgnoreCase(info.getAddress())) {
                return info;
            }
        }
        return null;
    }

    private static boolean isBluetoothOutputType(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_BLE_BROADCAST;
    }

    private static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "A2DP";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "BLE_HEADSET";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return "BLE_SPEAKER";
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return "BLE_BROADCAST";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "SPEAKER";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB_HEADSET";
            default:
                return "TYPE_" + type;
        }
    }

    private static String describeDevices(List<AudioDeviceInfo> devices) {
        StringBuilder sb = new StringBuilder();
        for (AudioDeviceInfo info : devices) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(typeName(info.getType())).append(' ').append(info.getAddress());
        }
        return sb.toString();
    }

    private List<?> getPreferred() throws Exception {
        Object result = invoke(new String[]{"getPreferredDevicesForStrategy"}, strategyId);
        if (result instanceof List) {
            return (List<?>) result;
        }
        Object single = invoke(new String[]{"getPreferredDeviceForStrategy"}, strategyId);
        List<Object> one = new ArrayList<>();
        if (single != null) one.add(single);
        return one;
    }

    @SuppressLint("SoonBlockedPrivateApi") // This runs in the shell-UID user service process.
    private static List<AudioDeviceInfo> getOutputDevices() throws Exception {
        Method method = Class.forName("android.media.AudioManager")
                .getDeclaredMethod("getDevicesStatic", int.class);
        method.setAccessible(true);
        AudioDeviceInfo[] devices = (AudioDeviceInfo[]) method.invoke(null, GET_DEVICES_OUTPUTS);
        return devices == null ? new ArrayList<>() : Arrays.asList(devices);
    }

    /** AudioDeviceAttributes is a system API, so it is created reflectively. */
    private static Object toAttributes(AudioDeviceInfo info) throws Exception {
        Constructor<?> constructor = Class.forName("android.media.AudioDeviceAttributes")
                .getConstructor(int.class, int.class, String.class);
        return constructor.newInstance(ROLE_OUTPUT, info.getType(), info.getAddress());
    }

    private static Object call(Object target, String name) throws Exception {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static String key(int type, String address) {
        return type + ":" + (address == null ? "" : address.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Finds the product strategy that handles USAGE_MEDIA. The strategy ID is
     * stable per boot but not guaranteed to be 0 on every vendor build.
     */
    private static int findMediaStrategyId() {
        try {
            AudioAttributes media = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build();
            List<?> strategies = (List<?>) Class.forName("android.media.AudioManager")
                    .getMethod("getAudioProductStrategies")
                    .invoke(null);
            if (strategies != null) {
                for (Object strategy : strategies) {
                    boolean supports = (boolean) strategy.getClass()
                            .getMethod("supportsAudioAttributes", AudioAttributes.class)
                            .invoke(strategy, media);
                    if (supports) {
                        return (int) strategy.getClass().getMethod("getId").invoke(strategy);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not discover media strategy, using " + FALLBACK_MEDIA_STRATEGY_ID, t);
        }
        return FALLBACK_MEDIA_STRATEGY_ID;
    }

    /**
     * Calls the first method with one of the names and the matching arity.
     * The IAudioService AIDL shape changed across Android releases.
     */
    private Object invoke(String[] names, Object... args) throws Exception {
        for (String name : names) {
            for (Method method : audioService.getClass().getMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getParameterCount() != args.length) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(audioService, args);
                } catch (IllegalArgumentException ignored) {
                    // Try the next overload; some vendor builds add parameters.
                }
            }
        }
        throw new NoSuchMethodException(Arrays.toString(names)
                + " with " + args.length + " args");
    }
}
