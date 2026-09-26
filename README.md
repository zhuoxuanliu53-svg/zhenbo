# 枕波 / Zhenbo

完全离线的 Android 音频播放器。下载的视频可以直接听声音，支持后台和锁屏播放，无需先转换文件。

[下载 V1.0.0](https://github.com/zhuoxuanliu53-svg/zhenbo/releases/tag/v1.0.0) · [源码仓库](https://github.com/zhuoxuanliu53-svg/zhenbo) · [更新记录](CHANGELOG.md)

当前源码包含 **V1.1.0 字幕测试版**；GitHub 稳定发行仍为 V1.0.0。V1.1 新增离线画面 OCR、内置文本字幕提取、配对音频与 SRT 导出，以及可显示在锁屏之上的纯播放页。[使用说明](docs/V1.1_SUBTITLES.md) · [测试报告](docs/V1.1_TEST_REPORT.md)

## 安装与使用

最低支持 Android 10。从 Release 下载 `Zhenbo-1.0.0.apk` 安装；校验文件与安装包一同提供。更新官方旧版时请先尝试覆盖安装，保留集合与定时；自行编译或其他来源的签名可能无法覆盖。

首次打开后授权访问本地音视频。Android 14+ 可以只授权部分视频；遗漏的文件可点「＋」通过系统文件选择器补充。点封面播放，底部播放器展开后可调整进度、播放方式和停止计时。

- **本地图库**：三列封面、搜索和音视频筛选。长按编辑，可修改显示名称，也可批量加入集合和导出。
- **集合**：长按拖动排序，边缘自动滚动；支持播完停止、列表循环、单曲循环和随机播放。
- **音频处理**：从视频提取声音，按选择顺序拼接完整音轨。兼容 AAC 优先直接复制；其他受支持音轨可保存为 WAV，也可明确选择重新编码为 M4A。[导出规则](docs/V0.3_EXPORT.md)
- **播放任务**：计时任务立即播放、到时停止；定时任务在指定时间开始，支持单次和星期重复。右上角时钟管理任务，齿轮打开设置。

导出文件保存在 `Music/静听/`，沿用旧目录以便升级。源文件不变，每次导出创建新文件。卸载后公共音频仍会保留，但集合与定时会清空；重装并授权后可以再次扫描。刷新会隐藏已删除、回收或不再获授权的文件。

## 离线与隐私

应用不申请联网权限，没有账号、广告、统计或崩溃上传。封面、播放、音频处理和用户数据均在本机完成，关闭云备份与迁移备份。公共导出文件仍可能被手机上的其他获授权应用读取；锁屏通知可能显示媒体标题。[隐私说明](docs/PRIVACY.md)

## 构建

使用 JDK 17，安装 Android SDK `platforms;android-37.0`、`build-tools;36.0.0` 和 `platform-tools`，配置 `ANDROID_HOME` 或本地 `sdk.dir`：

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest
```

Windows 使用 `gradlew.bat`；也可运行 `python tools/bootstrap.py` 和 `powershell -ExecutionPolicy Bypass -File tools/build.ps1` 安装并使用项目内工具。

项目锁定 Gradle 9.3.1、AGP 9.1.1、Kotlin/Compose compiler 2.2.10、Media3 1.11.1，compileSdk 37 / targetSdk 36。首次构建需下载依赖。Debug APK 位于 `app/build/outputs/apk/debug/`；Release 构建默认未签名，发行密钥不在仓库或 CI 中。[参与开发](CONTRIBUTING.md) · [发行流程](docs/RELEASING.md)

## 验证范围与边界

本版的实际检查项目、通过记录和未验证场景见 [V1.0 测试报告](docs/V1.0_TEST_REPORT.md)。CI 执行 JVM 测试、Lint 和编译；设备测试单独执行。模拟器结果不能代替真机听感、耗电或厂商省电策略验证。

解码和导出能力取决于设备与源文件。强制停止、关机、撤回权限或系统省电限制可能阻止定时播放；长时间导出也受系统处理时限与可用空间约束。当前不提供裁剪、混音、MP3 编码、联网下载或原生鸿蒙版本。

## 许可证

项目采用 [MIT 许可证](LICENSE)。依赖及第三方内容遵循各自许可证，见 [第三方说明](THIRD_PARTY_NOTICES.md)。安全问题请参阅 [报告方式](SECURITY.md)。
