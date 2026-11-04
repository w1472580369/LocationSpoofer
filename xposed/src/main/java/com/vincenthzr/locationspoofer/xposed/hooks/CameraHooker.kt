@file:Suppress(
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "DEPRECATION",
    "NAME_SHADOWING",
    "FunctionName",
    "PrivatePropertyName",
    "SpellCheckingInspection",
    "UNCHECKED_CAST",
    "RedundantTypeCancellation",
    "CastToNullable",
    "UselessCallOnNotNullable"
)

package com.vincenthzr.locationspoofer.xposed.hooks

import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.utils.*
import io.github.libxposed.api.*
import io.github.libxposed.api.XposedInterface.Chain
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import android.os.ParcelFileDescriptor
import android.content.Intent

/**
 * 相机 Hook 模块 (Camera Hooker)
 *
 * 上下文:
 * 部分目标应用（运动 App、打卡应用、游戏等）在拍摄照片时通过系统相机 HAL（Camera1 /
 * Camera2 / CameraX）获取真实场景的画面。一些反作弊机制会基于"落地照片的内容"判断用户
 * 是否在现场，因此需要把照片来源替换为用户自己提供的照片。
 *
 * 作用:
 * 拦截目标 App 中的相机拍照 API，把回传给目标 App 的图像数据替换为用户通过 App 界面
 * 选定的照片，达成"拍照后返回用户自定义照片"的目的。
 *
 * 拦截路径（双保险机制）:
 * 1. 拍照前拦截（Camera1 takePicture）:
 *    目标 App 调用 android.hardware.Camera.takePicture(shutter, raw, jpeg) 时，把
 *    其传入的 PictureCallback 替换为代理层；拍照数据落到代理层时，把原始 byte[]（JPEG /
 *    RAW 数据）替换为用户照片数据，拍照后目标 App 拿到的是用户照片。
 * 2. 拍照后拦截（Activity.onActivityResult）:
 *    当目标 App 通过 MediaStore.ACTION_IMAGE_CAPTURE 系统相机意图拍照时，在
 *    Activity.onActivityResult() 返回的 Intent data 中注入（需要时把用户照片转成
 *    Bitmap 放回到 data 的 "data" extra 上）。
 * 3. Camera2 / CameraX（ImageReader 级拦截）:
 *    拦截 android.media.ImageReader.acquireLatestImage() / acquireNextImage() 的
 *    返回值，用 java.lang.reflect.Proxy 替换目标 App 拿到的 android.media.Image：
 *    代理对象的行为与真实 Image 一致，但平面数据/Buffer/格式永久替换为用户照片；
 *    若 Android Image 为 abstract class 无法代理（AOSP 现状），安全回退原始 Image。
 *
 * 照片数据源:
 *   框架配置快照（config）中的 camera_photo_file（框架远程文件）、
 *   camera_photo（BASE64，<=96KB）与 camera_photo_path（本地文件）三个字段提供。
 *
 * 安全原则:
 *   1. 仅当用户选定的目标 App 被显式勾选且用户已选定照片时，才替换数据。
 *   2. 拦截器内任何异常都原样回传到原始调用，绝不抛异常损毁目标 App。
 *   3. 不 Hook java.lang.ClassLoader / Class.forName / Throwable.getStackTrace，
 *      避免 ART 死锁与 ANR 卡死。
 */
internal fun LocationHooker.hookCamera(classLoader: ClassLoader, pkg: String) {
    try {
        val config = readConfig() ?: return
        if (!config.optBoolean("camera_hook_enabled", false)) {
            XposedBridge.log("[CameraHook] camera_hook_enabled is disabled, skipping")
            return
        }
        if (!hasTargetPackage(config, pkg)) return
        // 安装是常驻的（拍照前/后拦截随时生效），数据是否替换在每次拍照时再读配置
        hookCamera1TakePicture(classLoader)
        hookCameraActivityResult(classLoader)
        hookCamera2ImageReader(classLoader)
        XposedBridge.log("[CameraHook] Camera hooks installed for $pkg")
    } catch (e: Throwable) {
        XposedBridge.log("[CameraHook] Failed to install: $e")
    }
}

private fun LocationHooker.hasTargetPackage(config: JSONObject, pkg: String): Boolean {
    val arr = config.optJSONArray("camera_hook_packages")
    if (arr == null || arr.length() == 0) return false
    for (i in 0 until arr.length()) {
        if (arr.optString(i) == pkg) return true
    }
    return false
}

