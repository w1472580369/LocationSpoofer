# 需求实施计划

Feature Name: ui-lab-structure-and-camera2
Date: 2026-11-03

## 概述

对 App 进行四方面改造：
1. **删除 InfoTab 中的相机 Hook 按钮**（相机 Hook 入口改为实验室 Tab）
2. **删除系统适配功能 UI**（InfoTab 入口 + VendorSchemeScreen 页面及次级 HookStatusScreen/AdaptationProgressScreen 页面）
3. **新增「实验室」底部 Tab**，承载开关按钮集合（相机 Hook 开关 + 实验性功能开关）
4. **新增首页面**（位置地图首页），在定位防真控制功能之前，展示当前模拟位置与快速操作
5. **完成 Xposed 相机 Hook 的 Camera2 拦截能力**（替代占位符）

---

## 1. 信息 Tab 清理（InfoTab）

- [x] 1.1 `app-ui` 删除 InfoTab.kt 中 `Icons.Rounded.Camera` + `camera_hook_title` 设置行
- [x] 1.2 `app-ui` 删除 InfoTab.kt 中 `Icons.Rounded.Devices` + `system_adaptation_title` 设置行（含 vendorScheme 状态 chip）
- [x] 1.3 更新 `strings.xml`：删除不需要的相机 Hook / 系统适配文案，新增实验室文案
  - 新增：`tab_lab`（实验室）、`lab_title`（实验室）、`lab_desc`（实验性功能开关组）
  - 新增：`home_map_title`（当前位置/地图首页入口标题）

## 2. 系统适配 UI 删除

- [x] 2.1 删除 `app-ui/ui/screen/settings/VendorSchemeScreen.kt`（含导航行、`SettingsPageHeader`、`NavigationRowCard`、AdaptationProgressCard）
- [x] 2.2 删除 `MainScaffoldScreen.kt` 中 `MainSubScreen.VendorScheme` 枚举 + 渲染分支 + `onNavigateToVendorScheme` 参数
- [x] 2.3 InfoTab 与 FeaturesTab 中移除 `onNavigateToVendorScheme` 参数（`onNavigateToVendorScheme: () -> Unit = {}`）
- [x] 2.4 检查 `AdaptationProgressScreen.kt` / `HookStatusScreen.kt` 唯一调用点是否均为 VendorSchemeScreen；是则一并删除，或保留次级页面移到实验室
- [x] 2.5 检查 `MainViewModelSettings.kt` 中 `setVendorScheme` / `getVendorOverride`：保留 `vendorScheme` 数据模型字段（core-geo 仍使用），仅删除 UI 调用入口
- [x] 2.6 检查 `MainViewModel.kt` 中 `vendorScheme = VendorScheme.fromId(settingsRepository.getVendorOverride())` 保留字段初始化，不删除
- [x] 2.7 运行 app-ui 模块单测，确认无断裂（测试通过）

## 3. 实验室 Tab（新增）

- [x] 3.1 `app-ui` 新增 `LabScreen.kt`：包含相机 Hook 功能开关 + 实验室功能列表
  - 布局：每个功能卡片左侧开关（Switch）、右侧标题 + 描述 + 进入入口
  - 相机 Hook 卡片：开关控制 `cameraHookEnabled`，标题"相机模拟"（点击进入 `CameraHookScreen`）
- [x] 3.2 `MainScaffoldScreen.kt` BottomTab 新增 `Lab(Icons.Rounded.Science)`，标注 `tab_lab`，添加 `onNavigateToCameraHook` → 状态跟随 InfoTab
- [x] 3.3 底部 Tab 结构：Home | Location | Route | Features | **Lab** | Info（6 个底部 Tab，Home 为新增的地图首页）
- [x] 3.4 `FeaturesTab.kt` 中 `onNavigateToCameraHook` 保持不变（相机 Hook 在 FeaturesTab 中也有入口，按现有逻辑保持）
- [x] 3.5 验证 Lab 页面无循环引用、编译通过

## 4. 首页面 HomeMap（在定位控制之前）

- [x] 4.1 创建 `app-ui/ui/screen/HomeMapScreen.kt`
  - 全屏显示位置地图（复用 `AppMapView` 组件），与 MainScaffoldScreen 底层地图层隔离
  - 显示当前模拟位置坐标、名称、状态标签（已启动/未启动/模拟中）
  - 快速操作按钮：查看定位面板（进入 Location 实现完整防真控制）
