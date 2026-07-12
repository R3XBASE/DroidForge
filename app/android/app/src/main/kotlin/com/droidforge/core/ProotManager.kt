package com.droidforge.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
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
    private val rootfsDir: File = File(prootDir, "ubuntu")
    private val bootstrapScript: File = File(prootDir, "bootstrap.sh")
    private var runningProcess: Process? = null
    private var terminalProcess: Process? = null

    /** Check if the proot environment is bootstrapped */
    fun isBootstrapped(): Boolean {
        return rootfsDir.exists() && rootfsDir.listFiles()?.isNotEmpty() == true
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
     * Set up the bootstrap environment — download proot and create directory structure.
     * Calls [onProgress] during setup.
     */
    fun setupBootstrap(onProgress: (Double, String) -> Unit) {
        Thread {
            try {
                onProgress(0.05, "Creating directories...")
                prootDir.mkdirs()
                rootfsDir.mkdirs()

                onProgress(0.1, "Installing proot...")
                executeShellCommand("pkg install -y proot tar x11-repo")

                onProgress(0.2, "Setting up bootstrap script...")
                writeBootstrapScript()

                onProgress(0.3, "Installing base packages...")
                executeShellCommand(
                    "pkg install -y proot-distro x11-repo " +
                    "termux-x11-nightly pulseaudio virglrenderer-mesa-zink"
                )

                onProgress(0.4, "Setting up Termux-X11...")
                executeShellCommand("pkg install -y termux-x11-nightly")

                onProgress(0.5, "Installing PRoot utilities...")
                executeShellCommand(
                    "pkg install -y proot " +
                    "curl wget git python build-essential " +
                    "x11-apps xterm"
                )

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
                runningProcess = Runtime.getRuntime().exec(
                    arrayOf("/data/data/com.termux/files/usr/bin/bash", "-c", startCmd)
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

        // Kill proot processes
        executeShellCommand("pkill -f proot || true")
        executeShellCommand("pkill -f Xvnc || true")
        executeShellCommand("pkill -f xfce4-session || true")
        executeShellCommand("pkill -f lxqt-session || true")
        executeShellCommand("pkill -f mate-session || true")
        executeShellCommand("pkill -f startplasma-wayland || true")
    }

    /** Backup the container to a tar.gz file */
    fun backupContainer(name: String) {
        Thread {
            try {
                val backupDir = File(prootDir, "backups")
                backupDir.mkdirs()
                val backupFile = File(backupDir, "backup_${name}_${System.currentTimeMillis()}.tar.gz")
                executeShellCommand(
                    "tar -czf '${backupFile.absolutePath}' -C '${prootDir}' ubuntu"
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
                    rootfsDir.deleteRecursively()
                    // Restore from backup
                    executeShellCommand(
                        "tar -xzf '${backupFiles.first().absolutePath}' -C '${prootDir}'"
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
        val prootRoot = rootfsDir.absolutePath
        val debianMirror = "https://deb.debian.org/debian"
        val ubuntuMirror = "https://ports.ubuntu.com/ubuntu-ports"

        val prootArgs = buildString {
            append("proot")
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
        val configDir = File(rootfsDir, ".config")
        configDir.mkdirs()

        when (de) {
            "xfce4" -> {
                // XFCE4 config for Termux compatibility
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
        // Detect GPU and install appropriate Mesa drivers
        val gpuVendor = detectGPUVendor()
        when {
            gpuVendor.contains("adreno", ignoreCase = true) -> {
                executeShellCommand("pkg install -y mesa-vulkan-icd-freedreno")
            }
            gpuVendor.contains("mali", ignoreCase = true) -> {
                executeShellCommand("pkg install -y mesa-panfrost-dri")
            }
            else -> {
                executeShellCommand("pkg install -y mesa-zink")
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
        val prootDistroDir = File(context.filesDir, "proot-distro")
        if (prootDistroDir.exists()) {
            prootDistroDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) {
                    return dir.name
                }
            }
        }
        // Check if ubuntu rootfs exists
        if (isBootstrapped()) return "ubuntu"
        return ""
    }

    private fun detectInstalledDE(): String {
        if (!isBootstrapped()) return ""
        
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
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("/data/data/com.termux/files/usr/bin/bash", "-c", command)
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
        bootstrapScript.writeText(
            """#!/data/data/com.termux/files/usr/bin/bash
# DroidForge Bootstrap Script
echo "Setting up DroidForge environment..."

# Update packages
pkg update -y

# Install core dependencies
pkg install -y proot tar x11-repo proot-distro

echo "Bootstrap complete!"
"""
        )
        bootstrapScript.setExecutable(true)
    }

    private fun sendProgress(
        channel: MethodChannel,
        progress: Double,
        status: String
    ) {
        try {
            channel.invokeMethod(
                "onInstallProgress",
                mapOf("progress" to progress, "status" to status)
            )
        } catch (_: Exception) {}
    }
}
