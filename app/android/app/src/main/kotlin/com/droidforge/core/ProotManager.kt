package com.droidforge.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * ProotManager — handles proot container lifecycle.
 *
 * - Bootstrap proot environment
 * - Install desktop environments
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
        return "proot" // hope it's on PATH
    }

    /** Check if the proot environment is bootstrapped */
    fun isBootstrapped(): Boolean {
        // Check if any distro rootfs dir has files
        if (prootDir.exists()) {
            prootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) {
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
     * Set up the bootstrap environment — ensure proot and base tools exist.
     * Calls [onProgress] during setup.
     */
    fun setupBootstrap(onProgress: (Double, String) -> Unit) {
        Thread {
            try {
                onProgress(0.05, "Creating directories...")
                prootDir.mkdirs()

                onProgress(0.1, "Checking proot installation...")
                val bash = bashPath()

                onProgress(0.2, "Setting up bootstrap script...")
                writeBootstrapScript()

                onProgress(0.3, "Installing base packages...")
                // Try installing via pkg (Termux), but don't crash if it fails
                try {
                    executeShellCommand("$bash -c 'pkg install -y proot tar x11-repo 2>/dev/null || true'")
                } catch (_: Exception) {}

                onProgress(0.5, "Installing PRoot utilities...")
                try {
                    executeShellCommand(
                        "$bash -c 'pkg install -y proot curl wget git python build-essential x11-apps xterm 2>/dev/null || true'"
                    )
                } catch (_: Exception) {}

                onProgress(0.7, "Installing display servers...")
                try {
                    executeShellCommand(
                        "$bash -c 'pkg install -y termux-x11-nightly pulseaudio virglrenderer-mesa-zink 2>/dev/null || true'"
                    )
                } catch (_: Exception) {}

                onProgress(1.0, "Bootstrap complete!")
            } catch (e: Exception) {
                onProgress(-1.0, "Bootstrap failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Install a desktop environment inside the proot container.
     * @param de Desktop environment: xfce4, lxqt, mate, kde
     * @param type Installation type: minimal or full
     */
    fun installDesktopEnvironment(
        de: String,
        type: String,
        messenger: BinaryMessenger
    ) {
        val channel = MethodChannel(messenger, "com.droidforge/core")

        Thread {
            try {
                sendProgress(channel, 0.05, "Installing $de ($type)...")

                val packages = getDEPackages(de, type)
                val cmd = "pkg install -y $packages"
                sendProgress(channel, 0.1, "Installing packages...")

                executeShellCommand(cmd)
                sendProgress(channel, 0.5, "Configuring desktop environment...")

                configureDE(de, type)
                sendProgress(channel, 0.8, "Installing GPU drivers...")
                installGPUDrivers()

                sendProgress(channel, 1.0, "$de installation complete!")
            } catch (e: Exception) {
                sendProgress(channel, -1.0, "DE installation failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Start the Linux desktop session.
     * @param de Desktop environment
     * @param mode Display mode: vnc or x11
     * @param width Display width
     * @param height Display height
     */
    fun startLinux(
        de: String,
        mode: String,
        width: Int,
        height: Int
    ) {
        stopLinux() // Kill any existing session

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

        // Kill proot processes (ignore errors)
        val bash = bashPath()
        try { executeShellCommand("$bash -c 'pkill -f proot || true'") } catch (_: Exception) {}
        try { executeShellCommand("$bash -c 'pkill -f Xvnc || true'") } catch (_: Exception) {}
        try { executeShellCommand("$bash -c 'pkill -f xfce4-session || true'") } catch (_: Exception) {}
        try { executeShellCommand("$bash -c 'pkill -f lxqt-session || true'") } catch (_: Exception) {}
        try { executeShellCommand("$bash -c 'pkill -f mate-session || true'") } catch (_: Exception) {}
        try { executeShellCommand("$bash -c 'pkill -f startplasma-wayland || true'") } catch (_: Exception) {}
    }

    /** Backup the container to a tar.gz file */
    fun backupContainer(name: String) {
        Thread {
            try {
                val backupDir = File(prootDir, "backups")
                backupDir.mkdirs()
                val backupFile = File(backupDir, "backup_${name}_${System.currentTimeMillis()}.tar.gz")
                val bash = bashPath()
                executeShellCommand(
                    "$bash -c 'tar -czf \"${backupFile.absolutePath}\" -C \"${prootDir}\" ubuntu'"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    /** Restore the container from a backup file */
    fun restoreContainer(name: String) {
        Thread {
            try {
                val backupDir = File(prootDir, "backups")
                val backupFiles = backupDir.listFiles()?.filter {
                    it.name.contains("backup_$name") && it.name.endsWith(".tar.gz")
                }?.sortedByDescending { it.lastModified() }

                if (backupFiles != null && backupFiles.isNotEmpty()) {
                    // Stop existing session
                    stopLinux()
                    // Remove current rootfs
                    val rootfsDir = File(prootDir, "ubuntu")
                    rootfsDir.deleteRecursively()
                    // Restore from backup
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

    /** Cleanup when the activity is destroyed */
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
        val rootfsDir = File(prootDir, detectInstalledDistro().ifEmpty { "ubuntu" })
        val prootRoot = rootfsDir.absolutePath
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
        val distro = detectInstalledDistro().ifEmpty { "ubuntu" }
        val rootfsDir = File(prootDir, distro)
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
    }

    private fun installGPUDrivers() {
        val gpuVendor = detectGPUVendor()
        when {
            gpuVendor.contains("adreno", ignoreCase = true) -> {
                executeShellCommand("pkg install -y mesa-vulkan-icd-freedreno || true")
            }
            gpuVendor.contains("mali", ignoreCase = true) -> {
                executeShellCommand("pkg install -y mesa-panfrost-dri || true")
            }
            else -> {
                executeShellCommand("pkg install -y mesa-zink || true")
            }
        }
    }

    private fun detectGPUVendor(): String {
        return try {
            val result = executeShellCommand("getprop ro.hardware.chipset")
            result.lowercase()
        } catch (_: Exception) {
            ""
        }
    }

    private fun detectInstalledDistro(): String {
        // Check proot directory for installed distros
        if (prootDir.exists()) {
            prootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
                    && dir.name != "backups") {
                    return dir.name
                }
            }
        }
        return ""
    }

    private fun detectInstalledDE(): String {
        val distro = detectInstalledDistro().ifEmpty { return "" }
        val rootfsDir = File(prootDir, distro)

        // Check common DE indicator files
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

    private fun writeBootstrapScript() {
        try {
            bootstrapScript.writeText(
                """#!/system/bin/sh
# DroidForge Bootstrap Script
echo "Setting up DroidForge environment..."

# Update packages
pkg update -y 2>/dev/null || true

# Install core dependencies
pkg install -y proot tar x11-repo proot-distro 2>/dev/null || true

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
        // Marshal to main thread for safety
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
