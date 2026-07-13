package com.droidforge.core

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * ProotManager — handles proot container lifecycle.
 *
 * - Bootstrap proot environment
 * - Install desktop environments (via apt INSIDE proot container)
 * - Start/stop Linux sessions
 * - Backup/restore containers
 */
class ProotManager(private val context: Context) {

    private val prootDir: File = File(context.filesDir, "proot")
    private val bootstrapScript: File = File(prootDir, "bootstrap.sh")
    private var runningProcess: Process? = null
    private var terminalProcess: Process? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Resolve the bash path — try Termux first, fall back to system sh */
    private fun bashPath(): String {
        val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
        return if (termuxBash.exists()) termuxBash.absolutePath else "/system/bin/sh"
    }

    /** Resolve the proot binary path — check Termux first */
    private fun prootBinary(): String {
        val termuxProot = File("/data/data/com.termux/files/usr/bin/proot")
        if (termuxProot.exists()) return termuxProot.absolutePath
        // Check common proot locations
        val localProot = File(context.filesDir, "proot/proot-arm64")
        if (localProot.exists()) return localProot.absolutePath
        return "proot"
    }

    /** Get the installed rootfs directory for the current distro */
    private fun getRootfsDir(distro: String? = null): File {
        val d = distro ?: detectInstalledDistro().ifEmpty { "ubuntu" }
        return File(prootDir, d)
    }

    /**
     * Build a proot command prefix that runs inside the container.
     * This is used for apt install, configuration, etc.
     */
    private fun buildProotExecPrefix(distro: String? = null): String {
        val rootfsDir = getRootfsDir(distro)
        val prootRoot = rootfsDir.absolutePath
        val proot = prootBinary()

        return buildString {
            append(proot)
            append(" -0")
            append(" -w /root")
            append(" --link2symlink")
            append(" -b /dev")
            append(" -b /proc")
            append(" -b /sys")
            append(" -b /dev/urandom:/dev/random")
            append(" -b $prootRoot")
            append(" -b /data/data/com.termux/files/home:/home")
            append(" -r $prootRoot")
            append(" --kill-on-exit")
            append(" --")
        }
    }

