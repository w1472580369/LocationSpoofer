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
    /** Home 页激活次数（由 MainScaffold 传进来），切回 Home 页时 +1，驱动绿点重挂 */
    homePageVersion: Int = 0,
    tabBarHeight: Dp = 90.dp
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    /** 已添加到地图的语音备注小绿点（0.5 比例，以录音文件路径为键，用于与列表同步） */
    var noteMarkers by remember { mutableStateOf<Map<String, AppMapMarker>>(emptyMap()) }
    var restoredViewport by remember { mutableStateOf<CameraViewport?>(null) }
    var notePosition by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showVoiceNoteInput by remember { mutableStateOf(false) }
    // 长按直达录制标记：长按地图开面板时直接开始录音，而非显示录制入口
    var longPressRecording by remember { mutableStateOf(false) }

    /** Home 页默认位置（坐标面板中的经纬度）：长按录音时会把录制坐标固定在这个位置，
     * 避免录音点与模拟坐标分处不同坐标。 */
    fun notePinPosition(): Pair<Double, Double> {
        val lat = uiState.latitudeInput.toDoubleOrNull() ?: 39.9042
        val lng = uiState.longitudeInput.toDoubleOrNull() ?: 116.4074
        return lat to lng
    }

    /** Home 页同步：Home 页一激活就恢复视口 + 清屏 + 重新挂好语音备注的小绿点。
     * 关键：key 包含 homePageVersion（每切回 Home 页 +1，由 MainScaffold 传进来），
     * 只有切 Home 页/切坐标面板/切地图类型/切录音列表/相机移动时才重挂 —
     * 切到 Home 页时，绿点始终跟着清屏后重新挂回来，不丢不重。 */
    LaunchedEffect(
        mapController,
        homePageVersion,
        lastViewport,
        uiState.latitudeInput,
        uiState.longitudeInput,
        uiState.mapType,
        uiState.voiceNoteList
    ) {
        if (mapController == null) {
            Log.e("HomeMapScreen", "mapController is null, skip home page sync")
            return@LaunchedEffect
        }
        Log.d("HomeMapScreen", "homePageVersion=$homePageVersion: voiceNoteList=【${uiState.voiceNoteList.size}】总计 voiceNoteCount=${uiState.voiceNoteCount}")
        // 先摘掉上次 Home 页留下的标记点，避免同一条备注被重复挂点
        noteMarkers.values.forEach { it.remove() }
        noteMarkers = emptyMap()
        mapController.clear()

        val lat = uiState.latitudeInput.toDoubleOrNull() ?: 39.9042
        val lng = uiState.longitudeInput.toDoubleOrNull() ?: 116.4074
        // Home 页首屏优先恢复记住的视口（坐标 + 缩放），没有记录时默认 15 级缩放
        if (lastViewport != null) {
            if (lastViewport != restoredViewport) {
                mapController.moveCamera(lastViewport.lat, lastViewport.lng, lastViewport.zoom)
                restoredViewport = lastViewport
            }
        } else {
            mapController.moveCamera(lat, lng, 15f)
            restoredViewport = CameraViewport(lat, lng, 15f)
        }
        // 语音备注的小绿点：小尺寸，负责标记已录制的备注坐标
        for (record in uiState.voiceNoteList) {
            val title = if (record.text.isNotBlank()) {
                record.text
            } else {
                context.getString(R.string.home_map_note_marker_title)
            }
            try {
                noteMarkers = noteMarkers + (record.path to mapController.addMarker(record.lat, record.lng, title, MarkerType.GREEN, scale = 0.5f))
            } catch (e: Exception) {
                Log.e("HomeMapScreen", "add marker for voice note failed", e)
            }
        }
    }

    DisposableEffect(mapController) {
        if (mapController != null) {
            mapController.setOnMapClickListener { lat, lng ->
                notePosition = lat to lng
                longPressRecording = false
                showVoiceNoteInput = true
            }
            mapController.setOnMapLongClickListener { lat, lng ->
                /** 长按直达录制：记录坐标不使用点击点，而是 Home 锚点图钉位置（模拟位置）；
                 * 音频备注的经纬度与图钉永远一致，避免录制点与图钉分处不同坐标。 */
                notePosition = notePinPosition()
                longPressRecording = true
                showVoiceNoteInput = true
            }
            mapController.setOnMarkerClickListener { lat, lng, title ->
                // 点击锚点图钉时展示备注详情（录制时间 / 时长 / 文本），不触发录制面板
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
