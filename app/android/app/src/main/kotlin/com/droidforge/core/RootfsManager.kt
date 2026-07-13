package com.droidforge.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.URL

/**
 * RootfsManager — downloads and extracts Linux rootfs for the proot container.
 *
 * Supports: Ubuntu, Debian, Kali, Arch, Alpine
 *
 * IMPORTANT: Uses .tar.gz format where available because Android's built-in
 * tar may not support .xz decompression without Termux.
 *
 * Ubuntu 24.04 uses merged-usr layout (/bin → usr/bin symlinks).
 * After extraction, we create real /bin/sh if it's missing (proot can't follow symlinks).
 */
class RootfsManager(private val context: Context) {

    // Distro rootfs download URLs — prefer .tar.gz for Android compatibility
    private val distroUrls = mapOf(
        "ubuntu" to "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-arm64.tar.gz",
        "debian" to "https://cdimage.debian.org/cdimage/cloud/bookworm/daily/latest/debian-12-genericarm64-daily.root.tar.xz",
        "kali-nethunter" to "https://images.kali.org/kali-2024.2-kali-rootfs-arm64.tar.xz",
        "archlinux" to "https://gitlab.archlinux.org/archlinux/archlinux-docker/-/packages/20849842/raw/latest/file/RootFs-x86_64.tar.xz",
        "alpine" to "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-rootfs-3.20.1-aarch64.tar.gz"
    )

    private val prootDir: File = File(context.filesDir, "proot")
    private val downloadDir: File = File(context.filesDir, "downloads")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val binaryManager = BinaryManager(context)

    /** Resolve the shell path — try Termux bash first, fall back to system sh */
    private fun bashPath(): String {
        val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
        if (termuxBash.exists()) return termuxBash.absolutePath
        return "/system/bin/sh"
    }

    /**
     * Download a rootfs image for the given distribution.
     * Sends progress via the MethodChannel (onDownloadProgress).
     */
    fun downloadRootfs(distro: String, messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, "com.droidforge/core")

