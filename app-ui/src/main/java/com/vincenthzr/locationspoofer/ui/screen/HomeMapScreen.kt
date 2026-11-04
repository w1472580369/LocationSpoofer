package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.components.AppMapController
import com.vincenthzr.locationspoofer.ui.components.MarkerType
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.screen.note.VoiceNoteInput
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.fetchCurrentLocation
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
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var restoredViewport by remember { mutableStateOf<CameraViewport?>(null) }
    var notePosition by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showVoiceNoteInput by remember { mutableStateOf(false) }
    // 长按直达录制标记：长按地图开面板时直接开始录音，而非显示录制入口
    var longPressRecording by remember { mutableStateOf(false) }

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
        }
        onDispose {
            mapController?.setOnMapClickListener { _, _ -> }
            mapController?.setOnMapLongClickListener { _, _ -> }
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
            // 状态提示条：位置 / 模拟状态
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .shadow(
                        elevation = 2.dp,
                        shape = RoundedCornerShape(24.dp),
                        clip = false
                    )
                    .clip(RoundedCornerShape(24.dp))
                    .background(AppColors.surface(isDark))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.MyLocation,
                        null,
                        tint = if (uiState.isSpoofingActive) AccentBlue else AppColors.textSecondary(isDark),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.home_map_status_line,
                                uiState.latitudeInput,
                                uiState.longitudeInput
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (uiState.isSpoofingActive) {
                                stringResource(R.string.home_spoofing_active)
                            } else {
                                stringResource(R.string.home_spoofing_inactive)
                            },
                            fontSize = 11.sp,
                            color = AppColors.textSecondary(isDark)
                        )
                    }
                }
            }

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
                        onDismiss = { showVoiceNoteInput = false },
                        autoStartRecording = longPressRecording
                    )
                }
            }
        }

        // 返回当前位置：小圆图标按钮放在顶部偏右，不再占有底部整行
        AnimatedVisibility(visible = !showVoiceNoteInput) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp),
                contentAlignment = Alignment.TopEnd
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
                    modifier = Modifier.size(44.dp),
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
