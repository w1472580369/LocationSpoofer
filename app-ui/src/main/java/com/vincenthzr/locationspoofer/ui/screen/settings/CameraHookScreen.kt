package com.vincenthzr.locationspoofer.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.net.Uri
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppInfoItem
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentOrange
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.loadInstalledAppsForSystemHook
import com.vincenthzr.locationspoofer.viewmodel.setCameraHookEnabled
import com.vincenthzr.locationspoofer.viewmodel.setCameraHookPackageEnabled
import com.vincenthzr.locationspoofer.viewmodel.setCameraPhotoPath
import com.vincenthzr.locationspoofer.viewmodel.setCameraPhotoBase64
import com.vincenthzr.locationspoofer.viewmodel.selectAllUserAppsForCameraHook
import com.vincenthzr.locationspoofer.viewmodel.clearAllCameraHookApps
import com.vincenthzr.locationspoofer.ui.screen.AppIconImage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

private const val CAMERA_PHOTO_MAX_BYTES = 95 * 1024

private enum class CameraHookFilterTab {
    All,
    Selected,
    UserApps,
    SystemApps;

    @Composable
    fun getLabel(selectedCount: Int): String {
        return when (this) {
            All -> stringResource(R.string.tab_all)
            Selected -> stringResource(R.string.camera_hook_target_apps_count, selectedCount)
            UserApps -> stringResource(R.string.tab_user_apps)
            SystemApps -> stringResource(R.string.tab_system_apps)
        }
    }
}

