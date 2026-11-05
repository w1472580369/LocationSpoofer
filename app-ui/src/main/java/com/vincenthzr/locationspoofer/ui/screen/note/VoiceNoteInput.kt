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
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

/** 语音备注录制面板：点击地图位置后弹出的底部操作条，录制并保存该位置的语音备注 */
@Composable
fun VoiceNoteInput(
    lat: Double,
    lng: Double,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    /** 长按直达录制：面板出现时直接开始录制（开启后跳过点击 Mic 的步骤） */
    autoStartRecording: Boolean = false,
    /** 备注保存成功后的回调（通知系统刷新语音备注列表状态） */
    onNoteSaved: () -> Unit = {},
    /** 语音备注仓库：必须由 HomeMapScreen 传入与 ViewModel 同一份实例。
     *  界面与 ViewModel 各持一份仓库时，Android 会为它们创建两个 ContextImpl，
     *  各自的 SharedPreferencesImpl 内存缓存不共享，
     *  界面侧保存的备注 ViewModel 侧读不到（列表/地图蓝点不刷新）。
     *  不允许使用默认值，必须显式传入同一份实例。 */
    voiceNoteRepository: VoiceNoteRepository
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val focusManager = LocalFocusManager.current
    val repository = remember { voiceNoteRepository }
    val recorder = remember { VoiceRecorder(context) }
    val timer = remember { RecordingTimer() }
    var isRecording by remember { mutableStateOf(false) }
    var canSave by remember { mutableStateOf(false) }
    var textNote by remember { mutableStateOf("") }
    var showSaved by remember { mutableStateOf(false) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    /** 录制时长（秒）：由 Compose 可观察的 State 驱动，确保秒数在录制过程中持续跳动 */
    var elapsedSeconds by remember { mutableStateOf(0.0) }

    /** 开始录制（权限已成功申请） */
    fun startRecording() {
        try {
            recorder.startRecording()
            timer.start()
            recordingFile = recorder.getCurrentFile()
            elapsedSeconds = timer.getElapsedSeconds()
            isRecording = true
        } catch (e: Exception) {
            recordingFile = null
            isRecording = false
            elapsedSeconds = 0.0
            // 仅在确实没有权限时提示权限；已授权时的录制器异常要给出准确提示，避免误报
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val messageRes = if (hasPermission) {
                R.string.voice_note_record_failed
            } else {
                R.string.audio_permission_required
            }
            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_LONG).show()
        }
    }

    // 通过系统弹窗申请录音权限，未通过时提示用户（避免 MediaRecorder 直接抛出 IllegalStateException）
    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, context.getString(R.string.audio_permission_required), Toast.LENGTH_LONG).show()
        } else if (autoStartRecording) {
            // 系统弹窗授权后补录：直接开始录制
            startRecording()
        }
    }

    /** 点击录制按钮：已有权限则直接录制，否则引导系统弹窗申请 */
    fun startRecordingWithPermission() {
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
            PackageManager.PERMISSION_GRANTED -> startRecording()
            else -> recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(Unit) {
        if (autoStartRecording) {
            // 长按直达录制：面板出现时无需点击 Mic，直接尝试开始录制（未授权时唤起系统弹窗）
            startRecordingWithPermission()
        }
        onDispose {
            // 面板关闭时释放录音资源
            recorder.release()
            recordingFile = null
            timer.stop()
        }
    }

    // 录制中：按 Compose 的状态更新机制每 100ms 刷新一次时长，
    // 否则秒数只会停留在录制开始时的 0.0（要等到后续任意重组才刷新）
    LaunchedEffect(isRecording) {
        while (isRecording) {
            elapsedSeconds = timer.getElapsedSeconds()
            delay(100)
        }
        // 停止瞬间补最后一次，确保停止按钮那一帧显示准确的录制秒数
        elapsedSeconds = timer.getElapsedSeconds()
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
                        // 先检查录音权限，未授权时唤起系统弹窗申请
                        startRecordingWithPermission()
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
                        text = stringResource(R.string.voice_note_recording, elapsedSeconds.toLong()),
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
                            // 录制器停止后立即定格时长（避免停止后被 LaunchedEffect 误刷）
                            elapsedSeconds = timer.getElapsedSeconds()
                            timer.stop()
                            isRecording = false
                            canSave = file != null
                            if (file == null) {
                                // AudioRecorder 只返回了路径，实际文件未落地（设备未录）
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.voice_note_files_not_saved),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
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
                            // 文件不在，不要保存空路径（补录组件找不到本地音频文件）
                            if (!file.exists()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.voice_note_files_not_saved),
                                    Toast.LENGTH_LONG
                                ).show()
                                canSave = false
                                recordingFile = null
                                return@Button
                            }
                            val record = VoiceNoteRecord(
                                path = file.absolutePath,
                                lat = lat,
                                lng = lng,
                                recordTime = System.currentTimeMillis(),
                                text = textNote,
                                durationSeconds = elapsedSeconds
                            )
                            repository.saveNote(record)
                            textNote = ""
                            canSave = false
                            showSaved = true
                            // 通知上层（ ViewModel / HomeMapScreen ）刷新语音备注列表，补录蓝色地图标记
                            onNoteSaved()
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

    // 保存后的提示消失，2 秒后自动关闭面板
    LaunchedEffect(showSaved) {
        if (showSaved) {
            delay(2000)
            showSaved = false
            onDismiss()
        }
    }
}
