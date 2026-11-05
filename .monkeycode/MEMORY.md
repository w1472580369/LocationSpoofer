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
- Date: 2026-11-03
- Context: Discovered by Agent while reproducing CI-equivalent test runs for a multi-flavor Android project
- Category: Build Methods
- Instructions:
  - 1. 同时有两个 flavor dimension 的模块（app-ui / core-data），测试 task 名字是 flavor 限定的，不能直接写 `:app-ui:testDebugUnitTest`，会报 "Ambiguous matches"；用 `testGlobalDebugUnitTest` 和 `testScopedDebugUnitTest`
  - 2. `core-data` 里 `var cameraHookPackages: Set<String>` 的 getter/setter 体引用了同名的 `fun getCameraHookPackages()`/`fun setCameraHookPackages()`，Kotlin 会在 JVM 层面生成一对同签名方法，导致 "Platform declaration clash"；修法是去掉外部显式函数，把读写逻辑直接写进属性 getter/setter，让 repository 层面改调属性
  - 3. 项目 pre-existing 的 compiler failure 可能在基线 commit 上就得复现，排查前先 `git stash` 确认 scope，避免改错模块
  - 4. `xposed` 模块的 `:xposed:test` 能同时跑 global + scoped flavor，但 app-ui 的 `testDebugUnitTest` 不被识别为两者，只会在 flavor 显式切分下存在

[Project Knowledge Summary]
- Date: 2026-11-04
- Context: Discovered by Agent while rewriting the CI release workflow (release.yml) to rebuild and republish the same Release tag on every push
- Category: Operations & Deployment
- Instructions:
  - 1. `.github/workflows/release.yml` 每次 push 到 main/master 都重新构建 Release APK（4 个：scoped/global × arm64-v8a/armeabi-v7a）
  - 2. 发布端使用 `softprops/action-gh-release@v2`，`tag_name` 固定来自 `gradle.properties` 的 `APP_VERSION_NAME`（前缀 v，如 v3.0.0），`delete_existing: true` + `force_create: true` 仅删除**同 tag** 的旧 Release 并新建，不会删其他版本的 Release
  - 3. 旧版流程里曾有一个 "Cleanup Old Releases" 步骤用 curl 循环删掉了所有旧 tag 的 Release，已移除
  - 4. 版本格式校验现在是硬性退出（校验失败 exit 1），不再悄悄跳过；changelog 取自 `PREV_TAG..HEAD` 的 feat:/fix: 提交