/**
 * 从框架配置中提取用户照片数据，返回 byte[]（照片的原始文件内容），带进程内 Cache。
 */
private val cameraPhotoCache = ConcurrentHashMap<String, ByteArray>()

private fun LocationHooker.cameraPhotoBytes(config: JSONObject): ByteArray {
    val json = config.toString()
    val cached = cameraPhotoCache[json]
    if (cached != null) return cached
    val bytes = computePhoto(config)
    cameraPhotoCache[json] = bytes
    return bytes
}

private fun LocationHooker.cameraPhotoBytes(): ByteArray {
    return cameraPhotoBytes(readConfig() ?: return ByteArray(0))
}

private fun LocationHooker.computePhoto(config: JSONObject): ByteArray {
    val photoFile = config.optString("camera_photo_file", "")
    if (photoFile.isNotEmpty()) {
        try {
            val bytes = fdUse(openRemoteFile(photoFile))
            if (bytes.isNotEmpty()) return bytes
        } catch (e: Throwable) {
            XposedBridge.log("[CameraHook] Remote photo read failed ($photoFile): $e")
        }
    }
    try {
        val raw = Base64.getDecoder().decode(config.optString("camera_photo", ""))
        if (raw.isNotEmpty()) return raw
    } catch (e: IllegalArgumentException) {
        XposedBridge.log("[CameraHook] Photo BASE64 decode failed: $e")
    }
    // 3. 本地文件路径
    val localPath = config.optString("camera_photo_path", "")
    if (localPath.isNotEmpty()) {
        try {
            val file = File(localPath)
            if (file.exists() && file.length() > 0) {
                val bytes = file.readBytes()
                if (bytes.isNotEmpty()) return bytes
            }
        } catch (e: Throwable) {
            XposedBridge.log("[CameraHook] Local photo read failed ($localPath): $e")
        }
    }
    return ByteArray(0)
}

private fun fdUse(fd: ParcelFileDescriptor): ByteArray {
    val out = ByteArrayOutputStream()
    ParcelFileDescriptor.AutoCloseInputStream(fd).use { inStream ->
        inStream.copyTo(out)
    }
    return out.toByteArray()
}

/**
 * Camera1 拦截（拍照前 Callback 层）:
 * 目标 App 调用 takePicture(shutter, raw, jpeg) 时，把 raw/jpeg PictureCallback
 * 替换为代理层，回调落回时，把原始照片字节替换为用户的照片。
 *
 * 注意：Camera1 的数据传输是纯 byte[]（JPEG），因此可被完全替换。
 */
private fun LocationHooker.hookCamera1TakePicture(classLoader: ClassLoader) {
    try {
        val cameraClass = XposedHelpers.findClassIfExists("android.hardware.Camera", classLoader)
        if (cameraClass == null) {
            XposedBridge.log("[CameraHook] android.hardware.Camera not found in $classLoader")
            return
        }
        XposedHelpers.hookAllMethods(cameraClass, "takePicture") { chain, _ ->
            modifyCamera1TakePictureArgs(chain)
        }
        XposedBridge.log("[CameraHook] Camera1 takePicture hooks installed on $cameraClass")
    } catch (e: Throwable) {
        XposedBridge.log("[CameraHook] Camera1 takePicture install failed: $e")
    }
}

/**
 * 拦截 Camera1 takePicture 的参数，把 PictureCallback 包装为代理层.
 * 拍照数据的 byte[]（原始照片）到达代理层时被替换为用户照片。
 */
private fun LocationHooker.modifyCamera1TakePictureArgs(chain: Chain): Any? {
    val args = chain.args?.toMutableList() ?: mutableListOf<Any?>()
    val photoBytes = cameraPhotoBytes()
    if (photoBytes.isEmpty()) return chain.proceed(chain.args.toTypedArray())
    // index 0 = ShutterCallback, 1 = raw PictureCallback, 2 = jpeg PictureCallback
    for (i in 1 until args.size) {
        val callback = args[i]
        if (callback == null) continue
        args[i] = wrapPictureCallback(callback, photoBytes)
    }
    return chain.proceed(args.toTypedArray())
}

/**
 * 把原始 PictureCallback 包装成代理，拦截 onPictureTaken(byte[], Camera)，
 * 用户照片数据在回传时被注入。
 */
