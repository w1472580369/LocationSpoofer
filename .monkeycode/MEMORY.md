# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-11-05
- Context: Discovered by Agent while fixing Gradle toolchain failures for the LocationSpoofer multi-module Android project
- Category: Build Methods
- Instructions:
  - 1. 本机只有 Java 17 **JRE**（在 PATH 中，但没有 javac），Gradle 自动下载并安装的 JDK 21 位于 `/root/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2`，编译和调试时统一指向 JDK 21
  - 2. `android的新Dsl=false`（旧版 Variant API）下，所有模块的 `compileOptions` 和 `jvmTarget.set(JvmTarget.JVM_17)` 必须保持 **Java 21 一致**（`VERSION_21` / `JVM_21`），否则 AGP 报 "Inconsistent JVM Target Compatibility Between Java and Kotlin Tasks"
  - 3. 修改编译器版本前，先 `grep -rn "jvmTarget\|VERSION_17" --include=*.kts` 搜全部模块的build.gradle.kts，5 个模块（app, app-ui, core-data, service, xposed）各改一处
  - 4. 在 `gradle.properties` 追加 `org.gradle.java.home=/root/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2` 可避免每次重复

[Project Knowledge Summary]
- Date: 2026-11-05
- Context: Discovered by Agent while fixing failed GitHub Actions CI build after upgrading Kotlin thrust target to JVM_21
- Category: Build Methods
- Instructions:
  - 1. 所有模块的 Kotlin `jvmTarget` 和 Java `compileOptions` 升级到 JVM_21 后，CI 中必须把 `build.yml` matrix、`release.yml` Setup Java 均改为 `java-version: 21`，否则 AGP 用 Java 17 编译会报 "source value 21 is only supported in Java 21 or higher" 级报错
  - 2. `gradle.properties` 里不能写 `org.gradle.java.home` 指向本机路径（如 `/root/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2`），该路径在 CI runner 上不存在；Gradle 的自动工具链下载（`gradle/gradle-daemon-jvm.properties` 标记 Java 21）收不到这个值时会用 setup-java 的 JDK
  - 3. 本地构建在 `export ANDROID_HOME=/opt/AndroidSDK` 时正常，`local.properties` 是 gitignore 的，不记录到仓库

[Project Knowledge Summary]
- Date: 2026-11-03
- Context: Discovered by Agent while fixing blocking compile errors in app-ui: VerticalScroll and VerticalScroll of Compose (BOM 2026.06.01) import location
- Category: Build Methods
- Instructions:
  - 1. `androidx.compose.foundation.verticalScroll` 和 `rememberScrollState` 在 Compose BOM 2026.06.01 下属于 `androidx.compose.foundation` 包，不是 `androidx.compose.foundation.layout`（旧版 / 文档中常见 layout 旁通的写法不适用）
  - 2. `isSystemInDarkTheme()` 是 Composable，不能写成参数的默认值（`isDark: Boolean = isSystemInDarkTheme()`），否则报 Unresolved reference；改为必须显式传参
  - 3. 修复规则：先 `javap` / `unzip -l` 查 jar 包里实际的方法候选，再决定 import，不要凭印象写

[Project Knowledge Summary]
- Date: 2026-11-05
- Context: Discovered by Agent while fixing voice-note recording on the home screen (seconds not ticking, no audio file, blue markers missing from map)
- Category: Troubleshooting & Debugging
- Instructions:
  - 1. 每个 `ContextImpl` 都有自己独立的 `SharedPreferencesImpl` 内存缓存，两个上下文之间不共享：如果 `VoiceNoteInput` 里 `new` 了一份 `VoiceNoteRepository`，而 `MainViewModel` 里又有一份，界面侧 `saveNote` 后列表/地图蓝点读不到这条备注。修复方式：`VoiceNoteInput` 不允许 `VoiceNoteRepository(context)` 默认值，必须由 `HomeMapScreen` 显式传入 `viewModel.voiceNoteRepository`（单例 = 唯一数据源）
  - 2. `VoiceRecorder.getOutputFile()` 的扩展名必须和 MediaRecorder 的输出格式匹配（S 版本以上 .m4a，以下 .3gp），否则 `prepare/start` 抛 `IllegalStateException` 被误报成缺录音权限
  - 3. `MediaRecorder` 必须先 `setOutputFormat` 再 `setAudioEncoder`（反序抛 `IllegalStateException`）
  - 4. 录制时长必须用 Compose 可观察的 State 驱动（`LaunchedEffect(isRecording)` 里每 100ms 刷新 `elapsedSeconds`），直接调 `Timer.getElapsedSeconds()` 面板不会自更

[Project Knowledge Summary]
- Date: 2026-11-05
- Context: Discovered by Agent while fixing the "saved voice note disappears / map blue dot not shown" bug from the user log in app-ui (VoiceNoteRepository)
- Category: Troubleshooting & Debugging
- Instructions:
  - 1. **真正的根因：`clearExpired()` 里 `30 * 24 * 60 * 60 * 1000` 是 Int 运算，2_592_000_000 超出 Int.MAX(2_147_483_647) 溢出成负数**，导致 `now - recordTime <= 负数` 永远为 false，刚保存的每条备注都被当作"已过期"清空。日志铁证：`getAll entries=1` → `clearExpired total=1 kept=0` → `getAll entries=0`
  - 2. 修复：时间常量必须用 Long 字面量 `30L * 24 * 60 * 60 * 1000`；已加回归测试 `app-ui/src/test/.../progress/RecordingTtlTest.kt`（断言 ttl==2_592_000_000L 且 Int 运算结果为负）
  - 3. 次要修复：`VoiceNoteRepository` 所有写盘从 `.apply()` 改为 `.commit()`（同步落盘），`clearExpired()` 加 `total/kept/ttl` 日志
  - 4. 症状链：录音文件其实生成了（`VoiceRecorder: file ready: ... size=16149 ok=true`），但列表永远是 0，功能实验室/地图蓝点都不出现
  - 5. 百度地图 SDK 启动时如果 auth 失败，logcat 里有 `LBSAuthManager` 的 `javax.crypto.BadPaddingException`；旧 key 报 errorcode 201 "APP被用户自己禁用"（在 Baidu 控制台被禁），新 key 报 PKCS 解码错误（Baidu 控制台未注册对应签名的 key）—— 用户需去控制台重新生成 `com.vincenthzr.locationspoofer` 的 key
  - 6. 本地 `~/.android/debug.keystore` 的 SHA1 是 `34:01:BD:14:DE:C0:3D:2C:4D:B4:1D:59:44:2C:5B:6D:48:73:16:B9`，包名 `com.vincenthzr.locationspoofer`
  - 7. app-ui 有 flavor（scoped/global），单元测试任务名带变体：`testGlobalDebugUnitTest` / `testScopedDebugUnitTest`，直接写 `testDebugUnitTest` 会报 ambiguous

