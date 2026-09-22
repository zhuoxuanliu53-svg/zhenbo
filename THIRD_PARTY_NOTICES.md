# 第三方依赖与图标说明

枕波（原静听）的 Kotlin 和 Android 实现使用 AndroidX（Compose、Material 3、Media3、Room、DataStore、Lifecycle、Activity、Core）及 Kotlin / kotlinx.coroutines 等开源库；使用不代表与这些项目存在官方关联。具体依赖版本由 `app/build.gradle` 和 `app/gradle.lockfile` 固定，第三方代码沿用其原许可。

- AndroidX：Copyright The Android Open Source Project；[Apache License 2.0 与 AndroidX 源码许可](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)。
- Kotlin / kotlinx.coroutines：Copyright JetBrains s.r.o. and respective authors；[Kotlin 许可](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt)、[kotlinx.coroutines 许可](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt)，Apache License 2.0。
- V0.1–V0.2 应用操作图标使用 Google Material Icons / AndroidX Compose Material Icons：[官方仓库](https://github.com/google/material-design-icons)、[许可](https://github.com/google/material-design-icons/blob/master/LICENSE)，Apache License 2.0。这些旧版图标不属于静听独家原创。

V0.3 的 `QuietIcons` 与桌面、通知标志是为本项目直接编写的矢量路径。AndroidX Material 控件内部及 Android 系统界面仍包含第三方或系统绘制的元素，不宣称为静听原创。

Apache License 2.0 全文随源码保存在 `licenses/Apache-2.0.txt`。

V0.5 桌面／通知月牙声波图标为本项目直接绘制的原始矢量，原稿为 `docs/zhenbo-icon.svg`，沿用 V0.3 的 QuietIcons 操作图标。名称未作商标注册或全球唯一性保证。