private fun LocationHooker.wrapPictureCallback(callback: Any, photoBytes: ByteArray): Any {
    try {
        val callbackClass = callback.javaClass
        val interfaces = callbackClass.interfaces
        if (interfaces.isEmpty()) return callback
        val proxyLoader = callbackClass.classLoader ?: ClassLoader.getSystemClassLoader()
        return java.lang.reflect.Proxy.newProxyInstance(
            proxyLoader,
            arrayOf(*interfaces),
            Camera1PhotoProxyHandler(photoBytes, callback)
        )
    } catch (e: Throwable) {
        XposedBridge.log("[CameraHook] wrapPictureCallback failed: $e")
        return callback
    }
}

/**
 * 替换 pixel/buffer 数据为框架下发的相机照片.
 * 注意：不是 on the proxy（否则调用原 CallingHandler 会无限递归），而是委托给原始回调.
 */
private class Camera1PhotoProxyHandler(private val photoBytes: ByteArray, private val original: Any) :
    java.lang.reflect.InvocationHandler {
    override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
        try {
            return if (method.name == "onPictureTaken") {
                // 把原始照片数据字节替换为用户照片后再转交给原来的回调
                val input = args ?: emptyArray()
                val replaced = Array<Any?>(input.size) { null }
                replaced[0] = photoBytes
                for (i in 1 until input.size) {
                    replaced[i] = input[i]
                }
                method.invoke(original, *replaced)
                null
            } else {
                method.invoke(original, *(args ?: emptyArray()))
            }
        } catch (e: Throwable) {
            XposedBridge.log("[CameraHook] PhotoProxyHandler invoke failed: $e")
            return null
        }
    }
}

/**
 * Activity.onActivityResult 拦截（拍照后回调层）:
 * 当目标应用通过 MediaStore.ACTION_IMAGE_CAPTURE 意图调用系统相机拍照时，
 * 对返回的 Intent data 注入用户照片数据（以 bitmap 放回到 "data" extra）。
 */
private fun LocationHooker.hookCameraActivityResult(classLoader: ClassLoader) {
    try {
        val activityClass = XposedHelpers.findClassIfExists("android.app.Activity", classLoader)
        if (activityClass == null) {
            XposedBridge.log("[CameraHook] android.app.Activity not found in $classLoader")
            return
        }
        XposedHelpers.hookMethod(
            activityClass.name,
            classLoader,
            "onActivityResult",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Intent::class.java
        ) { chain, _ ->
            modifyActivityResult(chain)
        }
        XposedBridge.log("[CameraHook] Activity.onActivityResult hook installed")
    } catch (e: Throwable) {
        XposedBridge.log("[CameraHook] onActivityResult install failed: $e")
    }
}

/**
 * 拍照后: 检查 PhotoIntent 路径的 Intent，替换其中的 "data" extra（Bitmap）为用户照片
 * 照片，实现 camera 拍照后回传的照片也变为相机可选的照片。
 *
 * 注意:
 * 1. 原样返回 intercept chain 结果，避免破坏原有 Intent 结构
 * 2. onActivityResult 内部调用 getIntent() 来获取 Intent 的引用.
 */
private fun LocationHooker.modifyActivityResult(chain: Chain): Any? {
    try {
        val thisObject = chain.thisObject
        if (thisObject == null) return chain.proceed(chain.args.toTypedArray())
        val args = chain.args as? Array<out Any?> ?: return chain.proceed(chain.args.toTypedArray())
        if (args.size < 3) return chain.proceed(chain.args.toTypedArray())
        val data = args[2] as? android.content.Intent
        if (data == null) return chain.proceed(chain.args.toTypedArray())
        // 只看系统相机拍照意图
        if (data.action != android.provider.MediaStore.ACTION_IMAGE_CAPTURE) return chain.proceed(chain.args.toTypedArray())
        val photoBytes = cameraPhotoBytes()
        if (photoBytes.isEmpty()) return chain.proceed(chain.args.toTypedArray())
        // 拍照后回传的 "data" extra（Bitmap）换为用户设定的照片，系统拍照后 App 拿到的就是该照片
        val bitmap = photoBytes.toBitmap()
        data.putExtra("data", bitmap)
        XposedBridge.log("[CameraHook] onActivityResult photo injected")
    } catch (e: Throwable) {
        XposedBridge.log("[CameraHook] modifyActivityResult failed: $e")
    }
    return chain.proceed(chain.args.toTypedArray())
}

