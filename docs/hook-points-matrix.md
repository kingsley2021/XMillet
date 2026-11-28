# 跨代 Hook 点位与适配矩阵

XMillet 基于 Modern LibXposed API（API 102）同时注入两个系统作用域：`"android"`（`system_server`）与 `"com.miui.powerkeeper"`（电量与性能）。本文列出当前代码实现的全部 Hook 点位、跨 ROM 签名差异、能力探测门控与核心裁决逻辑。

## 一、能力探测与覆盖记账机制

不同代次 ROM 会增删整个子系统（例如 OS3 移除 `com.miui.powerkeeper.millet.*`，OS4 移除 `FrozenAppController` 与 `PowerCheckerController`）。模块采用两层过滤（见 `core/RomCapabilities.kt` 与 `core/HookInstallRegistry.kt`）：

1. **版本门控（`HookSpec.gate`）**：用于明确二选一或按代次启用的点位（如 `S9` / `S10` 按 `SDK_INT > 33` 互斥，`P4` 仅在 MIUI 14/OS1 安装）；不满足时记为 `SKIPPED_GATED`。
2. **反射存在性探测（`RomCapabilities.isInstallable`）**：检查目标类与方法名是否存在，缺失时记为 `SKIPPED_UNSUPPORTED` 而非安装失败。
3. **双进程覆盖汇总**：两侧账本经 Binder 汇总到 App 端，合并时按 `HookDecisions.acceptLedgerReport` 的 `total` 单调递增规则避免热重载竞态下的残缺覆盖。

---

## 二、`system_server` 作用域 Hook 清单（共 42 项）

### 1. IPC 握手、服务句柄与生命周期

| ID | 目标类与方法 | 模式 | 适用代次 / 门控 | 作用与跨代签名要点 |
| --- | --- | --- | --- | --- |
| `S1` | `ActivityManagerService#registerReceiverWithFeature` | `BEFORE` | 全版本 | 拦截 `ACTION_XMILLET_BINDER` 注册请求，通过伪粘性 `Intent` extras 回传 `SystemBinderService` 句柄；通过 `paramResolver` 动态匹配含 `IntentFilter` 的重载 |
| `S2` | `GreezeManagerService#<init>` | `AFTER` | 全版本 | 捕获 `GreezeManagerService` 与 `Context` 实例；MIUI 14–OS2 为 `<init>(Context)`，OS3–OS4 改为无参 `<init>()`，由 `paramResolver` 兼容 |
| `S2_Gate` | `GreezeManagerService#<init>` | `AFTER` | 全版本 | 开启全局就绪标志 `isGreezeReady`，读取模块 `lastUpdateTime`，注册亮灭屏与统计广播监听，触发首次 `ForceFreezeBridge` 下发与 `NoRestrictSync` 同步 |
| `S3` | `AurogonImmobulusMode#<init>` | `AFTER` | 全版本 | 捕获 `Aurogon` 实例并同步消息应用与双开配置；OS1–OS2 为 3 参 `(Context, HandlerThread, GreezeManagerService)`，OS3–OS4 为无参 `<init>()` |
| `S11` | `ActivityManagerService#setSystemProcess` | `BEFORE` | 全版本 | 捕获 `ActivityManagerService` 实例用于提取系统 `Context` |

### 2. 进程缓存与 ANR 屏蔽

| ID | 目标类与方法 | 模式 | 适用代次 / 门控 | 作用与跨代签名要点 |
| --- | --- | --- | --- | --- |
| `S4` | `ProcessList#addProcessNameLocked` | `BEFORE` | 全版本 | 缓存非系统应用（及强冻目标）的 `ProcessRecord` 快照，供 ANR 与冻结态查询复用 |
| `S15` | `ProcessList#removeProcessNameLocked` | `AFTER` | 全版本 | 进程退出时清理 `ProcessRecord` 缓存与 PID 冻结记账表 |
| `S16` | `ProcessList#handleProcessStartedLocked` | `AFTER` | `simplifyLog` 门控 | 进程启动后补记 PID 并标记解冻状态，输出精简墓碑日志 |
| `S5` | `ProcessErrorStateRecord#appNotResponding` | `BEFORE` | 全版本 | 冻结或受管应用触发 ANR 时在 `ProcessErrorStateRecord` 层拦截；优先反射声明参数，并保留 10 参回退签名 |
| `S19` | `AnrHelper#appNotResponding` / `deferAppNotResponding` | `BEFORE` | 全版本 | 遍历 `AnrHelper` 全部 `void` 同名重载与 `S5` 组成双保险，阻断冻结态引发的 ANR 弹窗与杀进程 |

