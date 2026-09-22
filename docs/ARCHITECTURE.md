# 静听 V0.3 实现说明

## 分层

- `core`：不依赖 Android 的媒体引用、播放快照、模式、端口、文件名规则和当地时间调度计算。可直接执行 JVM 单元测试。
- `data`：MediaStore、受限的本地 SAF 导入、Room 与 DataStore。集合条目使用稳定 ID 与显式顺序；媒体读取采用 URI，不推测真实路径。Room schema 3 保存媒体、别名、集合、定时计划和导出记录；DataStore 保存播放快照与当前停止计时。
- `playback`：MediaSessionService 独立持有 ExoPlayer；UI 通过 MediaController 控制。全局 StateFlow 提供可观察状态。
- `schedule`：`ScheduleManager` 管理指定当地时间开始的任务，`TimerManager` 管理从现在开始的停止计时；播放器与任务页读取同一计时状态。全局只登记最近一个系统闹钟。
- `export`：独立串行前台服务，根据音轨选择直接复制、PCM WAV 或用户明确选择的 AAC 编码；临时文件、MediaStore 两阶段发布及中断恢复共用同一任务流程。
- `ui`：Compose 与 ViewModel，共用夜间配色和项目自绘的 `QuietIcons` 矢量线条。图标使用统一网格、圆角描边并继承主题颜色；常见播放、返回等轮廓沿用通用操作语义。持久业务状态不放在 Activity 生命周期内。

## 顺序与数据升级

媒体库、集合和添加文件面板用有序 ID 列表保存选择顺序；选中项直接显示 1、2、3 等序号。取消一项会重新编号，再次选择排在末尾。播放、添加集合及拼接消费同一选择顺序，避免按媒体库排序重新排列。

Room 1→2 创建显示名称表；2→3 为定时计划增加 `queueIds`（默认空数组），为导出记录增加 `exportMode`（默认 `ORIGINAL`）、实际 `strategy` 和 `elapsedMs`。升级采用显式迁移，不清空旧集合、别名、定时或导出记录。旧版睡眠截止时间在 DataStore 中转为统一 `TimerTask`。

## 定时语义

两类任务共用来源、播放模式及停止时长编辑器。来源可选集合或当前队列。计时任务立即播放；当前队列保留播放位置，集合从所选内容开始。活动任务同源编辑保留位置和暂停状态，并从修改时起计算新的剩余分钟数；如果用户已手动换到另一队列，再选原集合会切回该集合。

`TimerTask` 保存来源、模式、持续时间、单调时钟截止值、设备启动次数及 active/completed/cancelled 状态。暂停期间继续计时；设备重启取消。服务使用 `elapsedRealtime()` 判断到期，暂停与任务完成在同一互斥区执行，避免旧计时误停刚建立的新播放。取消计时本身不暂停播放。完成、取消及重启后的状态可从任务页查看。它不需要精确闹钟权限，也不支持延迟开始。

定时任务选择当前队列时，将当时的有序媒体 ID 保存为 `queueIds` 快照，后续手动换歌不改动该计划；选择集合时按触发时的集合内容读取。星期使用周一为 bit 0 的掩码；0 表示单次并记录当地日期。时间跟随设备时区。下一次时间使用 java.time 计算，DST 缺失时刻按时区规则顺延，重复时刻取第一次。

任务触发先记录当天已处理，再执行播放副作用，避免重复广播。迟到超过五分钟记为错过。同一批次多个到期任务按计划时间、创建时间选一个，其余冲突跳过。单次执行后关闭；星期任务重新登记下一次。

定时冲突策略由每个任务指定：SKIP 保留当前播放和停止计时；REPLACE 切换目标来源，并通过同一 `TimerManager` 设置新的停止时长，0 表示取消停止计时。无精确闹钟权限时显示未启用；重启、设备时间／时区变化及回到应用时重算未来的闹钟，不集中补播。定时服务必须先进入媒体前台，再请求焦点。常规音频使用 USAGE_MEDIA，不绕过系统音量或勿扰策略。

## 导出事务