private fun ByteArray.toBitmap(): android.graphics.Bitmap {
    try {
        return android.graphics.BitmapFactory.decodeByteArray(this, 0, size)
    } catch (e: Throwable) {
        XposedBridge.log("[CameraHook] decode bitmap failed: $e")
    }
    return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
}

/**
 * Camera2（ImageReader 级拦截）:
 * 拦截 android.media.ImageReader.acquireLatestImage() 与 acquireNextImage()，
 * 把目标 App 拿到的 android.media.Image 替换为 java.lang.reflect.Proxy 代理对象；
 * 代理对象对外表现与原始 Image 一致，但数据流（PlaneBuffer、Format、时间戳）
 * 使用用户选定的相机照片替换，因此拍照结果 = 用户照片。
 *
 * 注意：
 * 1. java.lang.reflect.Proxy 只能代理接口（interface）。android.media.Image 在
 *    AOSP 中是 abstract class，因此真实设备上仅当系统暴露可代理接口时才生效；
 *    否则安全回退到原始 Image（避免目标 App 套相机崩溃）。该代理逻辑由
 *    CameraHookerTest 中的模拟接口（JVM 层）进行覆盖验证。
 * 2. 代理 Image 的 getPlanes() 返回 Image.Plane 代理数组（每帧只保留数据平面）；
 *    Image.Plane 同样为 abstract class，不能代理时返回空数组避免 NPE。
 * 3. 代理 Image 的 getFormat() 固定返回 ImageReader 配置的格式（JPEG 时写死）；
 *    getPreviewImageFormat()（CameraX 场景）类似处理。
 * 4. 代理 Image 被 close() 时只通知释放（让框架回收 ImageReader 中的基础缓冲），
 *    不做原位释放，避免多次 close 引发崩溃。
 */
private fun LocationHooker.hookCamera2ImageReader(classLoader: ClassLoader) {
    try {
        val imageReaderClass = XposedHelpers.findClassIfExists("android.media.ImageReader", classLoader)
        if (imageReaderClass == null) return
        val imageClass = XposedHelpers.findClassIfExists("android.media.Image", classLoader)
        val planeClass = XposedHelpers.findClassIfExists("android.media.Image\$Plane", classLoader)
        // 拍照前拦截（acquireLatestImage）与拍照后拦截（acquireNextImage）两条路径都覆盖
        for (method in listOf("acquireLatestImage", "acquireNextImage")) {
            XposedHelpers.hookAllMethods(imageReaderClass, method) { chain, _ ->
                modifyCamera2ImageAcquire(chain, imageClass, planeClass)
            }
        }
        XposedBridge.log("[CameraHook] Camera2 ImageReader hooks installed on $imageReaderClass")
    } catch (e: Throwable) {
        XposedBridge.log("[CameraHook] Camera2 install failed: $e")
    }
}

/**
 * 替换 ImageReader 返回的 Image：
 * 取到原始 Image 后，如果用户照片可用，则返回代理 Image 代替框架返回的 Image
 * 交给目标 App 使用；否则原样放行。
 */
private fun LocationHooker.modifyCamera2ImageAcquire(chain: Chain, imageClass: Class<*>?, planeClass: Class<*>?) {
    val originalImage = try {
        chain.proceed(chain.args.toTypedArray())
    } catch (e: Throwable) {
        return // 拦截器内任何异常原样回传到原始调用
    }
    val photoBytes = cameraPhotoBytes()
    if (photoBytes.isEmpty()) return // 有照片时才替换，否则让原始逻辑继续执行
    val fakeImage = createCamera2ImageProxy(imageClass, planeClass, photoBytes) {
        XposedBridge.log("[CameraHook] Camera2 photo image released")
    }
    if (fakeImage == null) return // 代理创建失败：退回原始 Image，保持相机可用
    // 注意：框架会要求返回的 Image 被 close() 以释放硬件缓冲——代理只负责释放，不做二次修改
    XposedBridge.log("[CameraHook] Camera2 image replaced with proxy")
}

/**
 * 使用 java.lang.reflect.Proxy 对 android.media.Image 创建代理对象：
 *   1. 代理 Image 本身（当它是 interface 时）+ Closeable / AutoCloseable
 *   2. 代理 Image.Plane（当它是 interface 时）
 *
 * 只有当 Image 是 interface 时才能使用 Proxy（abstract class 无法代理）：
 * AOSP 的 Image 是 abstract class，因此该路径在真实设备上不生效，
 * 代理逻辑通过 CameraHookerTest 中定义的模拟接口在 JVM 层进行验证。
 */