### 3. 冻结 / 解冻闸门与墓碑统计

| ID | 目标类与方法 | 模式 | 适用代次 / 门控 | 作用与跨代签名要点 |
| --- | --- | --- | --- | --- |
| `S6` | `GreezeManagerService#freezeUids` | `BEFORE_AND_AFTER` | 全版本 | 批量 UID 冻结入口：调用 `GateDecision.decideFreeze(..., checkFgs = true)` 剔除受保护 UID，`after` 按返回值做冻结统计与日志 |
| `S7` | `GreezeManagerService#freezeAction` | `BEFORE_AND_AFTER` | 全版本 | 单 UID 冻结总闸；OS1–OS3 为 4 参 `(int, int, String, boolean)`，OS4 扩展为 5 参 `(int, int, String, boolean, boolean)`，由 `paramResolver` 适配 |
| `S8` | `AurogonImmobulusMode#checkAppStatusForFreeze` | `BEFORE` | 全版本 | 最早一道冻结否决门，拦截白名单/系统服务角色等硬保护目标进入冻结队列 |
| `S17` | `GreezeManagerService#freezeProcess` | `AFTER` | `simplifyLog` 门控 | 记录单进程级冻结事件与 UID 冻结进程占比（跳过 WebView 沙盒进程） |
| `S18` | `GreezeManagerService#thawProcess` | `AFTER` | `simplifyLog` 门控 | 异步从 `/proc/<pid>/status` 反查 UID 并更新进程解冻记账 |
| `S20` | `GreezeManagerService#thawUid` | `BEFORE_AND_AFTER` | 全版本 | 单 UID 同步解冻闸门，执行 `GateDecision.decideThaw`（禁止插电解冻、亮屏保持冻结、后台解冻压制、禁同步 Binder、忽略网络唤醒） |
| `S21` | `GreezeManagerService#thawUids` | `BEFORE_AND_AFTER` | 全版本 | 批量 UID 解冻闸门，逐 UID 复用 `decideThaw` 过滤被拦截目标 |
| `S22` | `GreezeManagerService#dispatchThawEvent` | `BEFORE_AND_AFTER` | 存在即装 | 解冻事件最终派发点；安装成功后作为 `FreezeStatsRecorder` 的精确解冻记账源 |
| `S23` | `GreezeManagerService#freezePids` | `BEFORE_AND_AFTER` | OS3–OS4 等存在即装 | 进程级批量冻结闸门（拦截 `ActionExecute.freezeNonKeyProcesses -> freezePids` 绕过 UID 闸门冻死多进程子进程的问题），并校验 `/proc/<pid>/stat` 启动时间戳防 PID 复用错记 |
| `S26` | `GreezeManagerService#thawUidAsync` | `BEFORE` | 存在即装 | 异步单 UID 解冻入口 `(int uid, int caller, String reason) -> void`，与 `S20` 共享裁决 |
| `S27` | `GreezeManagerService#thawAll` | `BEFORE` | 全版本 | 全量解冻入口 `(String reason) -> boolean`；按 `disableCharge` / `disableScreenOnThaw` 拦截全量解冻，并在拦截后异步单独放行白名单与亮屏豁免目标 |
| `S33_POLICY_ALLOW_FREEZE` | `com.miui.server.greeze.power.PolicyMaker#isAllowFreeze` | `AFTER` | OS4 | OS4 移除 `FrozenAppController` 后，冻结资格判定下沉至 `PolicyMaker`；命中硬保护目标时将 `FREEZE_ABLE` 改写为 `CANNOT_FREEZE` |

