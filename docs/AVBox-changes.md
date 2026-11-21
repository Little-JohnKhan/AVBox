# AVBox 二次开发改动总览（p63 → p94）

> 仓库：`Little-JohnKhan/AVBox`，包名 `com.github.avbox.osc`
> 目标：无广告正片 + 离线下载 + 本地播放 + 批量下载 + 本地投屏
> 阶段：第一阶段完成（2026-11-21，最新补丁 p94）

---

## 一、目标与达成情况

| 目标 | 状态 | 说明 |
|---|---|---|
| 无广告正片 | ✅ | 净化统一到 `PurifyEngine`，源已过滤时不再二次动手 |
| 离线下载 | ✅ | 分片下载 + 断点续传 + 时间轴修复 + 封装 mp4 |
| 本地播放 | ✅ | `LocalPlayActivity`（ExoPlayer + 复用在线播放器 UI） |
| 批量下载 | ✅ | 批次调度、全部暂停/开始、卡片中途清除 |
| 本地投屏 | ✅ | `LocalCastServer`（带 Range 的本地 HTTP 服务）+ DLNA |

---

## 二、核心认知（理解整套改动的钥匙）

### 1. 净化有两套逻辑，必须统一

| 链路 | 原来的实现 |
|---|---|
| 播放侧 | `StreamProxy.purifyM3u8` → `M3u8Purifier` → 不命中再 `M3u8.purify`（legacy） |
| 下载侧 | `TsDownloader.fetchSegments` → 只有 `M3u8Purifier` |

两套逻辑 → 同一条清单，播放和下载得到的净化结果可能不同。现已统一为 `PurifyEngine.purify()`，两侧共用。

### 2. 源过滤无法从 App 层检测

- spider（jar/js 插件）在**同进程**里直接 `Toast.makeText`，App 层拿不到回调
- `Spider.kt` / `SpiderApi.kt` / `JsSpider.kt` 里都没有 toast 接口
- **替代判据**：空断点。连续两个 `#EXT-X-DISCONTINUITY` 之间没有分片 = 源删过内容留下的痕迹
  - 光影 `wsym3u8`：50 个空断点（源过滤过）
  - `dytt-hot` / `dytt-see`：0 个（源没过滤）

### 3. 「播什么下什么」的本质与边界

`EpisodeResolver` 的直链分支改成**先问 spider**，拿不到才回落直链，保证清单来源一致。

但它保证的是**清单一致，不是时间轴一致**：

| | 谁负责时间轴 | 分片内部 PTS |
|---|---|---|
| 在线播放（ExoPlayer） | 按 `#EXTINF` 逐片累加，自己造时间轴 | **丢弃**，只看每片声明多长 |
| 我们下载 | 分片字节按顺序拼接 | **原样保留** |

所以同一份清单，播放器永远正常，我们拼出来可能是坏的。**时间轴必须由下载链路自己重建。**

---

## 三、时间轴修复（整套改动里最核心、也最曲折的部分）

### 3.1 问题形态

源的分片是**多段独立转码**拼成的，段与段之间 PTS 各自起算。拼成单个 ts 后：

- 视频 PTS 出现回退或空洞
- 音频 PTS 与视频完全不在同一坐标系
- 播放器按 PTS 播 → 画面跳跃 / 音频错位 / 片尾只剩声音

实测样本（强风吹拂 2018 第 01 集，风云 high25-playback 源）：

```
视频：33306 帧 × 41.7ms = 1388.9 秒
音频：8466 条 PES，其中 8359 条覆盖 0 → 2735 秒（第二份时间轴从 1368 秒重新起算）
清单：347 个分片，EXTINF 总和 1384 秒
```

### 3.2 四代修法的演进

| 代 | 判断 | 修法 | 结果 |
|---|---|---|---|
| 1 | PTS 跳变 | 重写 PTS/DTS，匀速化所有帧 | ❌ 把片头 24fps 的龙标拉慢成 40ms/帧 |
| 2 | 音频包被误删 | null 包只覆盖音频 PID | ❌ 自检失败：视频 PES 数不符 |
| 3 | 段间 PTS 重置 | 按段平移（段内保原节奏、段间接上） | ⚠️ 只修了视频，音频没跟上；45 分钟坏文件 |
| 4 | 分片独立转码 | **按 EXTINF 逐片重建** | ✅ 音画锁在同一条时间轴 |

