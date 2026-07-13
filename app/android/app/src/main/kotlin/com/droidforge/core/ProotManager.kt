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
 * IMPORTANT proot v5.1.0 notes:
 *   - NO '--' option separator — command follows directly after options
 *   - PROOT_TMP_DIR MUST be set to a writable dir (Android /tmp is not writable)
 *   - LD_LIBRARY_PATH must be on HOST side, not via --env
 *   - Rootfs may not have /bin/bash, use /bin/sh as fallback
 */
class ProotManager(private val context: Context) {

    private val prootDir: File = File(context.filesDir, "proot")
    private val bootstrapScript: File = File(prootDir, "bootstrap.sh")
    private var runningProcess: Process? = null
    private var terminalProcess: Process? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val binaryManager = BinaryManager(context)

    /** Writable tmp dir for proot — Android /tmp is not writable */
    private val prootTmpDir: File by lazy {
        val dir = File(context.filesDir, "proot_tmp")
        dir.mkdirs()
        dir
    }

    /** Resolve the shell path — try Termux bash, fall back to system sh */
    private fun bashPath(): String {
        val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
        if (termuxBash.exists()) return termuxBash.absolutePath
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
     * Find a working shell inside the rootfs.
     *
     * CRITICAL: We MUST prefer /bin/sh (dash) over /bin/bash.
     * - bash is dynamically-linked and needs ld-linux-aarch64.so.1 which proot
     *   often can't resolve on Android FUSE → "Permission denied"
     * - dash (/bin/sh) is statically-linked and always works
     * - Ubuntu Base has dash at /usr/bin/dash, with /bin/sh as symlink/copy
     */
    private fun rootfsShell(rootfsDir: File): String {
        // ALWAYS prefer /bin/sh first — dash is static, works everywhere
        val candidates = listOf(
            "/bin/sh", "/usr/bin/sh",
            "/bin/dash", "/usr/bin/dash",
            "/bin/busybox", "/usr/bin/busybox",
            "/bin/bash", "/usr/bin/bash"   // bash LAST — often broken on FUSE
        )
        for (c in candidates) {
            val f = File(rootfsDir, c.substring(1))
            if (f.exists() && f.length() > 0) return c
        }
        return "/bin/sh"
    }

    /**
     * Build environment prefix: HOST-side env vars for proot.
     * These MUST be set as shell env vars, not via proot --env.
     *
     * Critical vars (from DroidDesk):
     * - LD_LIBRARY_PATH: proot binary needs libtalloc.so.2 etc on host
     * - PROOT_TMP_DIR: Android /tmp not writable
     * - PROOT_LOADER: trampoline .so for dynamic linker fallback (merged-usr fix)
     * - PROOT_NO_SECCOMP=1: disables seccomp sandbox (Permission denied fix)
     */
    private fun buildEnvPrefix(): String {
        val libPath = binaryManager.getLibPath()
        // proot-loader must be a .so file for proot to dlopen it
        val loaderPath = binaryManager.getProotLoaderPath()
        return buildString {
            append("LD_LIBRARY_PATH=$libPath")
            append(" PROOT_TMP_DIR=${prootTmpDir.absolutePath}")
            if (loaderPath.isNotEmpty()) {
                append(" PROOT_LOADER=$loaderPath")
            }
            append(" PROOT_NO_SECCOMP=1")
        }
    }

    /**
     * Build proot options prefix (everything before the command to execute).
     *
     * Based on DroidDesk's approach (LinuxRuntime.kt):
     * - Uses --root-id instead of -0 (fake root, works on non-rooted Android)
     * - Uses --link2symlink (handles hard link limitations)
     * - Uses --sysvipc (shared memory for Chromium/LibreOffice)
     * - Binds /tmp to writable dir
     * - NOTE: proot v5.1.0 does NOT support '--' separator.
     */
    private fun buildProotOptions(distro: String? = null): String {
        val rootfsDir = getRootfsDir(distro)
        val prootRoot = rootfsDir.absolutePath
        val proot = prootBinary()
        val homeDir = context.getExternalFilesDir(null) ?: context.filesDir

        return buildString {
            append(proot)
            append(" --rootfs=$prootRoot")
            append(" -w /root")
            append(" --link2symlink")
            append(" --sysvipc")
            // Standard namespace binds
            append(" --bind=/dev")
            append(" --bind=/proc")
            append(" --bind=/sys")
            // Writable /tmp inside container
            append(" --bind=${prootTmpDir.absolutePath}:/tmp")
            // DNS resolution
            val resolvConf = File("/etc/resolv.conf")
            if (resolvConf.exists()) {
                append(" --bind=/etc/resolv.conf")
            }
            // Home directory
            append(" --bind=${homeDir.absolutePath}:/home")
            // Root ID (fake root, no real setuid needed)
            append(" --root-id")
            append(" --kill-on-exit")
        }
    }

    /**
     * Full proot command prefix: env vars + proot options.
     * Usage: "${buildProotExecPrefix()} /bin/sh -c '...'"
     */
    private fun buildProotExecPrefix(distro: String? = null): String {
        return "${buildEnvPrefix()} ${buildProotOptions(distro)}"
    }

    /**
     * Pre-flight rootfs fixup for rootfs extracted before fixMergedUsr was added.
     * Fixes critical merged-usr issues for proot:
     * 1. /bin may be symlink → proot can't follow → replace with real dir
     * 2. /lib may be symlink → proot can't find ld-linux-aarch64.so.1
     *    → execve("bash") fails with "Permission denied"
     * 3. /etc/passwd may have /bin/bash → bash can't run on FUSE
     * 4. Shell binaries may not have execute permission
     */
    private fun preflightRootfsFixup(rootfsDir: File) {
        // 1. Fix /bin (merged-usr symlink → real dir with binaries)
        val binDir = File(rootfsDir, "bin")
        val usrBinDir = File(rootfsDir, "usr/bin")
        if (binDir.exists() && !binDir.isDirectory && usrBinDir.exists()) {
            binDir.delete()
            binDir.mkdirs()
            for (name in listOf("sh", "dash", "bash", "ls", "cat", "cp", "mv", "rm", "mkdir",
                "chmod", "echo", "grep", "sed", "awk", "find", "env", "id", "whoami")) {
                val src = File(usrBinDir, name)
                val dst = File(binDir, name)
                if (src.exists() && !dst.exists()) {
                    try { src.copyTo(dst); dst.setExecutable(true) } catch (_: Exception) {}
                }
            }
        }

        // 2. Fix /lib (merged-usr symlink → critical for ld-linux-aarch64.so.1)
        // Ubuntu 24.04: /lib → usr/lib symlink. proot can't resolve the chain to find
        // ld-linux-aarch64.so.1 → execve("bash"): Permission denied.
        // PROOT_LOADER is the primary fix, but we also fix /lib for reliability.
        val libDir = File(rootfsDir, "lib")
        val usrLibDir = File(rootfsDir, "usr/lib")
        if (usrLibDir.exists() && libDir.exists() && !libDir.isDirectory) {
            // /lib is a symlink — replace with real dir + critical linker libs
            libDir.delete()
            libDir.mkdirs()
            // Copy critical arch-specific libs from /usr/lib/aarch64-linux-gnu/
            val archLibDir = File(rootfsDir, "usr/lib/aarch64-linux-gnu")
            if (archLibDir.exists()) {
                val targetArchDir = File(libDir, "aarch64-linux-gnu")
                targetArchDir.mkdirs()
                for (f in archLibDir.listFiles() ?: emptyArray()) {
                    // Copy dynamic linker + core libs that bash/apt need
                    if (f.name.startsWith("ld-linux") || f.name.startsWith("libc.so")
                        || f.name.startsWith("libm.so") || f.name.startsWith("libdl.so")
                        || f.name.startsWith("libpthread") || f.name.startsWith("librt.so")
                        || f.name.startsWith("libresolv.so") || f.name.startsWith("libgcc_s.so")) {
                        val dst = File(targetArchDir, f.name)
                        if (!dst.exists()) {
                            try { f.copyTo(dst) } catch (_: Exception) {}
                        }
                    }
                }
            }
            // Also copy non-arch-specific libs (e.g. libcrypt, libutil)
            for (f in usrLibDir.listFiles() ?: emptyArray()) {
                if (f.isFile && (f.name.startsWith("libcrypt.so") || f.name.startsWith("libutil.so"))) {
                    try { f.copyTo(File(libDir, f.name)) } catch (_: Exception) {}
                }
            }
        }

        // 3. Also fix /sbin if it's a symlink
        val sbinDir = File(rootfsDir, "sbin")
        val usrSbinDir = File(rootfsDir, "usr/sbin")
        if (sbinDir.exists() && !sbinDir.isDirectory && usrSbinDir.exists()) {
            sbinDir.delete()
            sbinDir.mkdirs()
        }

        // 4. Ensure /bin/sh exists and is a real file
        val binSh = File(rootfsDir, "bin/sh")
        if (!binSh.exists() || binSh.length() == 0L) {
            val sources = listOf(
                File(rootfsDir, "usr/bin/sh"),
                File(rootfsDir, "usr/bin/dash"),
                File(rootfsDir, "bin/dash")
            )
            for (src in sources) {
                if (src.exists() && src.length() > 0) {
                    try { binSh.parentFile?.mkdirs(); binSh.delete(); src.copyTo(binSh); binSh.setExecutable(true) } catch (_: Exception) {}
                    break
                }
            }
        }
        if (binSh.exists()) binSh.setExecutable(true)

        // 5. Force /etc/passwd to use /bin/sh
        val passwd = File(rootfsDir, "etc/passwd")
        passwd.parentFile?.mkdirs()
        try { passwd.writeText("root:x:0:0:root:/root:/bin/sh\n") } catch (_: Exception) {}
    }

    /**
     * Set up networking and apt inside the proot container.
     * Must be called before any apt operations.
     */
    private fun setupContainerNetworking(distro: String) {

        // Force /etc/passwd to use /bin/sh (not /bin/bash)
        val rootfsDir = getRootfsDir(distro)
        val passwd = File(rootfsDir, "etc/passwd")
        passwd.parentFile?.mkdirs()
        try {
            passwd.writeText("root:x:0:0:root:/root:/bin/sh\n")
        } catch (_: Exception) {}

        // 1. Create /etc/resolv.conf
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        if (!resolvConf.exists() || resolvConf.readText().trim().isEmpty()) {
            try {
                resolvConf.writeText(
                    "nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n"
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
                        "deb http://ports.ubuntu.com/ubuntu-ports/ noble main restricted universe multiverse\n" +
                        "deb http://ports.ubuntu.com/ubuntu-ports/ noble-updates main restricted universe multiverse\n" +
                        "deb http://ports.ubuntu.com/ubuntu-ports/ noble-security main restricted universe multiverse\n"
                    )
                }
                "debian" -> {
                    sourcesList.writeText(
                        "deb http://deb.debian.org/debian bookworm main contrib non-free non-free-firmware\n" +
                        "deb http://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware\n"
                    )
                }
                "kali-nethunter" -> {
                    sourcesList.writeText(
                        "deb http://http.kali.org/kali kali-rolling main contrib non-free non-free-firmware\n"
                    )
                }
            }
        }

        // 3. Create required dirs
        File(rootfsDir, "tmp").mkdirs()
        File(rootfsDir, "var/tmp").mkdirs()
        File(rootfsDir, "var/lib/dpkg").mkdirs()
        File(rootfsDir, "var/cache/apt/archives").mkdirs()
        File(rootfsDir, "var/lib/apt/lists/partial").mkdirs()
        File(rootfsDir, "proc").mkdirs()
        File(rootfsDir, "sys").mkdirs()
        File(rootfsDir, "dev").mkdirs()

        // 4. Ensure /etc/hostname and /etc/hosts
        val hostname = File(rootfsDir, "etc/hostname")
        hostname.parentFile?.mkdirs()
        if (!hostname.exists()) hostname.writeText("droidforge\n")

        val hosts = File(rootfsDir, "etc/hosts")
        if (!hosts.exists()) hosts.writeText("127.0.0.1 localhost droidforge\n")

        // 5. Force /etc/passwd to use /bin/sh (not /bin/bash)
        // Ubuntu Base rootfs ships with /bin/bash in passwd, but bash isn't installed.
        // proot reads this and fails with "execve: Permission denied".
        val passwd = File(rootfsDir, "etc/passwd")
        passwd.parentFile?.mkdirs()
        try {
            // Always overwrite to ensure /bin/sh is the shell
            passwd.writeText("root:x:0:0:root:/root:/bin/sh\n")
        } catch (_: Exception) {}
    }