### 4. 广播、WakeLock、查杀防护与双开 / 云白名单

| ID | 目标类与方法 | 模式 | 适用代次 / 门控 | 作用与跨代签名要点 |
| --- | --- | --- | --- | --- |
| `S9` | `BroadcastSkipPolicy#shouldSkipMessage` | `REPLACE` | `SDK_INT > 33`（Android 14+） | 冻结态动态广播跳过；OS1/OS2 为 2 参 `(Record, BroadcastFilter)`，OS3+ 为 3 参 `(Record, BroadcastFilter, boolean)`；放行 4 类核心网络状态广播 |
| `S10` | `BroadcastQueue#deliverToRegisteredReceiverLocked` | `REPLACE` | `SDK_INT <= 33`（Android 13） | 与 `S9` 互斥；冻结态投递瞬间置空 `receiverList.app` 并在 `finally` 恢复原引用，防止解冻后动态接收器永久失效；同样放行 4 类核心网络状态广播 |
| `S24` | `GreezeManagerService#isAllowBroadcast` | `REPLACE` | OS1–OS4 | 系统官方广播放行裁决入口（覆盖绕过广播队列的直连推送路径），通过 `GateDecision.decideBroadcastAllow` 区分拦截、消息/白名单放行与交回系统 |
| `S25` | `GreezeManagerService#deferBroadcastForMiui` | `REPLACE` | 存在即装 | 系统延后广播入口（仅含 `action`，无收件 UID），当前透传原实现 |
| `S21_WAKELOCK` | `PowerManagerService#acquireWakeLockInternal` | `BEFORE` | 全版本 | 拦截已冻结应用（`uid > 10000`）申请 WakeLock，防止持有唤醒锁影响待机 |
| `S22_KILL` | `miui.process.ProcessManager#kill` | `BEFORE` | 全版本 | `system_server` 侧查杀守卫：目标已处于冻结态或开启 `disableKill` 时拦截查杀并记入统计 |
| `S28` | `AurogonImmobulusMode#isUidValid` | `BEFORE` | OS2–OS4 | 绕过 OS2 机型白名单与 OS3/OS4 云控 `mDoubleAppCtrlCloud`，按 `freezeDualApp` 开关控制 `userId == 999` 准入 |
| `S29` | `GreezeManagerService#checkStateForScrOff` | `BEFORE` | OS2–OS4 | 解除 `uid <= 19999` 上限，使双开应用进入息屏状态检查 |
| `S30` | `GreezeManagerService#notifyExcuteServices` | `BEFORE` | 仅存在单参 `(int)` 重载（MIUI 14–OS2） | 补齐双开 UID 的服务启动通知；OS3/OS4 为 3 参重载且已自带 `999` 放行，门控自动跳过 |
| `S31` | `AurogonImmobulusMode#updateTargetList` | `BEFORE` | 仅无 `isUidValid` 时（MIUI 14 / OS1） | 将双开 UID 加入 `mImmobulusTargetList` |
| `S32_CLOUD_FROZEN_WL` | `com.miui.server.greeze.power.ListAppsManager#dealCloudWhiteList` | `BEFORE` | OS3–OS4 | 拦截系统侧官方云墓碑白名单注入，并缓存原始串以支持关闭开关后免重启热恢复 |

### 5. Doze 深度睡眠控制与观测