### 3.3 最终方案：`TsFixer.rebuildByExtinf()`

```
每片的起始时间 = 累计 EXTINF
片内视频：newDts = 片起点 + (原dts - 片内首帧dts)
         强制 newDts ≥ 上一帧 dts + 一帧
片内音频：newPts = 片起点 + (原pts - 片内首帧pts)
         CTS 偏移(pts-dts)原样保留，B 帧重排关系不能动
```

**两个必须注意的细节**：

1. **强制夹紧 DTS**：EXTINF 是四舍五入的，23.976fps 下 2.0s 的片实际 48 帧（2.002s），347 个片每个边界都会和上一片重叠 2ms。不夹紧的话「DTS 必须递增」的自检会直接判失败。
2. **判据不能用探针的 PTS 跨度**：分片内相对 PTS 会让探针报出完全失真的值（实测报 17 秒，而清单是 1384 秒）。只要清单可用就重建，重建自检不过会自动放弃、保留原文件。

### 3.4 自检门槛的取舍

| 检查项 | 规则 | 为什么 |
|---|---|---|
| 视频 DTS | 只要求递增，**不卡上限** | 分片边界的大间隔是「源删过画面」的真实反映，硬判失败等于把能用的结果扔掉 |
| 音频 PTS | 严格单调 | 非单调会让播放器重排后错乱 |
| 跨度 | 与 EXTINF 总和差 < 10% + 3 秒 | 帧率取整本来就有几秒差（实测 1388.9 vs 1384） |

---

## 四、封装（mp4 转换）

- 用 media3 `Transformer`，不用 `MediaMuxer`（系统 muxer 写不了 ctts，B 帧会抽帧/声画错位）
- **转换跑在独立 HandlerThread**：media3 的调度绑在创建它的 Looper 上，主线程被 ColorOS 限速就停住
- 输出先写 `.mp4.partial`，成功再改名，避免留下「看起来完整」的半成品
- 同名旧成品必须替换而不是跳过（否则用户重下后看到的还是旧文件）

### ColorOS 冻结机制（重要）

| 行为 | 是否被冻 |
|---|---|
| 下载 | ❌ 不受影响（IO 密集、有网络活动、CPU 低） |
| 封装 | ✅ 被冻（CPU 密集、无网络、CPU 30-60%） |

进程一旦进入 cached 状态就被冻结，**连独立 HandlerThread 都会停**，前台服务也挡不住。唯一解：加提示「封装期间需保持前台或忽略电池优化」。

---

## 五、下载链路

### 5.1 关键设计

| 项 | 实现 |
|---|---|
| 文件名 | `safeName = vodName + "_" + episodeName`（**不含线路 flag**）|
| 任务 ID | `DownloadTasks.idOf(vodId, flag, index)`（含 flag）|
| 分片对齐 | 每片截断到 188 的整数倍（源有分片末尾带垃圾字节，实测 29974 % 188 = 82）|
| 断点续传 | 记录 `durations` / `segmentPackets` 供重建使用 |

**文件名不含 flag 的副作用**：跨线路下载同一集会互相覆盖。

### 5.2 批量调度

- `DownloadBatch`：`running` / `runningBatchId` 标志、`abort()`、`resumeAll`
- 全部开始曾卡住的根因：`resumeAll` 用 `scope.launch` **异步**恢复任务，`run()` 已经先读到 STOPPED → 整批被暂停
- 修法：先 suspend 恢复任务再改批次状态；STOPPED 分支加 `delay(1000)` 后查真实状态
- 单集暂停不记失败

---

## 六、播放与 UI

| 改动 | 内容 |
|---|---|
| 详情页选集 | **取消置灰**（`downloadable = episodes.indices.toList()`）|
| 批量下载卡片 | 加「清除」按钮，中途可关闭任务 |
| 暂停按钮 | 移到 task-header |
| 封装提示卡 | 「封装期间需保持前台或忽略电池优化」|
| 详情页投屏按钮 | **已移除**（见踩坑档案 #9）|

---

## 七、本地投屏

### 7.1 实现

- **`player/cast/LocalCastServer.kt`**：自写 `ServerSocket`（零依赖，不引 NanoHTTPD）
  - 绑 `0.0.0.0` + 随机空闲端口（不占 9978，避开 StreamProxy）
  - 完整 Range：`bytes=a-b`、`bytes=-N`、越界 `416` + `Content-Range: bytes */total`
  - 路径白名单：只服务当前投屏的那一个文件
  - MIME 按扩展名；文件名 URL 编码（中文名电视才认）
  - 局域网 IP 优先取 `wlan` 接口
