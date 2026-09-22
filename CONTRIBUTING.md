# 参与枕波开发

欢迎提交可复现的错误报告和范围明确的 Pull Request。较大的功能调整，请先在 [Issues](https://github.com/zhuoxuanliu53-svg/zhenbo/issues) 说明使用场景。

## 开发环境

- JDK 17。
- Android SDK：`platforms;android-37.0`、`build-tools;36.0.0`、`platform-tools`。
- 使用仓库中的 Gradle Wrapper 9.3.1；AGP 固定为 9.1.1，依赖由锁文件管理。

这组版本符合 [AGP 9.1.1 官方兼容表](https://developer.android.com/build/releases/agp-9-1-0-release-notes)。配置 `ANDROID_HOME`，或在未提交的 `local.properties` 中设置 `sdk.dir`，然后运行：

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest
```

Windows 使用 `gradlew.bat`。首次构建需要下载工具和依赖；应用运行时保持离线。`assembleRelease` 生成未签名 APK，贡献者无需持有发行密钥。

## 提交前

- 保留离线原则：不添加联网权限、广告、统计、远程封面或数据上传。
- 与缺陷相关的测试应验证真实行为，涉及权限、后台播放、定时或导出时说明设备与 Android 版本。
- CI 执行 JVM 测试、Lint 和 APK 编译，并编译设备测试包；设备测试需要单独连接模拟器或真机执行，不能把编译成功写成设备验证通过。
- 不提交 `local.properties`、SDK、构建缓存、APK、签名文件、令牌或个人媒体。测试素材使用合成内容或可合法再分发的内容，并保留来源说明。
- 更新依赖时说明原因并同步锁文件；不要为无关修改升级工具链。

PR 描述请包含触发问题的步骤、修改后的行为、验证结果和仍未验证的场景。界面调整可附不含个人文件名的截图。贡献代码遵循项目的 [MIT 许可证](LICENSE)，第三方内容仍遵循各自许可证。