        Thread {
            try {
                val url = distroUrls[distro]
                if (url == null) {
                    sendProgress(channel, "onDownloadProgress", -1.0, "Unsupported distro: $distro")
                    return@Thread
                }

                downloadDir.mkdirs()
                val filename = "rootfs_${distro}.tar.gz"
                val outputFile = File(downloadDir, filename)

                // Also check for old .xz cache and delete it (we switched to .tar.gz)
                val oldXz = File(downloadDir, "rootfs_${distro}.tar.xz")
                if (oldXz.exists()) oldXz.delete()

                // Skip download if already cached
                if (outputFile.exists() && outputFile.length() > 1000) {
                    sendProgress(channel, "onDownloadProgress", 1.0, "Using cached rootfs...")
                    return@Thread
                }

                sendProgress(channel, "onDownloadProgress", 0.0, "Downloading $distro rootfs...")

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
                            channel, "onDownloadProgress", progress,
                            "Downloading: $percent% ($totalRead / $totalSize bytes)"
                        )
                    } else {
                        sendProgress(
                            channel, "onDownloadProgress", -0.0,
                            "Downloading: ${(totalRead / 1024 / 1024).toInt()} MB..."
                        )
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                sendProgress(channel, "onDownloadProgress", 1.0, "Download complete!")
            } catch (e: Exception) {
                sendProgress(channel, "onDownloadProgress", -1.0, "Download failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Extract the downloaded rootfs into the proot directory.
     * Sends progress via the MethodChannel (onExtractProgress).
     *
     * After extraction, validates /bin/sh exists. If missing (merged-usr symlink issue),
     * creates the necessary symlinks/copies so proot can find the shell.
     */
    fun extractRootfs(distro: String, messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, "com.droidforge/core")
        val rootfsDir = File(prootDir, distro)

        Thread {
            try {
                sendProgress(channel, "onExtractProgress", 0.0, "Preparing extraction...")

                // Clean old extraction to start fresh
                if (rootfsDir.exists()) {
                    rootfsDir.deleteRecursively()
                }
                rootfsDir.mkdirs()

                // Find the downloaded archive — try .tar.gz first, then .tar.xz
                val archiveFile = findArchive(distro)
                if (archiveFile == null) {
                    sendProgress(channel, "onExtractProgress", -1.0, "No rootfs archive found. Download first.")
                    return@Thread
                }

                val fileSize = archiveFile.length()
                sendProgress(channel, "onExtractProgress", 0.1, "Extracting ${archiveFile.name} (${fileSize / 1024 / 1024}MB)...")

                // Extract using tar based on file type
                val command = when {
                    archiveFile.name.endsWith(".tar.xz") -> {
                        "tar -xJf '${archiveFile.absolutePath}' -C '${rootfsDir}' --strip-components=0 2>&1; echo EXIT_CODE=\$?"
                    }
                    archiveFile.name.endsWith(".tar.gz") || archiveFile.name.endsWith(".tgz") -> {
                        "tar -xzf '${archiveFile.absolutePath}' -C '${rootfsDir}' --strip-components=0 2>&1; echo EXIT_CODE=\$?"
                    }
                    else -> {
                        "tar -xf '${archiveFile.absolutePath}' -C '${rootfsDir}' --strip-components=0 2>&1; echo EXIT_CODE=\$?"
                    }
                }

                sendProgress(channel, "onExtractProgress", 0.3, "Extracting rootfs...")
                val bash = bashPath()
                val process = Runtime.getRuntime().exec(
                    arrayOf(bash, "-c", command)
                )

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                Thread { errorReader.readText() }.start()

                val outputLines = mutableListOf<String>()
                var lastProgress = 0.3
                while (true) {
                    val line = reader.readLine() ?: break
                    outputLines.add(line)
                    if (line.isNotEmpty()) {
                        val match = Regex("(\\d+)%").find(line)
                        if (match != null) {
                            val pct = match.groupValues[1].toIntOrNull() ?: 0
                            val progress = 0.3 + (pct / 100.0 * 0.6)
                            if (progress > lastProgress) {
                                lastProgress = progress
                                sendProgress(channel, "onExtractProgress", progress, "Extracting: $pct%")
                            }
                        }
                    }
                }

                val exitCode = process.waitFor()
                val output = outputLines.joinToString("\n")

                // Check if extraction actually worked
                if (exitCode != 0 || output.contains("EXIT_CODE=1") || output.contains("EXIT_CODE=2")) {
                    sendProgress(channel, "onExtractProgress", -1.0,
                        "tar extraction failed (exit $exitCode). Output:\n${output.takeLast(500)}")
                    return@Thread
                }

                sendProgress(channel, "onExtractProgress", 0.85, "Validating rootfs...")

                // Validate: check if essential files exist
                val validationFailed = validateRootfs(rootfsDir, distro)
                if (validationFailed != null) {
                    // Try to fix merged-usr layout
                    sendProgress(channel, "onExtractProgress", 0.88, "Fixing merged-usr layout...")
                    fixMergedUsr(rootfsDir)

                    // Re-validate
                    val recheck = validateRootfs(rootfsDir, distro)
                    if (recheck != null) {
                        sendProgress(channel, "onExtractProgress", -1.0,
                            "Rootfs validation failed after fixup: $recheck\n" +
                            "Extracted files: ${rootfsDir.listFiles()?.map { it.name }}")
                        return@Thread
                    }
                }

                sendProgress(channel, "onExtractProgress", 0.9, "Setting up environment...")

                // Post-extraction setup
                postExtractSetup(rootfsDir, distro)

                // Final validation
                val binSh = File(rootfsDir, "bin/sh")
                val usrBinSh = File(rootfsDir, "usr/bin/sh")
                sendProgress(channel, "onExtractProgress", 1.0,
                    "Extraction complete! /bin/sh=${binSh.exists()}, /usr/bin/sh=${usrBinSh.exists()}")
            } catch (e: Exception) {
                sendProgress(channel, "onExtractProgress", -1.0, "Extraction failed: ${e.message}")
            }
        }.start()
    }

    /** Find the archive file — prefer .tar.gz over .tar.xz */
    private fun findArchive(distro: String): File? {
        val gzFile = File(downloadDir, "rootfs_${distro}.tar.gz")
        if (gzFile.exists() && gzFile.length() > 1000) return gzFile

        val xzFile = File(downloadDir, "rootfs_${distro}.tar.xz")
        if (xzFile.exists() && xzFile.length() > 1000) return xzFile

        // Fall back to any rootfs_ file for this distro
        return downloadDir.listFiles()
            ?.filter { it.name.startsWith("rootfs_${distro}") && it.length() > 1000 }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
    }

    /**
     * Validate the extracted rootfs has essential files.
     * Returns null if OK, error message if not.
     */
    private fun validateRootfs(rootfsDir: File, distro: String): String? {
        // Check for at least one of these shell paths
        val shellCandidates = listOf(
            File(rootfsDir, "bin/sh"),
            File(rootfsDir, "usr/bin/sh"),
            File(rootfsDir, "bin/dash"),
            File(rootfsDir, "usr/bin/dash"),
            File(rootfsDir, "bin/busybox"),
            File(rootfsDir, "usr/bin/busybox")
        )
        if (shellCandidates.none { it.exists() }) {
            val files = rootfsDir.listFiles()?.map { it.name } ?: emptyList()
            return "No shell found in rootfs. Top-level files: $files"
        }

        // Check for essential directories
        val essentialDirs = listOf("etc", "usr", "var", "tmp")
        for (dir in essentialDirs) {
            if (!File(rootfsDir, dir).exists()) {
                return "Missing essential directory: $dir"
            }
        }

        return null
    }

    /**
     * Fix merged-usr layout: Ubuntu 24.04+ and other modern distros use symlinks
     * like /bin → usr/bin, but proot cannot resolve them properly.
     *
     * This creates real /bin, /sbin, /lib directories and copies essential files.
     */
    private fun fixMergedUsr(rootfsDir: File) {
        val bash = bashPath()

        // Check if /bin is a symlink (merged-usr indicator)
        val binDir = File(rootfsDir, "bin")
        val usrBinDir = File(rootfsDir, "usr/bin")

        if (!usrBinDir.exists()) {
            // No /usr/bin at all — can't fix
            return
        }

        // If /bin doesn't exist or is empty, create it
        if (!binDir.exists() || (binDir.isDirectory && binDir.listFiles()?.isEmpty() == true)) {
            // Remove broken symlink if it exists
            if (binDir.exists() && !binDir.isDirectory) {
                binDir.delete()
            }
            binDir.mkdirs()
        }

        // Copy essential binaries from /usr/bin to /bin
        val essentialBinaries = listOf("sh", "bash", "dash", "ls", "cat", "cp", "mv", "rm",
            "mkdir", "chmod", "chown", "echo", "ln", "grep", "sed", "awk", "find", "env",
            "mount", "umount", "ps", "kill", "id", "whoami", "pwd", "df", "du", "tar", "gzip")

        for (bin in essentialBinaries) {
            val target = File(usrBinDir, bin)
            val link = File(binDir, bin)
            if (target.exists() && !link.exists()) {
                try {
                    // Try symlink first
                    Runtime.getRuntime().exec(
                        arrayOf(bash, "-c", "ln -sf usr/bin/$bin '${link.absolutePath}'")
                    ).waitFor()
                    // If symlink didn't work, copy instead
                    if (!link.exists() || link.length() == 0L) {
                        link.delete()
                        target.copyTo(link)
                        link.setExecutable(true)
                    }
                } catch (_: Exception) {
                    try {
                        if (target.exists() && !link.exists()) {
                            target.copyTo(link)
                            link.setExecutable(true)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Also handle /sbin → usr/sbin
        val sbinDir = File(rootfsDir, "sbin")
        val usrSbinDir = File(rootfsDir, "usr/sbin")
        if (usrSbinDir.exists() && (!sbinDir.exists() || sbinDir.listFiles()?.isEmpty() == true)) {
            if (sbinDir.exists() && !sbinDir.isDirectory) sbinDir.delete()
            sbinDir.mkdirs()
            // Copy essential sbin binaries
            for (bin in usrSbinDir.listFiles()?.map { it.name } ?: emptyList()) {
                val link = File(sbinDir, bin)
                val target = File(usrSbinDir, bin)
                if (!link.exists() && target.exists()) {
                    try {
                        Runtime.getRuntime().exec(
                            arrayOf(bash, "-c", "ln -sf usr/sbin/$bin '${link.absolutePath}'")
                        ).waitFor()
                        if (!link.exists() || link.length() == 0L) {
                            link.delete()
                            target.copyTo(link)
                            link.setExecutable(true)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Handle /lib → usr/lib (create symlinks for library dirs)
        val libDir = File(rootfsDir, "lib")
        val usrLibDir = File(rootfsDir, "usr/lib")
        if (usrLibDir.exists() && !libDir.exists()) {
            try {
                Runtime.getRuntime().exec(
                    arrayOf(bash, "-c", "ln -sf usr/lib '${libDir.absolutePath}'")
                ).waitFor()
            } catch (_: Exception) {
                // If symlink fails, create actual dir (won't have content but won't break)
                libDir.mkdirs()
            }
        }
    }

    /** Post-extraction: configure package manager, locale, etc. */
    private fun postExtractSetup(rootfsDir: File, distro: String) {
        try {
            // Create necessary directories
            val dirs = listOf("etc/apt", "var/cache/apt", "var/lib/apt", "tmp", "root",
                "proc", "sys", "dev", "var/tmp")
            dirs.forEach { dir ->
                File(rootfsDir, dir).mkdirs()
            }

            // Write resolv.conf for DNS
            val resolvConf = File(rootfsDir, "etc/resolv.conf")
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText(
                "nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n"
            )

            // Set locale
            val localeConf = File(rootfsDir, "etc/default/locale")
            localeConf.parentFile?.mkdirs()
            localeConf.writeText("LANG=en_US.UTF-8\nLC_ALL=en_US.UTF-8\n")

            // Write hostname
            val hostname = File(rootfsDir, "etc/hostname")
            hostname.parentFile?.mkdirs()
            if (!hostname.exists()) hostname.writeText("droidforge\n")

            val hosts = File(rootfsDir, "etc/hosts")
            if (!hosts.exists()) hosts.writeText("127.0.0.1 localhost droidforge\n")

            // Ensure /etc/passwd
            val passwd = File(rootfsDir, "etc/passwd")
            if (!passwd.exists()) {
                passwd.parentFile?.mkdirs()
                passwd.writeText("root:x:0:0:root:/root:/bin/sh\n")
            }

            // Write distro-specific apt configuration
            val aptConf = File(rootfsDir, "etc/apt/sources.list")
            aptConf.parentFile?.mkdirs()
            aptConf.writeText(
                when (distro) {
                    "ubuntu" -> """
deb http://ports.ubuntu.com/ubuntu-ports/ noble main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ noble-updates main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ noble-security main restricted universe multiverse
""".trimIndent()
                    "debian" -> """
deb http://deb.debian.org/debian bookworm main contrib non-free non-free-firmware
deb http://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware
""".trimIndent()
                    "kali-nethunter" -> """
deb http://http.kali.org/kali kali-rolling main contrib non-free non-free-firmware
""".trimIndent()
                    "alpine" -> """
https://dl-cdn.alpinelinux.org/alpine/v3.20/main
https://dl-cdn.alpinelinux.org/alpine/v3.20/community
""".trimIndent()
                    else -> """
deb http://ports.ubuntu.com/ubuntu-ports/ noble main restricted universe multiverse
""".trimIndent()
                }
            )

            // Create dpkg lock dirs to prevent issues
            File(rootfsDir, "var/lib/dpkg").mkdirs()
            File(rootfsDir, "var/cache/apt/archives").mkdirs()
            File(rootfsDir, "var/lib/apt/lists/partial").mkdirs()

        } catch (_: Exception) {}
    }

    private fun sendProgress(
        channel: MethodChannel,
        methodName: String,
        progress: Double,
        status: String
    ) {
        try {
            mainHandler.post {
                try {
                    channel.invokeMethod(
                        methodName,
                        mapOf("progress" to progress, "status" to status)
                    )
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
