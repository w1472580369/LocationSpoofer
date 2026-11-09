package com.vincenthzr.locationspoofer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.House
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vincenthzr.locationspoofer.ui.BuildConfig
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.components.AppMapController
import com.vincenthzr.locationspoofer.ui.components.AppMapView
import com.vincenthzr.locationspoofer.ui.liquid.AndroidFloatingBottomBar
import com.vincenthzr.locationspoofer.ui.liquid.FloatingBottomBarItem
import com.vincenthzr.locationspoofer.ui.screen.spoofing.SpoofingIntent
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.UpdateViewModel
import com.vincenthzr.locationspoofer.viewmodel.getIgnoredVersion
import com.vincenthzr.locationspoofer.viewmodel.handleSpoofingIntent
import com.vincenthzr.locationspoofer.viewmodel.isDomesticEnvironment
import com.vincenthzr.locationspoofer.viewmodel.loadInstalledAppsForSystemHook
import com.vincenthzr.locationspoofer.viewmodel.setIgnoredVersion
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

data class CameraViewport(val lat: Double, val lng: Double, val zoom: Float)

enum class BottomTab(val icon: ImageVector) {
    Home(Icons.Rounded.House),
    Location(Icons.Rounded.MyLocation),
    Route(Icons.Rounded.Route),
    Features(Icons.Rounded.Extension),
    Info(Icons.Rounded.Settings);

    @Composable
    fun getLabel(): String {
        return when (this) {
            Home -> stringResource(R.string.tab_home)
            Location -> stringResource(R.string.tab_location)
            Route -> stringResource(R.string.tab_route)
            Features -> stringResource(R.string.tab_features)
            Info -> stringResource(R.string.tab_info)
        }
    }
}

enum class MainSubScreen {
    None,
    CoordinateConfig,
    ScannerMap,
    ManageData,
    Update,
    LanguageSettings,
    MapEngineSettings,
    SignatureAuth,
    RootDiagnostics,
    MotionRealism,
    BackgroundKeepAlive,
    EnvTokens,
    SystemHookApps,
    CameraHook,
    RecordingFiles
}

