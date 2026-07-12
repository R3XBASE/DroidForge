package com.droidforge.core

import android.os.Bundle
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

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        prootManager = ProotManager(this)
        rootfsManager = RootfsManager(this)
        terminalManager = TerminalManager(this)
        vncManager = VncManager(this)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->
            // All calls dispatched to managers
            val channel = flutterEngine.dartExecutor.binaryMessenger

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
                        sendToFlutter(channel, "onInstallProgress", progress, status)
                    }
                    result.success(null)
                }

                // ── Rootfs ──
                "downloadRootfs" -> {
                    val distro = call.argument<String>("distro") ?: "ubuntu"
                    rootfsManager.downloadRootfs(distro, channel)
                    result.success(null)
                }
                "extractRootfs" -> {
                    rootfsManager.extractRootfs(channel)
                    result.success(null)
                }

                // ── Desktop Environment ──
                "installDesktopEnvironment" -> {
                    val de = call.argument<String>("de") ?: "xfce4"
                    val type = call.argument<String>("type") ?: "minimal"
                    prootManager.installDesktopEnvironment(de, type, channel)
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
                    terminalManager.executeCommand(command, channel)
                    result.success(null)
                }
                "interruptCommand" -> {
                    terminalManager.interruptCommand()
                    result.success(null)
                }

                // ── Battery ──
                "requestBatteryOptimization" -> {
                    result.success(null)
                }
                "isBatteryOptimized" -> {
                    result.success(false)
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

    /** Send a callback from Kotlin → Flutter */
    private fun sendToFlutter(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        method: String,
        progress: Double,
        status: String
    ) {
        MethodChannel(messenger, CHANNEL).invokeMethod(
            method,
            mapOf("progress" to progress, "status" to status)
        )
    }

    override fun onDestroy() {
        prootManager.cleanup()
        terminalManager.cleanup()
        super.onDestroy()
    }
}
