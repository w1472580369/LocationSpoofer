package com.vincenthzr.locationspoofer.ui.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/** 一条已保存的语音备注（定位 + 录音 + 文本备注 + 时长） */
@Serializable
data class VoiceNoteRecord(
    @SerialName("path")
    val path: String,
    @SerialName("lat")
    val lat: Double,
    @SerialName("lng")
    val lng: Double,
    @SerialName("time")
    val recordTime: Long,
    @SerialName("text")
    val text: String = "",
    @SerialName("duration")
    val durationSeconds: Double = 0.0
)

/**
 * 语音备注录制器。
 * 使用系统 MediaRecorder 录制麦克风声音（AAC/MP4），录音文件保存到应用私有目录中，
 * 不需要额外的存储空间授权。
 */
class VoiceRecorder(private val context: Context) {
    enum class State { IDLE, RECORDING }

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var currentState = State.IDLE
    private var startTimeMillis: Long = 0L

    /** 录制器支持的协议（只读） */
    fun getState(): State = currentState

    /** 当前正在录制的音频文件路径 */
    fun getCurrentFile(): File? = recordingFile

    /** 开始录制，完成时不要返回 null；失败时抛出异常 */
    suspend fun startRecording(): File? {
        if (currentState == State.RECORDING) release()
        if (!hasPermission()) {
            throw IllegalStateException("RECORD_AUDIO permission not granted")
        }
        try {
            val file = getOutputFile()
            // 先创建一个空文件，让 MediaRecorder 在底层打开这个 FileDescriptor；
            // 某些机型 prepare/start 后如果文件不存在或 0 字节，按 Sel 方案是录到了但没写盘
            val created = file.createNewFile()
            Log.d("VoiceRecorder", "createNewFile: $created path=$file")
            recorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                // MediaRecorder 的状态机要求先设置输出格式，再设置编码器；
                // 顺序颠倒会在已授权的情况下抛出 IllegalStateException，被误报成"需要录音权限"。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                }
                setOutputFile(file)
                setOnInfoListener { _, what, _ -> Log.d("VoiceRecorder", "info: what=$what") }
                setOnErrorListener { _, what, _ -> Log.e("VoiceRecorder", "error: what=$what") }
                prepare()
                start()
            }
            recordingFile = file
            startTimeMillis = System.currentTimeMillis()
            currentState = State.RECORDING
            Log.d("VoiceRecorder", "started at $file size=${file.length()}")
        } catch (e: Exception) {
            release()
            Log.e("VoiceRecorder", "startRecording failed", e)
            throw e
        }
        return recordingFile
    }

    /** 停止录制，返回录音文件（文件必须实际落在磁盘上，否则返回 null） */
    suspend fun stopRecording(): File? {
        if (currentState != State.RECORDING) return null
        // release() 会把 recordingFile 清空，先保存引用再释放，否则永远返回 null
        val file = recordingFile
        Log.d("VoiceRecorder", "stopRecording: file=${file?.absolutePath} exists=${file?.exists()}")
        try {
            recorder?.stop()
            Log.d("VoiceRecorder", "stop() ok")
        } catch (e: Exception) {
            // 录制被系统中断，文件可能不完整
            Log.e("VoiceRecorder", "stopRecording failed", e)
        } finally {
            release()
        }
        // 路径存在不等于文件落地，没有实际音频文件时不对外暴露，避免后续保存到列表后播放/标记全部失效
        if (file == null) return null
        // 部分机型/模拟器上 MediaRecorder 停止后由媒体管道异步写盘，
        // 对抗写盘延迟：轮询等待文件真正落地且大于 0，最长约 15 秒。
        var attempt = 0
        while (( !file.exists() || file.length() == 0L ) && attempt++ < 150) {
            delay(100)
        }
        val fileOk = file.exists() && file.length() > 0L
        Log.d("VoiceRecorder", "file ready: ${file.absolutePath} size=${file.length()} ok=$fileOk")
        return if (fileOk) file else null
    }

    /** 主动释放录制器资源 */
    fun release() {
        recorder?.release()
        recorder = null
        recordingFile = null
        currentState = State.IDLE
    }

    private fun getOutputFile(): File {
        val dir = File(context.cacheDir, "voice_notes").apply { if (!exists()) mkdirs() }
        // 扩展名必须和输出格式匹配，否则某些机型 prepare/start 会抛 IllegalStateException：
        // MPEG_4/AAC → .m4a，THREE_GPP/AMR_NB → .3gp
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ".m4a" else ".3gp"
        return File(dir, "record_${System.currentTimeMillis()}$ext")
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 语音备注仓库：负责持久化录音文件与每条备注的元数据。
 */
class VoiceNoteRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("voice_notes_repo", Context.MODE_PRIVATE)
    private val outputDir: File
        get() = File(context.cacheDir, "voice_notes")

    /** 已录制的语音备注列表（按录制时间倒序排列） */
    fun getAll(): List<VoiceNoteRecord> {
        val json = prefs.getString("notes", "[]") ?: "[]"
        return try {
            val list = Json.Default.decodeFromString<List<VoiceNoteRecord>>(json)
            Log.d("VoiceNoteRepository", "getAll entries=${list.size} jsonLen=${json.length}")
            list
        } catch (e: Exception) {
            Log.e("VoiceNoteRepository", "getAll failed: ${e.message}")
            emptyList()
        }
    }

    /** 添加一条语音备注（commit 同步落盘，保证随后 read getAll() 一定能读到抢救回这份备注） */
    fun saveNote(record: VoiceNoteRecord) {
        val notes = getAll().toMutableList()
        notes.add(record)
        Log.d("VoiceNoteRepository", "saveNote entries=${notes.size} path=${record.path}")
        val written = prefs.edit().putString("notes", Json.Default.encodeToString(notes)).commit()
        Log.d("VoiceNoteRepository", "saveNote committed=$written")
    }

    /** 按坐标寻找附近（<= 50m）的语音备注 */
    fun findNearby(lat: Double, lng: Double, maxMeters: Double = 50.0): List<VoiceNoteRecord> {
        return getAll().filter {
            if (lat == 0.0 && lng == 0.0) return@filter false
            val results = FloatArray(1)
            android.location.Location.distanceBetween(lat, lng, it.lat, it.lng, results)
            results[0].toDouble() <= maxMeters
        }.sortedByDescending { it.recordTime }
    }

    /** 清理超过 30 天的旧录音（释放磁盘空间），commit 保证落盘后可见，避免后续 getAll 读到过期空列表 */
    fun clearExpired() {
        val now = System.currentTimeMillis()
        // 注意：必须用 Long 字面量（30L），否则 Int 运算 30*24*60*60*1000 溢出为负数，导致刚录制的备注被误删
        val ttlMillis = 30L * 24 * 60 * 60 * 1000
        val all = getAll()
        val keeping = all.filter { now - it.recordTime <= ttlMillis }
        Log.d("VoiceNoteRepository", "clearExpired total=${all.size} kept=${keeping.size} ttl=$ttlMillis")
        val committed = prefs.edit().putString("notes", Json.Default.encodeToString(keeping)).commit()
        Log.d("VoiceNoteRepository", "clearExpired committed=$committed kept=${keeping.size}")
    }

    /** 更新指定录音的时长（秒） */
    fun updateDuration(record: VoiceNoteRecord, seconds: Double) {
        val notes = getAll().toMutableList()
        notes.forEach { entry ->
            if (entry.path == record.path) {
                notes[notes.indexOf(record)] = entry.copy(durationSeconds = seconds)
            }
        }
        val committed = prefs.edit().putString("notes", Json.Default.encodeToString(notes)).commit()
        Log.d("VoiceNoteRepository", "updateDuration committed=$committed")
    }

    /** 删除一条语音备注（释放关联的录音文件） */
    fun deleteNote(record: VoiceNoteRecord): Boolean {
        val file = File(record.path)
        val deleted = file.delete()
        val notes = getAll().toMutableList()
        notes.remove(record)
        val committed = prefs.edit().putString("notes", Json.Default.encodeToString(notes)).commit()
        Log.d("VoiceNoteRepository", "deleteNote committed=$committed remaining=${notes.size}")
        return deleted
    }
}

/** 简单的录音计时器，用于在界面展示录制时长 */
class RecordingTimer {
    private var startTimeMillis: Long = 0L
    private val _running = AtomicBoolean(false)

    fun start() {
        startTimeMillis = System.currentTimeMillis()
        _running.set(true)
    }

    fun stop() {
        _running.set(false)
    }

    fun isRunning() = _running.get()
    fun getElapsedSeconds() = ((System.currentTimeMillis() - startTimeMillis) / 1000.0).coerceAtLeast(0.0)
}
