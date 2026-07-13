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

    /** Find a working shell inside the rootfs */
    private fun rootfsShell(rootfsDir: File): String {
        val candidates = listOf("/bin/bash", "/usr/bin/bash", "/bin/sh", "/usr/bin/sh")
        for (c in candidates) {
            if (File(rootfsDir, c.substring(1)).exists()) return c
        }
        return "/bin/sh"
    }

    private fun buildEnvPrefix(): String {
        val libPath = binaryManager.getLibPath()
        return "LD_LIBRARY_PATH=$libPath PROOT_TMP_DIR=${prootTmpDir.absolutePath}"
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
                val shell = rootfsShell(distroDir)

                val process = if (isBootstrapped) {
                    val proot = prootBinary()
                    val libPath = binaryManager.getLibPath()
                    val homeDir = context.getExternalFilesDir(null) ?: context.filesDir
                    val prootCmd = buildString {
                        append("LD_LIBRARY_PATH=$libPath PROOT_TMP_DIR=${prootTmpDir.absolutePath} ")
                        append(proot)
                        append(" -0")
                        append(" -w /root")
                        append(" --link2symlink")
                        append(" -b /dev")
                        append(" -b /proc")
                        append(" -b /sys")
                        append(" -b /dev/urandom:/dev/random")
                        append(" -b ${distroDir.absolutePath}/:/root")
                        append(" -b ${homeDir.absolutePath}:/home")
                        append(" -r ${distroDir.absolutePath}")
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
