# 验证记录

最近一次源码验证：2026-11-12。以下仅描述已实际验证的范围，不代表所有 Android 设备均兼容。

## 构建与单元测试

主应用、独立测试目标与 instrumentation APK 构建成功；Android Lint 为 0 errors，仍有工具链/依赖版本及样式建议。

18 项 JVM 测试通过，覆盖启动协议、搜索规范化、最近使用规则，以及虚拟屏映射的唯一性、大整数 ID、输出失效和未知格式回退。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :test-target:assembleDebug
```

## 模拟器验证

在 Android 16 / API 36 的 Pixel 10 Pro Fold 模拟器上，新包名版本的 15 项设备测试通过：

- 应用目录首次加载、磁盘缓存、损坏与清理恢复、失败保留列表、并发刷新合并。
- 系统 ICU 拼音搜索、图标内存/磁盘复用、语言与密度变更失效、磁盘容量限制。
- 测试包更新、禁用/启用和卸载，DataStore 最近记录与数量持久化。
- 搜索、清空、设置 0/20 边界、Activity 重建、最近应用使用当前所选屏幕启动。
- 普通/singleTask/跳转入口、无效屏幕和跨用户拒绝、模拟副屏启动、物理屏预览。

准备测试设备：启动 Shizuku，在主应用中授权，并安装测试 APK。以下命令的 `<serial>` 需替换为 `adb devices` 中的测试设备序列号。

```sh
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r test-target/build/outputs/apk/debug/test-target-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> push test-target/build/outputs/apk/debug/test-target-debug.apk /data/local/tmp/display-launcher-fixture.apk
```

运行通用测试，排除需要真实 vivo 车联环境的用例：

```sh
adb -s <serial> shell am instrument -w -e class 'me.seasonyuu.displaylauncher.CatalogIntegrationTest,me.seasonyuu.displaylauncher.LauncherUiTest,me.seasonyuu.displaylauncher.PrivilegedIntegrationTest#startsStandardAndReusesSingleTask,me.seasonyuu.displaylauncher.PrivilegedIntegrationTest#invalidDisplayAndCrossUserFail,me.seasonyuu.displaylauncher.PrivilegedIntegrationTest#trampolineDoesNotFalselyConfirm,me.seasonyuu.displaylauncher.PrivilegedIntegrationTest#launchesOnSimulatedSecondaryDisplay,me.seasonyuu.displaylauncher.PrivilegedIntegrationTest#physicalPreviewIsScaledAndVirtualMappingFailsClosed' me.seasonyuu.displaylauncher.test/androidx.test.runner.AndroidJUnitRunner
```

测试仅应用于测试设备：包事件用例会更新、禁用及卸载独立 fixture 包，最后重新安装；副屏用例会临时修改 `overlay_display_devices` 并恢复。包事件测试需要上面的 push 步骤。不要在正在使用的车机或重要会话中运行这些用例。

## vivo 虚拟屏截图

在 vivo V2547A（设备报告 Android 17 / API 37）上，包名迁移前的修复版本已验证：

- 两个车联虚拟屏通过 Shizuku Binder/PFD 调用链连续返回真实快照，耗时约 0.8～1.1 秒，缩略图最长边不超过 640 px。
- 无有效输出 Surface 的镜像屏正确回退到比例示意图。
- 主屏截图正常，不存在的 Display 返回不支持。
- 已目视检查应用内屏幕选择器；未更改车联配置。

对应的两个设备测试分别执行通过；合并运行在切换测试服务时曾停滞，因此在该设备上建议分别执行。包名迁移后的通用通信测试已在模拟器通过，迁移后版本尚未再次在 vivo 上复验。

```sh
adb -s <serial> shell am instrument -w -e class me.seasonyuu.displaylauncher.PrivilegedIntegrationTest#capturesVivoVirtualOutputsAndFallsBackForUnbackedMirror me.seasonyuu.displaylauncher.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am instrument -w -e class me.seasonyuu.displaylauncher.PrivilegedIntegrationTest#physicalPreviewIsScaledAndVirtualMappingFailsClosed me.seasonyuu.displaylauncher.test/androidx.test.runner.AndroidJUnitRunner
```

vivo 专用测试要求车联功能已经启用，并存在相应三个逻辑屏；测试本身不会开启车联或创建这些屏幕。

## 已知覆盖限制

- 最低支持 API 30，但尚未在 Android 11 设备上验证。
- 未覆盖所有 OEM、真实 HDMI / USB-C 外接屏、桌面模式和受保护内容组合。
- 刷新失败通过注入异常验证；未做真实存储耗尽或 DataStore 写权限故障的端到端测试。
- 未做严格帧耗时基准、完整 StrictMode 自动化或像素级滚动位置断言。

原始设备转储、截图和本机测试日志不随源码发布。README 截图来自模拟器。
