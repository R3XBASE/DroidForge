package com.droidforge.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * TerminalManager — manages the in-app terminal session.
 *
 * Executes commands in the proot environment and streams output to Flutter.
 *
 * IMPORTANT: proot v5.1.0 does NOT support '--' separator.
 * Uses PROOT_TMP_DIR, LD_LIBRARY_PATH, and /bin/sh fallback.
 */
class TerminalManager(private val context: Context) {

    private var currentProcess: Process? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val binaryManager = BinaryManager(context)

    private val prootTmpDir: File by lazy {
        val dir = File(context.filesDir, "proot_tmp")
        dir.mkdirs()
        dir
    }

    private fun bashPath(): String {
        val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
        if (termuxBash.exists()) return termuxBash.absolutePath
        return "/system/bin/sh"
    }

    private fun prootBinary(): String {
        val termuxProot = File("/data/data/com.termux/files/usr/bin/proot")
        if (termuxProot.exists()) return termuxProot.absolutePath
        return binaryManager.getProotPath()
    }

    /** Find a working shell inside the rootfs — prefer /bin/sh (dash, static) over bash */
    private fun rootfsShell(rootfsDir: File): String {
        val candidates = listOf(
            "/bin/sh", "/usr/bin/sh",
            "/bin/dash", "/usr/bin/dash",
            "/bin/busybox", "/usr/bin/busybox",
            "/bin/bash", "/usr/bin/bash"   // bash LAST — dynamic, often broken on FUSE
        )
        for (c in candidates) {
            val f = File(rootfsDir, c.substring(1))
            if (f.exists() && f.length() > 0) return c
        }
        return "/bin/sh"
    }

    private fun buildEnvPrefix(): String {
        val libPath = binaryManager.getLibPath()
        return "LD_LIBRARY_PATH=$libPath PROOT_TMP_DIR=${prootTmpDir.absolutePath}"
    }

    /**
     * Quick fixup: ensure rootfs is compatible with proot.
     * Handles merged-usr symlinks for /bin and /lib.
     */
    private fun quickShellFixup(rootfsDir: File) {
        // Fix /bin if it's a symlink (merged-usr)
        val binDir = File(rootfsDir, "bin")
        if (binDir.exists() && !binDir.isDirectory) {
            val usrBinDir = File(rootfsDir, "usr/bin")
            if (usrBinDir.exists()) {
                binDir.delete()
                binDir.mkdirs()
                for (name in listOf("sh", "dash", "bash", "ls", "cat", "cp", "mv", "rm")) {
                    val src = File(usrBinDir, name)
                    val dst = File(binDir, name)
                    if (src.exists() && !dst.exists()) {
                        try { src.copyTo(dst); dst.setExecutable(true) } catch (_: Exception) {}
                    }
                }
            }
        }
        // Ensure /bin/sh is executable
        val binSh = File(rootfsDir, "bin/sh")
        if (binSh.exists()) binSh.setExecutable(true)

        // Fix /lib if it's a symlink (merged-usr — needed for ld-linux-aarch64.so.1)
        val libDir = File(rootfsDir, "lib")
        val usrLibDir = File(rootfsDir, "usr/lib")
        if (libDir.exists() && !libDir.isDirectory && usrLibDir.exists()) {
            libDir.delete()
            libDir.mkdirs()
            val archLibDir = File(rootfsDir, "usr/lib/aarch64-linux-gnu")
            if (archLibDir.exists()) {
                val targetArchDir = File(libDir, "aarch64-linux-gnu")
                targetArchDir.mkdirs()
                for (f in archLibDir.listFiles() ?: emptyArray()) {
                    if (f.name.startsWith("ld-linux") || f.name.startsWith("libc.so")
                        || f.name.startsWith("libm.so") || f.name.startsWith("libdl.so")) {
                        try { f.copyTo(File(targetArchDir, f.name)) } catch (_: Exception) {}
                    }
                }
            }
        }

        // Fix /etc/passwd
        val passwd = File(rootfsDir, "etc/passwd")
        passwd.parentFile?.mkdirs()
        try { passwd.writeText("root:x:0:0:root:/root:/bin/sh\n") } catch (_: Exception) {}
    }

