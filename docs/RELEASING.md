# 枕波发行流程

此流程用于官方 Android APK 发行。应用包名为 `app.jingting.localplayer`；发行版本由 `app/build.gradle` 的 `versionName` 和递增的 `versionCode` 决定。

## 仓库与 Release 的内容

Git 提交源码、资源、测试及合成样本、Gradle Wrapper 和依赖锁文件、文档、许可证、CI 配置，以及 `release/signing/` 中的公开证书和轮换证明。不要提交 SDK、缓存、构建输出、个人媒体、设备日志、`local.properties`、密码或任何私钥。

APK、源码压缩包和 SHA-256 校验文件放在对应 GitHub Release，不放进 Git 历史。V1.0 使用标签 `v1.0.0`；Release 链接为 [V1.0.0](https://github.com/zhuoxuanliu53-svg/zhenbo/releases/tag/v1.0.0)。标签应指向经过检查的源码提交。

## 构建与检查

使用 JDK 17、Gradle Wrapper 9.3.1、AGP 9.1.1，以及 SDK `platforms;android-37.0` / `build-tools;36.0.0`。构建依赖版本保持锁定。Windows 维护环境可用 `python tools/bootstrap.py` 安装项目内工具。

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest
```

在测试设备或模拟器上运行与修改有关的设备测试，单独记录 Android 版本、升级安装、后台播放、定时、导出与异常恢复结果。检查最终 APK 的权限、版本、非调试属性和签名，不把编译成功当作设备测试通过。未验证的真机场景应保留在测试报告中。

依赖变化时复核许可清单，将根目录 `licenses/` 与 `app/src/main/assets/licenses/` 保持一致。后者随独立 APK 分发，包含 Apache 2.0、Checker Framework MIT、重打包 Protobuf BSD 许可及来源说明。维护脚本使用 Python 3.11 或更新版本。

## 官方签名身份

`tools/sign_release.py` 是官方维护者的 Windows 离线签名工具。首次初始化：

```powershell
python tools/sign_release.py init
```

脚本校验旧测试版证书，生成正式签名身份和 APK Signature Scheme v3 轮换证明。它要求持有匹配的旧密钥；没有该密钥的克隆仓库不能借此签出官方升级包。Fork 应使用自己的应用 ID 和签名密钥。

私有文件保存在维护者用户目录下的 `.zhenbo-signing/`，不在项目目录内。需要安全备份的内容包括 `release.p12`、`keystore-password.txt`、`legacy-debug.keystore` 和 `signing-lineage.bin`。将它们作为完整配套材料加密、离线备份，限制访问，并确认备份可恢复；不要放入 Git、Issue、Release 或 CI Secrets。遗失官方签名材料可能导致无法继续发布可覆盖安装的更新。

`release/signing/` 中的 `legacy-cert.der`、`release-cert.der`、`certificates.json` 和 `signing-lineage.bin` 是可公开的证书、指纹与轮换证明，不含签名私钥。链证明用于让支持的 Android 版本验证从旧身份到新身份的升级关系，不等于把旧包变成新签名。保持应用 ID 和升级链一致，并在发行前实际检查覆盖安装；不要随意重新生成或替换官方身份。

完成 `assembleRelease` 后运行：

```powershell
python tools/sign_release.py sign
python tools/package.py
```

签名脚本生成并验证 `artifacts/Zhenbo-1.0.0.apk`，拒绝调试 APK。打包脚本检查关键权限并生成源码 ZIP、包信息记录，以及 `SHA256-1.0.0.txt` / `SHA256-1.0.0.json`。

签名后不得修改 APK 中的任何字节，包括资源、Manifest 或 ZIP 内容；需要调整时重新构建、签名、验证并计算校验值。计算 Release 校验值后也不要替换 APK 而保留旧校验文件。

## 发布

1. 检查 Git 文件清单与待提交差异，确认没有密钥、凭据、真实用户媒体或本机路径。完成源码和文档提交，确认 CI 结果。
2. 核对最终安装包与被验证的安装包 SHA-256 一致，补齐测试报告；在相同源码提交上建立版本标签。
3. 在 GitHub Release 附上 `Zhenbo-1.0.0.apk`、`Zhenbo-1.0.0-source.zip`、`SHA256-1.0.0.txt` 和 `SHA256-1.0.0.json`，说明更新内容、安装方式与验证限制。
4. 从 Release 重新读取资源清单并核对校验值，确认链接可用。安装签名 APK 做最后的启动与升级检查。

CI 只进行测试和构建，Release APK 保持未签名；正式签名及公开发布由维护者执行，不需要向 CI 提供发行私钥。
