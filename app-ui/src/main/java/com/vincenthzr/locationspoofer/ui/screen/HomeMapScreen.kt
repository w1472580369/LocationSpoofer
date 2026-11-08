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

    /** 已添加到地图的语音备注小绿点（0.5 比例，以录音文件路径为键，用于与列表同步） */
    var noteMarkers by remember { mutableStateOf<Map<String, AppMapMarker>>(emptyMap()) }
    var restoredViewport by remember { mutableStateOf<CameraViewport?>(null) }
    var notePosition by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showVoiceNoteInput by remember { mutableStateOf(false) }
    // 长按直达录制标记：长按地图开面板时直接开始录音，而非显示录制入口
    var longPressRecording by remember { mutableStateOf(false) }

    /** Home 锚点图钉位置（模拟位置）：坐标面板中的经纬度即为图钉位置 */
    fun notePinPosition(): Pair<Double, Double> {
        val lat = uiState.latitudeInput.toDoubleOrNull() ?: 39.9042
        val lng = uiState.longitudeInput.toDoubleOrNull() ?: 116.4074
        return lat to lng
    }

    /** Home 页视口恢复 / 坐标面板 / 地图类型变更时，Home 页面会执行 mapController.clear()。
     * 如果标记点的添加独立于 Home 页，清屏后标记点就再也不会出现 —
     * 因此将 Home 锚点与语音备注的标记点合在同一个效果里，
     * 随时重挂，保证只要有录音文件就持续显示在绑定的经纬度坐标上。
     * 注意：
     *  - Home 锚点图钉使用路线模块起点图钉的样式（MarkerType.ORANGE），固定在模拟位置
     *  - 长按录音时记录坐标使用 Home 锚点图钉位置（模拟坐标），录音经纬度始终与图钉一致
     *  - 语音备注的小绿点只负责标记已录制的备注坐标，与锚点图钉颜色和比例都不同（已隔离）。 */
    LaunchedEffect(
        mapController,
        uiState.voiceNoteList,
        uiState.latitudeInput,
        uiState.longitudeInput,
        lastViewport,
        uiState.mapType
    ) {
        if (mapController == null) {
            Log.e("HomeMapScreen", "mapController is null, skip voice-note marker")
            return@LaunchedEffect
        }
        Log.d("HomeMapScreen", "voiceNoteList=【${uiState.voiceNoteList.size}】总计 voiceNoteCount=${uiState.voiceNoteCount}")
        // 先清理上一次 Home 页留下的锚点 + 旧标记点（可能已被主框架 clear() 静默删掉），避免重复挂点
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
        /** Home 锚点图钉：使用路线模块起点图钉样式（橙色标记 + Current Location），
         * 固定在模拟坐标上，长按录音时记录坐标以图钉位置为准。 */
        mapController.addMarker(lat, lng, context.getString(R.string.current_location), MarkerType.ORANGE)

        // 语音备注的小绿点：与 Home 锚点颜色不同，且大小为标准标记的一半
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