    /**
     * Execute a shell command and stream output back to Flutter.
     * Commands run in the proot environment if bootstrapped.
     */
    fun executeCommand(command: String, messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, "com.droidforge/core")

        currentProcess?.destroyForcibly()

        Thread {
            try {
                val prootDir = context.filesDir.resolve("proot")
                val distroDir = prootDir.listFiles()?.firstOrNull {
                    it.isDirectory && it.listFiles()?.isNotEmpty() == true
                        && it.name != "backups" && it.name != "proot_tmp"
                } ?: File(prootDir, "ubuntu")

                val isBootstrapped = distroDir.exists() && distroDir.listFiles()?.isNotEmpty() == true
                val bash = bashPath()

                // Quick fixup: ensure /bin/sh is real (not symlink) and passwd uses /bin/sh
                quickShellFixup(distroDir)

                val shell = rootfsShell(distroDir)

                val process = if (isBootstrapped) {
                    val proot = prootBinary()
                    val libPath = binaryManager.getLibPath()
                    val loaderPath = binaryManager.getProotLoaderPath()
                    val homeDir = context.getExternalFilesDir(null) ?: context.filesDir
                    val prootCmd = buildString {
                        append("LD_LIBRARY_PATH=$libPath PROOT_TMP_DIR=${prootTmpDir.absolutePath}")
                        if (loaderPath.isNotEmpty()) append(" PROOT_LOADER=$loaderPath")
                        append(" PROOT_NO_SECCOMP=1")
                        append(" $proot")
                        append(" --rootfs=${distroDir.absolutePath}")
                        append(" -w /root")
                        append(" --link2symlink")
                        append(" --sysvipc")
                        append(" --bind=/dev")
                        append(" --bind=/proc")
                        append(" --bind=/sys")
                        append(" --bind=${prootTmpDir.absolutePath}:/tmp")
                        append(" --bind=${homeDir.absolutePath}:/home")
                        append(" --root-id")
                        append(" --kill-on-exit")
                        // NO '--' — proot v5.1.0 doesn't support it
                        append(" $shell -c '$command 2>&1'")
                    }
                    Runtime.getRuntime().exec(arrayOf(bash, "-c", prootCmd))
                } else {
                    Runtime.getRuntime().exec(arrayOf(bash, "-c", "$command 2>&1"))
                }

                currentProcess = process

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                val stdoutThread = Thread {
                    var line: String? = reader.readLine()
                    while (line != null) {
                        sendTerminalOutput(channel, "$line\n")
                        line = reader.readLine()
                    }
                }

                val stderrThread = Thread {
                    var line: String? = errorReader.readLine()
                    while (line != null) {
                        sendTerminalOutput(channel, "$line\n")
                        line = errorReader.readLine()
                    }
                }

                stdoutThread.start()
                stderrThread.start()
                stdoutThread.join()
                stderrThread.join()

                val exitCode = process.waitFor()
                sendTerminalOutput(channel, "\n[Exit code: $exitCode]\n")

                currentProcess = null
            } catch (e: Exception) {
                sendTerminalOutput(channel, "Error: ${e.message}\n")
                currentProcess = null
            }
        }.start()
    }

    fun interruptCommand() {
        currentProcess?.let { process ->
            try { process.destroyForcibly(); currentProcess = null } catch (_: Exception) {}
        }
    }

    fun cleanup() {
        currentProcess?.destroyForcibly()
        currentProcess = null
    }

    private fun sendTerminalOutput(channel: MethodChannel, text: String) {
        mainHandler.post {
            try {
                channel.invokeMethod("onTerminalOutput", mapOf("text" to text))
            } catch (_: Exception) {}
        }
    }
}
