# 第三方依赖与图标说明

枕波的项目代码使用根目录 `LICENSE` 中的 MIT 许可。**MIT 不改变第三方依赖的许可、版权归属或声明。** 以下按 V1.1 的 `app/gradle.lockfile` 中 `releaseRuntimeClasspath` 核查；含间接依赖和 BOM，完整坐标与版本见 [依赖清单](licenses/DEPENDENCIES.txt)。使用这些项目不代表官方关联或背书。

| 项目及当前版本 | 许可 |
|---|---|
| ONNX Runtime Android 1.22.0 | MIT；附带上游第三方声明 |
| PP-OCRv5 mobile 检测、识别模型与字典 | Apache-2.0；固定来源与 SHA256 见 `app/src/main/assets/ocr/models.json` |
| AndroidX：Activity、Compose / Material、Core、Lifecycle、Room、SQLite、DataStore、Media3 等，具体版本见清单 | Apache-2.0；下列重打包 protobuf 除外 |
| Kotlin 标准库 2.2.10、兼容运行库 1.8.0 / 1.9.22；kotlinx.coroutines 1.10.2；kotlinx.serialization 1.7.3；JetBrains annotations 23.0.0 | Apache-2.0 |
| Guava 33.3.1-android、failureaccess 1.0.2、listenablefuture 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 |
| Okio 3.4.0 | Apache-2.0 |
| JSR-305 / FindBugs annotations 3.0.2、Error Prone annotations 2.28.0、J2ObjC annotations 3.0.0、JSpecify 1.0.0 | Apache-2.0 |
| Checker Framework checker-qual 3.43.0 | MIT；包含该项目自己的版权声明 |
| AndroidX datastore-preferences-external-protobuf 1.1.7，重打包 protobuf Java 4.28.2 | BSD-3-Clause |

完整许可与归属随源码保存在 [licenses/](licenses/)，并以相同字节内容随 APK 放在 `assets/licenses/`：

- [ONNX Runtime MIT](licenses/ONNX-Runtime-MIT.txt)、[上游第三方声明](licenses/ONNX-Runtime-ThirdPartyNotices.txt)
- [PaddleOCR Apache-2.0](licenses/PaddleOCR-Apache-2.0.txt)
- [Apache-2.0 全文](licenses/Apache-2.0.txt)
- [Checker Framework MIT 全文及版权声明](licenses/Checker-Framework-MIT.txt)
- [Protocol Buffers BSD-3-Clause 全文及版权声明](licenses/Protobuf-BSD-3-Clause.txt)
- [第三方归属说明](licenses/NOTICE.txt)、[核查来源](licenses/SOURCES.md)
- [枕波项目 MIT 全文](licenses/Zhenbo-MIT.txt)，与根目录 `LICENSE` 一致

许可根据已解析依赖的 Maven POM（包括父 POM）、JAR / AAR 内 LICENSE 和上游正式版本核对。FFmpeg 仅由开发工具生成合成测试文件，不随应用提供；测试框架和编译工具也不属于本清单中的应用运行依赖。

- V0.1–V0.2 应用操作图标使用 Google Material Icons / AndroidX Compose Material Icons：[官方仓库](https://github.com/google/material-design-icons)、[许可](https://github.com/google/material-design-icons/blob/master/LICENSE)，Apache License 2.0。这些旧版图标不属于静听独家原创。

V0.3 的 `QuietIcons` 与桌面、通知标志是为本项目直接编写的矢量路径。AndroidX Material 控件内部及 Android 系统界面仍包含第三方或系统绘制的元素，不宣称为静听原创。

V0.5 桌面／通知月牙声波图标为本项目直接绘制的原始矢量，原稿为 `docs/zhenbo-icon.svg`，沿用 V0.3 的 QuietIcons 操作图标。名称未作商标注册或全球唯一性保证。