| ID | 目标类与方法 | 模式 | 适用代次 / 门控 | 作用 |
| --- | --- | --- | --- | --- |
| `S12` | `DeviceIdleController#onStart` | `AFTER` | 全版本 | 捕获控制器句柄、按构造器参数类型动态反射初始化 `DeviceIdleController$Shell`（兼容 Android 16 无参/静态构造变化），执行启动期同步 |
| `S13` | `DeviceIdleController#motionLocked` | `BEFORE` | 全版本 | 开启屏蔽移动传感器后，将 `mNotMoving` 置 `true` 并拦截原方法，避免移动打断 Doze |
| `S14` | `DeviceIdleController#moveToStateLocked` | `BEFORE_AND_AFTER` | 全版本 | `skipStateCheck` 跳过 `IDLE_PENDING(2)`（强制态改写为 `LOCATING(4)`，非强制态改写为 `IDLE_MAINTENANCE(6)` 并补超时字段）；`after` 取消延时闹钟并上报统计 |
| `S34` | `DeviceIdleController#isUpcomingAlarmClock` | `BEFORE` | 全版本 | 强制推进（`mForceIdle == true`）期间全状态短路为 `false`，避免 10 分钟闹钟窗口中断推进 |
| `S41_ALARM_ADJUST_IDLE_UNTIL` | `AlarmManagerService#adjustIdleUntilTime` | `BEFORE` | 全版本 | 强制深睡期间跳过 `FLAG_IDLE_UNTIL` 提前唤醒裁切，并调用 `restoreRequestedTime` 维持 `IDLE(5)` 直至闹钟准点触发 |
| `S42_DOZE_CONSTRAINTS` | `DeviceIdleController#updateActiveConstraintsLocked` | `AFTER` | 全版本 | 强制推进经过 `INACTIVE`–`LOCATING`（状态 1..4）时清零 `mNumBlockingConstraints` |
| `S38_DOZE_BECOME_ACTIVE` | `DeviceIdleController#becomeActiveLocked` | `AFTER` | 全版本 | 捕获绕过 `moveToStateLocked` 直接回到 `ACTIVE(0)` 的退出事件 |
| `S39_DOZE_STEP_IDLE` | `DeviceIdleController#stepIdleStateLocked` | `AFTER` | 全版本 | 在不易被内联的步进入口读取 `mState`，补齐深睡进入与维护观测 |
| `S40_DOZE_EVENT` | `DeviceIdleController#addEvent` | `AFTER` | 全版本 | 订阅 ROM 事件环写入（`4 -> IDLE`、`5 -> IDLE_MAINTENANCE`、`1 -> ACTIVE`），在 `S14`/`S39` 受 AOT 内联影响时稳定提供实时统计 |

---

## 三、`com.miui.powerkeeper` 作用域 Hook 清单（共 33 项）

### 1. 启动链路、旧版 Millet 策略层（MIUI 14–OS2）

| ID | 目标类与方法 | 模式 | 适用代次 | 作用 |
| --- | --- | --- | --- | --- |
| `P1` | `PowerKeeperApplication#onCreate` | `BEFORE` | 全版本 | 建立至 `system_server` 的 Binder 双向链路并上报 `MILLET_NO_RESTRICT_APP` |
| `P2` | `MilletPolicy#<init>(Context)` | `BEFORE_AND_AFTER` | MIUI 14–OS2 | 捕获实例、清空内置 `musicApp` 列表，按 union 方式重放 6 组白/消息/强冻名单 |
| `P3` | `MilletPolicy#dealCloudWhiteList` | `BEFORE` | MIUI 14–OS2 | 受 `blockCloudFrozenWhitelist` 控制，拦截官方云墓碑白名单载入 |
| `P4` | `MilletPolicy#onCloudWhiteListChanged` | `BEFORE` | MIUI 14 / OS1（`isLegacyMilletStack`） | 拦截运行期云白名单热更新回调 |
| `P5` | `MilletPolicy#dealNoRestrictApp` | `AFTER` | MIUI 14–OS2 | 系统省电「无限制」变更后重放模块白名单并上报 `system_server` |
| `P6` | `MilletPolicy#dealSleepModeWhiteList` | `BEFORE` | MIUI 14–OS2 | 阻止睡眠模式改写免冻结与省电名单 |
| `P9` | `MilletHandler#handleMessage` | `BEFORE` | MIUI 14–OS2 | `what == 13` 按 `forbidSyncBinderSet` 剔除查询队列；`what == 16`（`arg1 == 1`）在 `disableCharge` 时阻止插电全解冻 |
| `P10` | `MilletBrightHandler#handleMessage` | `BEFORE` | MIUI 14–OS2 | 亮屏处理器同口径拦截同步查询（`what == 13`）与插电全解冻（`what == 16`） |
| `P17` | `FreezeBinder#<init>` | `BEFORE` | MIUI 14–OS2 | 旧架构 Millet 宿主能力检测点 |
| `P18` | `PowerKeeperManager#<init>` | `BEFORE` | 全版本 | 缓存 `PowerKeeperManager` 句柄（供查找 `FrozenAppController` 纠正插电标志）并同步双开开关 |
| `P20` | `BrightScenarioBase#<init>` | `AFTER` | MIUI 14–OS2 | 缓存场景基类句柄并重建 `preloadWhiteApps` |
| `P29` | `MilletUtils#isAppUid` | `BEFORE` | OS2（含 `ENABLE_DOUBLE_APP` 字段） | 绕过 OS2 四机型限制，按 `freezeDualApp` 放行双开 UID |
| `P36_BRIGHT_FROZEN_THAW` | `BrightController#frozenThaw` | `BEFORE` | MIUI 14–OS2 | 将超过 `5,000ms` 的息屏冻结延迟（原生 `62,000ms`）钳位至 `5,000ms` |

