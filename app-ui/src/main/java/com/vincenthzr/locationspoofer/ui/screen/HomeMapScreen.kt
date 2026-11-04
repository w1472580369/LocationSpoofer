package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.AnimatedVisibility

@Composable
fun HomeMapScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    mapController: AppMapController?,
    onEnterLocation: () -> Unit = {},
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

    // 底部快捷按钮在录制时隐藏，避免与语音备注面板重叠
    var showBottomButtons by remember { mutableStateOf(true) }

    DisposableEffect(mapController) {
        if (mapController != null) {
            mapController.setOnMapClickListener { lat, lng ->
                notePosition = lat to lng
                showVoiceNoteInput = true
            }
        }
        onDispose {
            mapController?.setOnMapClickListener { _, _ -> }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
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

            // 底部快捷按钮（录制时隐藏，避免与语音备注面板重叠）
            AnimatedVisibility(visible = !showVoiceNoteInput) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onEnterLocation()
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.home_enter_location),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.home_map_enter_location_desc),
                        fontSize = 11.sp,
                        color = AppColors.textSecondary(isDark)
                    )
                }
            }

            // 语音备注录制面板：点击地图位置后显示在底部，录制时隐藏快捷按钮
            AnimatedVisibility(visible = showVoiceNoteInput) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    VoiceNoteInput(
                        lat = notePosition?.first ?: uiState.latitudeInput.toDoubleOrNull() ?: 39.9042,
                        lng = notePosition?.second ?: uiState.longitudeInput.toDoubleOrNull() ?: 116.4074,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        onDismiss = { showVoiceNoteInput = false }
                    )
                }
            }
        }
    }
}
