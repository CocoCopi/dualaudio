package com.cococopi.dualaudio.routing;

/**
 * Contract with the Shizuku user service, which runs in an app_process with
 * shell (ADB) or root UID. Do not reorder transaction IDs without bumping the
 * service version in ShizukuManager.
 */
interface IAudioRoutingService {

    /** Reserved by Shizuku: called when the user service is unbound. */
    void destroy() = 16777114;

    /** Routes media to exactly these Bluetooth output addresses. */
    String apply(in String[] addresses) = 1;

    /** Removes the preferred devices and restores normal routing. */
    String clear() = 2;

    /** Human-readable routing state. */
    String status() = 3;

    /** True when the media strategy already prefers exactly these addresses. */
    boolean isApplied(in String[] addresses) = 4;

    /** Experimental: asks the Bluetooth stack to connect a bonded device. */
    String connectDevice(String address) = 5;

    /** Experimental: asks the Bluetooth stack to disconnect a device. */
    String disconnectDevice(String address) = 6;
}