### 2. 新版控制器架构（OS3–OS4）

| ID | 目标类与方法 | 模式 | 适用代次 | 作用 |
| --- | --- | --- | --- | --- |
| `P22_KILL_POLICY` | `KillProcessController#shouldKillByCheckerPolicy` | `BEFORE` | OS3–OS4 | `disableKill` 或命中免冻/硬保护目标时返回 `false`，阻断策略层查杀判定 |
| `P23_FROZEN_DECISION` | `FrozenAppController#appIsAllowToFrozen` | `BEFORE` | OS3（OS4 已移除该类） | 冻结资格判定拦截，保护白名单、当前默认输入法与无障碍服务 |
| `P24_NO_CHARGE_THAW` | `EventsAggregator#notifyForCharging` | `BEFORE` | OS3–OS4 | `disableCharge` 开启时拦截充电事件广播，并尝试纠正 `mCurFreezeCondition` |
| `P28_NO_RESTRICT` | `ActiveStateController#dealNoRestrictApp` | `AFTER` | OS3–OS4 | 替代 `P5`，在 `ActiveStateController` 重建 `MILLET_NO_RESTRICT_APP` 后回传快照至 `system_server` |
| `P33_CHARGE_STRATEGY` | `FrozenAppController#setChangeFrozenStrategy` | `BEFORE` | OS1–OS3 | `disableCharge` 开启时阻止插电置位 `CHARGING(0x01)`（防止 `mCurFreezeCondition` 由 40 变为 41 后停止产生新冻结） |
| `P34` | `AppClusterController#isControlApp` | `BEFORE` | 全版本（存在即装） | 解除 `uid <= 19999` 限制，按 `freezeDualApp` 控制双开应用进入集群管控 |
| `P35_FAST_SCREEN_OFF_FREEZE` | `FrozenAppController$AppStateFrozenControl#frozenAppLater` | `BEFORE` | OS1–OS3 | 将息屏延迟冻结（原生 `62,000ms`）收敛至 `5,000ms` |

### 3. 后台查杀、云控配置与系统省电策略保留（全版本 / 按存在挂载）

