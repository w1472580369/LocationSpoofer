package com.vincenthzr.locationspoofer.viewmodel

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.vincenthzr.locationspoofer.ui.audio.VoiceNoteRecord
import com.vincenthzr.locationspoofer.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

// MainViewModel 的语音备注（录音文件）状态管理：列表展示、播放与删除

/** 从数据仓库中恢复语音备注完整列表（先过期清理、倒序），并同步到视图状态供 UI 使用 */
internal fun MainViewModel.refreshVoiceNoteList() {
    viewModelScope.launch(Dispatchers.IO) {
        voiceNoteRepository.clearExpired()
        val notes = voiceNoteRepository.getAll().sortedByDescending { it.recordTime }
        Log.d("MainViewModel", "refreshVoiceNoteList notes=${notes.size}")
        _voiceNoteList.value = notes
        withContext(Dispatchers.Main) {
            updateVoiceNoteState()
            Log.d("MainViewModel", "updateVoiceNoteState voiceNoteCount=${notes.size}")
        }
    }
}

/** 保存一条录音备注，并刷新列表 */
internal fun MainViewModel.saveVoiceNote(record: VoiceNoteRecord) {
    voiceNoteRepository.saveNote(record)
    refreshVoiceNoteList()
}

/** 删除一条录音备注（音频文件 + 元数据），并刷新列表 */
internal fun MainViewModel.deleteVoiceNote(record: VoiceNoteRecord, onSuccess: () -> Unit = {}) {
    voiceNoteRepository.deleteNote(record)
    refreshVoiceNoteList()
    onSuccess()
}

/**
 * 将语音备注列表同步到统一视图状态（uiState.voiceNoteList / voiceNoteCount），
 * HomeMapScreen 的蓝点与功能实验室计数均依赖这两个字段。
 */
internal fun MainViewModel.updateVoiceNoteState() {
    Log.d("MainViewModel", "updateVoiceNoteState in")
    val notes = _voiceNoteList.value
    _uiState.update {
        it.copy(
            voiceNoteList = notes,
            voiceNoteCount = notes.size
        )
    }
}

/** 展示一条录音备注的详情（录制时间 / 时长 / 文本），用于标记点点击事件反馈 */
internal fun Context.showVoiceNoteInfo(record: VoiceNoteRecord) {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(record.recordTime)
    val duration = String.format(Locale.US, "%.1fs", record.durationSeconds)
    Toast.makeText(this, "$time | $duration | ${record.text.takeIf { it.isNotBlank() } ?: ""}", Toast.LENGTH_LONG).show()
}
