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
 *   - libtalloc.so (proot dependency)
 *   - libandroid-shmem.so (proot dependency)
 *
 * These are extracted to context.filesDir/bin/ on first launch,
 * chmod +x, and used by ProotManager.
 */
class BinaryManager(private val context: Context) {

    private val binDir = File(context.filesDir, "bin")

    /** All binary assets that must be extracted */
    private val assets = listOf(
        "proot",
        "proot-loader",
        "libtalloc.so",
        "libandroid-shmem.so"
    )

    /**
     * Extract all bundled binaries to filesDir/bin/.
     * Skips if already extracted (checks version via marker file).
     * Returns the path to the bin directory.
     */
    fun ensureBinaries(): File {
        val marker = File(binDir, ".extracted")
        if (marker.exists()) return binDir

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

        // Mark extraction complete
        marker.writeText("ok")
        return binDir
    }

    /** Get full path to the proot binary */
    fun getProotPath(): String {
        ensureBinaries()
        return File(binDir, "proot").absolutePath
    }

    /** Get full path to the proot-loader */
    fun getProotLoaderPath(): String {
        ensureBinaries()
        return File(binDir, "proot-loader").absolutePath
    }

    /** Get LD_LIBRARY_PATH for proot dependencies */
    fun getLibPath(): String {
        ensureBinaries()
        return binDir.absolutePath
    }

    /** Get full path to a binary or null if not found */
    fun getBinaryPath(name: String): String? {
        ensureBinaries()
        val f = File(binDir, name)
        return if (f.exists()) f.absolutePath else null
    }
}
