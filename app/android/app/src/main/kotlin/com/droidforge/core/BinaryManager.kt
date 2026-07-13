package com.droidforge.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * BinaryManager — extracts bundled binaries from APK assets to app private dir.
 *
 * Standalone APK has no Termux, so we bundle:
 *   - proot (container runtime)
 *   - proot-loader (seccomp filter loader)
 *   - libtalloc.so.2 (proot dependency)
 *   - libandroid-shmem.so (proot dependency)
 *
 * These are extracted to context.filesDir/bin/ on first launch,
 * chmod +x, and used by ProotManager.
 *
 * LD_LIBRARY_PATH must be set on the HOST side (not via proot --env)
 * because the proot binary itself needs to link against these libs.
 */
class BinaryManager(private val context: Context) {

    private val binDir = File(context.filesDir, "bin")

    /** All binary assets that must be extracted */
    private val assets = listOf(
        "proot",
        "proot-loader",
        "libtalloc.so.2",
        "libandroid-shmem.so"
    )

    /**
     * Extract all bundled binaries to filesDir/bin/.
     * Skips if already extracted (checks version via marker file).
     * Creates symlinks for library compatibility.
     */
    fun ensureBinaries(): File {
        val marker = File(binDir, ".extracted_v2")
        if (marker.exists()) return binDir

        // Clean old extraction if marker is v1
        val oldMarker = File(binDir, ".extracted")
        if (oldMarker.exists()) {
            binDir.listFiles()?.forEach { it.delete() }
        }

        binDir.mkdirs()

        for (asset in assets) {
            val outFile = File(binDir, asset)
            try {
                context.assets.open("bin/$asset").use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                outFile.setReadable(true, true)
                outFile.setExecutable(true, true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Create symlinks for compatibility
        createSymlink(File(binDir, "libtalloc.so"), File(binDir, "libtalloc.so.2"))
        createSymlink(File(binDir, "libtalloc.so.2"), File(binDir, "libtalloc.so.2.4.3"))

        // proot-loader must have .so extension for proot's dlopen() in PROOT_LOADER
        val loader = File(binDir, "proot-loader")
        val loaderSo = File(binDir, "libproot-loader.so")
        if (loader.exists() && !loaderSo.exists()) {
            try { loader.copyTo(loaderSo) } catch (_: Exception) {}
        }

        // Mark extraction complete
        marker.writeText("ok")
        return binDir
    }

    /** Get full path to the proot binary */
    fun getProotPath(): String {
        ensureBinaries()
        return File(binDir, "proot").absolutePath
    }

    /** Get full path to the proot-loader (.so for dlopen) */
    fun getProotLoaderPath(): String {
        ensureBinaries()
        // Prefer .so version for PROOT_LOADER dlopen
        val soFile = File(binDir, "libproot-loader.so")
        if (soFile.exists()) return soFile.absolutePath
        return File(binDir, "proot-loader").absolutePath
    }

    /**
     * Get LD_LIBRARY_PATH for proot HOST-SIDE dependencies.
     * This MUST be set on the host shell, not via proot --env,
     * because the proot binary itself needs these libraries to run.
     */
    fun getLibPath(): String {
        ensureBinaries()
        return binDir.absolutePath
    }

    /**
     * Get the LD_LIBRARY_PATH prefix string for shell commands.
     * Usage: "$ldLibPath proot -0 ..."
     */
    fun getLdLibraryPathPrefix(): String {
        return "LD_LIBRARY_PATH=${getLibPath()}"
    }

    /** Get full path to a binary or null if not found */
    fun getBinaryPath(name: String): String? {
        ensureBinaries()
        val f = File(binDir, name)
        return if (f.exists()) f.absolutePath else null
    }

    private fun createSymlink(link: File, target: File) {
        try {
            if (!link.exists() && target.exists()) {
                link.parentFile?.mkdirs()
                Runtime.getRuntime().exec(
                    arrayOf("/system/bin/sh", "-c", "ln -sf '${target.name}' '${link.absolutePath}'")
                ).waitFor()
            }
        } catch (_: Exception) {
            // If symlink fails, just copy the file
            try {
                if (target.exists() && !link.exists()) {
                    target.copyTo(link, overwrite = true)
                }
            } catch (_: Exception) {}
        }
    }
}
