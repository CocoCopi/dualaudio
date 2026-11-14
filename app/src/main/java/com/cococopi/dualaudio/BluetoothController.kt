package com.cococopi.dualaudio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Reads bonded Bluetooth audio devices and their profile state using only
 * public Android APIs. Connecting and disconnecting is intentionally not done
 * here: those calls are hidden, so they are delegated to the optional
 * [com.cococopi.dualaudio.routing.ShizukuManager] helper or to system settings.
 */
@SuppressLint("MissingPermission") // Every entry point checks BLUETOOTH_CONNECT first.
class BluetoothController(context: Context) {

    fun interface Listener {
        fun onBluetoothStateChanged()
    }

    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val listeners = CopyOnWriteArraySet<Listener>()

    private var a2dp: BluetoothA2dp? = null
    private var headset: BluetoothHeadset? = null
    private var leAudio: BluetoothProfile? = null
    private var receiverRegistered = false

    val adapter: BluetoothAdapter?
        get() = manager?.adapter

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun start() {
        registerReceiver()
        requestProfileProxies()
    }

    fun stop() {
        unregisterReceiver()
        closeProfileProxies()
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(): Boolean {
        if (!hasPermission()) return false
        return runCatching { adapter?.isEnabled == true }.getOrDefault(false)
    }

    /**
     * Bonded devices that can play audio. If the phone reports no device with
     * an audio major class (some LE devices do this), every bonded device is
     * returned so the picker is never empty when pairings exist.
     */
    fun bondedDevices(): List<BluetoothDevice> {
        if (!hasPermission()) return emptyList()
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull().orEmpty()
        val audio = bonded.filter { isAudioDevice(it) }
        val result = if (audio.isEmpty()) bonded.toList() else audio
        return result.sortedWith(
            compareByDescending<BluetoothDevice> { isConnected(it) }
                .thenBy { nameOf(it).lowercase() },
        )
    }

    fun isAudioDevice(device: BluetoothDevice): Boolean {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return true
        // LE Audio devices occasionally expose no class at all.
        if (major == null) return true
        return isConnected(device)
    }

    fun nameOf(device: BluetoothDevice): String {
        val alias = runCatching { device.alias }.getOrNull()
        if (!alias.isNullOrBlank()) return alias
        val name = runCatching { device.name }.getOrNull()
        if (!name.isNullOrBlank()) return name
        return device.address
    }

    /** Best overall connection state across A2DP, headset and LE Audio. */
    fun connectionState(device: BluetoothDevice): Int {
        val states = mutableListOf<Int>()
        a2dp?.let { states.add(runCatching { it.getConnectionState(device) }.getOrDefault(DISCONNECTED)) }
        headset?.let { states.add(runCatching { it.getConnectionState(device) }.getOrDefault(DISCONNECTED)) }
        leAudio?.let { states.add(runCatching { it.getConnectionState(device) }.getOrDefault(DISCONNECTED)) }

        if (states.isEmpty()) {
            // Proxies may still be binding; fall back to the per-profile call.
            if (hasPermission()) {
                val a2dpState = runCatching {
                    adapter?.getProfileConnectionState(BluetoothProfile.A2DP)
                }.getOrNull()
                if (a2dpState == BluetoothProfile.STATE_CONNECTED) return BluetoothProfile.STATE_CONNECTED
            }
            return DISCONNECTED
        }
        return when {
            states.any { it == BluetoothProfile.STATE_CONNECTED } -> BluetoothProfile.STATE_CONNECTED
            states.any { it == BluetoothProfile.STATE_CONNECTING } -> BluetoothProfile.STATE_CONNECTING
            states.any { it == BluetoothProfile.STATE_DISCONNECTING } -> BluetoothProfile.STATE_DISCONNECTING
            else -> DISCONNECTED
        }
    }

    fun isConnected(device: BluetoothDevice): Boolean =
        connectionState(device) == BluetoothProfile.STATE_CONNECTED

    fun connectedDevices(): List<BluetoothDevice> =
        bondedDevices().filter { isConnected(it) }

    /** Number of connected classic A2DP sinks, or null when it cannot be read. */
    fun connectedA2dpCount(): Int? {
        val proxy = a2dp ?: return null
        return runCatching { proxy.connectedDevices.size }.getOrNull()
    }

    fun isSamsung(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun isLeAudioSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!hasPermission()) return false
        val status = runCatching { adapter?.isLeAudioSupported }.getOrNull() ?: return false
        return status == BluetoothStatusCodes.FEATURE_SUPPORTED
    }

    fun isLeAudioBroadcastSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!hasPermission()) return false
        val status = runCatching {
            adapter?.isLeAudioBroadcastSourceSupported
        }.getOrNull() ?: return false
        return status == BluetoothStatusCodes.FEATURE_SUPPORTED
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ALIAS_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
            }
        }
        try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        } catch (_: SecurityException) {
            // Permission revoked between checks; the UI already handles that case.
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    private fun requestProfileProxies() {
        val current = adapter ?: return
        if (!hasPermission()) return
        if (a2dp == null) {
            runCatching {
                current.getProfileProxy(appContext, profileListener, BluetoothProfile.A2DP)
            }
        }
        if (headset == null) {
            runCatching {
                current.getProfileProxy(appContext, profileListener, BluetoothProfile.HEADSET)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && leAudio == null) {
            runCatching {
                current.getProfileProxy(appContext, profileListener, BluetoothProfile.LE_AUDIO)
            }
        }
    }

    private fun closeProfileProxies() {
        val current = adapter
        a2dp?.let { proxy -> runCatching { current?.closeProfileProxy(BluetoothProfile.A2DP, proxy) } }
        headset?.let { proxy -> runCatching { current?.closeProfileProxy(BluetoothProfile.HEADSET, proxy) } }
        leAudio?.let { proxy -> runCatching { current?.closeProfileProxy(BluetoothProfile.LE_AUDIO, proxy) } }
        a2dp = null
        headset = null
        leAudio = null
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = proxy as? BluetoothA2dp
                BluetoothProfile.HEADSET -> headset = proxy as? BluetoothHeadset
                BluetoothProfile.LE_AUDIO -> leAudio = proxy
            }
            notifyListeners()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = null
                BluetoothProfile.HEADSET -> headset = null
                BluetoothProfile.LE_AUDIO -> leAudio = null
            }
            notifyListeners()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) requestProfileProxies()
            }
            notifyListeners()
        }
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            runCatching { listener.onBluetoothStateChanged() }
        }
    }

    private companion object {
        const val DISCONNECTED = BluetoothProfile.STATE_DISCONNECTED
    }
}