- **接线** `LocalPlayActivity`：点投屏 → 起服务 → `CastVideo(url, name, null, position)` → 复用现有 `CastSheet` → 成功后本机暂停

### 7.2 为什么不能复用在线投屏

在线投屏是「把 URL 交给电视，电视自己去拉」。本地文件是 `file://` 路径，电视认不了，必须由手机起 HTTP 服务暴露成局域网 URL。

---

## 八、踩坑档案

> 每条都是「症状 → 成因 → 修法」，按排查难度排序

| # | 症状 | 成因 | 修法 |
|---|---|---|---|
| 1 | 音画不同步，正片中途开始错位 | 音频被错误地按「视频段表 + 时间戳」落段，音频重置点比视频早几十毫秒 → 拿到 offset=0 → 被单调保护压成「每帧 +1 tick」，整段音频被压扁 | 音频按自己的回退点切段，段号与视频段号一一对应套平移量；换段时重置单调保护 |
| 2 | 文件「看起来正常」但整片不同步 | `rebuild` 判据用探针的 PTS 跨度（失真，报 17s vs 清单 1384s）→ 永远不触发；回落 `fix()` 又因音频非单调放弃 → **文件一个字节都没被修过** | 只要有完整清单就重建，不再看探针跨度 |
| 3 | 片尾画面定格、只剩广告声音 | 源只过滤了画面、音频完整保留，音频轨比视频长一倍 | 曾尝试裁剪 → 见 #4，最终**接受**（源对片尾广告是主动不处理）|
| 4 | 播放器 seek 直接跳到片尾，画面静止只剩声音 | 用 null 包「删除」多余音频，在音频轨打出空洞，mp4 的 edit list / duration 错乱 | **彻底删除**这套逻辑。要动音频只能整 PES 从文件剔除，不能用占位包 |
| 5 | 装了新包「改了完全没效果」 | 推送了 commit 但没重新编译 APK | 用 `grep -c` 查源码 + `unzip -p ... classes*.dex \| grep -c` 查 APK，两边都为 0 说明没编进去 |
| 6 | 下载完文件消失（两次） | ① `run()` 用 `publicFileOf(task)` 当写盘目标，重下时解析成已封装 mp4 → delete + rename；② `Mp4Converter` 发现同名 mp4 就 `src.delete(); continue` | ① 改用 `tsFileOf(task)`；② 删旧成品再转换 |
| 7 | 45 分钟的坏文件 | 按段平移把 PTS 回退的两段接成两倍时长 | 加跨度校验（rawSpan > expectSpan×1.3 拒绝）|
| 8 | 编译报错但没有 `e:` 前缀 | Kotlin 里 `as` 是硬关键字，写了 `var as = 0`，语法分析阶段就失败 | 变量改名 `aseg` |
| 9 | 详情页投屏按钮点了没反应 | 走 `DetailActivity.openCast()`：静默解析直链 → 等 5 秒 → 拿不到就静默结束（电视源 + spider 常拿不到直链）| **移除按钮**及整条参数链，播放器内的投屏保留 |
| 10 | 投屏 URL `ERR_CONNECTION_REFUSED` | 把停服绑在 `CastSheet` 的 `onDismiss` 上，而投屏成功后 `CastSheet` 自己 `dismiss()` → 电视刚拿到 URL 服务就关了 | 关面板只清 UI 状态；服务由换集/退出页面回收；同文件复用端口 |
| 11 | 首次「源过滤」判断错误 | 只看 toast 无记录就下结论「是源删的」 | 拉清单复刻判定 + 抽帧验证（块 13 = 博彩广告 ✅ 该删；块 15 = 片尾 ❌ 误删）|

---

## 九、已知取舍（有意为之，不要「顺手修」）

1. **片尾广告音频保留**
   - 源对片尾广告是主动不处理（不影响观感）
   - 强行裁剪会产生 seek 跳片尾的坏 mp4（见踩坑 #4）
   - 代价：片尾可能有一小段声音拖尾

2. **封装需要前台或忽略电池优化**
   - ColorOS 应用智能冻结是进程级的，代码层面绕不过
   - 只在 UI 做提示，不再优化