音频与视频可混合加入拼接，处理时读取第一条音轨，不生成或编码视频画面。导出前根据实际音轨参数给出以下路径：

| 路径 | 实际处理与边界 |
| --- | --- |
| `COPY_AAC` | AAC 音轨的采样率、声道、配置及编码初始化数据一致时，复制压缩音频包并重封装为 M4A；不解码、不重新编码。保留编码包及左右声道，拼接边界可能包含原编码器的延迟／填充，不承诺无缝裁剪。 |
| `PCM_WAV` | 原音质模式下无法直接复制、但采样率相同的输入，解码后保存为 32-bit float WAV，不再有损编码、不重采样。保留左右声道；混合单／双声道时将单声道复制到两侧。PCM WAV 尽量直接读取原样本；位深无法可靠保留、采样率不同或超出约 4 GB RIFF 限制时提示无法原样导出，不暗中改走 AAC。 |
| `ENCODE_AAC` | 用户明确选择“兼容 AAC”后，Transformer 将输入转为 16-bit PCM、48 kHz、双声道，输出 AAC 256 kbps 的 M4A。适合不同采样率拼接，但会重新编码，不能称为无损。 |

PCM WAV 保留的是源音频的 PCM／设备解码结果，不会恢复有损源文件已经丢失的信息；体积通常比 M4A 大。原音质处理仍受本地解码器、读写速度与格式支持限制，不承诺所有文件同样快。多声道、受保护及无音轨文件会明确失败。

任务状态为 `queued → running → publishing → completed`，或转为 `failed / cancelled`。服务串行执行，记录实际路径与耗时，支持取消。应用私有 noBackup 目录保存临时产物；空间预检计入临时文件与公共文件同时存在的占用。

导出完成后回读校验单条 AAC 或 PCM 音轨，再创建 `Music/静听/` 下新的 `IS_PENDING` 项、复制文件并发布。发布与成功落盘使用不可取消的提交区；公开成功后不因后续数据库或扫描异常删除音频。每次创建新媒体项，不打开旧文件覆盖。恢复时区分未完成 pending 与已发布项，清理本应用的残留临时／未发布文件，原始媒体始终不改动。

## 官方依据

- https://developer.android.com/media/media3/session/background-playback
- https://developer.android.com/media/media3/exoplayer/track-selection
- https://developer.android.com/media/media3/transformer/composition
- https://developer.android.com/training/data-storage/shared/media
- https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- https://developer.android.com/develop/background-work/services/alarms
- https://developer.android.com/about/versions/17/changes/bg-audio
- https://developer.android.com/develop/background-work/services/fgs/service-types

## V0.5：图库与拖动

应用更名为枕波，包名、签名、Room v3 和 Music/静听/ 保持不变。新桌面图标为自绘月牙与声波，提供自适应和单色资源。

媒体库、集合文件和添加面板使用三列 LazyVerticalGrid，稳定媒体／条目 ID 为 key。集合网格统一处理长按选中和拖动，边缘自动滚动在主线程执行；松手后在 ViewModel 生命周期内以数据库事务更新顺序。手动批量选择仍按点击顺序编号。排序不重启当前播放。拖动期间关闭网格自身的滚动手势，避免与排序抢占；换位后在新列表已完成组合时恢复视口位置，防止首个可见条目被移走导致整页跳动。手指位置与布局偏移在 graphicsLayer 阶段读取，避免每次位移重组全部可见封面。

MediaStore 刷新排除 pending 和（Android 11+）trashed 条目，并确认可打开。文档来源重新检查读取能力和可映射的 MediaStore 状态。列表查询过滤 available=false，刷新同步清理队列，恢复播放快照时跳过失效文件。保留私有别名／集合关系用于恢复授权，不能把 Android 部分视频授权缺失当成永久删除；不操作源文件。

界面切换淡入约180ms、淡出120ms，网格换位180ms，拖起缩放140ms。系统动画倍率仍由 Compose 遵循。系统无障碍长按可进入编辑，另保留按钮和按选择顺序排序入口。
