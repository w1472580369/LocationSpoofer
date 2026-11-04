package com.vincenthzr.locationspoofer.ui.screen.note

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.audio.VoiceNoteRecord
import com.vincenthzr.locationspoofer.ui.audio.VoiceNoteRepository
import com.vincenthzr.locationspoofer.ui.audio.VoiceRecorder
import com.vincenthzr.locationspoofer.ui.audio.RecordingTimer
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentOrange
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

/** 语音备注录制面板：点击地图位置后弹出的底部操作条，录制并保存该位置的语音备注 */
@Composable
fun VoiceNoteInput(
    lat: Double,
    lng: Double,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val focusManager = LocalFocusManager.current
    val repository = remember { VoiceNoteRepository(context) }
    val recorder = remember { VoiceRecorder(context) }
    val timer = remember { RecordingTimer() }
    var isRecording by remember { mutableStateOf(false) }
    var canSave by remember { mutableStateOf(false) }
    var textNote by remember { mutableStateOf("") }
    var showSaved by remember { mutableStateOf(false) }
    var recordingFile by remember { mutableStateOf<File?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // 面板关闭时释放录音资源
            recorder.release()
            recordingFile = null
        }
    }

    // 界面层：录制照片/文本
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.surface(isDark), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.home_map_voice_note_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )

            // 用于关闭面板的按钮
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_map_note_close), fontSize = 13.sp)
            }
        }

        if (!isRecording && !canSave) {
            // 未录制：点击即可开始录制
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.home_map_voice_note_desc, lat, lng),
                        fontSize = 13.sp,
                        color = AppColors.textSecondary(isDark)
                    )
                    Text(
                        text = stringResource(R.string.home_map_voice_note_start_desc),
                        fontSize = 11.sp,
                        color = AppColors.textSecondary(isDark).copy(alpha = 0.7f)
                    )
                }
                IconButton(
                    onClick = {
                        try {
                            recorder.startRecording()
                            timer.start()
                            recordingFile = recorder.getCurrentFile()
                            isRecording = true
                        } catch (e: Exception) {
                            recordingFile = null
                            isRecording = false
                        }
                    },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = AccentBlue,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Rounded.Mic, null)
                }
            }
        } else if (isRecording) {
            // 录制中：显示计时和停止按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(AccentOrange)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.voice_note_recording, timer.getElapsedSeconds().toLong()),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            recorder.release()
                            recordingFile = null
                            isRecording = false
                            textNote = ""
                            focusManager.clearFocus()
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 100.dp).height(40.dp)
                    ) {
                        Text(stringResource(R.string.home_map_note_cancel), fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            val file = recorder.stopRecording()
                            recordingFile = file
                            isRecording = false
                            timer.stop()
                            canSave = file != null
                            focusManager.clearFocus()
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 100.dp).height(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentOrange,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(stringResource(R.string.home_map_note_stop), fontSize = 13.sp)
                    }
                }
            }
        } else if (canSave) {
            // 已录制：确认文本备注并保存
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = textNote,
                    onValueChange = { textNote = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.add_remark_hint), fontSize = 13.sp)
                    },
                    maxLines = 3,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = AppColors.surface(isDark),
                        unfocusedContainerColor = AppColors.surface(isDark)
                    )
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        canSave = false
                        textNote = ""
                    }) {
                        Text(stringResource(R.string.home_map_note_cancel), fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = {
                            val file = recordingFile
                            if (file == null) {
                                canSave = false
                                return@Button
                            }
                            val record = VoiceNoteRecord(
                                path = file.absolutePath,
                                lat = lat,
                                lng = lng,
                                recordTime = System.currentTimeMillis(),
                                text = textNote
                            )
                            repository.saveNote(record)
                            textNote = ""
                            canSave = false
                            showSaved = true
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 100.dp).height(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentOrange,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(stringResource(R.string.save), fontSize = 13.sp)
                    }
                }
            }
        }

        // 保存成功的提示（2 秒后自动消失）
        AnimatedVisibility(visible = showSaved) {
            Text(
                text = stringResource(R.string.voice_note_saved),
                color = AccentOrange,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    // 保存后的提示消失
    LaunchedEffect(showSaved) {
        if (showSaved) {
            delay(2000)
            showSaved = false
        }
    }
}
