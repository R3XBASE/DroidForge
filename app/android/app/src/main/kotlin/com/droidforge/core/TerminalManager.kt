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
 */
class TerminalManager(private val context: Context) {

    private var currentProcess: Process? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Resolve the bash path — try Termux first, fall back to system sh */
    private fun bashPath(): String {
        val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
        return if (termuxBash.exists()) termuxBash.absolutePath else "/system/bin/sh"
    }

    /**
     * Execute a shell command and stream output back to Flutter.
     * Commands run in the proot environment if bootstrapped.
     */
    fun executeCommand(command: String, messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, "com.droidforge/core")

        // Kill previous command
        currentProcess?.destroyForcibly()

        Thread {
            try {
                val prootDir = context.filesDir.resolve("proot")
                // Find first installed distro directory
                val distroDir = prootDir.listFiles()?.firstOrNull {
                    it.isDirectory && it.listFiles()?.isNotEmpty() == true
                        && it.name != "backups"
                } ?: File(prootDir, "ubuntu")

                val isBootstrapped = distroDir.exists() && distroDir.listFiles()?.isNotEmpty() == true
                val bash = bashPath()

                val process = if (isBootstrapped) {
                    // Run command inside proot
                    val prootCmd = buildString {
                        append("proot")
                        append(" -0")
                        append(" -w /root")
                        append(" --link2symlink")
                        append(" -b /dev")
                        append(" -b /proc")
                        append(" -b /sys")
                        append(" -b /dev/urandom:/dev/random")
                        append(" -b ${distroDir.absolutePath}/:/root")
                        append(" -b /data/data/com.termux/files/home:/home")
                        append(" -r ${distroDir.absolutePath}")
                        append(" --kill-on-exit")
                        append(" -- /bin/bash -c '$command 2>&1'")
                    }
                    Runtime.getRuntime().exec(
                        arrayOf(bash, "-c", prootCmd)
                    )
                } else {
                    // Run command in shell
                    Runtime.getRuntime().exec(
                        arrayOf(bash, "-c", "$command 2>&1")
                    )
                }

                currentProcess = process

                // Read stdout
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                // Stream stdout
                val stdoutThread = Thread {
                    var line: String? = reader.readLine()
                    while (line != null) {
                        sendTerminalOutput(channel, "$line\n")
                        line = reader.readLine()
                    }
                }

                // Stream stderr
                val stderrThread = Thread {
                    var line: String? = errorReader.readLine()
                    while (line != null) {
                        sendTerminalOutput(channel, "$line\n")
                        line = errorReader.readLine()
                    }
                }

                stdoutThread.start()
                stderrThread.start()

                // Wait for completion
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

    /** Send interrupt signal to current command */
    fun interruptCommand() {
        currentProcess?.let { process ->
            try {
                // Send SIGINT
                process.destroyForcibly()
                currentProcess = null
            } catch (_: Exception) {}
        }
    }

    /** Cleanup when the activity is destroyed */
    fun cleanup() {
        currentProcess?.destroyForcibly()
        currentProcess = null
    }

    private fun sendTerminalOutput(channel: MethodChannel, text: String) {
        // Marshal to main thread for safety
        mainHandler.post {
            try {
                channel.invokeMethod(
                    "onTerminalOutput",
                    mapOf("text" to text)
                )
            } catch (_: Exception) {}
        }
    }
}
