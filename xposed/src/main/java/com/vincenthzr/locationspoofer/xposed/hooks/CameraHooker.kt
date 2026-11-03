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
 * 3. Camera2 / CameraX（预留扩展点）:
 *    Camera2 的 ImageReader 产生的 android.media.Image 由系统相机驱动创建，现有
 *    Xposed API 无法安全地创建出一份额外的 Image 对象，因此当前作为扩展点记录，
 *    后续待 ImageReader 级实现。
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
            val fd = openRemoteFile(photoFile)
            if (fd != null) {
                val bytes = fdUse(fd)
                if (bytes.isNotEmpty()) return bytes
            }
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
        if (bitmap != null) {
            data.putExtra("data", bitmap)
            XposedBridge.log("[CameraHook] onActivityResult photo injected")
        } else {
            XposedBridge.log("[CameraHook] onActivityResult bitmap decode skipped, forward original intent")
        }
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
 * Camera2（预留扩展点）:
 * android.media.ImageReader 的 Image 由系统相机驱动创建，目前还无法在 Xposed 层创建一个
 * 新的 Image 对象来替换.
 * TODO: 未来可以通过 ImageReader 级别的回调拦截或 AOSP 修改来实现。
 */
private fun LocationHooker.hookCamera2ImageReader(classLoader: ClassLoader) {
    try {
        val imageReaderClass = XposedHelpers.findClassIfExists("android.media.ImageReader", classLoader)
        if (imageReaderClass == null) return
        XposedHelpers.hookAllMethods(imageReaderClass, "acquireLatestImage") { chain, _ ->
            chain.proceed(chain.args.toTypedArray())
        }
        XposedBridge.log("[CameraHook] Camera2 acquireLatestImage placeholder ok")
    } catch (e: Throwable) {
        XposedBridge.log("[CameraHook] Camera2 intercept is a placeholder: $e")
    }
}