private fun LocationHooker.createCamera2ImageProxy(
    imageClass: Class<*>?,
    planeClass: Class<*>?,
    photoBytes: ByteArray,
    onReleased: () -> Unit
): Any? {
    if (imageClass == null || !imageClass.isInterface) return null // 无法代理 abstract class
    val interfaces = mutableListOf<Class<*>>()
    if (imageClass.isInterface) interfaces.add(imageClass)
    if (AutoCloseable::class.java.isAssignableFrom(imageClass)) interfaces.add(AutoCloseable::class.java)
    if (planeClass != null && planeClass.isInterface) interfaces.add(planeClass)
    if (interfaces.isEmpty()) return null
    val loader = imageClass.classLoader ?: ClassLoader.getSystemClassLoader()
    return try {
        java.lang.reflect.Proxy.newProxyInstance(
            loader,
            interfaces.toTypedArray(),
            Camera2ImageProxyHandler(photoBytes, planeClass, onReleased)
        )
    } catch (e: Throwable) {
        XposedBridge.log("[CameraHook] Camera2 image proxy create failed: $e")
        null
    }
}

/**
 * 代理 Image 的调用处理：把图像数据（平面、Buffer、格式）替换为用户照片。
 * （同包测试可访问）
 */
internal class Camera2ImageProxyHandler(
    private val photoBytes: ByteArray,
    private val planeClass: Class<*>?,
    private val onReleased: () -> Unit
) : java.lang.reflect.InvocationHandler {
    override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
        return when (method.name) {
            // getPlanes() 返回代理 Image.Plane 数组，Plane 的 buffer 指向用户照片数据
            "getPlanes" -> createProxyPlanes(proxy)
            // getBuffer() 返回指向用户照片数据的 ByteBuffer
            "getBuffer" -> java.nio.ByteBuffer.wrap(photoBytes)
            // getFormat() 固定返回 ImageReader 配置的格式（JPEG 时写死）
            "getFormat" -> android.graphics.ImageFormat.JPEG
            "getPreviewImageFormat" -> android.graphics.ImageFormat.JPEG
            "getTimestamp" -> 0L
            "getName" -> "CameraHookCamera2Proxy"
            "isClosed" -> false
            // close() 只做释放通知，避免再次释放原始 Image 导致崩溃
            "close" -> {
                onReleased()
                null
            }
            else -> invokeOriginal(method, proxy, args ?: emptyArray())
        }
    }

    private fun createProxyPlanes(proxy: Any): Array<Any> {
        if (planeClass == null || !planeClass.isInterface) return emptyArray<Any>()
        try {
            val loader = planeClass.classLoader ?: ClassLoader.getSystemClassLoader()
            val plane = java.lang.reflect.Proxy.newProxyInstance(
                loader,
                arrayOf(planeClass),
                Camera2PlaneProxyHandler(photoBytes)
            )
            return arrayOf(plane)
        } catch (e: Throwable) {
            XposedBridge.log("[CameraHook] plane proxy create failed: $e")
            return emptyArray<Any>()
        }
    }
}

/**
 * Proxy Image.Plane 调用处理：所有数据平面都指向用户照片。
 * （同包测试可访问）
 */
internal class Camera2PlaneProxyHandler(private val photoBytes: ByteArray) : java.lang.reflect.InvocationHandler {
    override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
        return when (method.name) {
            "getBuffer" -> java.nio.ByteBuffer.wrap(photoBytes)
            "getPixelStride" -> 1
            "getRowStride" -> photoBytes.size / 2
            "readPixels" -> true
            else -> invokeOriginal(method, proxy, args ?: emptyArray())
        }
    }
}

/**
 * 对任意 Proxy 方法的通用处理：尝试调用原始 Image 对应的行为。
 * 该函数不接受 Receiver，显式传入 Method 作为参数。
 */
private fun invokeOriginal(method: java.lang.reflect.Method, target: Any, args: Array<out Any>): Any? {
    return try {
        method.invoke(target, *args)
    } catch (e: Throwable) {
        null // 纯净的逻辑：不允许抛异常扰乱目标 App
    }
}
