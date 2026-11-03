# Requirements Document - Camera Hook

Feature Name: hook-camera
Date: 2026-11-03

## Introduction

本功能为 LocationSpoofer 增加相机模拟 Hook 能力：在 Xposed 层 Hook 目标应用的相机 API 调用链路，返回由用户通过 App 界面上传/选择的照片，构成拍照前后的双保险图像回调，并支持按应用开关控制与系统全局开关控制。

## Glossary

- **Hook 应用（Hook App）**：用户通过 App 界面勾远的、需要执行相机 Hook 的目标应用包名。
- **全局开关（Global Switch）**：控制相机 Hook 功能总开关，控制所有应用是否生效。
- **应用开关（App Switch）**：控制单个 Hook 应用的相机 Hook 是否生效。
- **照片源（Photo Source）**：用户通过 App 界面选取或上传的自定义照片，用于替换目标拍的图像。
- **拍前回调（Pre-capture Callback）**：在相机开始拍照前拦截并替换照片数据。
- **拍后回调（Post-capture Callback）**：在相机返回拍摄结果前拦截并替换最终照片数据。
- **双保险（Double Insurance）**：拍前回调 + 拍后回调同时启用，任一回调生效即完成替换。

## Goals

- 通过 App 界面管理开关和照片源，使目标应用在不触发反作弊检测的情况下返回用户自定义照片
- 支持指定应用 Hook 和全局 Hook 两种模式
- 确保 Hook 对目标应用不可检测
- 保证相机 Hook 与位置模拟 Hook 并行不冲突

## Requirements

### Requirement 1 - 开关控制

**User Story:** AS 一个功能用户，I want 通过开关控制 Hook 应用的生效，so that 我不必需启用相机 Hook。

#### Acceptance Criteria

1. WHEN 用户开启全局开关，相机 Hook 功能生效；WHEN 用户关闭，相机 Hook 功能不生效。
2. WHILE 全局开关开启，系统在执行 Hook 时，按对应的应用开关决定是否返回照片。
3. IF 用户关闭了某目标应用的开关，目标应用的相机数据不被修改。
4. IF 用户未选择任何目标应用，系统不 Hook 任何相机调用。
5. WHILE 目标应用拍照，用户在 App 界面选择照片，系统返回该照片。

### Requirement 2 - 照片源管理

**User Story:** AS 一个功能用户，I want 通过 App 界面管理照片，so that 我能快速选择想要的照片。

#### Acceptance Criteria

1. WHEN 用户在 App 界面上传照片，系统保存该照片并返回 PreviewData。
2. WHEN 用户选择照片后，相机 Hook 返回该照片数据。
3. IF 用户没有选择照片，相机 Hook 功能不修改默认返回照片。
4. WHILE 用户在 App 界面管理照片，系统提供删除、替换、添加等功能。

### Requirement 3 - 双保险调用

**User Story:** AS 一个用户，I want 双保险，so that 确保 Hook 在目标拍照时生效。

#### Acceptance Criteria

1. WHEN 用户开启双保险，相机 Hook 功能拦截拍前回调和拍后回调。
2. WHILE 相机调用拍照 API，系统分别在拍前和拍后拦截并返回用户照片。
3. IF 用户关闭双保险，相机 Hook 功能只拦截拍前或拍后任一回调。
4. WHEN 用户未开启双保险，系统根据开关设置决定返回照片。
5. IF 相机调用失败，系统返回原数据。

### Requirement 4 - 目标应用管理

**User Story:** AS 一个用户，I want 选择需要 Hook 的应用，so that 可以有针对性的 Hook 特定应用。

#### Acceptance Criteria

1. WHEN 用户选择目标应用，系统记录该应用包名并应用开关设置。
2. WHILE 目标应用开启相机功能，系统检查开关状态并控制 Hook。
3. IF 目标应用未选择，系统不 Hook 任何相机调用。

### Requirement 5 - 防检测

**User Story:** AS 一个用户，I want Hook 过程不可检测，so that 系统不会发现 Hook 行为。

#### Acceptance Criteria

1. WHEN 系统执行 Hook，相机 Hook 行为对目标应用不可检测。
2. IF 相机 Hook 被目标应用检测到，系统自动恢复原始调用。
3. WHILE Hook 运行，系统记录异常日志到 App 日志系统。

## References

- [支持相机 API 列表](#)
- [应用界面设计规范](#)
