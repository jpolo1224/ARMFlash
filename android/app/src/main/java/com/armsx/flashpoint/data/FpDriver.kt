package com.armsx.flashpoint.data

/** libfpdriver.so: opens a custom Vulkan driver with libadrenotools. */
object FpDriver {
    private val loaded = runCatching { System.loadLibrary("fpdriver") }.isSuccess

    @JvmStatic private external fun nativeOpen(driverDir: String, libName: String, redirectDir: String, hookDir: String): Long
    @JvmStatic private external fun nativeLastError(): String

    /** vkGetInstanceProcAddr of the opened driver, or 0. */
    fun open(driverDir: String, libName: String, redirectDir: String, hookDir: String): Long =
        if (loaded) nativeOpen(driverDir, libName, redirectDir, hookDir) else 0L

    fun lastError(): String = if (loaded) nativeLastError() else "libfpdriver.so is missing"
}
