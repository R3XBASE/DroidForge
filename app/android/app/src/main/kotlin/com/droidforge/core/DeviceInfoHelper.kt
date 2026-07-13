package com.droidforge.core

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.os.Build
import android.os.StatFs
import java.io.File

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
            "board" to Build.BOARD,
            "hardware" to Build.HARDWARE,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkVersion" to Build.VERSION.SDK_INT,
            "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown") as String,
            "gpuVendor" to detectGPUVendor(),
            "gpuRenderer" to detectGLRenderer(),
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
     * Detect GPU vendor from Build properties and sysfs files.
     * Uses multiple methods for maximum compatibility.
     */
    private fun detectGPUVendor(): String {
        // Method 1: Check Build.HARDWARE (most reliable on modern Android)
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val model = Build.MODEL.lowercase()
        val brand = Build.BRAND.lowercase()

        // Qualcomm/Adreno detection
        if (hardware.contains("qcom") || hardware.contains("msm") ||
            hardware.contains("sdm") || hardware.contains("sm8") ||
            hardware.contains("sm7") || hardware.contains("sm6") ||
            hardware.contains("sdm8") || hardware.contains("msm8") ||
            hardware.contains("kona") || hardware.contains("lahaina") ||
            hardware.contains("taro") || hardware.contains("cape") ||
            board.contains("qcom") || board.contains("lumia")) {
            return "adreno"
        }

        // MediaTek/Mali detection
        if (hardware.contains("mt") || hardware.contains("mediatek") ||
            board.contains("mt") || board.contains("mediatek")) {
            return "mali"
        }

        // Samsung/Exynos/Mali detection
        if (brand.contains("samsung") && (hardware.contains("exynos") || board.contains("exynos"))) {
            return "mali"
        }

        // Huawei/HiSilicon/Mali detection
        if (brand.contains("huawei") || brand.contains("honor")) {
            return "mali"
        }

        // PowerVR detection
        if (hardware.contains("powervr") || hardware.contains("img")) {
            return "powervr"
        }

        // Method 2: Read sysfs GPU info files
        val gpuInfoFiles = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/class/kgsl/kgsl-3d0/gpu_vendor",
            "/sys/class/misc/mali0/device/gpu_model",
            "/sys/class/misc/mali0/device/gpuvendor",
            "/sys/class/devfreq/gpufreq/available_frequencies"
        )

        for (file in gpuInfoFiles) {
            try {
                val content = File(file).readText().trim().lowercase()
                if (content.isNotEmpty()) {
                    when {
                        content.contains("adreno") || content.contains("qualcomm") -> return "adreno"
                        content.contains("mali") -> return "mali"
                        content.contains("powervr") || content.contains("sgx") -> return "powervr"
                    }
                }
            } catch (_: Exception) {}
        }

        // Method 3: Check OpenGL renderer string
        val glRenderer = detectGLRenderer()
        when {
            glRenderer.contains("adreno", ignoreCase = true) -> return "adreno"
            glRenderer.contains("mali", ignoreCase = true) -> return "mali"
            glRenderer.contains("powervr", ignoreCase = true) -> return "powervr"
            glRenderer.contains("freedreno", ignoreCase = true) -> return "adreno"
            glRenderer.contains("panfrost", ignoreCase = true) -> return "mali"
        }

        // Method 4: Check Qualcomm SoC model numbers
        if (model.contains("sm8") || model.contains("sdm") ||
            model.contains("qcom") || brand.contains("qualcomm")) {
            return "adreno"
        }

        return "unknown"
    }

    /**
     * Get the OpenGL ES renderer string.
     */
    private fun detectGLRenderer(): String {
        return try {
            // Try to get renderer from EGL
            val eglConfig = android.opengl.EGL14.eglGetCurrentContext()
            if (eglConfig != android.opengl.EGL14.EGL_NO_CONTEXT) {
                val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
                renderer?.lowercase() ?: ""
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }
}
