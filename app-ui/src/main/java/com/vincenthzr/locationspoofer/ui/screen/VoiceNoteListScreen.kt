package com.vincenthzr.locationspoofer.ui.screen

import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentOrange
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import com.vincenthzr.locationspoofer.ui.audio.VoiceNoteRecord
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.deleteVoiceNote
import com.vincenthzr.locationspoofer.viewmodel.refreshVoiceNoteList
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import java.text.SimpleDateFormat
import java.util.Locale

/** 录音文件列表子页面（功能实验室入口，全新页） */
@Composable
fun VoiceNoteListScreen(
    viewModel: MainViewModel,
    uiState: com.vincenthzr.locationspoofer.data.model.AppState,
    isDark: Boolean,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val voiceNotes by viewModel.voiceNoteList.collectAsState()

    // 播放器
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playingId by remember { mutableStateOf<String?>(null) }

    var noteToDelete by remember { mutableStateOf<VoiceNoteRecord?>(null) }

    // 离开页面时释放播放器资源
    DisposableEffect(Unit) {
        onDispose {
            try {
                player?.stop()
                player?.release()
            } catch (e: Exception) {
                // 页面关闭，播放器可能无需释放
            }
            player = null
            isPlaying = false
            playingId = null
        }
    }

    fun playNote(record: VoiceNoteRecord) {
        val filePath = record.path
        if (playingId == filePath && player != null) {
            // 同一录音：暂停 / 继续
            val mp = player!!
            if (isPlaying) {
                mp.pause()
                isPlaying = false
            } else {
                mp.start()
                isPlaying = true
            }
            return
        }
        // 停止旧播放器，打开新录音
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingId = null
        isPlaying = false
        val medal = try {
            MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
                setOnCompletionListener { mp ->
                    mp.reset()
                    player = null
                    isPlaying = false
                    playingId = null
                }
            }
        } catch (e: Exception) {
            try {
                Toast.makeText(
                    context,
                    if (filePath.isBlank() || java.io.File(filePath).exists()) 
                        context.getString(R.string.voice_note_play_failed) else 
                        context.getString(R.string.voice_note_file_not_found),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                // 忽略
            }
            return
        }
        player = medal
        playingId = filePath
        isPlaying = true
        // 格式确实不合法时 MediaPlayer 会在 setDataSource / prepare 抛错，这里兜底返回 null
    }

    // 刷新列表
    fun refreshList() {
        viewModel.refreshVoiceNoteList()
    }

    fun deleteNote(record: VoiceNoteRecord) {
        if (playingId == record.path) {
            try {
                player?.stop()
                player?.release()
            } catch (e: Exception) {
                // finish
            }
            player = null
            isPlaying = false
            playingId = null
        }
        viewModel.deleteVoiceNote(record) { refreshList() }
        noteToDelete = null
    }

    LaunchedEffect(Unit) {
        // 首次进入时加载一次，确保列表是最新的
        refreshList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        // 顶部导航栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(if (isDark) Color(0xFF22272E) else Color.White)
                    .border(
                        width = 1.dp,
                        color = if (isDark) Color.White.copy(alpha = 0.14f) else Color(0xFFE5E8EC),
                        shape = CircleShape
                    )
                    .noRippleClickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = if (isDark) Color.White else Color(0xFF1A1D20),
                    modifier = Modifier.size(21.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column {
                Text(
                    text = stringResource(R.string.lab_recording_files_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.lab_recording_files_count, uiState.voiceNoteCount),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )
            }

            Spacer(Modifier.weight(1f))

            // 刷新：容错型手动刷新按钮
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .noRippleClickable({ refreshList() }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.refresh_data),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 列表
        if (voiceNotes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(
                                    alpha = 0.03f
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.MicOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.voice_note_list_empty),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.voice_note_list_empty_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // key 必须是路径 + 录制时间，不能只用 path（同一文件可能被重复保存，Compose 拒绝重复 key）
                items(voiceNotes, key = { "${it.recordTime}:${it.path}" }) { record ->
                    VoiceNoteListItem(
                        record = record,
                        isPlaying = playingId == record.path && isPlaying,
                        onPlay = { playNote(record) },
                        onLongClick = { noteToDelete = record },
                        isDark = isDark
                    )
                }
            }
        }
    }

    // 删除确认对话框
    noteToDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text(stringResource(R.string.voice_note_delete_title)) },
            text = { Text(stringResource(R.string.voice_note_delete_message)) },
            confirmButton = {
                TextButton(onClick = { deleteNote(record) }) {
                    Text(stringResource(R.string.voice_note_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) {
                    Text(stringResource(R.string.voice_note_delete_cancel))
                }
            }
        )
    }
}

/** 一条录音备注卡片：点击播放 / 暂停，长按删除 */
@Composable
fun VoiceNoteListItem(
    record: VoiceNoteRecord,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
    isDark: Boolean
) {
    val context = LocalContext.current
    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick = onPlay),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧图标：录音图标
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isPlaying) AccentOrange.copy(alpha = 0.15f) 
                        else AccentBlue.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Mic,
                    null,
                    tint = if (isPlaying) AccentOrange else AccentBlue,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (record.text.isNotBlank()) record.text else stringResource(R.string.voice_note_mark),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.voice_note_recording_time) + ": " +
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(record.recordTime),
                        fontSize = 12.sp,
                        color = AppColors.textSecondary(isDark)
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = AppColors.textSecondary(isDark).copy(alpha = 0.5f)
                    )
                    Text(
                        text = stringResource(R.string.voice_note_duration) + ": " +
                              String.format(Locale.US, "%.1fs", record.durationSeconds),
                        fontSize = 12.sp,
                        color = AppColors.textSecondary(isDark)
                    )
                }
            }

            // 播放按钮
            IconButton(
                onClick = { onPlay() },
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (isPlaying) AccentOrange else AccentBlue,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
