<div align="center">

<img src="imgs/icon.webp" width="160" height="160" style="display: block; margin: 0 auto;" alt="XMillet icon" />

# XMillet

### 面向小米澎湃 OS 官方墓碑机制（Millet）的现代 LSPosed 控制与统计面板

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-13%2B%20(API%2033)-3DDC84?style=flat-square&logo=android&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-21-437291?style=flat-square&logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-9.8.0-02303A?style=flat-square&logo=gradle&logoColor=white)
![HyperOS](https://img.shields.io/badge/HyperOS-OS1.0%20~%20OS4.0-FF6900?style=flat-square&logo=xiaomi&logoColor=white)

[<img src="https://github.com/LSPosed.png" height="20" alt="LSPosed">](https://github.com/LSPosed)
![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-FF4088?style=flat-square)
[<img src="https://github.com/compose-miuix-ui.png" height="20" alt="Miuix">](https://github.com/compose-miuix-ui/miuix)
![Miuix](https://img.shields.io/badge/Miuix-0.9.5--rc02-C9603F?style=flat-square)

</div>

> [!CAUTION]
> **框架与冲突提示**：本模块基于 **[libxposed API 102](<https://github.com/libxposed/api>)** 构建（如 [LSPosed](<https://t.me/LSPosedArchives>)），请勿与其他同样作用于**「电量与性能 (`com.miui.powerkeeper`)」**或**墓碑冻结调度**的模块同时启用，以免产生冲突。

---

## 简介

**XMillet** 是针对小米澎湃 OS 官方墓碑机制（**Millet**，系统底层由 `GreezeManagerService` 与 `PowerKeeper` 协同调度与执行）构建的 Xposed 模块，基于 `libxposed` API 102 与 Miuix Compose UI 实现。它**不替换系统原有冻结机制**，而是接管系统侧与电量侧的关键冻结/解冻闸门，提供状态观测、分应用策略与唤醒拦截统计面板。

- **支持范围**：Android 13+（minSdk 33），实机适配 HyperOS 1.0 / 2.0 / 3.0 / 4.0（Android 13 ~ 17）
- **LSPosed 作用域**：仅需勾选「系统框架 (`system`)」与「电量与性能 (`com.miui.powerkeeper`)」
- **交互设计**：基于 [Miuix](https://github.com/compose-miuix-ui/miuix)（`0.9.5-beta03`）构建，支持液态玻璃悬浮底栏、玻璃顶栏、渐进式模糊、实时毛玻璃模糊、动态取色（Monet）、首次引导（OOBE）与桌面图标隐藏

## 界面预览

<div align="center">

| 主页总览 | 进程列表 | 墓碑统计 |
| :---: | :---: | :---: |
| <img src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/XMillet/1.jpg" width="240" alt="XMillet 主页总览" /> | <img src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/XMillet/2.jpg" width="240" alt="XMillet 进程列表" /> | <img src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/XMillet/3.jpg" width="240" alt="XMillet 墓碑统计" /> |

</div>

## 核心能力

### 1. 冻结与解冻闸门控制
- 接管 `freezeUids` / `freezePids` / `freezeAction` 与 `thawUid(s)` / `thawUidAsync` / `thawAll` 等关键入口
- 支持按开关拦截**亮屏解冻**（默认开启，并自动豁免当前前台应用、系统核心进程、默认输入法、已启用无障碍服务与白名单）、**插电解冻**、**网络唤醒**与**同步 Binder 解冻**，并在查杀检查路径提供阻止查杀保护
- 采用 `libxposed RemotePreferences` 配置通道；常规策略调整可热更新。模块更新的 API 102 热重载依赖框架能力与目标进程已注入，不能替代首次注入

### 2. 分应用策略
- 支持应用策略配置与双开空间（user 999）独立分组。七个应用集合区分主用户与 user 999，UID 入口按目标用户裁决；系统仅接收裸包名的全局名单不保证跨用户独立
- 广播策略联动 `isAllowBroadcast` / `deferBroadcastForMiui`（HyperOS 2.0 及以上；更早版本缺少对应接口，自检时标为版本差异），常规消息放行仍受严格策略与安全豁免裁决，不保证严格冻结时 FCM 即时送达。模块不承担 GMS 心跳保活或专用推送唤醒
- 云名单与宿主特判兼容：直接走系统 `GlobalFeatureConfigureHelper` 读取云端三表，不自行篡改解析底层数据库，并在后台将「消息应用」同步进系统 `mAurogonAlarmAllowList` 闹钟唤醒白名单
- 支持系统/三方应用筛选、搜索定位，以及配置 JSON 备份/恢复与 `bugreport-*.tar.gz` 诊断日志打包分享

### 3. 内核态观测与实时状态
- 通过跨进程 Binder IPC 实时读取 `system_server` 中的 Greeze（Millet 底层冻结服务）运行态与 Hook 实装状态，并集成 `XposedService` 框架能力检测、一键授权作用域与过期进程热重载
- 应用前台沿用 5 秒快照请求间隔，`system_server` 按需采集并缓存成功结果 1.5 秒；前台与可见性另由 `system_server` 内每 2 秒刷新的无锁快照提供，钩子线程只读快照，不在持锁路径上查询 `ActivityManager` 或 `PackageManager`，快照缺失时按「可能在前台」放行。后台核心依赖既有 Hook、广播与配置回调，没有新增补冻任务
- 冻结状态按实际 cgroup 挂载与完成态读取，区分冻结、运行和未知；采样逐 PID 进行，不是原子全量快照。进程内存按 `/proc/<pid>/smaps_rollup` 的 PSS 统计，「主页」「进程列表」与「应用详情」同源一致

### 4. 墓碑统计面板
- 在 `system_server` 内使用内存定长结构与紧凑 `v3` 行协议聚合与持久化（无损兼容吸收 `v2` 历史数据并自动升级）；唤醒次数只统计存在对应冻结记录的解冻，并自动过滤无独立包名的沙盒/隔离进程（`90000..99999`），兼容 MIUI 双开应用包名与图标解析
- 新增 **Doze 深度睡眠待机归因**（连续 `IDLE / IDLE_MAINTENANCE` ≥30s 会话统计、开机历史缓冲按需补齐、三级 UID CPU 耗时采集：`/proc/uid_cputime/show_uid_stat` → CGroup v2 `cpu.stat` → `KernelCpuUidUserSysTimeReader` / `KernelCpuUidFreqTimeReader`，过滤 <100ms 噪声与充电掉电率）
- 提供**今日 / 近 7 天 / 开机以来**三个统计窗口：涵盖总览指标、息屏唤醒频次、Doze 待机归因、唤醒原因分类（用户打开、网络、Binder、广播通知、亮屏、充电等）、分时亮/息屏柱状分布图、XMillet 拦截明细、多口径应用排行（「仅三方」筛选 + 搜索 + 单应用详情弹窗）以及最近动态时间线
- 支持在设置页一键开启或关闭统计采集（关闭后自动隐藏底栏「墓碑统计」入口）与重置统计数据

## 版本适配与 Hook 探测

模块在注入前会对目标类与方法逐一探测：系统侧共 **39 个**点位、电量侧共 **33 个**点位（合计 **72 个**）。当 HyperOS 3.0 / 4.0 等新版本移除部分旧接口（如 `com.miui.powerkeeper.millet` 相关点位）时，对应 Hook 会自动安全跳过，并在应用内自检弹窗标明「版本差异」，避免误报异常。

环境通过还要求实际冻结证据、官方冻结链路关键 Hook 的实装记录，以及 ROM 后端启用标志和所需 monitor 注册状态。确认路径要求 Millet 与 PowerMillet 启用，不要求传统 Greeze 开关同时开启；只有传统开关开启不能证明官方后台策略已运行，未核实的替代路径保持未确认。只有节点、类、句柄或启动加速冻结样本不足以全部通过。缺少可读样本、权限或运行证据时标记未确认；后端关闭或 monitor 未完整注册时不通过。monitor 注册不等于内核握手或后续调度保证，不按第三方内核名称判断兼容性。

## 实机核验

四台不同代际设备的实机核验 Hook 实装情况如下：

| 设备 | 代号 | 系统版本 | Android | 内核 | Root | LSPosed | Hook 实装（系统侧 + 电量侧） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Redmi K40 游戏增强版 (M2012K10C) | `ares` | OS1.0.4.0.TKJCNXM | 13 (SDK 33) | `4.14.186-g844fd19c6` | Magisk | v2.2.1-log (7940) | 26/37 + 27/31 |
| Xiaomi Pad 5 Pro 12.4 (22081281AC) | `dagu` | OS2.0.10.0.ULZCNXM | 14 (SDK 34) | `4.19.157-perf-gb57830672689` | Magisk | v2.2.1-log (7939) | 29/37 + 28/31 |
| REDMI K80 Pro (24122RKC7C) | `miro` | OS3.0.304.0.WOMCNXM | 16 (SDK 36) | `6.6.118-android15-8-gc44b714366cc-abogki519650608-4k` | KernelSU | v2.2.1 (7940) | 31/37 + 20/31 |
| Xiaomi 15 (24129PN74C) | `dada` | OS4.0.4.0.XOCCNXM | 17 (SDK 37) | `6.6.118-android15-8-gf72a02f47f4b-abogki551678766-4k` | KernelSU | v2.2.1 (7939) | 29/30* + 14/28* |

> `ares`、`dagu` 与 `miro` 为当前版本（系统侧 37、电量侧 31）默认关闭 `simplifyLog` 下的实测值（开启 `simplifyLog` 后可额外启用 `S16–S18` 等日志点位）；标注 `*` 的 `dada` 为早期 `30 + 28` 点位基线下的历史核验数据，尚未按当前基线复测。

## 使用与构建

### 使用步骤
1. 安装 XMillet APK；
2. 在 **LSPosed 管理器** 中启用本模块，并仅勾选作用域：**系统框架 (`system`)** 与 **电量与性能 (`com.miui.powerkeeper`)**；
3. 按框架要求完成目标进程首次注入，再打开 XMillet 检查两侧作用域、Hook 实装与冻结能力。已注入进程的后续更新可使用受框架支持的 API 102 热重载；只有临时 root 权限不等于完成注入，也不保证跨重启继续生效。

### 构建

构建环境需要 **JDK 21**，支持 `arm64-v8a` 架构：

```bash
./gradlew :app:assembleDebug
```

## 开源许可

本项目基于 [GNU General Public License v3.0](LICENSE) 开源发布。

## 支持

<details>
<summary>欢迎打赏</summary>

<sub>如果 XMillet 对你有所帮助，欢迎请作者喝杯咖啡</sub>

| | |
| :---: | :---: |
| <img src="app/src/main/res/drawable-nodpi/qr_donate_alipay.webp" width="220" alt="赞赏码 1" /> | <img src="app/src/main/res/drawable-nodpi/qr_donate_wechat.webp" width="220" alt="赞赏码 2" /> |

</details>

---

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
