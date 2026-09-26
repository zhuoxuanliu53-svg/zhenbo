# V1.0 许可核查来源

- 范围：`app/gradle.lockfile` 精确包含 `releaseRuntimeClasspath` 的 142 个坐标。`DEPENDENCIES.txt` 由这些坐标的已解析 Maven POM 生成；Guava、failureaccess、listenablefuture 的 Apache-2.0 声明来自各自父 POM。
- AndroidX、Kotlin / kotlinx、JetBrains annotations、Okio、JSR-305、Error Prone annotations、J2ObjC annotations、JSpecify 的许可名称以实际解析版本 POM 为依据；同时检查其 JAR / AAR 与内层 `classes.jar` 的 LICENSE / NOTICE 条目。
- `Checker-Framework-MIT.txt` 原样取自 `org.checkerframework:checker-qual:3.43.0` 发布 JAR 的 `META-INF/LICENSE.txt`，保留项目版权声明，不以本项目的 MIT 声明替代。
- `androidx.datastore:datastore-preferences-external-protobuf:1.1.7` 的 POM 明确标注 BSD-3-Clause。该发布 JAR 的 `androidx.datastore.preferences.protobuf.RuntimeVersion` 常量为 4.28.2；`Protobuf-BSD-3-Clause.txt` 原样取自对应上游 [protobuf v28.2/LICENSE](https://raw.githubusercontent.com/protocolbuffers/protobuf/v28.2/LICENSE)。
- Apache-2.0 全文见 [Apache 官方许可](https://www.apache.org/licenses/LICENSE-2.0.txt)；JSpecify 1.0.0 的上游 [LICENSE](https://github.com/jspecify/jspecify/blob/v1.0.0/LICENSE) 也确认采用 Apache-2.0。
- `Zhenbo-MIT.txt` 与根目录 `LICENSE` 字节一致。`app/src/main/assets/licenses/` 中全部文件均从根目录 `licenses/` 同源复制，用于单独 APK 分发。

测试和构建依赖未冒充应用运行依赖；完整构建版本仍由 Gradle 锁文件记录。

## V1.1 新增离线 OCR 依赖

- `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`：MIT，附上游 LICENSE 与完整 ThirdPartyNotices。运行时明确调用 `setTelemetry(false)`，APK 保持无 INTERNET 权限。
- `androidx.documentfile:documentfile:1.0.1`：Apache-2.0。
- PP-OCRv5 mobile 检测与识别 ONNX 模型：Apache-2.0；来源固定于 PaddlePaddle 官方 Hugging Face 仓库的提交，完整下载 URL、SHA256、大小见 APK `ocr/models.json`。识别字典从随模型发布的 inference.yml 提取，未使用动态模型下载。