| ID | 目标类与方法 | 模式 | 适用代次 | 作用 |
| --- | --- | --- | --- | --- |
| `P11` | `PowerCheckerController#clearApp` | `BEFORE` | MIUI 14–OS3 | `disableKill` 时拦截后台清理（OS4 已移除该类） |
| `P12` | `PowerStateMachine#clearAppWhenScreenOffTimeOut` | `BEFORE` | 存在即装 | `disableKill` 时拦截息屏超时清理 |
| `P13` | `PowerStateMachine#clearAppWhenScreenOffTimeOutInNight` | `BEFORE` | 存在即装 | `disableKill` 时拦截夜间超时清理 |
| `P14` | `PowerStateMachine#clearUnactiveApps` | `BEFORE` | 存在即装 | `disableKill` 时拦截不活跃应用清理 |
| `P21_KILL` | `miui.process.ProcessManager#kill` | `BEFORE` | MIUI 14–OS2（OS3 移除 `miui.process`） | `disableKill` 时拦截 `PowerKeeper` 进程内直接杀后台 |
| `P25_AUTO_KILL` | `PowerCheckerController#autoKillApp` | `BEFORE` | OS3（OS4 已移除） | 自动查杀落地入口守卫 |
| `P26_AUDIO_KILL` | `AudioDisguiseController#killApp` | `BEFORE` | OS3–OS4 | 音频欺骗查杀入口守卫 |
| `P27` | `GlobalFeatureConfigureHelper#updateGlobalConfigure` | `BEFORE` | 全版本 | 按 `blockCloudDozeWhitelist` 与 `blockCloudFrozenWhitelist` 分别过滤 `dozeWhiteListApps` / `FrozenNewWhiteList` / `bright_frozen` 等 Bundle 键 |
| `P30_CLOUD_DOZE_READ` | `GlobalFeatureConfigureHelper#getDozeWhiteListApps` | `BEFORE` | 全版本 | 开启 `blockCloudDozeWhitelist` 时对宿主返回空列表（模块自身查询读取时绕过） |
| `P7` / `P8` | `CommonAdapter#addPowerSaveWhitelistApps` / `removePowerSaveWhitelistApps` | `BEFORE` | 全版本 | 拦截 `PowerKeeper` 自动增删 Doze 省电白名单，同时结合 `P31`/`P32` 短时租约与 `userTable.bgControl` 真值放行用户在系统设置中的手动切换 |
| `P31_USER_POWER_SAVE_CONFIG` | `PowerSaveConfigureManager#setPowerSaveAppConfigure` | `BEFORE_AND_AFTER` | 全版本 | 记录用户在系统设置「省电策略」手动设置 `no_restrict` / 默认的短时意图；返回 `-1`（缺库行）时调用 `BatteryPolicyApplier` 补写配置行避免界面回弹 |
| `P32_GET_POWER_SAVE_CONFIG` | `PowerSaveConfigureManager#getPowerSaveAppConfigure` | `BEFORE_AND_AFTER` | 全版本 | 标记读取重入深度，区分系统内部自发回退写入与用户手动点击 |

---

## 四、统一裁决引擎（`GateDecision`）与关键边界

双进程所有冻结、解冻与广播裁决统一收口在 `core/GateDecision.kt`，避免 `system_server` 与 `powerkeeper` 语义漂移：

1. **冻结三层优先级（`decideFreeze`）**：
   - 硬安全层：用户免冻白名单 → 内置核心保护（`BuiltinProtect.FREEZE_EXEMPT_PACKAGES`）→ 当前默认输入法 → 已启用无障碍服务 → 服务角色读取未就绪保守保护；
   - 软豁免层：未配置强制冻结且勾选「有前台服务不冻结」时，若 `checkAppFgService(uid)` 为真则放行（单纯消息应用仍正常冻结，仅在推送/广播到达时短暂解冻）；
   - 默认层：交回官方链路冻结。
2. **亮屏保持冻结的精准豁免（`decideScreenOnThawExempt`）**：
   - 核心系统 UID（`appId < 10000`）、免冻白名单、当前前台/可见应用、输入法/无障碍服务、当前动态壁纸、当前自动填充服务、前台 Binder 关联 UID 无条件随亮屏放行；
   - 未配置后台解冻压制（`blockThawSet` / `ignoreNetworkWakeSet`）且未强制冻结的桌面小组件应用及活跃前台服务豁免应用同样随亮屏放行。
3. **热重载清理与安全边界**：
   - `SystemHooks.onHotReloading()` 与 `PowerKeeperHooks.onHotReloading()` 在卸载旧 ClassLoader 前注销亮灭屏/电量广播、取消 Doze 闹钟、停止后台线程并清空 `HookInstallRegistry`，`PowerKeeperHooks.ensureBinderLink()` 在热重载后补建 Binder 链路。
