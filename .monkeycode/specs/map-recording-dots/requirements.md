# Requirements Document - Map Recording Dots

Feature Name: map-recording-dots
Date: 2026-11-05

## Introduction

LocationSpoofer 首页已支持在地图上长按录制语音备注（ VoiceNote ），每条备注已绑定经纬度坐标并存入本地仓库，但仓库中记录未到地图 UI 与功能实验室中呈现。本需求将已录制的语音备注在地图上标记为小蓝点，并在功能实验室中提供录音文件查看与播放入口。

## Glossary

- **首页地图（Home Map）**：底部导航栏 Home 标签下的全屏地图界面。
- **语音备注（Voice Note）**：用户在首页地图上录制的一段音频，附带经纬度坐标、录制时间、文本描述和语音时长。
- **录音文件（Recording File）**：存储在 `cacheDir/voice_notes/` 目录下的 `.3gp` 或 `.aac` 音频文件。
- **功能实验室（Function Lab）**：FeaturesTab 中标记为"Experimental"的实验性功能区域。
- **小蓝点（Blue Dot）**：地图上标记语音备注坐标位置的默认类型 Marker，颜色为 `#388BFD`。
- **VoiceNoteRepository**：管理语音备注元数据（SharedPreferences + I/O）和录音文件路径的数据层。
- **App 内嵌播放器（In-App Player）**：使用 `MediaPlayer` 在 App 内播放录音文件，支持暂停/进度条/再次播放控件。
- **蓝点始终显示（Always shown）**：小蓝点默认常驻显示，无需用户手动开启。

## Requirements

### Requirement 1 - 地图标记显示

**User Story:** AS 一个在首页查看地图的用户，I want 看到已录制语音备注的位置，so that 我能快速定位这些坐标点。

#### Acceptance Criteria

1. WHEN 首页地图完成渲染，系统 SHALL 读取 VoiceNoteRepository 中所有未过期且坐标有效的语音备注，并在每条备注的经纬度坐标处绘制一个默认颜色（#388BFD）的小蓝点 Marker。蓝点始终显示，无需用户切换。
2. WHILE 用户在首页地图上移动、缩放，已标记的蓝点 SHOULD 跟随相机位置持续可见。
3. IF 语音备注被删除（代码已支持的 `deleteNote`），其对应的地图上蓝点 SHALL 从地图中移除。
4. IF 没有已录制的语音备注，首页地图上不绘制任何蓝点。
5. WHEN 用户点击蓝点 Marker，系统 SHALL 弹出一条简短信息，显示该备注关联的录制时间、时长和文本描述。

### Requirement 2 - 功能实验室录音查看

**User Story:** AS 一个需要管理录音的用户，I want 在功能实验室中查看和管理已录制的语音备注，so that 我能统一播放和回顾录音文件。

#### Acceptance Criteria

1. WHEN 用户导航到FeaturesTab，系统 SHALL 在功能实验室区域渲染一个"录音文件"卡片入口，其标题显示已录制的录音总数量。
2. WHEN 用户点击该卡片入口，系统 SHALL 展开一个列表，列出所有未过期的录音备注，每条记录包含文本描述、录制时间、语音时长。
3. WHILE 用户在列表中滑动，列表的下拉刷新 SHOULD 重新从 VoiceNoteRepository 读取数据。
4. IF 用户点击列表中的任一条记录，系统 SHALL 在App内嵌播放器中播放对应录音文件。
5. IF 用户长按列表中的任一条记录，系统 SHALL 展示删除确认对话框，确认后删除录音文件与元数据。
6. WHILE 录音文件卡片展开，系统 SHOULD 始终显示录音总数量作为标题计数。

### Requirement 3 - 首页录音绑定坐标

**User Story:** AS 一个在首页录制语音的用户，I want 录音自动绑定地图坐标，so that 每条录音都有对应的地理位置。

#### Acceptance Criteria

1. WHEN 用户长按地图上的任意坐标并完成录制，系统 SHALL 将坐标的经纬度保存到该条备注的 VoiceNoteRecord 中。
2. WHEN 用户通过按钮点击打开备注面板录制，系统 SHALL 使用长按面板中已经选择的经纬度。
3. IF 录制过程中找不到有效的坐标，系统 SHALL 使用最后一次更新的地图中心坐标作为默认坐标。
4. WHILE 保存备注，系统 SHALL 将备注存储到 VoiceNoteRepository 并覆盖之前的录制文件路径。
5. IF 录制失败（如录音权限拒绝或存储异常），系统 SHALL 提示用户录制失败并保留现有所有的备注数据。

## References

- [VoiceNoteInput.kt](/workspace/app-ui/src/main/java/com/vincenthzr/locationspoofer/ui/audio/VoiceNoteInput.kt)
- [VoiceNoteManager.kt](/workspace/app-ui/src/main/java/com/vincenthzr/locationspoofer/ui/audio/VoiceNoteManager.kt)
- [VoiceNoteRepository.kt](/workspace/app-ui/src/main/java/com/vincenthzr/locationspoofer/ui/audio/VoiceNoteRepository.kt)
- [FeaturesTab.kt](/workspace/app-ui/src/main/java/com/vincenthzr/locationspoofer/ui/tabs/FeaturesTab.kt)
- [AppMapController.kt](/workspace/app-ui/src/main/java/com/vincenthzr/locationspoofer/ui/components/AppMapView.kt)
- [LocationTab.kt](/workspace/app-ui/src/main/java/com/vincenthzr/locationspoofer/ui/tabs/LocationTab.kt)