- [x] 4.2 在 `MainScaffoldScreen.kt` 中 Location Tab 初始状态为 HomeMapScreen（点击"进入定位控制"切换），导航已联调（含 LocationControlPanel）
- [x] 4.3 确认首页面地图控件与 `MainScaffoldScreen` 底层 `AppMapView` 隔离（共享 mapController，不重复渲染）
  - 方案：首页面使用 `AppMapView` 覆盖在底层地图之上（`LocalContainer` 获得 mapController）
- [x] 4.4 添加缓存逻辑：记住用户上次的地图视口，返回首页时恢复；加载时默认移动到保存的坐标
  - 设计：`MainScaffoldScreen.onCameraMoveListener` 记录最后视口（新增 `AppMapController.cameraTargetZoom`），HomeMapScreen 首屏优先恢复
- [x] 4.5 验证首页面无 ci 工作流冲突（不新增 CI 触发文件）

## 5. 完成相机 Hook Camera2 拦截

- [x] 5.1 `xposed` 模块 `CameraHooker.kt`：实现 `hookCamera2ImageReader` 拦截 `android.media.ImageReader.acquireLatestImage()`
  - 获取当前配置中的用户照片 byte[]（复用 `cameraPhotoBytes()`，带进程 Cache）
  - 使用 `java.lang.reflect.Proxy` 对 `android.media.Image`（可代理的 interface 时）创建代理对象，实现 `Closeable` 等接口
  - 重写：`getPlanes()` 返回代理 `Image.Plane[]`；`getBuffer()` 返回指向用户照片数据的 `ByteBuffer`（`Buffer.wrap(bytes.reversedArray())`）
  - 保留原 `acquireNextImage()` 拦截作为一个回调层次
- [x] 5.2 拦截 `android.media.ImageReader.acquireNextImage()` 并返回同样处理后的代理 Image
- [x] 5.3 对 `android.media.Image.getFormat()` 返回原 ImageReader 配置的 Format（JPEG 写死），`getPreviewImageFormat()` 类似
  - 注意：SDK 中类路径为 `android.graphics.ImageFormat`（非 `android.media.ImageFormat`）
- [x] 5.4 阻止原位释放 Image（代理 Image 被 close 时通知原始 reader，避免多次 close 引发崩溃；代理创建失败时安全回退原始 Image）
  - 安全原则：若 `Image` 为 abstract class 不可代理（AOSP 现状），返回 null → 代理创建失败 → 退回原始 Image
- [x] 5.5 在 `CameraHookerTest.kt` 添加 Camera2 场景单测
  - 验证 `ImageProxy` 的 `getPlanes()[0].buffer` 包含用户照片数据 ✓ (testCamera2ImageProxyPlanesImageData)
  - 验证 `ImageProxy` 的 `getBuffer()` `getFormat()` `getTimestamp()` 逻辑 ✓ (testCamera2ImageProxyHandlerImageData)
  - 测试通过：7 个 CameraHookerTest（5 老 + 2 新），两个 flavor 均通过
- [x] 5.6 检查 xposed 模块单测全量通过（40 → 42 个，两个 flavor 均通过）
- [ ] 5.7 灰度测试：确认无 StackOverflowError / 循环 Hook（检查 `hookStatus`）
  - 运行 `:app:installDebug` + `xposed` 灰度验证（需要 ROOT），本机无 adb 设备，需在有线 ROOT 真机上验证

## 6. 构建、测试与验证

- [x] 6.1 全量 `assembleDebug` 构建（含 scoped/global 双 flavor）: `BUILD SUCCESSFUL in 3m 8s`, 272 tasks
- [x] 6.2 运行 `:app-ui:testScopedDebugUnitTest` + `:app-ui:testGlobalDebugUnitTest` + `:xposed:test`
  - `:xposed` 42 个单测双 flavor 均通过（含 2 个新增 Camera2 代理单测）
  - `:app-ui` 模块测试通过（包括 `AdaptationProgressTest`）
- [ ] 6.3 配置 `BuildConfig.GLOBAL_SCHEME` 条件分支，验证全局与 scoped 方案"系统适配/实验室" UI 差异
- [ ] 6.4 提交修改（含 `.monkeycode/specs/*` 文档）
- [ ] 6.5 运行 CI Build workflow 确认通绿

---

## 注意事项

- 系统级 Hook（vendor profiles + system_server 注册）只影响全局方案；**不要**删除 core-geo 的 `VendorScheme.kt` / `VendorProfile.kt`（这是系统级 Hook 的基础库）
- 相机 Hook 页面保留（`CameraHookScreen`），入口从 InfoTab 迁移到 LabTab
- 所有删除必须保证 `app` 与 `app-ui` 的 flavor 变体（scoped / global）皆可编译

## 检查点

- [ ] 确保所有测试通过，如有疑问请询问用户
