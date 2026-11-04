package com.vincenthzr.locationspoofer.ui.screen.tabs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Science
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vincenthzr.locationspoofer.ui.BuildConfig
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.screen.AppCoordinateConfigCard
import com.vincenthzr.locationspoofer.ui.screen.CameraHookConfigCard
import com.vincenthzr.locationspoofer.ui.screen.FooterLinks
import com.vincenthzr.locationspoofer.ui.screen.ImportExportDataCard
import com.vincenthzr.locationspoofer.ui.screen.ManageDataCard
import com.vincenthzr.locationspoofer.ui.screen.ScannerMapCard
import com.vincenthzr.locationspoofer.ui.screen.SystemHookAppsConfigCard
import com.vincenthzr.locationspoofer.ui.screen.SectionHeader
import com.vincenthzr.locationspoofer.ui.components.ImportExportSelectionDialog
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentOrange
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import com.vincenthzr.locationspoofer.viewmodel.setCameraHookEnabled
import com.vincenthzr.locationspoofer.data.model.ImportExportCounts
import com.vincenthzr.locationspoofer.data.model.ImportExportSelection
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.applyImportPackage
import com.vincenthzr.locationspoofer.viewmodel.collectExportCounts
import com.vincenthzr.locationspoofer.viewmodel.exportEnvironmentData
import com.vincenthzr.locationspoofer.viewmodel.parseImportPackage

@Composable
fun FeaturesTab(
    viewModel: MainViewModel,
    uiState: AppState,
    tabBarHeight: Dp = 90.dp,
    onNavigateToCoordinate: () -> Unit,
    onNavigateToScanner: () -> Unit,
    onNavigateToManageData: () -> Unit,
    onNavigateToSystemHookApps: () -> Unit,
    onNavigateToCameraHook: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 导出：先让用户选内容，再拉起系统选择器（SAF 必须在拉起前就知道要写什么）
    var exportCounts by remember { mutableStateOf<ImportExportCounts?>(null) }
    var exportSelection by remember { mutableStateOf(ImportExportSelection()) }
    // 导入：先解析出文件里有什么，再让用户勾选要应用的部分
    var pendingImportPackage by remember {
        mutableStateOf<com.vincenthzr.locationspoofer.data.db.LocationSpooferDataPackage?>(null)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            viewModel.exportEnvironmentData(it, exportSelection) { success ->
                Toast.makeText(
                    context,
                    context.getString(if (success) R.string.export_success else R.string.export_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.parseImportPackage(it) { pkg ->
                if (pkg == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_parse_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    pendingImportPackage = pkg
                }
            }
        }
    }

    exportCounts?.let { counts ->
        ImportExportSelectionDialog(
            isExport = true,
            counts = counts,
            onConfirm = { selection ->
                exportSelection = selection
                exportCounts = null
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                exportLauncher.launch("environment_data_$stamp.json")
            },
            onDismiss = { exportCounts = null }
        )
    }

    pendingImportPackage?.let { pkg ->
        ImportExportSelectionDialog(
            isExport = false,
            counts = ImportExportCounts(
                locations = pkg.locations.size + pkg.collectionRoutes.size,
                savedLocations = pkg.savedLocations.size,
                savedRoutes = pkg.savedRoutes.size,
                appCoordinateSystems = pkg.appCoordinateSystems.size,
                settings = if (pkg.settings != null) 1 else 0,
                apiKeys = if (pkg.apiKeys != null) 1 else 0
            ),
            onConfirm = { selection ->
                pendingImportPackage = null
                viewModel.applyImportPackage(pkg, selection) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_merge_success),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { pendingImportPackage = null }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AccentBlue.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Extension, null, tint = AccentBlue)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.features_tab_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.features_tab_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = tabBarHeight + 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. 采集本地数据
                item {
                    ScannerMapCard(
                        uiState = uiState,
                        isDark = isDark,
                        onClick = onNavigateToScanner
                    )
                }

                // 2. 管理本地数据
                item {
                    ManageDataCard(
                        isDark = isDark,
                        onClick = onNavigateToManageData
                    )
                }

                // 3. 配置应用坐标系
                item {
                    AppCoordinateConfigCard(
                        isDark = isDark,
                        onClick = onNavigateToCoordinate
                    )
                }

                // 4. 系统级模拟（全局 / 指定应用），仅全局方案
                if (BuildConfig.GLOBAL_SCHEME) {
                    item {
                        SystemHookAppsConfigCard(
                            uiState = uiState,
                            isDark = isDark,
                            onClick = onNavigateToSystemHookApps
                        )
                    }
                }

                // 相机 Hook：拦截目标应用的相机 API 并返回用户自定义照片
                item {
                    CameraHookConfigCard(
                        uiState = uiState,
                        isDark = isDark,
                        onClick = onNavigateToCameraHook
                    )
                }

                // 5. 实验性功能：相机 Hook 开关 + 相机预览入口
                // 实验室 Tab 已合并到此 Tab，保留实验功能入口
                item {
                    SectionHeader(
                        icon = Icons.Rounded.Science,
                        title = stringResource(R.string.lab_header_title),
                        isDark = isDark
                    )
                }

                // 相机 Hook 开关：启用/关闭相机模拟
                item {
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(AccentBlue.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Camera,
                                    null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.lab_camera_hook_title),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (uiState.cameraHookEnabled) {
                                        stringResource(
                                            R.string.lab_camera_hook_switch_hint,
                                            stringResource(R.string.lab_camera_hook_enabled)
                                        )
                                    } else {
                                        stringResource(R.string.lab_camera_hook_disabled)
                                    },
                                    fontSize = 12.sp,
                                    color = AppColors.textSecondary(isDark)
                                )
                            }
                            Switch(
                                checked = uiState.cameraHookEnabled,
                                onCheckedChange = { enabled ->
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    viewModel.setCameraHookEnabled(enabled)
                                }
                            )
                        }
                    }
                }

                // 相机设置预览卡：进入完整相机设置
                item {
                    MiuixCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .noRippleClickable {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onNavigateToCameraHook()
                            },
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(AccentOrange.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Camera,
                                    null,
                                    tint = AccentOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.lab_camera_preview_title),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.lab_camera_preview_desc),
                                    fontSize = 12.sp,
                                    color = AppColors.textSecondary(isDark)
                                )
                            }
                            Icon(
                                Icons.Rounded.ChevronRight,
                                null,
                                tint = AppColors.textSecondary(isDark).copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // 实验性功能说明卡片
                item {
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.lab_warning_title),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.lab_warning_desc),
                                fontSize = 11.sp,
                                color = AppColors.textSecondary(isDark)
                            )
                        }
                    }
                }

                // 5. 导入与导出数据
                item {
                    ImportExportDataCard(
                        isDark = isDark,
                        onImportClick = {
                            importLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        onExportClick = {
                            // 先查各分类数量，弹出选择对话框；实际拉起 SAF 在对话框确认之后。
                            // 文件名在那里再带上时间戳，保证每次导出都是新文件名：
                            // 固定用 environment_data.json 时第二次导出必然撞名，而部分 ROM 的
                            // DocumentsProvider 处理重名的方式是另建一个 xxx_1.json 空占位文件、
                            // 却把返回的 Uri 指向原来那个旧文件，导致"旧备份被覆盖 + 多出 0B 空文件"（issue #57）。
                            viewModel.collectExportCounts { counts -> exportCounts = counts }
                        }
                    )
                }
            }
        }
    }
}
