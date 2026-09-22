# 静听 V0.1 测试报告

日期：2026-09-20。交付为个人安装测试版，使用 debug 签名；不是应用商店正式发行包。

## 构建与静态检查

Windows / JDK 17 / Gradle 9.3.1 / AGP 9.1.1，minSdk 29、compileSdk 37、targetSdk 36、Media3 1.11.1。

| 检查 | 结果 |
|---|---|
| assembleDebug / assembleDebugAndroidTest | 通过，生成应用与设备测试 APK |
| JVM 单元测试 | 10 项通过：单次、星期多选、跨周、DST、重复处理、五分钟容差、输入与文件名规则 |
| Android Lint | 0 errors、23 warnings；含锁定依赖版本、目标版本、API 弃用及代码风格建议 |
| APK 权限 | 打包脚本检查无 INTERNET、ACCESS_NETWORK_STATE、RECORD_AUDIO、MANAGE_EXTERNAL_STORAGE |
| APK 签名 | apksigner verify 通过，APK Signature Scheme v2 |
| 数据备份 | Manifest 禁止备份，提供系统备份排除规则 |

完整 Lint 报告在本地 `app/build/reports/lint-results-debug.html`；单元测试报告在 `app/build/reports/tests/testDebugUnitTest/`。

## 模拟器验证

使用 Windows WHPX、x86_64 模拟器和合成音视频，设备启用飞行模式。未使用用户的私人媒体。后台播放测试检查播放器状态和进度；无音频输出设备的模拟器不能替代真机听感验证。

Android 16：九项主测试和一项一分钟睡眠测试全部通过。记录：`artifacts/instrumentation-android16.txt`、`artifacts/sleep-timer-android16.txt`。此轮早于最后补充的恢复清理和队列退出竞态修复。

Android 17：初轮十项通过；最终 APK 的十一项回归全部通过，耗时约 290 秒。最终版本回归结果见 `artifacts/instrumentation-android17-final.txt`。

设备测试覆盖：

1. 视频直接播放声音，Activity 退到后台后继续推进。
2. 真正登记精确闹钟，页面在后台时到点开始播放。
3. 实际等待一分钟，睡眠计时到期暂停并清除计时。
4. 定时跳过及替换策略，替换后采用新队列和停止时长。
5. 集合排序持久化，删除集合不删除源文件。
6. 视频提取 M4A，回读确认仅有 AAC 音轨。
7. 不同采样率、单／双声道音频拼接与回读。
8. 无音轨视频被拒绝，临时文件清理。
9. 刚入队立即取消，不发布残缺音频。
10. 处理中取消，后续队列仍可完成。
11. 构造进程中断遗留状态：清理孤立 pending 项和私有临时文件，同时保留已发布的完整音频（最后新增）。

修复并回归了前台导出服务类型被兼容库掩码过滤、立即取消竞态、队列退出时新任务丢失窗口，以及发布前后中断的恢复问题。

## 音频内容验证

将三秒 48 kHz 双声道测试音（左 440 Hz、右 880 Hz）与两秒 44.1 kHz 单声道测试音按顺序拼接，导出后解码为 PCM 检查：

- 输出时长约 5.023 秒，AAC 编码尾部填充在容差内。
- 左、右声道各自目标频率对另一频率的幅度比均超过 3,900。
- 后段单声道复制到左右两侧，两侧幅度一致。
- 片段顺序正确，导出文件可回读。

机器结果：`artifacts/audio-verification.json`。原始文件未被改写。

## 尚未验证的验收项

以下内容不计作通过，安装测试版后仍需在目标手机验证：

- Android 10、13、14 的设备运行与权限差异，特别是部分视频授权及撤权。
- 真机连续锁屏两小时、划掉最近任务、锁屏按钮、蓝牙／有线耳机断开、来电及音频焦点。
- 厂商省电策略、深度 Doze、设备重启、实际时区调整及权限撤回后的端到端定时行为。
- 真实磁盘耗尽、损坏媒体、多声道拒绝、超大文件、服务系统超时及实际强杀进程注入。恢复测试是构造遗留状态，不能等同全部强杀时机覆盖。
- 系统文件选择器在不同厂商设备上的完整交互，随机队列一轮不重复及进度恢复的人工验收。
- 真机外放／耳机是否真实出声及 ASMR 听感、耗电和温升。

当前没有连接目标手机，未执行两小时长播，也未生成或验证鸿蒙原生应用。`tools/soak_test.py` 提供真机长播状态记录辅助；它不能代替听感判断。

## 复现与交付

构建步骤见 README。设备脚本仅用于专用测试设备，会安装 APK、启用飞行模式并写入合成媒体：

```powershell
python tools/test_device.py --serial emulator-5556 --report artifacts/instrumentation-android17-final.txt
python tools/check_export_audio.py
python tools/package.py
```

`artifacts/` 包含测试 APK、源码 ZIP、APK 权限清单和 SHA256 校验值。源码 ZIP 不包含 SDK、构建缓存、私有签名密钥或 local.properties。
