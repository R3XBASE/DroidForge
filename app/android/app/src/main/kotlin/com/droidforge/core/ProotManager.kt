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
    private val binaryManager = BinaryManager(context)

    /** Resolve the shell path — try Termux bash, then bundled binary, fall back to system sh */
    private fun bashPath(): String {
        val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
        if (termuxBash.exists()) return termuxBash.absolutePath
        val bundledBash = binaryManager.getBinaryPath("bash")
        if (bundledBash != null) return bundledBash
        return "/system/bin/sh"
    }

    /** Resolve the proot binary path — check Termux first, then bundled assets */
    private fun prootBinary(): String {
        val termuxProot = File("/data/data/com.termux/files/usr/bin/proot")
        if (termuxProot.exists()) return termuxProot.absolutePath
        return binaryManager.getProotPath()
    }

    /** Get the installed rootfs directory for the current distro */
    private fun getRootfsDir(distro: String? = null): File {
        val d = distro ?: detectInstalledDistro().ifEmpty { "ubuntu" }
        return File(prootDir, d)
    }

    /**
     * Build a proot command prefix that runs inside the container.
     *
     * Bind mounts:
     *   /dev, /proc, /sys       — standard Linux namespaces
     *   /dev/urandom:/dev/random — entropy
     *   /data/data/com.termux/files/home:/home — home directory access
     *   /etc/resolv.conf         — DNS resolution (critical for apt)
     *   tmp dir for apt locks     — avoid lock contention
     *
     * NOTE: We do NOT bind -b $prootRoot because that's the root itself (-r).
     */
    private fun buildProotExecPrefix(distro: String? = null): String {
        val rootfsDir = getRootfsDir(distro)
        val prootRoot = rootfsDir.absolutePath
        val proot = prootBinary()
        val libPath = binaryManager.getLibPath()

        return buildString {
            // LD_LIBRARY_PATH on HOST side — proot binary itself needs libtalloc.so.2
            append("LD_LIBRARY_PATH=$libPath ")
            append(proot)
            append(" -0")
            append(" -w /root")
            append(" --link2symlink")
            // Standard namespace binds
            append(" -b /dev")
            append(" -b /proc")
            append(" -b /sys")
            append(" -b /dev/urandom:/dev/random")
            // DNS resolution — required for apt to resolve mirrors
            val resolvConf = File("/etc/resolv.conf")
            if (resolvConf.exists()) {
                append(" -b /etc/resolv.conf")
            }
            // Home directory
            val homeDir = context.getExternalFilesDir(null) ?: context.filesDir
            append(" -b ${homeDir.absolutePath}:/home")
            // Root filesystem
            append(" -r $prootRoot")
            append(" --kill-on-exit")
            append(" --")
        }
    }

    /**
     * Set up networking and apt inside the proot container.
     * Must be called before any apt operations.
     */
    private fun setupContainerNetworking(distro: String) {
        val rootfsDir = getRootfsDir(distro)
        val bash = bashPath()

        // 1. Create /etc/resolv.conf if DNS bind didn't work
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        if (!resolvConf.exists() || resolvConf.readText().trim().isEmpty()) {
            try {
                resolvConf.writeText(
                    """# DroidForge DNS config
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 1.1.1.1
"""
                )
            } catch (_: Exception) {}
        }

        // 2. Create /etc/apt/sources.list if missing
        val sourcesList = File(rootfsDir, "etc/apt/sources.list")
        sourcesList.parentFile?.mkdirs()
        if (!sourcesList.exists() || sourcesList.readText().trim().isEmpty()) {
            when (distro) {
                "ubuntu" -> {
                    sourcesList.writeText(
                        """deb http://ports.ubuntu.com/ubuntu-ports/ noble main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ noble-updates main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ noble-security main restricted universe multiverse
"""
                    )
                }
                "debian" -> {
                    sourcesList.writeText(
                        """deb http://deb.debian.org/debian bookworm main contrib non-free non-free-firmware
deb http://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware
"""
                    )
                }
                "kali-nethunter" -> {
                    sourcesList.writeText(
                        """deb http://http.kali.org/kali kali-rolling main contrib non-free non-free-firmware
"""
                    )
                }
                "archlinux" -> {
                    // Arch uses pacman, not apt — create a marker
                    File(rootfsDir, "etc/pacman.d/mirrorlist").parentFile?.mkdirs()
                    File(rootfsDir, "etc/pacman.d/mirrorlist").writeText(
                        "Server = https://archlinux.org/\$repo/os/\$arch\n"
                    )
                }
                "alpine" -> {
                    val reposFile = File(rootfsDir, "etc/apk/repositories")
                    reposFile.parentFile?.mkdirs()
                    reposFile.writeText(
                        "https://dl-cdn.alpinelinux.org/alpine/v3.20/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.20/community\n"
                    )
                }
            }
        }

        // 3. Create /tmp with proper permissions for apt lock files
        val tmpDir = File(rootfsDir, "tmp")
        tmpDir.mkdirs()

        // 4. Create /var/tmp for apt
        val varTmpDir = File(rootfsDir, "var/tmp")
        varTmpDir.mkdirs()

        // 5. Create required dirs for dpkg
        File(rootfsDir, "var/lib/dpkg").mkdirs()
        File(rootfsDir, "var/cache/apt/archives").mkdirs()
        File(rootfsDir, "var/lib/apt/lists/partial").mkdirs()

        // 6. Ensure proper /etc/hostname
        val hostname = File(rootfsDir, "etc/hostname")
        hostname.parentFile?.mkdirs()
        if (!hostname.exists()) {
            hostname.writeText("droidforge\n")
        }

        // 7. Ensure /etc/hosts
        val hosts = File(rootfsDir, "etc/hosts")
        if (!hosts.exists()) {
            hosts.writeText("127.0.0.1 localhost droidforge\n")
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
     * Set up the bootstrap environment — extract bundled binaries.
     * No longer requires Termux or network access for proot.
     */
    fun setupBootstrap(onProgress: (Double, String) -> Unit) {
        Thread {
            try {
                onProgress(0.05, "Creating directories...")
                prootDir.mkdirs()

                onProgress(0.2, "Extracting proot binaries...")
                binaryManager.ensureBinaries()

                onProgress(0.5, "Setting up bootstrap script...")
                writeBootstrapScript()

                onProgress(0.8, "Verifying installation...")
                val proot = prootBinary()
                val shell = bashPath()
                onProgress(0.9, "Proot: $proot")
                onProgress(0.95, "Shell: $shell")

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

                // Step 0: Set up container networking and apt sources
                sendProgress(channel, 0.08, "Configuring container networking...")
                setupContainerNetworking(distro)

                val bash = bashPath()
                val prootPrefix = buildProotExecPrefix(distro)

                // Step 1: Update apt inside proot container
                sendProgress(channel, 0.12, "Updating package lists (apt update)...")
                val updateCmd = "$bash -c '$prootPrefix /bin/bash -c \"export DEBIAN_FRONTEND=noninteractive && apt update -y 2>&1 || echo APT_UPDATE_FAILED\"'"
                val updateOutput = executeShellCommand(updateCmd)
                if (updateOutput.contains("APT_UPDATE_FAILED") || updateOutput.contains("Err:") || updateOutput.contains("Could not resolve")) {
                    // Try one more time with verbose output
                    sendProgress(channel, 0.15, "Retrying apt update...")
                    val retryOutput = executeShellCommand(
                        "$bash -c '$prootPrefix /bin/bash -c \"apt update 2>&1 | tail -20\"'"
                    )
                    if (retryOutput.contains("Could not resolve") || retryOutput.contains("Err:")) {
                        sendProgress(channel, -1.0, "apt update failed — no network access inside container. Check DNS resolution.")
                        return@Thread
                    }
                }
                sendProgress(channel, 0.2, "Package lists updated.")

                // Step 2: Install DE packages inside proot container via apt
                val packages = getDEPackages(de, type)
                sendProgress(channel, 0.22, "Installing $de packages (this may take a while)...")

                // Run apt install with progress streaming
                val aptCmd = "$bash -c '$prootPrefix /bin/bash -c \"export DEBIAN_FRONTEND=noninteractive && apt install -y --no-install-recommends $packages 2>&1 | tail -50\"'"
                val aptOutput = executeLongCommand(aptCmd, channel, 0.22, 0.70)

                // Check if apt install succeeded
                if (aptOutput.contains("E: Unable to locate package") || aptOutput.contains("has no installation candidate")) {
                    val pkg = aptOutput.lines().firstOrNull { it.contains("Unable to locate package") } ?: "unknown"
                    sendProgress(channel, -1.0, "Package not found: $pkg\nTry: apt update first, or check package names.")
                    return@Thread
                }

                // Step 3: Install additional base tools inside proot
                sendProgress(channel, 0.72, "Installing base tools (dbus, fonts, sudo)...")
                val baseCmd = "$bash -c '$prootPrefix /bin/bash -c \"export DEBIAN_FRONTEND=noninteractive && apt install -y --no-install-recommends dbus-x11 fonts-liberation fonts-dejavu-core sudo wget curl 2>&1 | tail -20 || true\"'"
                executeLongCommand(baseCmd, channel, 0.72, 0.85)

                // Step 4: Configure DE
                sendProgress(channel, 0.87, "Configuring desktop environment...")
                configureDE(de, type)

                // Step 5: Verify installation
                sendProgress(channel, 0.90, "Verifying installation...")
                val verifyCmd = buildProotExecPrefix(distro)
                val verifyResult = executeShellCommand(
                    "$bash -c '$verifyCmd /bin/bash -c \"which startxfce4 2>/dev/null || which startlxqt 2>/dev/null || which mate-session 2>/dev/null || echo NO_DE_FOUND\"'"
                )
                if (verifyResult.contains("NO_DE_FOUND")) {
                    sendProgress(channel, -1.0, "DE binaries not found after install. Installation may have failed.")
                    return@Thread
                }

                // Step 6: Install GPU drivers inside proot
                sendProgress(channel, 0.93, "Installing GPU drivers...")
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
        val libPath = binaryManager.getLibPath()

        val prootArgs = buildString {
            // LD_LIBRARY_PATH on HOST side — proot binary itself needs libtalloc.so.2
            append("LD_LIBRARY_PATH=$libPath ")
            append(proot)
            append(" -0")
            append(" -w /root")
            append(" --link2symlink")
            append(" -b /dev")
            append(" -b /proc")
            append(" -b /sys")
            append(" -b /dev/urandom:/dev/random")
            val resolvConf = File("/etc/resolv.conf")
            if (resolvConf.exists()) {
                append(" -b /etc/resolv.conf")
            }
            val homeDir = context.getExternalFilesDir(null) ?: context.filesDir
            append(" -b ${homeDir.absolutePath}:/home")
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
                "$bash -c '$prootPrefix /bin/bash -c \"export DEBIAN_FRONTEND=noninteractive && apt install -y mesa-utils libgl1-mesa-dri libgl1-mesa-glx libegl1-mesa libgles2-mesa 2>&1 | tail -10 || true\"'"
            )
        } catch (_: Exception) {}

        // Try specific GPU drivers (best-effort)
        val gpuVendor = getGPUVendor()
        try {
            when {
                gpuVendor.contains("adreno", ignoreCase = true) || gpuVendor.contains("qualcomm", ignoreCase = true) -> {
                    executeShellCommand(
                        "$bash -c '$prootPrefix /bin/bash -c \"export DEBIAN_FRONTEND=noninteractive && apt install -y mesa-vulkan-drivers 2>&1 | tail -5 || true\"'"
                    )
                }
                gpuVendor.contains("mali", ignoreCase = true) -> {
                    executeShellCommand(
                        "$bash -c '$prootPrefix /bin/bash -c \"export DEBIAN_FRONTEND=noninteractive && apt install -y libmali-midgard-gbm 2>&1 | tail -5 || true\"'"
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
                    && dir.name != "backups") {
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
            "EXEC_ERROR: ${e.message}"
        }
    }

    /**
     * Execute a long-running command (like apt install) with streaming progress.
     * Returns the last 50 lines of output for error checking.
     */
    private fun executeLongCommand(
        command: String,
        channel: MethodChannel,
        progressStart: Double,
        progressEnd: Double
    ): String {
        val bash = bashPath()
        val outputLines = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf(bash, "-c", command)
            )
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            // Merge stderr into stdout for apt output
            Thread {
                try {
                    errorReader.forEachLine { line ->
                        outputLines.add(line)
                    }
                } catch (_: Exception) {}
            }.start()

            var lineCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                outputLines.add(line)
                lineCount++
                // Send progress updates periodically
                if (lineCount % 25 == 0) {
                    val progress = progressStart + (progressEnd - progressStart) * 0.8
                    // Show meaningful apt output to user
                    val meaningfulLine = line.trim()
                        .removePrefix("[")
                        .replace(Regex("^\\d+%" ), "")
                        .trim()
                    if (meaningfulLine.isNotEmpty() && meaningfulLine.length > 3) {
                        sendProgress(channel, progress, "Installing... $meaningfulLine")
                    }
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                // Include last 10 lines of output for debugging
                val tail = outputLines.takeLast(10).joinToString("\n")
                sendProgress(channel, -1.0, "Command failed (exit $exitCode):\n$tail")
            }

            return outputLines.joinToString("\n")
        } catch (e: Exception) {
            sendProgress(channel, -1.0, "Command error: ${e.message}")
            return "EXEC_ERROR: ${e.message}"
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