@Composable
fun CameraHookScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean = isSystemInDarkTheme(),
    onClose: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(CameraHookFilterTab.All) }
    var photoBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var photoPath by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onClose)

    // 进入时刷新官方走查的"全局已安装 App"列表
    LaunchedEffect(Unit) {
        viewModel.loadInstalledAppsForSystemHook()
    }

    // 载入已选的照片：Base64 串用于预览，文件路径用于大图通道
    LaunchedEffect(uiState.cameraPhoto, uiState.cameraPhotoPath) {
        photoBitmap = null
        photoPath = null
        val base64 = uiState.cameraPhoto.takeIf { it.isNotBlank() }
        val path = uiState.cameraPhotoPath.takeIf { it.isNotBlank() }
        base64?.let {
            photoBitmap = runCatching {
                val bytes = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()?.let { bm -> bm.asImageBitmap() }
        }
        path?.let { photoPath = it }
    }

    val selectedApps = uiState.cameraHookPackages
    val appsToShow = remember(
        uiState.installedAppsForSystemHook,
        searchQuery,
        selectedFilter,
        selectedApps
    ) {
        uiState.installedAppsForSystemHook.filter { app ->
            val matchesSearch = searchQuery.isBlank() ||
                    app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)

            if (!matchesSearch) return@filter false

            val isSelected = selectedApps.contains(app.packageName)

            when (selectedFilter) {
                CameraHookFilterTab.All -> true
                CameraHookFilterTab.Selected -> isSelected
                CameraHookFilterTab.UserApps -> !app.isSystem
                CameraHookFilterTab.SystemApps -> app.isSystem
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                val bytes = readUriBytes(context, it)
                if (bytes.isEmpty()) return@launch
                val photo = compressImage(bytes, CAMERA_PHOTO_MAX_BYTES)
                if (photo.size > CAMERA_PHOTO_MAX_BYTES) {
                    // 超过 BASE64 承载限度时，存入本地文件通道
                    val file = writePhotoToDisk(context, photo)
                    file?.let { f -> viewModel.setCameraPhotoPath(f) }
                } else {
                    viewModel.setCameraPhotoBase64(android.util.Base64.encodeToString(photo, android.util.Base64.NO_WRAP))
                }
            }
        }
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
        ) {
            SettingsPageHeader(
                title = stringResource(R.string.camera_hook_title),
                subtitle = stringResource(R.string.camera_hook_subtitle),
                isDark = isDark,
                onBack = onClose
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(AccentOrange.copy(alpha = 0.10f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = null,
                            tint = AccentOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.camera_hook_warning),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                        )
                    }
                }

                // 全局开关
                MiuixCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .noRippleClickable { viewModel.setCameraHookEnabled(!uiState.cameraHookEnabled) },
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (uiState.cameraHookEnabled) AccentBlue.copy(alpha = 0.16f)
                                    else if (isDark) Color.White.copy(alpha = 0.08f)
                                    else Color.Black.copy(alpha = 0.05f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Cameraswitch,
                                contentDescription = null,
                                tint = if (uiState.cameraHookEnabled) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.camera_hook_enabled_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.camera_hook_enabled_desc),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = uiState.cameraHookEnabled,
                            onCheckedChange = { viewModel.setCameraHookEnabled(it) }
                        )
                    }
                }

                // 照片源
                MiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.camera_hook_photo_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.camera_hook_photo_desc),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = stringResource(R.string.camera_hook_photo_hint),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (photoBitmap != null) {
                                    PhotoPreviewImage(
                                        bitmap = photoBitmap!!,
                                        contentDescription = stringResource(R.string.camera_hook_photo_preview)
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.Photo,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = photoPath?.takeIf { it.isNotBlank() }
                                        ?: (uiState.cameraPhoto.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.camera_hook_photo_no_selected)),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.camera_hook_photo_hint_large),
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f)
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { launcher.launch("image/*") },
                                modifier = Modifier.weight(1f),
                                elevation = null,
                                shape = RoundedCornerShape(11.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.camera_hook_photo_select),
                                    fontSize = 13.sp
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.setCameraPhotoBase64("")
                                    viewModel.setCameraPhotoPath("")
                                },
                                modifier = Modifier.weight(1f),
                                elevation = null,
                                shape = RoundedCornerShape(11.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.camera_hook_photo_clear),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                // 目标应用列表
                MiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(14.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.camera_hook_target_apps_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.camera_hook_target_apps_desc),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        if (selectedApps.isEmpty()) {
                            Text(
                                text = stringResource(R.string.camera_hook_no_target_apps),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        // 搜索胶囊栏
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isDark) Color(0xFF22272E) else Color.White)
                                .border(
                                    width = 0.8.dp,
                                    color = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(
                                        alpha = 0.05f
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                textStyle = TextStyle(
                                    fontSize = 13.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                ),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.search_app_hint),
                                            fontSize = 13.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (searchQuery.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                        .noRippleClickable { searchQuery = "" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                        // 分类快捷过滤标签
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(CameraHookFilterTab.values()) { tab ->
                                val isSelected = selectedFilter == tab
                                val label = tab.getLabel(selectedApps.size)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isSelected) AccentBlue
                                            else if (isDark) Color.White.copy(alpha = 0.06f)
                                            else Color.Black.copy(alpha = 0.04f)
                                        )
                                        .noRippleClickable { selectedFilter = tab }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = 0.7f
                                        )
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        // 批量操作
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.camera_hook_target_apps_count, selectedApps.size),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = { viewModel.selectAllUserAppsForCameraHook() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.camera_hook_select_all_user_apps),
                                    fontSize = 12.sp,
                                    color = AccentBlue
                                )
                            }
                            if (selectedApps.isNotEmpty()) {
                                TextButton(
                                    onClick = { viewModel.clearAllCameraHookApps() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.camera_hook_clear_selection),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (uiState.isLoadingInstalledApps && uiState.installedAppsForSystemHook.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AccentBlue)
                            }
                        } else if (appsToShow.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(
                                                if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.SearchOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.no_hooked_apps),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = stringResource(R.string.search_no_match_hint),
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (app in appsToShow) {
                                    val isSelected = selectedApps.contains(app.packageName)
                                    CameraHookAppItemCard(
                                        appInfo = app,
                                        isSelected = isSelected,
                                        isDark = isDark,
                                        onToggle = { enabled ->
                                            viewModel.setCameraHookPackageEnabled(app.packageName, enabled)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CameraHookAppItemCard(
    appInfo: AppInfoItem,
    isSelected: Boolean,
    isDark: Boolean,
    onToggle: (Boolean) -> Unit
) {
    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable { onToggle(!isSelected) },
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                AppIconImage(
                    packageName = appInfo.packageName,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = appInfo.appName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (appInfo.isSystem) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.system_tag),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = appInfo.packageName,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(checked = isSelected, onCheckedChange = onToggle)
        }
    }
}

/** 照片预览 */
@Composable
private fun PhotoPreviewImage(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = modifier.fillMaxSize()
    )
}

/** 从 content Uri 中读取图片内容 */
private suspend fun readUriBytes(context: android.content.Context, uri: Uri): ByteArray {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes()
        } ?: ByteArray(0)
    } catch (e: Exception) {
        android.util.Log.e("CameraHook", "read photo uri failed", e)
        ByteArray(0)
    }
}

/** 把图片压缩为可写入 BASE64 的大小（不突破 96KB 的框架上限），仅考虑大文件 */
private fun compressImage(bytes: ByteArray, maxBytes: Int): ByteArray {
    var current = bytes
    if (current.size <= maxBytes) return current
    val bm = android.graphics.BitmapFactory.decodeByteArray(current, 0, current.size)
        ?: return current
    var quality = 70
    val out = java.io.ByteArrayOutputStream()
    bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
    current = out.toByteArray()
    while (current.size > maxBytes && quality > 10) {
        quality -= 5
        out.reset()
        bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        current = out.toByteArray()
    }
    return current
}

/** 把大图存入本地文件通道，路径由相机 Hook 模块读取 */
private suspend fun writePhotoToDisk(context: android.content.Context, bytes: ByteArray): String? {
    return try {
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: return null
        val file = java.io.File(dir, "camera_hook_photo.jpg")
        file.writeBytes(bytes)
        file.absolutePath
    } catch (e: Exception) {
        android.util.Log.e("CameraHook", "write photo to disk failed", e)
        null
    }
}