    /** Check if the proot environment is bootstrapped and has a working rootfs */
    fun isBootstrapped(): Boolean {
        if (prootDir.exists()) {
            prootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
                    && dir.name != "backups" && dir.name != "proot_tmp") {
                    // Also verify /bin/sh exists in the rootfs (merged-usr may have broken symlinks)
                    val shell = rootfsShell(dir)
                    val shellFile = File(dir, shell.substring(1))
                    if (shellFile.exists() && shellFile.length() > 0) {
                        return true
                    }
                    // Rootfs exists but has no shell — broken extraction, not bootstrapped
                    return false
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
     */
    fun setupBootstrap(onProgress: (Double, String) -> Unit) {
        Thread {
            try {
                onProgress(0.05, "Creating directories...")
                prootDir.mkdirs()
                prootTmpDir.mkdirs()

                onProgress(0.2, "Extracting proot binaries...")
                binaryManager.ensureBinaries()

                onProgress(0.5, "Setting up bootstrap script...")
                writeBootstrapScript()

                onProgress(0.8, "Verifying installation...")
                val proot = prootBinary()
                onProgress(0.9, "Proot: $proot")

                onProgress(1.0, "Bootstrap complete!")
            } catch (e: Exception) {
                onProgress(-1.0, "Bootstrap failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Install a desktop environment inside the proot container.
     * Uses /bin/sh as fallback if /bin/bash is missing from rootfs.
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
                val rootfsDir = getRootfsDir(distro)
                sendProgress(channel, 0.05, "Preparing $de ($type) installation...")

                // Step 0: Pre-flight rootfs fixup (for rootfs extracted before fixMergedUsr)
                sendProgress(channel, 0.06, "Checking rootfs compatibility...")
                preflightRootfsFixup(rootfsDir)

                val shell = rootfsShell(rootfsDir)

                // Step 0b: Set up container networking and apt sources
                sendProgress(channel, 0.08, "Configuring container networking...")
                setupContainerNetworking(distro)

                val bash = bashPath()
                val prootPrefix = buildProotExecPrefix(distro)

                // Step 1: Update apt inside proot container
                sendProgress(channel, 0.12, "Updating package lists (apt update)...")
                val updateCmd = "$bash -c '${prootPrefix} $shell -c \"export DEBIAN_FRONTEND=noninteractive && apt update -y 2>&1 || echo APT_UPDATE_FAILED\"'"
                val updateOutput = executeShellCommand(updateCmd)
                if (updateOutput.contains("APT_UPDATE_FAILED") || updateOutput.contains("Err:") || updateOutput.contains("Could not resolve")) {
                    sendProgress(channel, 0.15, "Retrying apt update...")
                    val retryOutput = executeShellCommand(
                        "$bash -c '${prootPrefix} $shell -c \"apt update 2>&1 | tail -20\"'"
                    )
                    if (retryOutput.contains("Could not resolve") || retryOutput.contains("Err:")) {
                        sendProgress(channel, -1.0, "apt update failed. Check DNS/network.")
                        return@Thread
                    }
                }
                sendProgress(channel, 0.2, "Package lists updated.")

                // Step 2: Install DE packages
                val packages = getDEPackages(de, type)
                sendProgress(channel, 0.22, "Installing $de packages (this may take a while)...")
                val aptCmd = "$bash -c '${prootPrefix} $shell -c \"export DEBIAN_FRONTEND=noninteractive && apt install -y --no-install-recommends $packages 2>&1 | tail -50\"'"
                val aptOutput = executeLongCommand(aptCmd, channel, 0.22, 0.70)

                if (aptOutput.contains("E: Unable to locate package") || aptOutput.contains("has no installation candidate")) {
                    val pkg = aptOutput.lines().firstOrNull { it.contains("Unable to locate package") } ?: "unknown"
                    sendProgress(channel, -1.0, "Package not found: $pkg")
                    return@Thread
                }

                // Step 3: Install additional base tools
                sendProgress(channel, 0.72, "Installing base tools (dbus, fonts, sudo)...")
                val baseCmd = "$bash -c '${prootPrefix} $shell -c \"export DEBIAN_FRONTEND=noninteractive && apt install -y --no-install-recommends dbus-x11 fonts-liberation fonts-dejavu-core sudo wget curl 2>&1 | tail -20 || true\"'"
                executeLongCommand(baseCmd, channel, 0.72, 0.85)

                // Step 4: Configure DE
                sendProgress(channel, 0.87, "Configuring desktop environment...")
                configureDE(de, type)

                // Step 5: Verify installation
                sendProgress(channel, 0.90, "Verifying installation...")
                val verifyResult = executeShellCommand(
                    "$bash -c '${buildProotExecPrefix(distro)} $shell -c \"which startxfce4 2>/dev/null || which startlxqt 2>/dev/null || which mate-session 2>/dev/null || echo NO_DE_FOUND\"'"
                )
                if (verifyResult.contains("NO_DE_FOUND")) {
                    sendProgress(channel, -1.0, "DE binaries not found after install.")
                    return@Thread
                }

                // Step 6: Install GPU drivers
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
    fun startLinux(de: String, mode: String, width: Int, height: Int) {
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
            try { process.destroyForcibly() } catch (_: Exception) {}
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

    private fun buildStartCommand(de: String, mode: String, width: Int, height: Int): String {
        val rootfsDir = getRootfsDir()
        val shell = rootfsShell(rootfsDir)
        val prootOpts = buildProotOptions()
        val envPrefix = buildEnvPrefix()

        val deCommand = when (de) {
            "xfce4" -> "startxfce4"
            "lxqt" -> "startlxqt"
            "mate" -> "startmate-session"
            "kde" -> "dbus-launch startplasma-wayland"
            else -> "startxfce4"
        }

        return when (mode) {
            "vnc" -> {
                "$envPrefix $prootOpts $shell -c 'export DISPLAY=:1 && export PULSE_SERVER=tcp:127.0.0.1:4713 && vncserver -geometry ${width}x${height} -depth 24 :1 && sleep infinity'"
            }
            else -> {
                "$envPrefix $prootOpts $shell -c 'export DISPLAY=:0 && export PULSE_SERVER=tcp:127.0.0.1:4713 && $deCommand'"
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
        val shell = rootfsShell(rootfsDir)
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
                val xinitrc = File(rootfsDir, "root/.xinitrc")
                xinitrc.parentFile?.mkdirs()
                xinitrc.writeText("#!$shell\nexec startxfce4\n")
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

        val vncStart = File(rootfsDir, "usr/local/bin/start-vnc.sh")
        vncStart.parentFile?.mkdirs()
        vncStart.writeText(
            "#!$shell\nexport DISPLAY=:1\nexport PULSE_SERVER=tcp:127.0.0.1:4713\nvncserver -geometry 1920x1080 -depth 24 :1 2>/dev/null || true\nsleep infinity\n"
        )
        vncStart.setExecutable(true)
    }

    private fun installGPUDriversInsideProot(distro: String) {
        val bash = bashPath()
        val rootfsDir = getRootfsDir(distro)
        val shell = rootfsShell(rootfsDir)
        val prootPrefix = buildProotExecPrefix(distro)

        try {
            executeShellCommand(
                "$bash -c '${prootPrefix} $shell -c \"export DEBIAN_FRONTEND=noninteractive && apt install -y mesa-utils libgl1-mesa-dri libgl1-mesa-glx libegl1-mesa libgles2-mesa 2>&1 | tail -10 || true\"'"
            )
        } catch (_: Exception) {}
    }

    private fun getGPUVendor(): String {
        return try {
            val board = Build.BOARD?.lowercase() ?: ""
            val hardware = Build.HARDWARE?.lowercase() ?: ""
            val chipset = Build.SOC_MANUFACTURER?.lowercase() ?: ""
            val model = Build.MODEL?.lowercase() ?: ""
            "$board $hardware $chipset $model"
        } catch (_: Exception) { "" }
    }

    private fun detectInstalledDistro(): String {
        if (prootDir.exists()) {
            prootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
                    && dir.name != "backups" && dir.name != "proot_tmp") {
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
            if (File(rootfsDir, indicator).exists()) return de
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
     * Execute a long-running command with streaming progress.
     * Returns full output for error checking.
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

            Thread {
                try { errorReader.forEachLine { line -> outputLines.add(line) } } catch (_: Exception) {}
            }.start()

            var lineCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                outputLines.add(line)
                lineCount++
                if (lineCount % 25 == 0) {
                    val progress = progressStart + (progressEnd - progressStart) * 0.8
                    val meaningfulLine = line.trim().removePrefix("[").replace(Regex("^\\d+%"), "").trim()
                    if (meaningfulLine.isNotEmpty() && meaningfulLine.length > 3) {
                        sendProgress(channel, progress, "Installing... $meaningfulLine")
                    }
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
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
            bootstrapScript.writeText("#!/system/bin/sh\necho 'Bootstrap complete!'\n")
            bootstrapScript.setExecutable(true)
        } catch (_: Exception) {}
    }

    private fun sendProgress(channel: MethodChannel, progress: Double, status: String) {
        mainHandler.post {
            try {
                channel.invokeMethod("onInstallProgress", mapOf("progress" to progress, "status" to status))
            } catch (_: Exception) {}
        }
    }
}
