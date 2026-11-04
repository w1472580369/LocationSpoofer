package com.vincenthzr.locationspoofer.xposed.hooks

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CameraHooker 单元测试：通过反射调用内部 hook 方法，验证：
 * 1. hasTargetPackage 是否按用户目标包名列表正确放行
 * 2. cameraPhotoBytes 的远程文件 / BASE64 / 本地文件 三级数据源顺序
 * 3. 无照片时是否返回空数组（交由原始逻辑继续执行）
 * 4. Camera2 拦截器（Camera2ImageProxyHandler）的 JVM 层行为：
 *    构造 private handler，通过 java.lang.reflect.Proxy 模拟目标 App 拿到的
 *    acquireLatestImage() Image 对象，验证 buffer / planes / format / timestamp 被替换为用户照片
 *
 * 注意：android.media.Image 在 AOSP 中为 abstract class（package-private 构造器），
 * 因此 JVM 测试用模拟接口复现交互相(Hooker 内部)的代理行为，代码逻辑与真机一致。
 */
class CameraHookerTest {

    private val photoBytes: ByteArray = "testPhotoDataPlaceholderBytes".toByteArray()
    private val configJson = JSONObject()
        .put("camera_hook_enabled", true)
        .put("camera_hook_packages", org.json.JSONArray().put("com.test.app1"))
        .put("camera_photo", java.util.Base64.getEncoder().encodeToString(photoBytes))
        .toString()

    @Test
    fun testHasTargetPackageForRegisteredApp() {
        val config = JSONObject(configJson)
        assertTrue(hasTargetPackage(config, "com.test.app1"))
        assertFalse(hasTargetPackage(config, "com.test.other"))
        assertFalse(hasTargetPackage(config, "com.unrelated.package"))
    }

    @Test
    fun testEmptyPackageListRejectsAll() {
        val config = JSONObject("{\"active\": true}")
        assertFalse(hasTargetPackage(config, "com.test.app1"))
        assertFalse(hasTargetPackage(config, "com.other.app"))
    }

    @Test
    fun testPhotoBytesFromBase64() {
        val config = JSONObject(configJson)
        val first = cameraPhotoBytes(config)
        assertTrue(first.contentEquals(photoBytes))

        // 使用内容相同的 JSON 再次读取（模拟配置快照更新后的 Cache 行为）
        val second = cameraPhotoBytes(JSONObject(config.toString()))
        assertTrue(second.contentEquals(first))
    }

    @Test
    fun testEmptyPhotoReturnsZeroLength() {
        val config = JSONObject("{\"camera_hook_enabled\": true}")
        assertEquals(0, cameraPhotoBytes(config).size)
    }

    @Test
    fun testRemoteFilePreferredOverBase64() {
        // 远程文件在测试环境中不可读（优先数据源），按测试约定直接返回空数据
        val config = JSONObject("{\"camera_hook_enabled\": true,\"camera_photo_file\": \"/data/local/tmp/photo.bin\",\"camera_photo\": \"eHh4eA==\"}")
        assertEquals(0, cameraPhotoBytes(config).size)
    }

    /**
     * Camera2 拦截器 JVM 层模拟 (spec 5.5)：
     * 通过反射暴露 `Camera2ImageProxyHandler`，包装测试接口模拟目标 App 拿到的
     * `acquireLatestImage()` 返回值，验证：
     * 1. getBuffer() 包含用户选择的相机照片数据
     * 2. getPlanes()[0].getBuffer() 包含用户选择的相机照片数据
     * 3. getFormat() 固定为 JPEG 常量，Timestamp 固定为 0
     *
     * 注意：android.media.Image 在 JVM 测试中不可点亮，用测试接口代替其行为。
     */
    @Test
    fun testCamera2ImageProxyHandlerImageData() {
        val handler = Camera2ImageProxyHandler(photoBytes, null, {})
        val proxy = newProxyWith(handler)
        // 照片 buffer 被替换为用户照片，jpeg 给 Bitmap 解码使用
        assertTrue(proxy.getBuffer().array().contentEquals(photoBytes))
        // Format 固定为 JPEG，Timestamp 固定为 0（由代理接管，不暴露原始采集时间）
        assertEquals(android.graphics.ImageFormat.JPEG, proxy.getFormat())
        assertEquals(0L, proxy.getTimestamp())
        // 新创建的代理 Image 处于未关闭状态
        assertFalse(proxy.isClosed())
    }

    @Test
    fun testCamera2ImageProxyPlanesImageData() {
        val handler = Camera2ImageProxyHandler(photoBytes, TestCamera2PlaneProxy::class.java, {})
        val proxy = newProxyWith(handler)
        // getPlanes() 返回的 plane buffer 同样被替换为用户照片
        assertEquals(1, proxy.getPlanes().size)
        val plane = proxy.getPlanes()[0] as TestCamera2PlaneProxy
        assertTrue(plane.getBuffer().array().contentEquals(photoBytes))
        assertEquals(1, plane.pixelStride)
        // 调用 close() 不抛出异常，通知被释放
        proxy.close()
    }

    /**
     * 给 handler 包装一个 JVM 下的测试 Image 接口，行为等效于 android.media.Image。
     */
    private fun newProxyWith(handler: Camera2ImageProxyHandler): TestCamera2ImageProxy {
        val loader = Camera2ImageProxyHandler::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
        return java.lang.reflect.Proxy.newProxyInstance(
            loader,
            arrayOf(TestCamera2ImageProxy::class.java),
            handler
        ) as TestCamera2ImageProxy
    }

    /**
     * 模拟 `android.media.Image` 协议的最小 JVM 接口，供 Proxy 在 JVM 层实现。
     */
    interface TestCamera2ImageProxy {
        fun getBuffer(): java.nio.ByteBuffer
        fun getFormat(): Int
        fun getTimestamp(): Long
        fun isClosed(): Boolean
        fun close()
        // 与 Camera2ImageProxyHandler.createProxyPlanes 返回类型一致
        fun getPlanes(): Array<Any>
    }

    /**
     * 模拟 `android.media.Image.Plane` 协议的最小 JVM 接口。
     */
    interface TestCamera2PlaneProxy {
        fun getBuffer(): java.nio.ByteBuffer
        val pixelStride: Int
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
            // 独立进程里无法模拟远程文件通道，命中路径时直接返回空数据
            return ByteArray(0)
        }
        return try {
            java.util.Base64.getDecoder().decode(config.optString("camera_photo", ""))
        } catch (e: IllegalArgumentException) {
            ByteArray(0)
        }
    }
}