@Composable
fun MainScaffoldScreen(
    viewModel: MainViewModel,
    uiState: AppState
) {
    val context = LocalContext.current
    val pageCount = BottomTab.values().size
    var selectedTab by remember { mutableIntStateOf(BottomTab.Home.ordinal) }
    var currentSubScreen by remember { mutableStateOf(MainSubScreen.None) }
    var activeSubScreen by remember { mutableStateOf(MainSubScreen.None) }
    var mapController by remember { mutableStateOf<AppMapController?>(null) }
    val currentSelectedTab by rememberUpdatedState(selectedTab)
    val isDark = isSystemInDarkTheme()
    val updateViewModel: UpdateViewModel = koinViewModel()

    val backdrop = rememberLayerBackdrop()

    val updateUiState by updateViewModel.uiState.collectAsState()
    var hasAutoCheckedUpdates by remember { mutableStateOf(false) }
    var showStartupUpdateDialog by remember { mutableStateOf(false) }
    val targetReleases = remember(updateUiState.releases, uiState.checkBetaUpdates) {
        if (uiState.checkBetaUpdates) updateUiState.releases else updateUiState.releases.filter { !it.isPrerelease }
    }
    val latestRelease = targetReleases.firstOrNull()

    // 记住最近一次的地图视口（坐标 + 缩放），返回首页（Home）或下次打开应用时恢复
    var lastViewport by remember { mutableStateOf<CameraViewport?>(null) }

    /** Home 页被切到的次数：切回 Home 页时 +1，驱动 Home 页面重新挂坐标、视口与语音备注的小绿点
     * 避免切回 Home 页时，已清过的地图上的绿点不复活 */
    var homePageVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedTab) {
        if (selectedTab == BottomTab.Home.ordinal) {
            homePageVersion++
        }
    }

    // 恢复上次保存的地图视口（坐标 + 缩放），在 Home 页首次展示时回到上次的位置
    val lastMapPosition = remember { viewModel.settingsRepository.getLastMapPosition() }
    LaunchedEffect(lastMapPosition) {
        lastMapPosition?.let { pair ->
            lastViewport = CameraViewport(
                pair.first.toDouble(),
                pair.second.toDouble(),
                viewModel.settingsRepository.lastMapZoom
            )
        }
    }

    // 软件启动进入主界面时，立即在后台检索是否有新版本发布，同时预加载已安装 App 列表（相机 Hook 页直连使用）
    LaunchedEffect(Unit) {
        updateViewModel.fetchReleases()
        viewModel.loadInstalledAppsForSystemHook()
    }

    // 检测到未被忽略的新版本时弹出启动更新提示框
    LaunchedEffect(targetReleases, updateUiState.isLoading) {
        if (!hasAutoCheckedUpdates && !updateUiState.isLoading && targetReleases.isNotEmpty()) {
            if (latestRelease != null) {
                val latestVersion = latestRelease.versionName
                val currentVersion = BuildConfig.VERSION_NAME
                val ignoredVersion = viewModel.getIgnoredVersion()
                if (isNewerVersion(
                        latestVersion,
                        currentVersion
                    ) && latestVersion != ignoredVersion
                ) {
                    showStartupUpdateDialog = true
                }
            }
            hasAutoCheckedUpdates = true
        }
    }

    LaunchedEffect(currentSubScreen) {
        if (currentSubScreen != MainSubScreen.None) {
            activeSubScreen = currentSubScreen
        }
    }

    // 针对非首页 Tab（路线/功能/信息）的系统返回拦截：返回到首页
    BackHandler(enabled = currentSubScreen == MainSubScreen.None && selectedTab != BottomTab.Home.ordinal) {
        selectedTab = BottomTab.Home.ordinal
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == BottomTab.Features.ordinal) {
            // 提前加载已安装 App 列表，避免后面打开相机 Hook 页时卡 2-3 秒
            viewModel.loadInstalledAppsForSystemHook()
        }
    }

    LaunchedEffect(currentSubScreen) {
        if (currentSubScreen != MainSubScreen.None) {
            activeSubScreen = currentSubScreen
        }
    }

    LaunchedEffect(selectedTab, mapController, uiState.mapType) {
        if (selectedTab == BottomTab.Home.ordinal || selectedTab == BottomTab.Location.ordinal) {
            mapController?.clear()
            mapController?.setMapType(uiState.mapType)
        }
    }

    val selectPage: (Int) -> Unit = { index ->
        selectedTab = index.coerceIn(0, pageCount - 1)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                AnimatedVisibility(
                    visible = currentSubScreen == MainSubScreen.None,
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(140))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = 14.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        AndroidFloatingBottomBar(
                            selectedIndex = { selectedTab },
                            onSelected = selectPage,
                            backdrop = backdrop,
                            tabsCount = pageCount
                        ) {
                            BottomTab.values().forEachIndexed { index, tab ->
                                val isSelected = selectedTab == index
                                FloatingBottomBarItem(
                                    modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                                    onClick = { selectPage(index) }
                                ) {
                                    val label = tab.getLabel()
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = label,
                                        tint = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = if (isDark) 0.65f else 0.70f
                                        ),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = if (isDark) 0.65f else 0.70f
                                        ),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            ) {
                // 底层地图层（定位与路线 Tab 共享）
                Box(modifier = Modifier.fillMaxSize()) {
                    AppMapView(
                        modifier = Modifier.fillMaxSize(),
                        mapEngine = uiState.mapEngine,
                        isDomestic = viewModel.isDomesticEnvironment(),
                        onMapLoadFailed = {
                            // 地图未加载时没有任何地图控制器，蓝点/坐标拾取全部静默失效，
                            // 这里上报失败态并 toast 提示，避免用户拿到一个无地图的界面。
                            Log.e("MainScaffold", "map view failed to load: engine=${uiState.mapEngine}")
                            Toast.makeText(context, R.string.map_load_failed, Toast.LENGTH_LONG).show()
                        },
                        onMapReady = { controller ->
                            mapController = controller
                            controller.disableUiControls()
                            controller.setMapType(uiState.mapType)
                            val initLat = uiState.latitudeInput.toDoubleOrNull() ?: 39.9042
                            val initLng = uiState.longitudeInput.toDoubleOrNull() ?: 116.4074
                            controller.moveCamera(initLat, initLng, 15f)

                            controller.setOnCameraChangeListener { lat, lng ->
                                if (currentSelectedTab == BottomTab.Home.ordinal || currentSelectedTab == BottomTab.Location.ordinal) {
                                    viewModel.handleSpoofingIntent(
                                        SpoofingIntent.ConfirmMapPoint(
                                            lat,
                                            lng
                                        )
                                    )
                                }
                            }
                            controller.setOnCameraMoveListener { lat, lng ->
                                if (currentSelectedTab == BottomTab.Location.ordinal) {
                                    viewModel.handleSpoofingIntent(
                                        SpoofingIntent.MapPointMoved(
                                            lat,
                                            lng
                                        )
                                    )
                                }
                                if (currentSelectedTab == BottomTab.Home.ordinal) {
                                    // Home 页记录最后一次地图视口（坐标 + 缩放），下次进入首页时恢复
                                    val zoom = controller.cameraTargetZoom
                                    if (zoom != null) {
                                        lastViewport = CameraViewport(lat, lng, zoom)
                                        // 持久化视口坐标与缩放，下次打开应用/Home 页时恢复
                                        viewModel.settingsRepository.setLastMapPosition(
                                            String.format(java.util.Locale.US, "%.6f", lat),
                                            String.format(java.util.Locale.US, "%.6f", lng)
                                        )
                                        viewModel.settingsRepository.lastMapZoom = zoom
                                    }
                                }
                            }
                        }
                    )

                    // 定位选点十字准心（仅在定位 Tab 显示）
                    AnimatedVisibility(
                        visible = selectedTab == 0,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Icon(
                            Icons.Rounded.AddLocationAlt,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier
                                .size(36.dp)
                                .padding(bottom = 16.dp)
                        )
                    }
                }

                // 底部 Tab 内容切换（纯左右完整切换，无渐变）
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val isForward = targetState > initialState
                        if (isForward) {
                            slideInHorizontally(
                                animationSpec = tween(
                                    300,
                                    easing = FastOutSlowInEasing
                                )
                            ) { width -> width }
                                .togetherWith(
                                    slideOutHorizontally(
                                        animationSpec = tween(
                                            300,
                                            easing = FastOutSlowInEasing
                                        )
                                    ) { width -> -width })
                        } else {
                            slideInHorizontally(
                                animationSpec = tween(
                                    300,
                                    easing = FastOutSlowInEasing
                                )
                            ) { width -> -width }
                                .togetherWith(
                                    slideOutHorizontally(
                                        animationSpec = tween(
                                            300,
                                            easing = FastOutSlowInEasing
                                        )
                                    ) { width -> width })
                        }
                    },
                    label = "main_tabs_transition"
                ) { targetTab ->
                    when (targetTab) {
                        BottomTab.Home.ordinal -> com.vincenthzr.locationspoofer.ui.screen.HomeMapScreen(
                            viewModel = viewModel,
                            uiState = uiState,
                            mapController = mapController,
                            lastViewport = lastViewport,
                            homePageVersion = homePageVersion,
                            tabBarHeight = paddingValues.calculateBottomPadding()
                        )

                        BottomTab.Location.ordinal -> com.vincenthzr.locationspoofer.ui.screen.tabs.LocationTab(
                            viewModel = viewModel,
                            uiState = uiState,
                            mapController = mapController,
                            onCollectionRouteSelected = { selectedTab = BottomTab.Route.ordinal },
                            tabBarHeight = paddingValues.calculateBottomPadding()
                        )

                        BottomTab.Route.ordinal -> com.vincenthzr.locationspoofer.ui.screen.tabs.RouteTab(
                            viewModel = viewModel,
                            uiState = uiState,
                            mapController = mapController,
                            isActive = true,
                            bottomBarHeight = paddingValues.calculateBottomPadding()
                        )

                        BottomTab.Features.ordinal -> com.vincenthzr.locationspoofer.ui.screen.tabs.FeaturesTab(
                            viewModel = viewModel,
                            uiState = uiState,
                            tabBarHeight = paddingValues.calculateBottomPadding(),
                            onNavigateToCoordinate = {
                                currentSubScreen = MainSubScreen.CoordinateConfig
                            },
                            onNavigateToScanner = { currentSubScreen = MainSubScreen.ScannerMap },
                            onNavigateToManageData = { currentSubScreen = MainSubScreen.ManageData },
                            onNavigateToSystemHookApps = { currentSubScreen = MainSubScreen.SystemHookApps },
                            onNavigateToCameraHook = { currentSubScreen = MainSubScreen.CameraHook },
                        onNavigateToRecordingFiles = { currentSubScreen = MainSubScreen.RecordingFiles }
                        )

                        BottomTab.Info.ordinal -> com.vincenthzr.locationspoofer.ui.screen.tabs.InfoTab(
                            viewModel = viewModel,
                            uiState = uiState,
                            updateUiState = updateUiState,
                            tabBarHeight = paddingValues.calculateBottomPadding(),
                            onNavigateToUpdate = { currentSubScreen = MainSubScreen.Update },
                            onNavigateToLanguage = { currentSubScreen = MainSubScreen.LanguageSettings },
                            onNavigateToMapEngine = { currentSubScreen = MainSubScreen.MapEngineSettings },
                            onNavigateToSignatureAuth = { currentSubScreen = MainSubScreen.SignatureAuth },
                            onNavigateToRootDiagnostics = { currentSubScreen = MainSubScreen.RootDiagnostics },
                            onNavigateToMotionRealism = { currentSubScreen = MainSubScreen.MotionRealism },
                            onNavigateToBackgroundKeepAlive = { currentSubScreen = MainSubScreen.BackgroundKeepAlive },
                            onNavigateToEnvTokens = { currentSubScreen = MainSubScreen.EnvTokens }
                        )
                    }
                }
            }
        }

        // 全屏子页面覆盖层（纯左右完整滑入与退出，无内部冲突动画）
        AnimatedVisibility(
            visible = currentSubScreen != MainSubScreen.None,
            enter = slideInHorizontally(
                tween(
                    300,
                    easing = FastOutSlowInEasing
                )
            ) { width -> width },
            exit = slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { width -> width }
        ) {
            val subScreenToRender =
                if (currentSubScreen != MainSubScreen.None) currentSubScreen else activeSubScreen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (subScreenToRender) {
                    MainSubScreen.CoordinateConfig -> AppCoordinateScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        onBack = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.ScannerMap -> ScannerMapScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.ManageData -> ManageDataScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.Update -> UpdateScreen(
                        updateViewModel = updateViewModel,
                        viewModel = viewModel,
                        isDark = isDark,
                        onBack = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.LanguageSettings -> com.vincenthzr.locationspoofer.ui.screen.settings.LanguageSettingsScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.MapEngineSettings -> com.vincenthzr.locationspoofer.ui.screen.settings.MapEngineSettingsScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.SignatureAuth -> com.vincenthzr.locationspoofer.ui.screen.settings.SignatureAuthScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.RootDiagnostics -> com.vincenthzr.locationspoofer.ui.screen.settings.RootDiagnosticsScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.MotionRealism -> com.vincenthzr.locationspoofer.ui.screen.settings.MotionRealismScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.BackgroundKeepAlive -> com.vincenthzr.locationspoofer.ui.screen.settings.BackgroundKeepAliveScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.EnvTokens -> com.vincenthzr.locationspoofer.ui.screen.settings.EnvTokensScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.SystemHookApps -> SystemHookAppsScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        onBack = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.CameraHook -> com.vincenthzr.locationspoofer.ui.screen.settings.CameraHookScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.RecordingFiles -> com.vincenthzr.locationspoofer.ui.screen.VoiceNoteListScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.None -> Unit
                }
            }
        }

        // 启动时若检测到新版本，弹出更新弹窗（支持忽略此版本、前往更新）
        if (showStartupUpdateDialog && latestRelease != null) {
            StartupUpdateDialog(
                latestRelease = latestRelease,
                onDismiss = { showStartupUpdateDialog = false },
                onNavigateToUpdate = {
                    showStartupUpdateDialog = false
                    currentSubScreen = MainSubScreen.Update
                },
                onIgnore = { version ->
                    viewModel.setIgnoredVersion(version)
                    showStartupUpdateDialog = false
                }
            )
        }
    }
}