3. **投屏期间手机保持在播放页前台**
   - 服务生命周期跟随播放页
   - 播放页已加屏幕常亮

4. **不做实时转码**
   - 老电视对 mp4 profile 挑剔（H.264 High 可能不认）
   - 手机转码耗电且慢，不在本阶段范围内

---

## 十、文件清单（改动集中在这些）

```
player/download/
  TsFixer.kt            时间轴修复（按 EXTINF 重建 + 按段平移兜底）
  TsDownloader.kt       分片下载、断点续传、重建触发
  TsProbe.kt            只读时间轴探针
  PurifyEngine.kt       净化统一入口（源规则 → CDN → 原样）
  DownloadLog.kt        写 Download/AVBox/download-debug.log
  DownloadBatch.kt      批次调度、abort、resumeAll
  Mp4Converter.kt       封装（独立 HandlerThread）
  DownloadNotifications.kt  前台服务三态保活
  EpisodeResolver.kt    直链也先问 spider
  LocalLibrary.kt       本地文件扫描
player/cast/
  LocalCastServer.kt    本地 HTTP 文件服务（Range）
ui/
  LocalPlayActivity.kt  本地播放 + 投屏接线
  LocalPlayerScreen.kt  本地播放界面
  LocalPlaybackHost.kt  状态桥接
  DetailHero.kt         详情页按钮组（已移除投屏）
  DetailContent.kt      详情页内容
  DetailEpisodes.kt     选集（取消置灰）
  page/DownloadsTab.kt  下载页（批量卡片、清除、封装提示）
net/StreamProxy.kt      播放侧净化改为调 PurifyEngine
util/M3u8Purifier.java  首末块保护
```

---

## 十一、诊断速查

### 11.1 日志位置

| 日志 | 路径 | 用途 |
|---|---|---|
| 下载调试日志 | `Download/AVBox/download-debug.log` | 解析、净化、修复、封装全过程 |
| App 日志 | `Android/data/com.github.avbox.osc/files/logs/` | 常规运行日志（含 `LocalCastServer`）|

### 11.2 关键日志行

```
rebuild 判定：EXTINF 总和 1384s / 分片 347
rebuild 结果 video=33306 audio=8466 segs=347 frameMs=41.7 ... fixed=true note=按EXTINF重建
rebuild 缺帧分片 n=0
pts packets=1145714 streams=2 jumps=0 maxGap=0ms span=1389255ms
fixer video=... audio=... fixed=false note=自检失败:音频 PTS 非单调 @8359 ...
purify 跳过：清单来自本地净化代理
LocalCastServer serve 强风吹拂_2.mp4 size=... -> http://192.168.1.10:39153/...
LocalCastServer first-req GET /强风吹拂_2.mp4 range=bytes=0-
```

### 11.3 判读要点

| 现象 | 结论 |
|---|---|
| `fixed=true note=按EXTINF重建` | 重建成功，时间轴可信 |
| `span` ≈ 视频内容（帧数×帧间隔） | 时间轴正常 |
| `jumps=0 maxGap=0ms` | 全片没有超过 2 秒的空洞（阈值 `JUMP_MS = 2000`）|
| `rebuild 缺帧分片 n>0` | 源真的删过画面，日志里会列出 `分:秒(实际帧/应有帧)` |
| `purify 跳过：清单来自本地净化代理` | 下载用的是播放侧同一份清单，「播什么下什么」生效 |

### 11.4 验证版本是否装进去

```
grep -c "音频分段对齐" ~/AVBox/app/src/main/java/com/github/avbox/osc/player/download/TsFixer.kt
cd ~/AVBox && ./gradlew assembleDebug
unzip -p app/build/outputs/apk/debug/app-debug.apk "classes*.dex" | grep -c "音频分段对齐"
```

源码有、APK 里没有 = 没编进去（踩坑 #5）。

---

## 十二、遗留与可选方向

| 项 | 说明 |
|---|---|
| 站点级净化豁免名单 | 曾讨论，未做（净化已改为「源过滤优先」后可能不需要）|
| 封装长时间停滞自动放弃 | 代码层不做，只留 UI 提示 |
| 投屏字幕推送 | 外挂 srt 需按 DLNA 规范单独推（三星用 `sec:SubtitleUri`），未做 |
| 本地投屏后台保活 | 当前随播放页生命周期，锁屏会断流 |