    /** Check if the proot environment is bootstrapped */
    fun isBootstrapped(): Boolean {
        if (prootDir.exists()) {
            prootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
                    && dir.name != "backups") {
                    return true
                }
            }
        }
        return false
    }

    /** Get the current runtime status */
    fun getRuntimeStatus(): Map<String, Any> {
        return mapOf(
            "isBootstrapped" to isBootstrapped(),
            "isRunning" to (runningProcess != null),
            "distro" to detectInstalledDistro(),
            "installedDE" to detectInstalledDE()
        )
    }

    /**
     * Set up the bootstrap environment — download proot binary if needed.
     * This is called before rootfs extraction.
     */
    fun setupBootstrap(onProgress: (Double, String) -> Unit) {
        Thread {
            try {
                onProgress(0.05, "Creating directories...")
                prootDir.mkdirs()

                onProgress(0.2, "Checking proot installation...")
                val proot = prootBinary()
                val bash = bashPath()

                onProgress(0.4, "Installing base packages...")
                // Try installing via pkg (Termux only), but don't crash if not available
                if (File("/data/data/com.termux/files/usr/bin/bash").exists()) {
                    try {
                        executeShellCommand("$bash -c 'pkg install -y proot tar x11-repo 2>/dev/null || true'")
                    } catch (_: Exception) {}
                }

                onProgress(0.6, "Setting up bootstrap script...")
                writeBootstrapScript()

                onProgress(0.8, "Installing additional tools...")
                if (File("/data/data/com.termux/files/usr/bin/bash").exists()) {
                    try {
                        executeShellCommand(
                            "$bash -c 'pkg install -y proot curl wget git python build-essential x11-apps xterm termux-x11-nightly pulseaudio virglrenderer-mesa-zink 2>/dev/null || true'"
                        )
                    } catch (_: Exception) {}
                }

                onProgress(1.0, "Bootstrap complete!")
            } catch (e: Exception) {
                onProgress(-1.0, "Bootstrap failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Install a desktop environment inside the proot container.
     * Runs apt install INSIDE the proot container (not pkg install from Termux).
     */
    fun installDesktopEnvironment(
        de: String,
        type: String,
        messenger: BinaryMessenger
    ) {
        val channel = MethodChannel(messenger, "com.droidforge/core")

        Thread {
            try {
                val distro = detectInstalledDistro().ifEmpty { "ubuntu" }
                sendProgress(channel, 0.05, "Preparing $de ($type) installation...")

                // Step 1: Update apt inside proot container
                val prootPrefix = buildProotExecPrefix(distro)
                val bash = bashPath()

                sendProgress(channel, 0.1, "Updating package lists...")
                executeShellCommand(
                    "$bash -c '$prootPrefix /bin/bash -c \"apt update -y 2>/dev/null || true\"'"
                )

                // Step 2: Install DE packages inside proot container via apt
                val packages = getDEPackages(de, type)
                sendProgress(channel, 0.2, "Installing $de packages (this may take a while)...")

                // Run apt install with progress streaming
                val aptCmd = "$bash -c '$prootPrefix /bin/bash -c \"DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends $packages 2>&1\"'"
                executeLongCommand(aptCmd, channel, 0.2, 0.7)

                // Step 3: Install additional base tools inside proot
                sendProgress(channel, 0.75, "Installing base tools (dbus, fonts)...")
                val baseCmd = "$bash -c '$prootPrefix /bin/bash -c \"DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends dbus-x11 fonts-liberation fonts-dejavu-core sudo wget curl 2>&1 || true\"'"
                executeLongCommand(baseCmd, channel, 0.75, 0.85)

                // Step 4: Configure DE
                sendProgress(channel, 0.88, "Configuring desktop environment...")
                configureDE(de, type)

                // Step 5: Install GPU drivers inside proot
                sendProgress(channel, 0.92, "Installing GPU drivers...")
                installGPUDriversInsideProot(distro)

                sendProgress(channel, 1.0, "$de installation complete!")
            } catch (e: Exception) {
                sendProgress(channel, -1.0, "DE installation failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Start the Linux desktop session.
     */
    fun startLinux(
        de: String,
        mode: String,
        width: Int,
        height: Int
    ) {
        stopLinux()

        Thread {
            try {
                val startCmd = buildStartCommand(de, mode, width, height)
                val bash = bashPath()
                runningProcess = Runtime.getRuntime().exec(
                    arrayOf(bash, "-c", startCmd)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    /** Stop all running Linux sessions */
    fun stopLinux() {
        runningProcess?.let { process ->
            try {
                process.destroyForcibly()
            } catch (_: Exception) {}
        }
        runningProcess = null

        val bash = bashPath()
        try { executeShellCommand("$bash -c 'pkill -f proot || true'") } catch (_: Exception) {}
        try { executeShellCommand("$bash -c 'pkill -f Xvnc || true'") } catch (_: Exception) {}
        try { executeShellCommand("$bash -c 'pkill -f xfce4-session || true'") } catch (_: Exception) {}
        try { executeShellCommand("$bash -c 'pkill -f lxqt-session || true'") } catch (_: Exception) {}
        try { executeShellCommand("$bash -c 'pkill -f mate-session || true'") } catch (_: Exception) {}
        try { executeShellCommand("$bash -c 'pkill -f startplasma-wayland || true'") } catch (_: Exception) {}
    }

    /** Backup the container */
    fun backupContainer(name: String) {
        Thread {
            try {
                val backupDir = File(prootDir, "backups")
                backupDir.mkdirs()
                val backupFile = File(backupDir, "backup_${name}_${System.currentTimeMillis()}.tar.gz")
                val bash = bashPath()
                val distro = detectInstalledDistro().ifEmpty { "ubuntu" }
                executeShellCommand(
                    "$bash -c 'tar -czf \"${backupFile.absolutePath}\" -C \"${prootDir}\" \"$distro\"'"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    /** Restore the container from a backup */
    fun restoreContainer(name: String) {
        Thread {
            try {
                val backupDir = File(prootDir, "backups")
                val backupFiles = backupDir.listFiles()?.filter {
                    it.name.contains("backup_$name") && it.name.endsWith(".tar.gz")
                }?.sortedByDescending { it.lastModified() }

                if (backupFiles != null && backupFiles.isNotEmpty()) {
                    stopLinux()
                    val distro = detectInstalledDistro().ifEmpty { "ubuntu" }
                    val rootfsDir = File(prootDir, distro)
                    rootfsDir.deleteRecursively()
                    val bash = bashPath()
                    executeShellCommand(
                        "$bash -c 'tar -xzf \"${backupFiles.first().absolutePath}\" -C \"${prootDir}\"'"
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    /** Cleanup when activity is destroyed */
    fun cleanup() {
        stopLinux()
        terminalProcess?.destroyForcibly()
        terminalProcess = null
    }

    // ── Private Helpers ──

    private fun buildStartCommand(
        de: String,
        mode: String,
        width: Int,
        height: Int
    ): String {
        val prootRoot = getRootfsDir().absolutePath
        val proot = prootBinary()

        val prootArgs = buildString {
            append(proot)
            append(" -0")
            append(" -w /root")
            append(" --link2symlink")
            append(" -b /dev")
            append(" -b /proc")
            append(" -b /sys")
            append(" -b /dev/urandom:/dev/random")
            append(" -b $prootRoot/:/root")
            append(" -b /data/data/com.termux/files/home:/home")
            append(" -r $prootRoot")
            append(" --kill-on-exit")
            append(" --")
        }

        val deCommand = when (de) {
            "xfce4" -> "startxfce4"
            "lxqt" -> "startlxqt"
            "mate" -> "startmate-session"
            "kde" -> "dbus-launch startplasma-wayland"
            else -> "startxfce4"
        }

        return when (mode) {
            "vnc" -> {
                "$prootArgs /bin/bash -c 'export DISPLAY=:1 && export PULSE_SERVER=tcp:127.0.0.1:4713 && vncserver -geometry ${width}x${height} -depth 24 :1 && sleep infinity'"
            }
            "x11" -> {
                "$prootArgs /bin/bash -c 'export DISPLAY=:0 && export PULSE_SERVER=tcp:127.0.0.1:4713 && $deCommand'"
            }
            else -> {
                "$prootArgs /bin/bash -c 'export DISPLAY=:0 && export PULSE_SERVER=tcp:127.0.0.1:4713 && $deCommand'"
            }
        }
    }

    private fun getDEPackages(de: String, type: String): String {
        return when (de) {
            "xfce4" -> if (type == "minimal") "xfce4 xfce4-terminal"
                       else "xfce4 xfce4-terminal xfce4-goodies thunar-archive-plugin catfish"
            "lxqt" -> if (type == "minimal") "lxqt"
                      else "lxqt sddm lxqt-about"
            "mate" -> if (type == "minimal") "mate-desktop mate-terminal"
                      else "mate-desktop mate-terminal mate-utils pluma eom engrampa"
            "kde" -> if (type == "minimal") "kde-plasma-desktop"
                     else "kde-full kde-standard"
            else -> "xfce4"
        }
    }

    private fun configureDE(de: String, type: String) {
        val rootfsDir = getRootfsDir()
        val configDir = File(rootfsDir, ".config")
        configDir.mkdirs()

        when (de) {
            "xfce4" -> {
                val xfceConfig = File(configDir, "xfce4/xfconf/xfce-perchannel-xml/xsettings.xml")
                xfceConfig.parentFile?.mkdirs()
                xfceConfig.writeText(
                    """<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string"><value value="Adwaita-dark"/></property>
    <property name="IconThemeName" type="string"><value value="Papirus"/></property>
  </property>
</channel>"""
                )
                // Create XFCE start script
                val xinitrc = File(rootfsDir, "root/.xinitrc")
                xinitrc.parentFile?.mkdirs()
                xinitrc.writeText("#!/bin/bash\nexec startxfce4\n")
                xinitrc.setExecutable(true)
            }
            "lxqt" -> {
                val lxqtConfig = File(configDir, "lxqt/lxqt.conf")
                lxqtConfig.parentFile?.mkdirs()
                lxqtConfig.writeText("[General]\nTheme=Adwaita-dark\n")
            }
            "mate" -> {
                val mateConfig = File(configDir, "dconf/user")
                mateConfig.parentFile?.mkdirs()
            }
        }

        // Create a common start script for VNC
        val vncStart = File(rootfsDir, "usr/local/bin/start-vnc.sh")
        vncStart.parentFile?.mkdirs()
        vncStart.writeText(
            """#!/bin/bash
export DISPLAY=:1
export PULSE_SERVER=tcp:127.0.0.1:4713
vncserver -geometry 1920x1080 -depth 24 :1 2>/dev/null || true
sleep infinity
"""
        )
        vncStart.setExecutable(true)
    }

    /**
     * Install GPU drivers INSIDE the proot container via apt.
     */
    private fun installGPUDriversInsideProot(distro: String) {
        val bash = bashPath()
        val prootPrefix = buildProotExecPrefix(distro)

        // Install mesa drivers inside proot — always install generic mesa as fallback
        try {
            executeShellCommand(
                "$bash -c '$prootPrefix /bin/bash -c \"DEBIAN_FRONTEND=noninteractive apt install -y mesa-utils libgl1-mesa-dri libgl1-mesa-glx libegl1-mesa libgles2-mesa 2>&1 || true\"'"
            )
        } catch (_: Exception) {}

        // Try specific GPU drivers (best-effort)
        val gpuVendor = getGPUVendor()
        try {
            when {
                gpuVendor.contains("adreno", ignoreCase = true) || gpuVendor.contains("qualcomm", ignoreCase = true) -> {
                    executeShellCommand(
                        "$bash -c '$prootPrefix /bin/bash -c \"DEBIAN_FRONTEND=noninteractive apt install -y mesa-vulkan-drivers 2>&1 || true\"'"
                    )
                }
                gpuVendor.contains("mali", ignoreCase = true) -> {
                    executeShellCommand(
                        "$bash -c '$prootPrefix /bin/bash -c \"DEBIAN_FRONTEND=noninteractive apt install -y libmali-midgard-gbm 2>&1 || true\"'"
                    )
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Detect GPU vendor using Android system properties (no shell needed).
     */
    private fun getGPUVendor(): String {
        return try {
            // Method 1: Use Build class to detect chipset
            val board = Build.BOARD?.lowercase() ?: ""
            val hardware = Build.HARDWARE?.lowercase() ?: ""
            val chipset = Build.SOC_MANUFACTURER?.lowercase() ?: ""
            val model = Build.MODEL?.lowercase() ?: ""

            val combined = "$board $hardware $chipset $model"
            combined
        } catch (_: Exception) {
            ""
        }
    }

    private fun detectInstalledDistro(): String {
        if (prootDir.exists()) {
            prootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
                    && dir.name != "backups" && dir.name != "backups") {
                    return dir.name
                }
            }
        }
        return ""
    }

    private fun detectInstalledDE(): String {
        val rootfsDir = getRootfsDir()
        if (!rootfsDir.exists()) return ""

        val checks = mapOf(
            "xfce4" to "usr/bin/startxfce4",
            "lxqt" to "usr/bin/startlxqt",
            "mate" to "usr/bin/mate-session",
            "kde" to "usr/bin/startplasma-wayland"
        )

        for ((de, indicator) in checks) {
            val file = File(rootfsDir, indicator)
            if (file.exists()) return de
        }
        return ""
    }

    private fun executeShellCommand(command: String): String {
        val bash = bashPath()
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf(bash, "-c", command)
            )
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Execute a long-running command (like apt install) with streaming progress.
     * Reports progress between [progressStart] and [progressEnd].
     */
    private fun executeLongCommand(
        command: String,
        channel: MethodChannel,
        progressStart: Double,
        progressEnd: Double
    ) {
        val bash = bashPath()
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf(bash, "-c", command)
            )
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            // Drain stderr in background
            Thread { errorReader.readText() }.start()

            var lineCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineCount++
                // Send progress updates periodically (every 50 lines)
                if (lineCount % 50 == 0) {
                    val progress = progressStart + (progressEnd - progressStart) * 0.8
                    sendProgress(channel, progress, "Installing packages... ($lineCount lines processed)")
                }
            }

            process.waitFor()
        } catch (e: Exception) {
            // Non-fatal
        }
    }

    private fun writeBootstrapScript() {
        try {
            bootstrapScript.writeText(
                """#!/system/bin/sh
# DroidForge Bootstrap Script
echo "Setting up DroidForge environment..."
echo "Bootstrap complete!"
"""
            )
            bootstrapScript.setExecutable(true)
        } catch (_: Exception) {}
    }

    private fun sendProgress(
        channel: MethodChannel,
        progress: Double,
        status: String
    ) {
        mainHandler.post {
            try {
                channel.invokeMethod(
                    "onInstallProgress",
                    mapOf("progress" to progress, "status" to status)
                )
            } catch (_: Exception) {}
        }
    }
}
