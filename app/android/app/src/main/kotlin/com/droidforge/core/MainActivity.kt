package com.droidforge.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * MainActivity — Flutter ↔ Kotlin bridge.
 *
 * MethodChannel: com.droidforge/core
 *
 * Kotlin handles: proot lifecycle, rootfs download/extract,
 * VNC/X11 session, terminal, device info, battery, backups.
 */
class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.droidforge/core"

    private lateinit var prootManager: ProotManager
    private lateinit var rootfsManager: RootfsManager
    private lateinit var terminalManager: TerminalManager
    private lateinit var vncManager: VncManager

    private var cachedChannel: MethodChannel? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        prootManager = ProotManager(this)
        rootfsManager = RootfsManager(this)
        terminalManager = TerminalManager(this)
        vncManager = VncManager(this)

        val messenger = flutterEngine.dartExecutor.binaryMessenger
        cachedChannel = MethodChannel(messenger, CHANNEL)

        cachedChannel!!.setMethodCallHandler { call, result ->
            when (call.method) {
                // ── Device Info ──
                "getDeviceInfo" -> {
                    result.success(DeviceInfoHelper.getDeviceInfo(this))
                }
                "getRuntimeStatus" -> {
                    result.success(prootManager.getRuntimeStatus())
                }
                "getSystemInfo" -> {
                    result.success(DeviceInfoHelper.getSystemInfo(this))
                }
                "getAvailableStorage" -> {
                    result.success(DeviceInfoHelper.getAvailableStorage(this))
                }

                // ── Bootstrap ──
                "setupBootstrap" -> {
                    prootManager.setupBootstrap { progress, status ->
                        sendToFlutter("onInstallProgress", progress, status)
                    }
                    result.success(null)
                }

                // ── Rootfs ──
                "downloadRootfs" -> {
                    val distro = call.argument<String>("distro") ?: "ubuntu"
                    rootfsManager.downloadRootfs(distro, messenger)
                    result.success(null)
                }
                "extractRootfs" -> {
                    val distro = call.argument<String>("distro") ?: "ubuntu"
                    rootfsManager.extractRootfs(distro, messenger)
                    result.success(null)
                }

                // ── Desktop Environment ──
                "installDesktopEnvironment" -> {
                    val de = call.argument<String>("de") ?: "xfce4"
                    val type = call.argument<String>("type") ?: "minimal"
                    prootManager.installDesktopEnvironment(de, type, messenger)
                    result.success(null)
                }

                // ── Linux Session ──
                "startLinux" -> {
                    val de = call.argument<String>("de") ?: "xfce4"
                    val mode = call.argument<String>("mode") ?: "vnc"
                    val width = call.argument<Int>("width") ?: 1920
                    val height = call.argument<Int>("height") ?: 1080
                    prootManager.startLinux(de, mode, width, height)
                    result.success(null)
                }
                "stopLinux" -> {
                    prootManager.stopLinux()
                    result.success(null)
                }
                "launchDesktopActivity" -> {
                    vncManager.launchVncActivity()
                    result.success(null)
                }

                // ── Terminal ──
                "executeCommand" -> {
                    val command = call.argument<String>("command") ?: ""
                    terminalManager.executeCommand(command, messenger)
                    result.success(null)
                }
                "interruptCommand" -> {
                    terminalManager.interruptCommand()
                    result.success(null)
                }

                // ── Battery ──
                "requestBatteryOptimization" -> {
                    openBatterySettings()
                    result.success(true)
                }
                "isBatteryOptimized" -> {
                    result.success(isBatteryIgnoringOptimizations())
                }

                // ── Backup/Restore ──
                "backupContainer" -> {
                    val name = call.argument<String>("name") ?: "default"
                    prootManager.backupContainer(name)
                    result.success(null)
                }
                "restoreContainer" -> {
                    val name = call.argument<String>("name") ?: "default"
                    prootManager.restoreContainer(name)
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }

    /**
     * Open battery optimization settings for this app.
     * On Android 6+, tries to request ignoring battery optimization,
     * falls back to the battery optimization settings page.
     */
    private fun openBatterySettings() {
        try {
            // Method 1: Request to ignore battery optimizations directly (shows system dialog)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
        } catch (_: Exception) {}

        try {
            // Method 2: Open battery optimization settings for this app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
        } catch (_: Exception) {}

        try {
            // Method 3: Open general battery settings
            val intent = Intent()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.action = Settings.ACTION_BATTERY_SAVER_SETTINGS
            } else {
                intent.action = Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
    }

    /**
     * Check if this app is ignoring battery optimizations.
     */
    private fun isBatteryIgnoringOptimizations(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(packageName)
            } else {
                true // Below Android 6, no battery optimization
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Send a callback from Kotlin → Flutter.
     * Thread-safe: always posts to main thread using cached MethodChannel.
     */
    private fun sendToFlutter(
        method: String,
        progress: Double,
        status: String
    ) {
        mainHandler.post {
            try {
                cachedChannel?.invokeMethod(
                    method,
                    mapOf("progress" to progress, "status" to status)
                )
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        prootManager.cleanup()
        terminalManager.cleanup()
        super.onDestroy()
    }
}
