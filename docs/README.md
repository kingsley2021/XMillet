# XMillet 技术文档索引

本目录整理 XMillet 在适配 MIUI 14 及 HyperOS 1–4（Android 13–17）过程中沉淀的系统机制分析、Hook 点位规格与 Doze 状态机研究，所有代码符号与行为均与当前仓库实现核对一致。

## 文档列表

| 文档 | 内容范围 |
| --- | --- |
| [小米官方 Millet / Greeze 架构分析](./official-millet-architecture.md) | MIUI 14 与 HyperOS 1–4 的内核模块、`millet_monitor` 进程模型、Netlink 协议、`millet_pkg` 网络统计与不丢包证据、cgroup 冻结粒度及双开应用（`userId = 999`）跨代演进 |
| [跨代 Hook 点位与适配矩阵](./hook-points-matrix.md) | `system_server`（`S*`）与 `com.miui.powerkeeper`（`P*`）全部 Hook 点位清单、签名跨代变化、能力探测门控、冻结/解冻/广播闸门规则及省电策略保护机制 |
| [Doze 状态机、强制深睡与阻断因素](./doze-state-machine-and-blockers.md) | `DeviceIdleController` 状态流转与 OS3/OS4 `QUICK_DOZE_DELAY`、系统拒绝深睡的阻断条件、强制深睡四点联动（`S14`/`S34`/`S41`/`S42`）、多通道观测（`S38`/`S39`/`S40`）与诊断排查命令 |

## 覆盖的系统代次与基准版本

| 档位（`VersionGate.RomTier`） | 典型系统属性特征 | Android / SDK | PowerKeeper 冻结决策架构 |
| --- | --- | --- | --- |
| `MIUI14` | `ro.mi.os.version.name` 为空且 `ro.miui.ui.version.name` 以 `V14` 开头 | Android 13（API 33） | 传统 `com.miui.powerkeeper.millet.*` |
| `OS1` | `ro.mi.os.version.name = OS1.x` | Android 13–14（API 33–34） | 传统 `com.miui.powerkeeper.millet.*` |
| `OS2` | `ro.mi.os.version.name = OS2.x` | Android 14–15（API 34–35） | 传统 `millet.*` + 扩展字段（如 `BRIGHT_WHITE_APPS`、4 机型双开白名单） |
| `OS3` | `ro.mi.os.version.name = OS3.x` | Android 16（API 36） | 移除 `millet.*`，改由 `FrozenAppController`、`KillProcessController`、`ActiveStateController` 承担 |
| `OS4` | `ro.mi.os.version.name = OS4.x` | Android 17（API 37） | 移除 `FrozenAppController` 与 `PowerCheckerController`，冻结资格判定下沉至 `system_server` 的 `PolicyMaker` 与 `ListAppsManager` |
