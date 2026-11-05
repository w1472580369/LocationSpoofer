package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.components.AppMapController
import com.vincenthzr.locationspoofer.ui.components.AppMapMarker
import com.vincenthzr.locationspoofer.ui.components.MarkerType
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.screen.note.VoiceNoteInput
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.showVoiceNoteInfo
import com.vincenthzr.locationspoofer.viewmodel.refreshVoiceNoteList
import com.vincenthzr.locationspoofer.viewmodel.fetchCurrentLocation
import android.util.Log
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.AnimatedVisibility

@Composable
fun HomeMapScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    mapController: AppMapController?,
    lastViewport: CameraViewport? = null,
    tabBarHeight: Dp = 90.dp
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var restoredViewport by remember { mutableStateOf<CameraViewport?>(null) }
    var notePosition by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showVoiceNoteInput by remember { mutableStateOf(false) }
    // 长按直达录制标记：长按地图开面板时直接开始录音，而非显示录制入口
    var longPressRecording by remember { mutableStateOf(false) }
    /** 已添加到地图的语音备注蓝点（以录音文件路径为键，用于与列表同步） */
    var noteMarkers by remember { mutableStateOf<Map<String, AppMapMarker>>(emptyMap()) }

    DisposableEffect(mapController) {
        if (mapController != null) {
            mapController.setOnMapClickListener { lat, lng ->
                notePosition = lat to lng
                longPressRecording = false
                showVoiceNoteInput = true
            }
            mapController.setOnMapLongClickListener { lat, lng ->
                notePosition = lat to lng
                longPressRecording = true
                showVoiceNoteInput = true
            }
            mapController.setOnMarkerClickListener { lat, lng, title ->
                // 点击蓝点时展示备注详情（录制时间 / 时长 / 文本），不触发录制面板
                val record = uiState.voiceNoteList.firstOrNull {
                    it.lat == lat && it.lng == lng
                }
                if (record != null) {
                    context.showVoiceNoteInfo(record)
                }
            }
        }
        onDispose {
            mapController?.setOnMapClickListener { _, _ -> }
            mapController?.setOnMapLongClickListener { _, _ -> }
            mapController?.setOnMarkerClickListener { _, _, _ -> }
        }
    }

    LaunchedEffect(mapController, lastViewport, uiState.latitudeInput, uiState.longitudeInput) {
        if (mapController == null) return@LaunchedEffect
        val lat = uiState.latitudeInput.toDoubleOrNull() ?: 39.9042
        val lng = uiState.longitudeInput.toDoubleOrNull() ?: 116.4074
        mapController.clear()
        // Home 页首屏优先恢复记住的视口（坐标 + 缩放）；没有记录时默认 15 级缩放
        if (lastViewport != null) {
            if (lastViewport != restoredViewport) {
                mapController.moveCamera(lastViewport.lat, lastViewport.lng, lastViewport.zoom)
                restoredViewport = lastViewport
            }
            mapController.addMarker(
                lastViewport.lat,
                lastViewport.lng,
                context.getString(R.string.home_map_title),
                MarkerType.GREEN
            )
            return@LaunchedEffect
        }
        mapController.moveCamera(lat, lng, 15f)
        restoredViewport = CameraViewport(lat, lng, 15f)
        mapController.addMarker(
            lat,
            lng,
            context.getString(R.string.home_map_title),
            MarkerType.GREEN
        )
    }

    // 语音备注蓝点：与绿色标记点隔离，仅凭 voiceNoteList 变化感知，避免重复添加
    LaunchedEffect(mapController, uiState.voiceNoteList) {
        if (mapController == null) {
            Log.e("HomeMapScreen", "mapController is null, skip voice-note marker")
            return@LaunchedEffect
        }
        Log.d("HomeMapScreen", "voiceNoteList=【${uiState.voiceNoteList.size}】总计 voiceNoteCount=${uiState.voiceNoteCount}")
        val currentNotes = uiState.voiceNoteList.associate { it.path to it }
        // 取消已不在列表中的旧蓝点（避免删除后标记残留）
        noteMarkers
            .filter { !currentNotes.containsKey(it.key) }
            .forEach { it.value.remove() }
        noteMarkers = noteMarkers.filter { currentNotes.containsKey(it.key) }
        // 新增的语音备注：在录制坐标处绘制默认蓝色标记点
        for (record in uiState.voiceNoteList) {
            if (noteMarkers.containsKey(record.path)) continue
            val title = if (record.text.isNotBlank()) {
                record.text
            } else {
                context.getString(R.string.home_map_note_marker_title)
            }
            try {
                noteMarkers = noteMarkers + (record.path to mapController.addMarker(record.lat, record.lng, title, MarkerType.DEFAULT))
            } catch (e: Exception) {
                Log.e("HomeMapScreen", "add marker for voice note failed", e)
            }
        }
    }

    // 背景必须透明（不使用不透明色块），否则会把底层的共享地图层遮挡住，导致首页白屏/无地图显示
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                // 清晰地图 Top 的虚化层：底部内容避让底部 Tab 条，避免按钮被遮挡
                .padding(bottom = (tabBarHeight + 10.dp).coerceAtLeast(0.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            // 语音备注录制面板：点击地图位置后显示在底部，录制时隐藏快捷按钮
            AnimatedVisibility(visible = showVoiceNoteInput) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    VoiceNoteInput(
                        lat = notePosition?.first ?: uiState.latitudeInput.toDoubleOrNull() ?: 39.9042,
                        lng = notePosition?.second ?: uiState.longitudeInput.toDoubleOrNull() ?: 116.4074,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        // 必须与 MainViewModel 共用同一份 VoiceNoteRepository，
                        // 否则面板存的备注 ViewModel 读不到（列表与地图蓝点不刷新）
                        voiceNoteRepository = viewModel.voiceNoteRepository,
                        onDismiss = { showVoiceNoteInput = false },
                        onNoteSaved = { viewModel.refreshVoiceNoteList() },
                        autoStartRecording = longPressRecording
                    )
                }
            }
        }

        // 返回当前位置：小圆图标按钮放在右侧中间偏下，不再占有顶部/底部空间
        AnimatedVisibility(visible = !showVoiceNoteInput) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                IconButton(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        // 回到当前真实位置：让设备实际定位，地图跟回真实的坐标与缩放
                        viewModel.fetchCurrentLocation(context) { lat, lng ->
                            mapController?.animateCamera(lat, lng, 16f)
                        }
                    },
                    modifier = Modifier
                        .offset(y = 64.dp)
                        .size(44.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = AccentBlue,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        Icons.Rounded.MyLocation,
                        null,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
