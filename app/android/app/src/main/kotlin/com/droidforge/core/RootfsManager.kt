package com.droidforge.core

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * RootfsManager — downloads and extracts Linux rootfs for the proot container.
 *
 * Supports: Ubuntu, Debian, Kali, Arch, Alpine
 */
class RootfsManager(private val context: Context) {

    // Distro rootfs download URLs
    private val distroUrls = mapOf(
        "ubuntu" to "https://cloud-images.ubuntu.com/releases/24.04/release/ubuntu-24.04-server-cloudimg-arm64-root.tar.xz",
        "debian" to "https://cdimage.debian.org/cdimage/cloud/bookworm/daily/latest/debian-12-generic-arm64-daily.root.tar.xz",
        "kali-nethunter" to "https://images.kali.org/kali-2024.2-kali-rootfs-arm64.tar.xz",
        "archlinux" to "https://gitlab.archlinux.org/archlinux/archlinux-docker/-/packages/20849842/raw/latest/file/RootFs-x86_64.tar.xz",
        "alpine" to "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-rootfs-3.20.1-aarch64.tar.gz"
    )

    private val rootfsDir: File = File(context.filesDir, "proot/ubuntu")
    private val downloadDir: File = File(context.filesDir, "downloads")

    /**
     * Download a rootfs image for the given distribution.
     * Sends progress via the MethodChannel.
     */
    fun downloadRootfs(distro: String, messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, "com.droidforge/core")

        Thread {
            try {
                val url = distroUrls[distro]
                if (url == null) {
                    sendProgress(channel, -1.0, "Unsupported distro: $distro")
                    return@Thread
                }

                downloadDir.mkdirs()
                val filename = "rootfs_${distro}.tar.xz"
                val outputFile = File(downloadDir, filename)

                // Skip download if already cached
                if (outputFile.exists() && outputFile.length() > 1000) {
                    sendProgress(channel, 1.0, "Using cached rootfs...")
                    return@Thread
                }

                sendProgress(channel, 0.0, "Downloading $distro rootfs...")

                val connection = URL(url).openConnection()
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.connect()

                val totalSize = connection.contentLengthLong
                val inputStream = connection.getInputStream()
                val outputStream = FileOutputStream(outputFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    if (totalSize > 0) {
                        val progress = totalRead.toDouble() / totalSize.toDouble()
                        val percent = (progress * 100).toInt()
                        sendProgress(
                            channel, progress,
                            "Downloading: $percent% ($totalRead / $totalSize bytes)"
                        )
                    } else {
                        sendProgress(
                            channel, -0.0,
                            "Downloading: ${(totalRead / 1024 / 1024).toInt()} MB..."
                        )
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                sendProgress(channel, 1.0, "Download complete!")
            } catch (e: Exception) {
                sendProgress(channel, -1.0, "Download failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Extract the downloaded rootfs into the proot directory.
     * Sends progress via the MethodChannel.
     */
    fun extractRootfs(messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, "com.droidforge/core")

        Thread {
            try {
                sendProgress(channel, 0.0, "Preparing extraction...")

                rootfsDir.mkdirs()
                val downloadFiles = downloadDir.listFiles()
                    ?.filter { it.name.startsWith("rootfs_") }
                    ?.sortedByDescending { it.lastModified() }

                if (downloadFiles.isNullOrEmpty()) {
                    sendProgress(channel, -1.0, "No rootfs archive found. Download first.")
                    return@Thread
                }

                val archiveFile = downloadFiles.first()
                val fileSize = archiveFile.length()
                sendProgress(channel, 0.1, "Extracting ${archiveFile.name}...")

                // Extract using tar/pv based on file type
                val command = when {
                    archiveFile.name.endsWith(".tar.xz") -> {
                        "tar -xJf '${archiveFile.absolutePath}' -C '${rootfsDir}' --strip-components=0"
                    }
                    archiveFile.name.endsWith(".tar.gz") -> {
                        "tar -xzf '${archiveFile.absolutePath}' -C '${rootfsDir}' --strip-components=0"
                    }
                    else -> {
                        "tar -xf '${archiveFile.absolutePath}' -C '${rootfsDir}' --strip-components=0"
                    }
                }

                sendProgress(channel, 0.3, "Extracting rootfs...")
                val process = Runtime.getRuntime().exec(
                    arrayOf("/data/data/com.termux/files/usr/bin/bash", "-c", command)
                )

                // Read output for progress tracking
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                // Start error reader thread
                Thread { errorReader.readText() }.start()

                var lastProgress = 0.3
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotEmpty()) {
                        // Try to parse tar progress output
                        val match = Regex("(\\d+)%").find(line)
                        if (match != null) {
                            val pct = match.groupValues[1].toIntOrNull() ?: 0
                            val progress = 0.3 + (pct / 100.0 * 0.6)
                            if (progress > lastProgress) {
                                lastProgress = progress
                                sendProgress(channel, progress, "Extracting: $pct%")
                            }
                        }
                    }
                }

                process.waitFor()
                sendProgress(channel, 0.9, "Setting up environment...")

                // Post-extraction setup
                postExtractSetup()

                sendProgress(channel, 1.0, "Extraction complete!")
            } catch (e: Exception) {
                sendProgress(channel, -1.0, "Extraction failed: ${e.message}")
            }
        }.start()
    }

    /** Post-extraction: configure package manager, locale, etc. */
    private fun postExtractSetup() {
        try {
            // Create necessary directories
            val dirs = listOf("etc/apt", "var/cache/apt", "var/lib/apt", "tmp")
            dirs.forEach { dir ->
                File(rootfsDir, dir).mkdirs()
            }

            // Write basic apt configuration
            val aptConf = File(rootfsDir, "etc/apt/sources.list")
            aptConf.parentFile?.mkdirs()
            aptConf.writeText(
                """
deb http://ports.ubuntu.com/ubuntu-ports/ noble main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ noble-updates main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ noble-security main restricted universe multiverse
"""
            )

            // Set locale
            val localeConf = File(rootfsDir, "etc/default/locale")
            localeConf.parentFile?.mkdirs()
            localeConf.writeText("LANG=en_US.UTF-8\nLC_ALL=en_US.UTF-8\n")

            // Write resolv.conf for DNS
            val resolvConf = File(rootfsDir, "etc/resolv.conf")
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText(
                "nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n"
            )

            // Create home directory
            File(rootfsDir, "root").mkdirs()

        } catch (_: Exception) {}
    }

    private fun sendProgress(
        channel: MethodChannel,
        progress: Double,
        status: String
    ) {
        try {
            channel.invokeMethod(
                "onExtractProgress",
                mapOf("progress" to progress, "status" to status)
            )
        } catch (_: Exception) {}
    }
}
