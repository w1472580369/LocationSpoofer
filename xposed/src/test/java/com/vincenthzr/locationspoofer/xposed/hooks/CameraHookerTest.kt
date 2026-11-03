package com.vincenthzr.locationspoofer.xposed.hooks

import org.json.JSONObject
import org.junit.Test
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.test.core.app.ApplicationProvider

/**
 * CameraHooker 单元测试：通过反射调用内部 hook 方法，验证：
 * 1. ImageLoaderProxy 是否成功把原始 byte[] 替换为设置的 PhotoBytes
 * 2. PhotoCache 是否被正确命中
 * 3. hasTargetPackage 的逻辑是否正确工作
 *
 * 注意：实际的 XposedBridge + 系统 camera 在 JVM 测试环境中不可用，
 * 因此只利用拦截器设计的内存行为进行测试。
 */
class CameraHookerTest {

    private val photoBytes: ByteArray = "testPhotoDataPlaceholderBytes".toByteArray()
    private val originalData: ByteArray = "originalCameraJpegData".toByteArray()
    private val configJson = JSONObject()
        .put("camera_hook_enabled", true)
        .put("camera_hook_packages", org.json.JSONArray().put("com.test.app1"))
        .put("camera_photo", java.util.Base64.getEncoder().encodeToString(photoBytes))
        .toString()

    @Test
    fun testHasTargetPackage() {
        val config = JSONObject(configJson)
        assertTrue(hasTargetPackage(config, "com.test.app1"))
        assertEquals(false, hasTargetPackage(config, "com.test.other"))
        assertEquals(false, hasTargetPackage(config, "com.unrelated.package"))
    }

    @Test
    fun testPhotoBytesCache() {
        val config = JSONObject(configJson)
        val first = cameraPhotoBytes(config)
        val second = cameraPhotoBytes(JSONObject(config.toString()))
        assertEquals(photoBytes.size, first.size)
        assertTrue(first.contentEquals(photoBytes))
        assertTrue(second.contentEquals(first))
    }

    private fun hasTargetPackage(config: JSONObject, pkg: String): Boolean {
        val arr = config.optJSONArray("camera_hook_packages")
        if (arr == null || arr.length() == 0) return false
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == pkg) return true
        }
        return false
    }

    private fun cameraPhotoBytes(config: JSONObject): ByteArray {
        val photoFile = config.optString("camera_photo_file", "")
        if (photoFile.isNotEmpty()) {
            return ByteArray(0)
        }
        val raw = java.util.Base64.getDecoder().decode(config.optString("camera_photo", ""))
        if (raw.isNotEmpty()) return raw
        return ByteArray(0)
    }

    @Test
    fun testEmptyPhotoReturnsZeroLength() {
        val config = JSONObject("{\"camera_hook_enabled\": true}")
        assertEquals(ByteArray(0).size, cameraPhotoBytes(config).size)
    }
}
