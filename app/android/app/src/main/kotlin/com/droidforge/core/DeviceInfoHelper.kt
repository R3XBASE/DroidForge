package com.droidforge.core

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import java.io.File
import java.io.RandomAccessFile

/**
 * DeviceInfoHelper — collects device information for Flutter.
 */
object DeviceInfoHelper {

    fun getDeviceInfo(context: Context): Map<String, Any> {
        val totalMem = getTotalMemory(context)
        val availStorage = getAvailableStorage(context)
        return mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "device" to Build.DEVICE,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkVersion" to Build.VERSION.SDK_INT,
            "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown") as String,
            "gpuVendor" to detectGPUVendor(),
            "totalMemory" to totalMem,
            "totalRamMB" to (totalMem / (1024 * 1024)).toInt(),
            "availableMemory" to getAvailableMemory(context),
            "totalStorage" to getTotalStorage(),
            "availableStorage" to availStorage,
            "availableStorageMB" to (availStorage / (1024 * 1024)).toInt(),
            "processorCount" to Runtime.getRuntime().availableProcessors()
        )
    }

    fun getSystemInfo(context: Context): Map<String, Any> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        return mapOf(
            "totalMemory" to memInfo.totalMem,
            "availableMemory" to memInfo.availMem,
            "lowMemory" to memInfo.lowMemory,
            "totalStorage" to getTotalStorage(),
            "availableStorage" to getAvailableStorage(context),
            "cpuCores" to Runtime.getRuntime().availableProcessors(),
            "uptime" to android.os.SystemClock.elapsedRealtime()
        )
    }

    fun getAvailableStorage(context: Context): Long {
        val stat = StatFs(context.filesDir.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    private fun getTotalMemory(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.totalMem
    }

    private fun getAvailableMemory(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.availMem
    }

    private fun getTotalStorage(): Long {
        val stat = StatFs("/data")
        return stat.blockCountLong * stat.blockSizeLong
    }

    /**
     * Detect GPU vendor from system properties.
     * Returns: "qualcomm", "adreno", "mali", "powervr", or "unknown"
     */
    private fun detectGPUVendor(): String {
        return try {
            // Try to read GPU info from system files
            val gpuInfoFiles = listOf(
                "/sys/class/kgsl/kgsl-3d0/gpu_model",
                "/sys/class/kgsl/kgsl-3d0/gpu_vendor",
                "/sys/class/misc/mali0/device/gpu_model"
            )

            for (file in gpuInfoFiles) {
                try {
                    val content = File(file).readText().trim().lowercase()
                    if (content.isNotEmpty()) {
                        when {
                            content.contains("adreno") || content.contains("qualcomm") -> return "adreno"
                            content.contains("mali") -> return "mali"
                            content.contains("powervr") -> return "powervr"
                            else -> return content
                        }
                    }
                } catch (_: Exception) {}
            }

            // Fallback: check OpenGL renderer
            val bashPath = if (File("/data/data/com.termux/files/usr/bin/bash").exists())
                "/data/data/com.termux/files/usr/bin/bash" else "/system/bin/sh"
            val glRenderer = try {
                val process = Runtime.getRuntime().exec(
                    arrayOf(bashPath, "-c", "getprop ro.hardware.chipset")
                )
                val reader = java.io.BufferedReader(
                    java.io.InputStreamReader(process.inputStream)
                )
                val result = reader.readLine() ?: ""
                process.waitFor()
                result.lowercase()
            } catch (_: Exception) { "" }

            when {
                glRenderer.contains("adreno") || glRenderer.contains("qualcomm") -> "adreno"
                glRenderer.contains("mali") -> "mali"
                glRenderer.contains("powervr") -> "powervr"
                else -> "unknown"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }
}
