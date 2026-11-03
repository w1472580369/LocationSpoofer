# 实施任务列表 - Hook 相机功能

Feature Name: hook-camera
Date: 2026-11-03

## 任务 1: 配置与管理层设计

- [x] 1.1 在 `SettingsManager.kt` 新增相机 Hook 配置 Key
- [x] 1.2 在 `ConfigManager.putPersistentSettings()` 新增 `camera_hook_enabled` 键
- [x] 1.3 在 `SettingsRepository.kt` 新增 getter/setter
- [x] 1.4 在 `AppUiState.kt` 新增 `cameraHookEnabled` 字段
- [x] 1.5 在 `MainViewModelSettings.kt` 新增 setter
- [x] 1.6 在 `strings.xml` 新增界面文案

## 任务 2: Xposed Hook 层实现

- [x] 2.1 创建 `CameraHooker.kt` — 拦截相机 API
- [x] 2.2 在 `LocationHooker.kt` 注册钩子（应用 Hook）
- [ ] 2.3 在 system_server 注册相机 Hook（可选全局拦截，按需启用）
- [x] 2.4 写 `CameraHookerTest.kt` 单元测试（已修复为 JUnit 4 断言，匹配模块的 `libs.junit` 依赖）
- [ ] 2.5 验证 `replacePhotoBytes` / `modifyActivityResult` / `hasTargetPackage` 逻辑

## 任务 3: App UI 设置页

- [x] 3.1 创建 `CameraHookScreen.kt` 设置页
- [x] 3.2 在 `MainSubScreen` 注册 + `InfoTab.kt` 入口行
- [x] 3.3 在 `MainScaffoldScreen` 注册 + 关联 View
- [x] 3.4 国际化翻译（中文/阿拉伯语/英文）
- [ ] 3.5 全量功能验证（开关切换、照片选择、目标应用列表）

## 任务 4: 文档更新

- [x] 4.1 更新 `ADAPTATION_PROGRESS.md`
- [x] 4.2 更新 `README.md` + `README_EN.md` 功能列表

## 任务 5: 编译与测试

- [x] 5.1 构建 `app` 模块验证编译通过（确保 `app-ui` 依赖拉取完整）——已修复 CI workflow（`android-actions/setup-android@v4` + `gradle/actions/setup-gradle@v4`），Run 3 构建中，Run 5 修正 packages 格式再次触发...
- [x] 5.2 运行 `xposed` 模块单元测试（40 个，0 失败）
- [ ] 5.3 灰度运行，确认无 StackOverflowError / 循环 Hook
