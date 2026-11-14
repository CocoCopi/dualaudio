package com.cococopi.dualaudio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRouter2
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.cococopi.dualaudio.databinding.ActivityMainBinding
import com.cococopi.dualaudio.databinding.ViewDeviceSlotBinding
import com.cococopi.dualaudio.routing.ShizukuManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences
    private lateinit var bluetooth: BluetoothController

    private var deviceA: BluetoothDevice? = null
    private var deviceB: BluetoothDevice? = null
    private var tonePlayer: TestTonePlayer? = null
    private var autoApplyAttempted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshDevices()
        ShizukuManager.refresh()
        render()
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        render()
    }

    private val bluetoothListener = BluetoothController.Listener {
        runOnUiThread {
            refreshDevices()
            render()
        }
    }

    private val shizukuListener: (ShizukuManager.State) -> Unit = {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            render()
            maybeAutoApply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        bluetooth = BluetoothController(this)

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_bluetooth_settings -> {
                    openBluetoothSettings()
                    true
                }
                R.id.action_help -> {
                    showHowItWorks()
                    true
                }
                else -> false
            }
        }

        supportFragmentManager.setFragmentResultListener(
            DevicePickerSheet.REQUEST_KEY,
            this,
        ) { _, bundle ->
            val address = bundle.getString(DevicePickerSheet.KEY_ADDRESS)
                ?: return@setFragmentResultListener
            val slot = bundle.getInt(DevicePickerSheet.KEY_SLOT, SLOT_A)
            assignDevice(slot, address)
        }

        binding.slotA.root.setOnClickListener { showPicker(SLOT_A) }
        binding.slotB.root.setOnClickListener { showPicker(SLOT_B) }
        binding.slotA.btnAction.setOnClickListener { onSlotAction(SLOT_A) }
        binding.slotB.btnAction.setOnClickListener { onSlotAction(SLOT_B) }

        binding.btnPairNew.setOnClickListener {
            openBluetoothSettings()
            snackbar(R.string.snackbar_settings_opened)
        }
        binding.btnGrantPermission.setOnClickListener { requestRuntimePermissions() }
        binding.btnEnableBluetooth.setOnClickListener { requestEnableBluetooth() }

        binding.btnPrimary.setOnClickListener { onPrimaryAction() }
        binding.btnSecondary.setOnClickListener { showHowItWorks() }
        binding.cardSystem.setOnClickListener { openOutputSwitcher() }
        binding.cardShizuku.setOnClickListener { onShizukuCardClick() }
        binding.cardLe.setOnClickListener { openOutputSwitcher() }

        binding.btnShizukuSetup.setOnClickListener { showShizukuSetup() }
        binding.btnShizukuGrant.setOnClickListener { ShizukuManager.requestPermission() }
        binding.btnShizukuRefresh.setOnClickListener {
            ShizukuManager.refresh()
            snackbar(R.string.shizuku_refreshed)
        }
        binding.btnApplyRouting.setOnClickListener { applyForcedRouting() }
        binding.btnStopRouting.setOnClickListener { stopForcedRouting() }

        binding.switchAutoApply.isChecked = prefs.autoApply
        binding.switchAutoApply.setOnCheckedChangeListener { _, checked ->
            onAutoApplyChanged(checked)
        }

        binding.btnTestTone.setOnClickListener { toggleTestTone() }
        binding.btnShowDiagnostics.setOnClickListener { showDiagnostics(copy = false) }
        binding.btnCopyDiagnostics.setOnClickListener { showDiagnostics(copy = true) }

        binding.textVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        ShizukuManager.addListener(shizukuListener)

        if (!prefs.onboardingSeen) {
            showOnboarding()
        } else {
            requestRuntimePermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        bluetooth.addListener(bluetoothListener)
        bluetooth.start()
        binding.switchAutoApply.isChecked = prefs.autoApply
        ShizukuManager.refresh()
        refreshDevices()
        render()
        maybeAutoApply()
    }

    override fun onPause() {
        bluetooth.removeListener(bluetoothListener)
        bluetooth.stop()
        tonePlayer?.stop()
        renderToneButton()
        super.onPause()
    }

    override fun onDestroy() {
        ShizukuManager.removeListener(shizukuListener)
        tonePlayer?.stop()
        supportFragmentManager.clearFragmentResultListener(DevicePickerSheet.REQUEST_KEY)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private fun render() {
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        val hasPermission = bluetooth.hasPermission()
        binding.permissionCard.isVisible = !hasPermission

        val adapterAvailable = bluetooth.adapter != null
        binding.bluetoothCard.isVisible = !hasPermission || (adapterAvailable && !bluetooth.isEnabled())

        renderSlot(binding.slotA, deviceA, R.string.device_a_label)
        renderSlot(binding.slotB, deviceB, R.string.device_b_label)
        renderHero()
        renderCompatibility()
        renderShizuku()
        renderToneButton()
    }

    private fun renderSlot(
        slotBinding: ViewDeviceSlotBinding,
        device: BluetoothDevice?,
        emptyLabel: Int,
    ) {
        if (device == null) {
            slotBinding.textName.setText(emptyLabel)
            slotBinding.textStatus.setText(R.string.device_empty_status)
            slotBinding.btnAction.setText(R.string.device_action_choose)
            slotBinding.btnAction.isEnabled = true
            slotBinding.imageIcon.setImageResource(R.drawable.ic_headphones)
            return
        }

        slotBinding.textName.text = bluetooth.nameOf(device)
        when (bluetooth.connectionState(device)) {
            BluetoothProfile.STATE_CONNECTED -> {
                slotBinding.textStatus.setText(R.string.device_connected)
                slotBinding.btnAction.setText(R.string.device_action_connected)
                slotBinding.btnAction.isEnabled = false
            }
            BluetoothProfile.STATE_CONNECTING -> {
                slotBinding.textStatus.setText(R.string.device_connecting)
                slotBinding.btnAction.setText(R.string.device_connecting)
                slotBinding.btnAction.isEnabled = false
            }
            BluetoothProfile.STATE_DISCONNECTING -> {
                slotBinding.textStatus.setText(R.string.device_disconnected)
                slotBinding.btnAction.setText(R.string.device_action_connect)
                slotBinding.btnAction.isEnabled = false
            }
            else -> {
                slotBinding.textStatus.setText(R.string.device_disconnected)
                slotBinding.btnAction.setText(R.string.device_action_connect)
                slotBinding.btnAction.isEnabled = true
            }
        }
        slotBinding.imageIcon.setImageResource(iconFor(device))
    }

    @SuppressLint("MissingPermission") // Only called after the Bluetooth permission check.
    private fun iconFor(device: BluetoothDevice): Int {
        val deviceClass = runCatching {
            device.bluetoothClass?.deviceClass
        }.getOrNull()
        return if (deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ||
            deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO
        ) {
            R.drawable.ic_speaker
        } else {
            R.drawable.ic_headphones
        }
    }

    private fun renderHero() {
        val bothSelected = deviceA != null && deviceB != null
        val bothConnected = bothSelected &&
            bluetooth.isConnected(deviceA!!) && bluetooth.isConnected(deviceB!!)
        val ready = ShizukuManager.isReady

        binding.heroStatus.setText(
            when {
                !bluetooth.hasPermission() || !bluetooth.isEnabled() ->
                    R.string.hero_status_needs_devices
                !bothSelected -> R.string.hero_status_needs_devices
                !bothConnected -> R.string.hero_status_needs_devices
                prefs.routingActive && ready -> R.string.hero_status_active
                ready || Build.VERSION.SDK_INT >= 34 -> R.string.hero_status_ready
                else -> R.string.hero_status_unsupported
            },
        )

        binding.btnPrimary.setText(
            if (ready && bothConnected) {
                R.string.hero_primary_route_shizuku
            } else {
                R.string.hero_primary_open_switcher
            },
        )
        binding.btnPrimary.isEnabled = true
    }

    private fun renderCompatibility() {
        val supported = getString(R.string.compat_supported)
        val unavailable = getString(R.string.compat_unavailable)
        val unknown = getString(R.string.compat_unknown)
        val likely = getString(R.string.compat_likely)

        binding.textCompatRouting.text = supported
        binding.textCompatSwitcher.text =
            if (Build.VERSION.SDK_INT >= 34) supported else unavailable
        binding.textCompatSamsung.text =
            if (bluetooth.isSamsung()) likely else unavailable

        if (!bluetooth.hasPermission()) {
            binding.textCompatLe.text = unknown
            binding.textCompatBroadcast.text = unknown
            binding.textCompatTwoA2dp.text = unknown
        } else {
            binding.textCompatLe.text =
                if (bluetooth.isLeAudioSupported()) supported else unavailable
            binding.textCompatBroadcast.text =
                if (bluetooth.isLeAudioBroadcastSupported()) supported else unavailable
            val a2dpCount = bluetooth.connectedA2dpCount()
            binding.textCompatTwoA2dp.text = when {
                a2dpCount == null -> unknown
                a2dpCount >= 2 -> getString(R.string.compat_detected)
                else -> getString(R.string.compat_not_detected)
            }
        }
    }

    private fun renderShizuku() {
        val state = ShizukuManager.state
        binding.shizukuStatus.setText(
            when (state) {
                ShizukuManager.State.NOT_INSTALLED -> R.string.shizuku_not_installed
                ShizukuManager.State.NOT_RUNNING -> R.string.shizuku_not_running
                ShizukuManager.State.PERMISSION_REQUIRED -> R.string.shizuku_permission_denied
                ShizukuManager.State.READY_ROOT -> R.string.shizuku_ready_root
                ShizukuManager.State.READY_SHELL -> R.string.shizuku_ready_shell
            },
        )

        binding.btnShizukuGrant.isVisible = state == ShizukuManager.State.PERMISSION_REQUIRED
        binding.btnShizukuSetup.isVisible =
            state == ShizukuManager.State.NOT_INSTALLED || state == ShizukuManager.State.NOT_RUNNING
        binding.btnShizukuSetup.setText(
            if (state == ShizukuManager.State.NOT_INSTALLED) {
                R.string.shizuku_install
            } else {
                R.string.shizuku_setup
            },
        )

        val ready = ShizukuManager.isReady
        binding.btnApplyRouting.isVisible = ready
        binding.btnStopRouting.isVisible = ready && prefs.routingActive
        binding.btnApplyRouting.isEnabled = ready && ShizukuManager.isServiceBound
    }

    private fun renderToneButton() {
        val playing = tonePlayer?.isPlaying == true
        binding.btnTestTone.setText(if (playing) R.string.test_playing else R.string.test_play)
        binding.btnTestTone.setIconResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
    }

    // ---------------------------------------------------------------------
    // Device selection
    // ---------------------------------------------------------------------

    private fun refreshDevices() {
        if (!bluetooth.hasPermission()) {
            deviceA = null
            deviceB = null
            return
        }
        val bonded = bluetooth.bondedDevices()
        val savedA = prefs.deviceAAddress
        val savedB = prefs.deviceBAddress

        var a = savedA?.let { address ->
            bonded.firstOrNull { it.address.equals(address, ignoreCase = true) }
        }
        var b = savedB?.let { address ->
            bonded.firstOrNull { it.address.equals(address, ignoreCase = true) }
        }
        if (a == null && savedA != null) prefs.clearDeviceA()
        if (b == null && savedB != null) prefs.clearDeviceB()
        if (a != null && b != null && a.address == b.address) {
            prefs.clearDeviceB()
            b = null
        }
        deviceA = a
        deviceB = b
    }

    private fun showPicker(slot: Int) {
        if (!bluetooth.hasPermission()) {
            requestRuntimePermissions()
            return
        }
        val entries = bluetooth.bondedDevices().map { device ->
            DevicePickerSheet.Entry(
                address = device.address,
                name = bluetooth.nameOf(device),
                state = bluetooth.connectionState(device),
            )
        }
        DevicePickerSheet.newInstance(slot, entries)
            .show(supportFragmentManager, "device_picker")
    }

    private fun assignDevice(slot: Int, address: String) {
        val device = bluetooth.bondedDevices()
            .firstOrNull { it.address.equals(address, ignoreCase = true) } ?: return
        if (slot == SLOT_A) {
            if (deviceB?.address.equals(device.address, ignoreCase = true)) {
                deviceB = null
                prefs.clearDeviceB()
            }
            prefs.deviceAAddress = device.address
            prefs.deviceAName = bluetooth.nameOf(device)
        } else {
            if (deviceA?.address.equals(device.address, ignoreCase = true)) {
                deviceA = null
                prefs.clearDeviceA()
            }
            prefs.deviceBAddress = device.address
            prefs.deviceBName = bluetooth.nameOf(device)
        }
        refreshDevices()
        render()
    }

    private fun onSlotAction(slot: Int) {
        val device = if (slot == SLOT_A) deviceA else deviceB
        if (device == null) {
            showPicker(slot)
            return
        }
        if (bluetooth.isConnected(device)) return
        connectDevice(device)
    }

    private fun connectDevice(device: BluetoothDevice) {
        if (!bluetooth.isEnabled()) {
            snackbar(R.string.snackbar_bluetooth_off)
            return
        }
        if (ShizukuManager.isReady) {
            snackbar(R.string.snackbar_connect_via_shizuku)
            ShizukuManager.connectDevice(device.address) { result ->
                result.onSuccess {
                    snackbar(R.string.snackbar_auto_connected)
                }.onFailure {
                    snackbar(R.string.snackbar_connect_failed)
                    openBluetoothSettings()
                }
                render()
            }
        } else {
            openBluetoothSettings()
            snackbar(R.string.snackbar_settings_opened)
        }
    }

    // ---------------------------------------------------------------------
    // Routing actions
    // ---------------------------------------------------------------------

    private fun onPrimaryAction() {
        if (!bluetooth.hasPermission()) {
            requestRuntimePermissions()
            return
        }
        if (!bluetooth.isEnabled()) {
            requestEnableBluetooth()
            return
        }
        val a = deviceA
        val b = deviceB
        if (a == null || b == null) {
            snackbar(R.string.snackbar_two_devices)
            return
        }
        if (!bluetooth.isConnected(a) || !bluetooth.isConnected(b)) {
            snackbar(R.string.snackbar_two_devices)
            openBluetoothSettings()
            return
        }
        if (ShizukuManager.isReady) {
            applyForcedRouting()
        } else {
            openOutputSwitcher()
        }
    }

    private fun onShizukuCardClick() {
        when (ShizukuManager.state) {
            ShizukuManager.State.NOT_INSTALLED,
            ShizukuManager.State.NOT_RUNNING,
            ShizukuManager.State.PERMISSION_REQUIRED -> showShizukuSetup()
            ShizukuManager.State.READY_SHELL,
            ShizukuManager.State.READY_ROOT -> applyForcedRouting()
        }
    }

    private fun applyForcedRouting() {
        val a = deviceA
        val b = deviceB
        if (a == null || b == null) {
            snackbar(R.string.snackbar_two_devices)
            return
        }
        if (!bluetooth.isConnected(a) || !bluetooth.isConnected(b)) {
            snackbar(R.string.snackbar_two_devices)
            return
        }
        if (!ShizukuManager.isReady) {
            snackbar(R.string.snackbar_shizuku_needed)
            return
        }

        binding.btnPrimary.isEnabled = false
        binding.btnApplyRouting.isEnabled = false
        ShizukuManager.applyRouting(listOf(a.address, b.address)) { result ->
            binding.btnPrimary.isEnabled = true
            binding.btnApplyRouting.isEnabled = true
            result.onSuccess {
                prefs.routingActive = true
                snackbar(R.string.snackbar_routing_applied)
                DualAudioService.start(
                    this,
                    bluetooth.nameOf(a),
                    bluetooth.nameOf(b),
                )
            }.onFailure { error ->
                prefs.routingActive = false
                showRoutingFailure(error.message ?: error.javaClass.simpleName)
            }
            render()
        }
    }

    private fun stopForcedRouting() {
        ShizukuManager.clearRouting {
            prefs.routingActive = false
            DualAudioService.stop(this)
            snackbar(R.string.snackbar_routing_stopped)
            render()
        }
    }

    private fun maybeAutoApply() {
        if (autoApplyAttempted) return
        if (!prefs.autoApply || !prefs.routingActive) return
        val a = deviceA ?: return
        val b = deviceB ?: return
        if (!bluetooth.isConnected(a) || !bluetooth.isConnected(b)) return
        if (!ShizukuManager.isReady || !ShizukuManager.isServiceBound) return
        autoApplyAttempted = true
        ShizukuManager.applyRouting(listOf(a.address, b.address)) { result ->
            if (result.isSuccess) {
                prefs.routingActive = true
                DualAudioService.start(this, bluetooth.nameOf(a), bluetooth.nameOf(b))
            } else {
                // Allow a later reconnect in this session to try again.
                autoApplyAttempted = false
            }
            render()
        }
    }

    private fun onAutoApplyChanged(checked: Boolean) {
        prefs.autoApply = checked
        if (checked) {
            val a = deviceA
            val b = deviceB
            if (prefs.routingActive && a != null && b != null) {
                DualAudioService.start(this, bluetooth.nameOf(a), bluetooth.nameOf(b))
            }
        } else {
            DualAudioService.stop(this)
        }
    }

    private fun openOutputSwitcher() {
        if (Build.VERSION.SDK_INT >= 34) {
            val shown = runCatching {
                MediaRouter2.getInstance(this).showSystemOutputSwitcher()
            }.getOrDefault(false)
            if (shown) {
                snackbar(R.string.snackbar_switcher_opened)
                return
            }
        }

        val candidates = listOf(
            Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .putExtra("android.intent.extra.PACKAGE_NAME", packageName)
                .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", packageName),
            Intent("com.android.settings.panel.action.MEDIA_OUTPUT"),
        )
        for (intent in candidates) {
            if (tryStartActivity(intent)) {
                snackbar(R.string.snackbar_switcher_opened)
                return
            }
        }
        snackbar(R.string.snackbar_switcher_failed)
        openBluetoothSettings()
    }

    private fun openBluetoothSettings() {
        if (tryStartActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) return
        snackbar(R.string.snackbar_switcher_failed)
    }

    private fun requestEnableBluetooth() {
        try {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: Throwable) {
            openBluetoothSettings()
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }

    // ---------------------------------------------------------------------
    // Testing, diagnostics, dialogs
    // ---------------------------------------------------------------------

    private fun toggleTestTone() {
        val current = tonePlayer
        if (current != null && current.isPlaying) {
            current.stop()
            renderToneButton()
            return
        }
        val player = TestTonePlayer()
        tonePlayer = player
        player.play {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) renderToneButton()
            }
        }
        renderToneButton()
    }

    private fun showDiagnostics(copy: Boolean) {
        buildDiagnostics { text ->
            if (copy) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Dual Audio diagnostics", text))
                snackbar(R.string.diagnostics_copied)
                return@buildDiagnostics
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.diagnostics_title)
                .setMessage(text)
                .setPositiveButton(R.string.diagnostics_copy) { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Dual Audio diagnostics", text))
                    snackbar(R.string.diagnostics_copied)
                }
                .setNegativeButton(R.string.action_close, null)
                .show()
        }
    }

    @SuppressLint("MissingPermission") // Only reads Bluetooth data after the permission check.
    private fun buildDiagnostics(onReady: (String) -> Unit) {
        val builder = StringBuilder()
        builder.appendLine("Dual Audio ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        builder.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        builder.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        builder.appendLine("Bluetooth permission: ${bluetooth.hasPermission()}")
        builder.appendLine("Bluetooth enabled: ${runCatching { bluetooth.adapter?.isEnabled }.getOrNull()}")
        builder.appendLine("Output A: ${describeSlot(deviceA)}")
        builder.appendLine("Output B: ${describeSlot(deviceB)}")
        builder.appendLine("Connected A2DP sinks: ${bluetooth.connectedA2dpCount() ?: "unknown"}")
        builder.appendLine("LE Audio supported: ${bluetooth.isLeAudioSupported()}")
        builder.appendLine("LE Audio broadcast supported: ${bluetooth.isLeAudioBroadcastSupported()}")
        builder.appendLine("Shizuku: ${ShizukuManager.state} (uid ${ShizukuManager.uid()}, v${ShizukuManager.version()})")
        builder.appendLine("Shizuku helper bound: ${ShizukuManager.isServiceBound}")
        builder.appendLine("Routing active (saved): ${prefs.routingActive}")
        builder.appendLine()
        builder.appendLine("Paired devices:")
        val paired = bluetooth.bondedDevices()
        if (paired.isEmpty()) {
            builder.appendLine("  (none visible)")
        } else {
            for (device in paired) {
                builder.appendLine(
                    "  - ${bluetooth.nameOf(device)} | ${device.address} | " +
                        "state=${stateName(bluetooth.connectionState(device))} | " +
                        "bond=${device.bondState}",
                )
            }
        }

        if (!ShizukuManager.isReady) {
            onReady(builder.toString())
            return
        }
        ShizukuManager.requestStatus { result ->
            result.onSuccess { status ->
                builder.appendLine()
                builder.appendLine("Shizuku route status:")
                builder.append(status)
            }.onFailure { error ->
                builder.appendLine()
                builder.appendLine("Shizuku status error: ${error.message}")
            }
            onReady(builder.toString())
        }
    }

    private fun describeSlot(device: BluetoothDevice?): String {
        if (device == null) return "none"
        return "${bluetooth.nameOf(device)} | ${device.address} | " +
            stateName(bluetooth.connectionState(device))
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "disconnected"
    }

    private fun requestRuntimePermissions() {
        val missing = mutableListOf<String>()
        if (!bluetooth.hasPermission()) missing += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun showOnboarding() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.onboarding_title)
            .setMessage(R.string.onboarding_body)
            .setCancelable(false)
            .setPositiveButton(R.string.onboarding_continue) { _, _ ->
                prefs.onboardingSeen = true
                requestRuntimePermissions()
                render()
            }
            .setNeutralButton(R.string.onboarding_learn) { _, _ ->
                prefs.onboardingSeen = true
                requestRuntimePermissions()
                showHowItWorks()
            }
            .show()
    }

    private fun showHowItWorks() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vd_title)
            .setMessage(R.string.vd_body)
            .setPositiveButton(R.string.vd_ok, null)
            .show()
    }

    private fun showShizukuSetup() {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shizuku_setup_title)
            .setMessage(R.string.shizuku_setup_body)
            .setNegativeButton(R.string.action_close, null)

        if (ShizukuManager.isInstalled()) {
            builder.setPositiveButton(R.string.shizuku_open) { _, _ ->
                ShizukuManager.openShizuku()
            }
            builder.setNeutralButton(R.string.shizuku_setup_play) { _, _ ->
                openUrl("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
            }
        } else {
            builder.setPositiveButton(R.string.shizuku_install) { _, _ ->
                openUrl("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
            }
            builder.setNeutralButton(R.string.shizuku_setup_github) { _, _ ->
                openUrl("https://github.com/RikkaApps/Shizuku")
            }
        }
        builder.show()
    }

    private fun showRoutingFailure(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.routing_failed_title)
            .setMessage(getString(R.string.routing_failed_body, message))
            .setPositiveButton(R.string.method_dialog_system) { _, _ ->
                openOutputSwitcher()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun openUrl(url: String) {
        tryStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun snackbar(messageRes: Int) {
        Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        const val SLOT_A = 0
        const val SLOT_B = 1
    }
}
