package com.cococopi.dualaudio.routing

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.cococopi.dualaudio.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Owns the optional Shizuku connection and the user service that performs
 * privileged audio routing.
 *
 * Shizuku runs the helper as the ADB shell user (uid 2000) or as root. That
 * process has MODIFY_AUDIO_ROUTING, which lets it call
 * AudioService.setPreferredDevicesForStrategy(). A normal app process cannot.
 */
object ShizukuManager {

    private const val TAG = "DualAudioShizuku"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4221
    private const val RETRY_COUNT = 10
    private const val RETRY_DELAY_MS = 450L

    enum class State {
        NOT_INSTALLED,
        NOT_RUNNING,
        PERMISSION_REQUIRED,
        READY_SHELL,
        READY_ROOT,
    }

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dual-audio-shizuku").apply { isDaemon = true }
    }

    private lateinit var appContext: Context

    @Volatile
    private var initialized = false

    @Volatile
    private var boundService: IAudioRoutingService? = null

    @Volatile
    private var binding = false

    @Volatile
    var state: State = State.NOT_INSTALLED
        private set

    @Volatile
    var lastStatus: String = ""
        private set

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, AudioRoutingUserService::class.java.name),
        )
            .daemon(true)
            .processNameSuffix("routing")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    val isReady: Boolean
        get() = state == State.READY_SHELL || state == State.READY_ROOT

    val isServiceBound: Boolean
        get() = boundService != null

    fun init(context: Context) {
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            initialized = true
        }
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
            .onFailure { Log.w(TAG, "could not listen for Shizuku binder", it) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
            .onFailure { Log.w(TAG, "could not listen for Shizuku death", it) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
            .onFailure { Log.w(TAG, "could not listen for Shizuku permission", it) }
        refresh()
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    /** Recomputes the state and binds the helper if access is granted. */
    fun refresh() {
        if (!initialized) return
        executor.execute {
            val next = computeState()
            val changed = state != next
            state = next
            if (next == State.READY_SHELL || next == State.READY_ROOT) {
                bindIfNeeded()
            } else {
                boundService = null
            }
            if (changed) notifyListeners()
        }
    }

    fun requestPermission() {
        if (!isReady && state != State.PERMISSION_REQUIRED) {
            refresh()
        }
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { Log.w(TAG, "could not request Shizuku permission", it) }
    }

    fun applyRouting(addresses: List<String>, callback: (Result<String>) -> Unit) {
        if (addresses.size < 2) {
            callback(Result.failure(IllegalArgumentException("two devices are required")))
            return
        }
        applyWhenBound(addresses, callback, attempt = 0)
    }

    /**
     * The user service may still be starting after Shizuku reports READY.
     * Retry briefly instead of failing the first tap.
     */
    private fun applyWhenBound(
        addresses: List<String>,
        callback: (Result<String>) -> Unit,
        attempt: Int,
    ) {
        val service = boundService
        if (service == null) {
            refresh()
            if (attempt >= RETRY_COUNT) {
                callback(
                    Result.failure(
                        IllegalStateException(
                            "The Shizuku helper did not start. Open Shizuku, make sure it is running, then try again.",
                        ),
                    ),
                )
                return
            }
            mainHandler.postDelayed(
                { applyWhenBound(addresses, callback, attempt + 1) },
                RETRY_DELAY_MS,
            )
            return
        }
        callServiceAsync({ service.apply(addresses.toTypedArray()) }) { result ->
            result.getOrNull()?.let { lastStatus = it }
            callback(result)
        }
    }

    fun clearRouting(callback: (Result<String>) -> Unit = {}) {
        val service = boundService
        if (service == null) {
            refresh()
            callback(Result.failure(IllegalStateException("The Shizuku helper is not connected.")))
            return
        }
        callServiceAsync({ service.clear() }) { result ->
            result.getOrNull()?.let { lastStatus = it }
            callback(result)
        }
    }

    fun connectDevice(address: String, callback: (Result<String>) -> Unit) {
        val service = boundService
        if (service == null) {
            refresh()
            callback(Result.failure(IllegalStateException("The Shizuku helper is not connected.")))
            return
        }
        callServiceAsync({ service.connectDevice(address) }) { callback(it) }
    }

    fun disconnectDevice(address: String, callback: (Result<String>) -> Unit) {
        val service = boundService
        if (service == null) {
            refresh()
            callback(Result.failure(IllegalStateException("The Shizuku helper is not connected.")))
            return
        }
        callServiceAsync({ service.disconnectDevice(address) }) { callback(it) }
    }

    fun requestStatus(callback: (Result<String>) -> Unit) {
        val service = boundService
        if (service == null) {
            refresh()
            callback(Result.failure(IllegalStateException("The Shizuku helper is not connected.")))
            return
        }
        callServiceAsync({ service.status() }) { result ->
            result.getOrNull()?.let { lastStatus = it }
            callback(result)
        }
    }

    fun isInstalled(): Boolean {
        if (!initialized) return false
        return runCatching {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun version(): Int = runCatching { Shizuku.getVersion() }.getOrDefault(0)

    fun uid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun openShizuku() {
        if (!initialized) return
        runCatching {
            val intent = appContext.packageManager
                .getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }
        }
    }

    private fun computeState(): State {
        if (!isInstalled()) return State.NOT_INSTALLED
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return State.NOT_RUNNING
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) return State.NOT_RUNNING
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) return State.PERMISSION_REQUIRED
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(2000)
        return if (uid == 0) State.READY_ROOT else State.READY_SHELL
    }

    @Synchronized
    private fun bindIfNeeded() {
        if (boundService != null || binding) return
        binding = true
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (t: Throwable) {
            binding = false
            Log.w(TAG, "could not bind user service", t)
        }
    }

    private fun callServiceAsync(
        block: () -> String,
        callback: (Result<String>) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val text = block()
                if (text.startsWith("ERROR:")) {
                    Result.failure(IllegalStateException(text.removePrefix("ERROR:").trim()))
                } else {
                    Result.success(text)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "user service call failed", t)
                Result.failure(t)
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun notifyListeners() {
        mainHandler.post {
            val current = state
            for (listener in listeners) {
                runCatching { listener(current) }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            boundService = if (binder != null && binder.pingBinder()) {
                IAudioRoutingService.Stub.asInterface(binder)
            } else {
                null
            }
            notifyListeners()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            boundService = null
            notifyListeners()
            refresh()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        boundService = null
        binding = false
        refresh()
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    refresh()
                } else {
                    state = State.PERMISSION_REQUIRED
                    notifyListeners()
                }
            }
        }
}
