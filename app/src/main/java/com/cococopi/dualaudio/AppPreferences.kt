package com.cococopi.dualaudio

import android.content.Context

/**
 * Small, synchronous preference store. Only non-sensitive device identifiers
 * and UI flags are persisted; everything can be cleared from system settings.
 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("dual_audio_prefs", Context.MODE_PRIVATE)

    var deviceAAddress: String?
        get() = prefs.getString(KEY_DEVICE_A_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_A_ADDRESS, value).apply()

    var deviceBAddress: String?
        get() = prefs.getString(KEY_DEVICE_B_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_B_ADDRESS, value).apply()

    var deviceAName: String?
        get() = prefs.getString(KEY_DEVICE_A_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_A_NAME, value).apply()

    var deviceBName: String?
        get() = prefs.getString(KEY_DEVICE_B_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_B_NAME, value).apply()

    /** Keeps the foreground helper alive and reapplies routing after reconnects. */
    var autoApply: Boolean
        get() = prefs.getBoolean(KEY_AUTO_APPLY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_APPLY, value).apply()

    /** True after a successful forced-routing request. */
    var routingActive: Boolean
        get() = prefs.getBoolean(KEY_ROUTING_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_ROUTING_ACTIVE, value).apply()

    var onboardingSeen: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_SEEN, value).apply()

    fun clearDeviceA() {
        prefs.edit()
            .remove(KEY_DEVICE_A_ADDRESS)
            .remove(KEY_DEVICE_A_NAME)
            .apply()
    }

    fun clearDeviceB() {
        prefs.edit()
            .remove(KEY_DEVICE_B_ADDRESS)
            .remove(KEY_DEVICE_B_NAME)
            .apply()
    }

    private companion object {
        const val KEY_DEVICE_A_ADDRESS = "device_a_address"
        const val KEY_DEVICE_B_ADDRESS = "device_b_address"
        const val KEY_DEVICE_A_NAME = "device_a_name"
        const val KEY_DEVICE_B_NAME = "device_b_name"
        const val KEY_AUTO_APPLY = "auto_apply"
        const val KEY_ROUTING_ACTIVE = "routing_active"
        const val KEY_ONBOARDING_SEEN = "onboarding_seen"
    }
}
