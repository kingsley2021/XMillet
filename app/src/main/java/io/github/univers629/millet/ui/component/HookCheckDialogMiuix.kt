package io.github.univers629.millet.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.millet.core.HookInstallRegistry
import io.github.univers629.millet.core.RomCapabilities
import io.github.univers629.millet.core.RootHelper
import io.github.univers629.millet.core.VersionGate
import io.github.univers629.millet.core.XposedPrefsBridge
import io.github.univers629.millet.ipc.HookCoverage
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Close2
import top.yukonga.miuix.kmp.icon.glass.Error
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * Hook 点位与系统环境状态等级。
 */
enum class HookDiagnosticLevel {
    PASSED,
    WARNING,
    FAILED
}

/**
 * 单个 Hook 点位或运行环境的检测条目模型。
 */
data class HookDiagnosticEntry(
    val id: String,
    val title: String,
    val targetClassOrMethod: String,
    val level: HookDiagnosticLevel,
    val summaryText: String,
    val detailText: String,
    val suggestion: String? = null,
    /**
     * 是否属于辅助信息。可选项不计入顶部通过数与整体状态；
     * 官方策略链路、安装账本与真实冻结证据不能作为可选项排除。
     */
    val optional: Boolean = false,
    /**
     * 面向流式检测日志与用户截图反馈的紧凑技术明细行（如两侧覆盖比、能力位、缺失/失败点位 ID）。
     */
    val consoleLines: List<String> = emptyList(),
    /**
     * 通过项也需要显示建议时置 true（如 Doze：观测点齐备，但上次深睡被系统拒绝）。
     * 默认 false，避免通过项堆满建议文案。
     */
    val suggestionWhenPassed: Boolean = false,
)

private fun triFlag(value: Boolean?): String = when (value) {
    true -> "1"
    false -> "0"
    null -> "?"
}

private fun formatWireBrief(label: String, wire: HookInstallRegistry.Wire): String {
    if (!wire.hasData) return "$label: 未上报"
    return buildString {
        append(label).append(": ").append(wire.installed).append('/').append(wire.total)
        if (wire.failed.isNotEmpty()) {
            append(" | 失败=").append(wire.failed.joinToString(",") { describeHookId(it) })
        }
        if (wire.unsupported.isNotEmpty()) {
            append(" | 未支持=").append(wire.unsupported.joinToString(",") { describeHookId(it) })
        }
        if (wire.gated.isNotEmpty()) {
            append(" | 门控=").append(wire.gated.joinToString(",") { describeHookId(it) })
        }
    }
}

/**
 * 点位编号到宿主方法名的对照表。
 *
 * 覆盖列表原始值仅含 `S22`、`P28_NO_RESTRICT` 等编号；此处将 73 个点位映射为
 * 「编号(类#方法)」，便于诊断页在未支持/失败项中直接显示对应的宿主类与方法。
 * 新增点位时同步补一行即可，缺条目会退化成裸编号。
 */
internal val HOOK_ID_TARGET_HINTS: Map<String, String> = mapOf(
    "P1" to "PowerKeeperApplication#onCreate",
    "P2" to "MilletPolicy#<init>",
    "P3" to "MilletPolicy#dealCloudWhiteList",
    "P4" to "MilletPolicy#onCloudWhiteListChanged",
    "P5" to "MilletPolicy#dealNoRestrictApp",
    "P6" to "MilletPolicy#dealSleepModeWhiteList",
    "P7" to "CommonAdapter#addPowerSaveWhitelistApps",
    "P8" to "CommonAdapter#removePowerSaveWhitelistApps",
    "P9" to "MilletHandler#handleMessage",
    "P10" to "MilletBrightHandler#handleMessage",
    "P11" to "PowerCheckerController#clearApp",
    "P12" to "PowerStateMachine#clearAppWhenScreenOffTimeOut",
    "P13" to "PowerStateMachine#clearAppWhenScreenOffTimeOutInNight",
    "P14" to "PowerStateMachine#clearUnactiveApps",
    "P17" to "FreezeBinder#<init>",
    "P18" to "PowerKeeperManager#<init>",
    "P20" to "BrightScenarioBase#<init>",
    "P21_KILL" to "ProcessManager#kill",
    "P22_KILL_POLICY" to "KillProcessController#shouldKillByCheckerPolicy",
    "P23_FROZEN_DECISION" to "FrozenAppController#appIsAllowToFrozen",
    "P24_NO_CHARGE_THAW" to "EventsAggregator#notifyForCharging",
    "P25_AUTO_KILL" to "PowerCheckerController#autoKillApp",
    "P26_AUDIO_KILL" to "AudioDisguiseController#killApp",
    "P27" to "GlobalFeatureConfigureHelper#updateGlobalConfigure",
    "P28_NO_RESTRICT" to "ActiveStateController#dealNoRestrictApp",
    "P29" to "MilletUtils#isAppUid",
    "P30_CLOUD_DOZE_READ" to "GlobalFeatureConfigureHelper#getDozeWhiteListApps",
    "P31_USER_POWER_SAVE_CONFIG" to "PowerSaveConfigureManager#setPowerSaveAppConfigure",
    "P32_GET_POWER_SAVE_CONFIG" to "PowerSaveConfigureManager#getPowerSaveAppConfigure",
    "P33_CHARGE_STRATEGY" to "FrozenAppController#setChangeFrozenStrategy",
    "P34" to "AppClusterController#isControlApp",
    "P35_FAST_SCREEN_OFF_FREEZE" to "AppStateFrozenControl#frozenAppLater",
    "P36_BRIGHT_FROZEN_THAW" to "BrightController#frozenThaw",
    "S1" to "ActivityManagerService#registerReceiverWithFeature",
    "S2" to "GreezeManagerService#<init>",
    "S2_Gate" to "GreezeManagerService#<init>_Lifecycle",
    "S3" to "AurogonImmobulusMode#<init>",
    "S4" to "ProcessList#addProcessNameLocked",
    "S5" to "ProcessErrorStateRecord#appNotResponding",
    "S6" to "GreezeManagerService#freezeUids",
    "S7" to "GreezeManagerService#freezeAction",
    "S8" to "AurogonImmobulusMode#checkAppStatusForFreeze",
    "S9" to "BroadcastSkipPolicy#shouldSkipMessage",
    "S10" to "BroadcastQueue#deliverToRegisteredReceiverLocked",
    "S11" to "ActivityManagerService#setSystemProcess",
    "S12" to "DeviceIdleController#onStart",
    "S13" to "DeviceIdleController#motionLocked",
    "S14" to "DeviceIdleController#moveToStateLocked",
    "S15" to "ProcessList#removeProcessNameLocked",
    "S16" to "ProcessList#handleProcessStartedLocked",
    "S17" to "GreezeManagerService#freezeProcess",
    "S18" to "GreezeManagerService#thawProcess",
    "S19" to "AnrHelper#appNotResponding/deferAppNotResponding",
    "S20" to "GreezeManagerService#thawUid",
    "S21" to "GreezeManagerService#thawUids",
    "S21_WAKELOCK" to "PowerManagerService#acquireWakeLockInternal",
    "S22" to "GreezeManagerService#dispatchThawEvent",
    "S22_KILL" to "ProcessManager#kill",
    "S23" to "GreezeManagerService#freezePids",
    "S24" to "GreezeManagerService#isAllowBroadcast",
    "S25" to "GreezeManagerService#deferBroadcastForMiui",
    "S26" to "GreezeManagerService#thawUidAsync",
    "S27" to "GreezeManagerService#thawAll",
    "S28" to "AurogonImmobulusMode#isUidValid",
    "S29" to "GreezeManagerService#checkStateForScrOff",
    "S30" to "GreezeManagerService#notifyExcuteServices",
    "S31" to "AurogonImmobulusMode#updateTargetList",
    "S32_CLOUD_FROZEN_WL" to "ListAppsManager#dealCloudWhiteList",
    "S33_POLICY_ALLOW_FREEZE" to "PolicyMaker#isAllowFreeze",
    "S34" to "DeviceIdleController#isUpcomingAlarmClock",
    "S38_DOZE_BECOME_ACTIVE" to "DeviceIdleController#becomeActiveLocked",
    "S39_DOZE_STEP_IDLE" to "DeviceIdleController#stepIdleStateLocked",
    "S40_DOZE_EVENT" to "DeviceIdleController#addEvent",
    "S41_ALARM_ADJUST_IDLE_UNTIL" to "AlarmManagerService#adjustIdleUntilTime",
    "S42_DOZE_CONSTRAINTS" to "DeviceIdleController#updateActiveConstraintsLocked",
)

private fun describeHookId(id: String): String {
    val hint = HOOK_ID_TARGET_HINTS[id]
    return if (hint != null) "$id($hint)" else id
}

/**
 * 依据当前运行时状态快照与系统环境构建诊断条目列表。
 */
fun generateHookDiagnostics(
    runtimeStatus: PowerKeeperBinderClient.RuntimeStatusSnapshot,
    kernelFreezer: RootHelper.KernelFreezer = RootHelper.KernelFreezer.UNKNOWN,
    frameworkState: XposedPrefsBridge.XposedFrameworkState = XposedPrefsBridge.XposedFrameworkState(),
    kernelEvidenceConfirmed: Boolean = false,
    kernelEvidenceSource: String = "UNCONFIRMED",
    milletBackendState: String = "UNCONFIRMED"
): List<HookDiagnosticEntry> {
    val entries = ArrayList<HookDiagnosticEntry>()
    val coverage = runtimeStatus.hookCoverage
    // 注册结果与宿主类能力位分开；旧模块未上报时保持未确认。
    val greezeRegistered = coverage.capability("greeze_registered")
    val milletRegistered = coverage.capability("millet_registered")
    val controllerRegistered = coverage.capability("controller_registered")
    val bothSidesReported = hasPositiveCoverage(coverage.sys) && hasPositiveCoverage(coverage.pk)

    // 0. 运行环境：宿主 ROM 决定了哪些点位本来就存在，必须先摆出来，
    //    否则用户会把「本版本没有这个子系统」误读成「模块装坏了」。
    val romTierLabel = VersionGate.romTier?.name ?: "UNKNOWN"
    entries.add(
        HookDiagnosticEntry(
            id = "run_env",
            title = "运行环境",
            targetClassOrMethod = "ro.mi.os.version.name / ro.miui.ui.version.name",
            level = if (VersionGate.romTier == null) {
                HookDiagnosticLevel.WARNING
            } else {
                HookDiagnosticLevel.PASSED
            },
            summaryText = VersionGate.describeRom(),
            detailText = when (VersionGate.romTier) {
                VersionGate.RomTier.MIUI14 ->
                    "MIUI 14 按旧版 Millet 路径适配，P4 云名单热更新 Hook 使用与澎湃 OS1 相同的版本门控。" +
                        "双开相关 notifyExcuteServices 与 updateTargetList Hook 按宿主方法和能力条件安装；" +
                        "各点位的安装结果见覆盖情况。"
                VersionGate.RomTier.OS1, VersionGate.RomTier.OS2 ->
                    "澎湃 OS 早期版本按 com.miui.powerkeeper.millet 子系统适配云控白名单、灭屏清理与无限制名单；本机注册结果见下方检测项。"
                VersionGate.RomTier.OS3 ->
                    "澎湃 OS3 的适配路径使用 KillProcessController 与 FrozenAppController 等新入口，不要求已移除的 com.miui.powerkeeper.millet 旧点位；" +
                        "具体宿主能力与注册结果仍以本机上报为准。"
                VersionGate.RomTier.OS4 ->
                    "澎湃 OS4 移除了 PowerKeeper 侧的 FrozenAppController 与 PowerCheckerController，冻结决策收归系统侧 Greeze/ListAppsManager，" +
                        "PowerKeeper 侧由 KillProcessController 与 ActiveStateController 承担；具体注册结果以本机上报为准。"
                null ->
                    "未能读取 ro.mi.os.version.name 与 ro.miui.ui.version.name，无法判断宿主版本；下面的条目按运行时探测结果展示。"
            } + "版本属性不能证明官方冻结链路已注册或内核冻结已完成。",
            consoleLines = listOf("档位=$romTierLabel | 属性: miOs=${VersionGate.miOsVersionName.ifBlank { "-" }}, miui=${VersionGate.miuiVersionName.ifBlank { "-" }}"),
        )
    )

    // 0.5 LibXposed 框架服务能力、作用域与热重载状态
    if (frameworkState.isServiceConnected) {
        val missingScopes = frameworkState.missingScopes
        val staleTargets = frameworkState.staleTargets
        val fwLevel = when {
            !frameworkState.supportsRemotePrefs -> HookDiagnosticLevel.FAILED
            missingScopes.isNotEmpty() || staleTargets.isNotEmpty() -> HookDiagnosticLevel.WARNING
            else -> HookDiagnosticLevel.PASSED
        }
        val targetLine = if (frameworkState.runningTargets.isNotEmpty()) {
            "目标进程: " + frameworkState.runningTargets.joinToString(", ") { t ->
                "${t.processName}=${t.state.name}(v${t.loadedVersionCode})"
            }
        } else {
            null
        }
        val capsSummary = buildString {
            append("${frameworkState.frameworkName} ${frameworkState.frameworkVersion} (API ${frameworkState.apiVersion})")
            append(" | 远程配置=${if (frameworkState.supportsRemotePrefs) "支持" else "不支持"}")
            append(", 系统作用域=${if (frameworkState.supportsSystemHook) "支持" else "不支持"}")
            append(", 作用域=${if (frameworkState.scope.isEmpty()) "空" else frameworkState.scope.joinToString(",")}")
            if (targetLine != null) append(" | ").append(targetLine)
        }
        val fwSuggestion = when {
            !frameworkState.supportsRemotePrefs -> "更新 LSPosed 到支持 RemotePreferences 的版本后点「重新检测」"
            missingScopes.isNotEmpty() -> "作用域缺少 ${missingScopes.joinToString("、")}，可点击下方「授权作用域」"
            staleTargets.isNotEmpty() -> "检测到 ${staleTargets.size} 个目标进程仍运行旧代代码，可点击下方「热重载」无需重启更新"
            else -> null
        }
        entries.add(
            HookDiagnosticEntry(
                id = "libxposed_service",
                title = "LibXposed 框架服务",
                targetClassOrMethod = "XposedService (API ${frameworkState.apiVersion})",
                level = fwLevel,
                summaryText = when {
                    !frameworkState.supportsRemotePrefs -> "缺少远程配置能力"
                    missingScopes.isNotEmpty() -> "作用域缺失 ${missingScopes.size} 项"
                    staleTargets.isNotEmpty() -> "${staleTargets.size} 个进程待热重载"
                    else -> "能力与作用域正常"
                },
                detailText = capsSummary,
                suggestion = fwSuggestion,
                optional = fwLevel != HookDiagnosticLevel.FAILED,
                consoleLines = buildList {
                    add("${frameworkState.frameworkName} ${frameworkState.frameworkVersion} (API ${frameworkState.apiVersion}) | 远程配置=${if (frameworkState.supportsRemotePrefs) "1" else "0"} | 作用域=[${frameworkState.scope.joinToString(",")}]")
                    if (targetLine != null) add(targetLine)
                },
            )
        )
    }

    // 1. System Server 核心服务
    val sysLevel = when {
        runtimeStatus.binderAlive && runtimeStatus.greezeReady && greezeRegistered == true &&
            hasPositiveCoverage(coverage.sys) -> HookDiagnosticLevel.PASSED
        runtimeStatus.binderAlive -> HookDiagnosticLevel.WARNING
        else -> HookDiagnosticLevel.FAILED
    }
    val sysGapReason = when {
        !runtimeStatus.binderAlive -> "缺口: system_server Binder 未建立"
        !runtimeStatus.greezeReady -> "缺口: GreezeManagerService 实例尚未就绪 (greezeReady=false)"
        greezeRegistered != true -> "缺口: Greeze 关键点位未确认 (greeze_reg=${triFlag(greezeRegistered)})"
        !hasPositiveCoverage(coverage.sys) -> "缺口: 系统侧账本异常 (${formatWireBrief("sys", coverage.sys)})"
        else -> null
    }
    entries.add(
        HookDiagnosticEntry(
            id = "system_server",
            title = "System Server 核心服务",
            targetClassOrMethod = "android / ActivityManagerService & GreezeManagerService",
            level = sysLevel,
            summaryText = when (sysLevel) {
                HookDiagnosticLevel.PASSED -> "服务与官方 Hook 已注册"
                HookDiagnosticLevel.WARNING -> "Binder 在线，官方链路待确认"
                HookDiagnosticLevel.FAILED -> "未连接系统服务"
            },
            detailText = when (sysLevel) {
                HookDiagnosticLevel.PASSED -> "SystemBinderService 在线，Greeze 实例与关键 Hook 注册结果已上报；本项不证明冻结成功。"
                HookDiagnosticLevel.WARNING -> "SystemBinderService 已建立通信通道，但 Greeze 实例或关键 Hook 注册结果未确认。Binder 在线不等同于冻结链路可用。"
                HookDiagnosticLevel.FAILED -> when {
                    frameworkState.isServiceConnected && !frameworkState.hasSystemScope ->
                        "XposedService 报告作用域尚未勾选「system（系统框架）」。点击下方「授权作用域」勾选即可；系统框架只在下次重启时注入，这一步由 LSPosed 决定，模块侧没有额外操作。"
                    !frameworkState.isServiceConnected ->
                        "未能连接 XposedService：确认 LSPosed 已启用、模块未被停用且未处于安全模式。启用后 system_server 会在下次启动时注入。"
                    else ->
                        "作用域已包含系统框架，但 system_server 尚未完成注入。点下方「热重载」可立即重装钩子（无需重启）；若仍失败，查看 /data/system/XMillet/log 中的注入错误。"
                }
            },
            suggestion = if (sysLevel == HookDiagnosticLevel.FAILED) {
                when {
                    frameworkState.isServiceConnected && !frameworkState.hasSystemScope ->
                        "点下方「授权作用域」，勾选后由 LSPosed 在下次重启时完成注入"
                    !frameworkState.isServiceConnected ->
                        "确认 LSPosed 与模块均处于启用状态"
                    frameworkState.staleTargets.isNotEmpty() ->
                        "点下方「热重载」重装 system_server 钩子，无需重启"
                    else ->
                        "查 /data/system/XMillet/log 中「Hook 安装结果」一行定位失败点位"
                }
            } else null,
            consoleLines = buildList {
                add(
                    "Binder=${if (runtimeStatus.binderAlive) "在线" else "离线"} | " +
                        "Greeze=${if (runtimeStatus.greezeReady) "就绪" else "未就绪"} | " +
                        "Aurogon=${if (runtimeStatus.aurogonReady) "就绪" else "未就绪"} | " +
                        "Doze=${if (runtimeStatus.dozeReady) "就绪" else "未就绪"} | " +
                        "greezeReg=${triFlag(greezeRegistered)}"
                )
                if (sysGapReason != null) add(sysGapReason)
            },
        )
    )

    // 2. 电量与性能 PowerKeeper 策略
    //    目标子系统随 ROM 版本不同：OS3 起 millet 已移除，OS4 进一步移除 FrozenAppController / PowerCheckerController。
    val capMillet = coverage.capability(HookCoverage.CAP_MILLET)
    val capController = coverage.capability(HookCoverage.CAP_CONTROLLER)
    val capFrozenCtrl = coverage.capability(HookCoverage.CAP_FROZEN_CTRL)
    val capMigard = coverage.capability(HookCoverage.CAP_MIGARD)
    val policyRegistered = when {
        milletRegistered == true || controllerRegistered == true -> true
        milletRegistered == false && controllerRegistered == false -> false
        else -> null
    }
    val officialChainConfirmed = bothSidesReported && greezeRegistered == true &&
        policyRegistered == true && runtimeStatus.greezeReady && runtimeStatus.powerKeeperConnected
    val pkTarget = when {
        milletRegistered == true -> "com.miui.powerkeeper.millet.MilletPolicy & FreezeBinder"
        controllerRegistered == true && capFrozenCtrl == false ->
            "com.miui.powerkeeper.controller.KillProcessController & ActiveStateController"
        controllerRegistered == true ->
            "com.miui.powerkeeper.controller.FrozenAppController & KillProcessController"
        capMillet == true -> "com.miui.powerkeeper.millet.MilletPolicy & FreezeBinder"
        capMillet == false && capController == true && capFrozenCtrl == false ->
            "com.miui.powerkeeper.controller.KillProcessController & ActiveStateController"
        capMillet == false && capController == true ->
            "com.miui.powerkeeper.controller.KillProcessController & FrozenAppController"
        capMillet == false -> "com.miui.powerkeeper（本版本无 millet / KillProcessController 点位）"
        else -> "com.miui.powerkeeper（宿主能力位未上报）"
    }
    val pkLevel = when {
        runtimeStatus.binderAlive && officialChainConfirmed -> HookDiagnosticLevel.PASSED
        runtimeStatus.powerKeeperConnected -> HookDiagnosticLevel.WARNING
        runtimeStatus.binderAlive -> HookDiagnosticLevel.WARNING
        else -> HookDiagnosticLevel.FAILED
    }
    val pkCapLine = "握手=${if (runtimeStatus.powerKeeperConnected) "已连接" else "未连接"} | " +
        "能力: millet=${triFlag(capMillet)} ctrl=${triFlag(capController)} frozenCtrl=${triFlag(capFrozenCtrl)} migard=${triFlag(capMigard)} | " +
        "注册: milletReg=${triFlag(milletRegistered)} ctrlReg=${triFlag(controllerRegistered)}"
    val pkGapLine = when {
        pkLevel == HookDiagnosticLevel.PASSED -> null
        !runtimeStatus.binderAlive -> "缺口: system_server 未连接，无法接收 PowerKeeper 握手"
        !runtimeStatus.powerKeeperConnected ->
            if (frameworkState.isServiceConnected && !frameworkState.hasPowerKeeperScope) {
                "缺口: LSPosed 作用域未勾选 com.miui.powerkeeper"
            } else {
                "缺口: PowerKeeper 尚未向 system_server 完成回调握手"
            }
        !bothSidesReported ->
            "缺口: 两侧账本未齐备 (sys=${if (coverage.sys.hasData) "${coverage.sys.installed}/${coverage.sys.total}" else "未上报"}, pk=${if (coverage.pk.hasData) "${coverage.pk.installed}/${coverage.pk.total}" else "未上报"})"
        greezeRegistered != true ->
            "缺口: system_server 侧 Greeze 关键 Hook 未注册 (greezeReg=${triFlag(greezeRegistered)})"
        policyRegistered == false -> buildString {
            append("缺口: PowerKeeper 关键策略点位未注册")
            if (coverage.pk.failed.isNotEmpty()) {
                append(" | 失败=").append(coverage.pk.failed.joinToString(",") { describeHookId(it) })
            }
            val keyMissing = coverage.pk.unsupported.filter {
                it in setOf("P2", "P22_KILL_POLICY", "P23_FROZEN_DECISION", "P28_NO_RESTRICT", "P25_AUTO_KILL")
            }
            if (keyMissing.isNotEmpty()) {
                append(" | 宿主缺失=").append(keyMissing.joinToString(","))
            }
        }
        else -> "缺口: 注册能力位缺失或实例未就绪 (greezeReady=${runtimeStatus.greezeReady}, policyReg=${triFlag(policyRegistered)})"
    }
    entries.add(
        HookDiagnosticEntry(
            id = "powerkeeper",
            title = "电量与性能 PowerKeeper",
            targetClassOrMethod = pkTarget,
            level = pkLevel,
            summaryText = when (pkLevel) {
                HookDiagnosticLevel.PASSED -> when {
                    milletRegistered == true -> "Millet 策略链路已注册"
                    controllerRegistered == true && capFrozenCtrl == false -> "Controller(OS4) 策略链路已注册"
                    controllerRegistered == true -> "Controller 策略链路已注册"
                    else -> "官方策略链路已注册"
                }
                HookDiagnosticLevel.WARNING -> if (runtimeStatus.powerKeeperConnected) "已握手，官方链路未确认" else "等待双向握手"
                HookDiagnosticLevel.FAILED -> "未连接"
            },
            detailText = when (pkLevel) {
                HookDiagnosticLevel.PASSED -> when {
                    milletRegistered == true ->
                        "Millet 关键 Hook 注册结果与两侧安装账本已上报，PowerKeeper 已握手；冻结行为仍须由真实进程证据确认。"
                    controllerRegistered == true && capFrozenCtrl == false ->
                        "OS4 Controller 路径（KillProcessController / ActiveStateController）关键 Hook 与两侧账本已上报，本宿主无 FrozenAppController 属预期；冻结行为仍须由真实进程证据确认。"
                    controllerRegistered == true ->
                        "Controller 替代路径的关键 Hook 注册结果与两侧账本已上报。此处不要求已移除的旧 Millet 点位，也不证明冻结成功。"
                    else ->
                        "已与「电量与性能」建立双向 Binder 回调；具体接管了哪些点位见下方安装点覆盖。"
                }
                HookDiagnosticLevel.WARNING -> if (runtimeStatus.powerKeeperConnected) {
                    when {
                        !bothSidesReported -> "PowerKeeper 已握手，但两侧有效安装账本未齐备，不能确认官方冻结链路。"
                        greezeRegistered == false || policyRegistered == false ->
                            "PowerKeeper 已握手，但关键官方 Hook 未注册或不受当前宿主支持。缺失与版本门控原因见安装点覆盖；类存在和握手不能替代注册结果。"
                        else -> "PowerKeeper 已握手，但官方 Greeze / 策略链路的注册结果或实例尚未确认；旧模块缺少注册字段时不按通过处理。"
                    }
                } else if (frameworkState.isServiceConnected && !frameworkState.hasPowerKeeperScope) {
                    "XposedService 报告作用域尚未勾选「com.miui.powerkeeper」，授权后强行停止电量与性能即可接入。"
                } else {
                    "系统服务已就绪，但「电量与性能」尚未完成双向 Binder 回调注册。亮灭屏一次或强行停止电量与性能即可自动握手。"
                }
                HookDiagnosticLevel.FAILED ->
                    "未能连接至 com.miui.powerkeeper。请在 LSPosed 中确认勾选「电量与性能」作用域。"
            },
            suggestion = when (pkLevel) {
                HookDiagnosticLevel.WARNING -> if (runtimeStatus.powerKeeperConnected) {
                    if (policyRegistered == false && coverage.pk.failed.isEmpty() && coverage.pk.unsupported.isNotEmpty()) {
                        "当前系统可能存在未适配的 PowerKeeper 类/方法变更，请点左上角复制报告或直接截图本页反馈"
                    } else {
                        "点「重新检测」；仍为未确认时用「热重载」重装两侧钩子（无需重启），或亮灭屏一次等待双向回调重建"
                    }
                } else if (frameworkState.isServiceConnected && !frameworkState.hasPowerKeeperScope) {
                    "可点击下方「授权作用域」"
                } else {
                    "请按电源键灭屏并亮屏一次，或在设置中结束「电量与性能」"
                }
                HookDiagnosticLevel.FAILED -> "请在 LSPosed 作用域中勾选「电量与性能」"
                else -> null
            },
            consoleLines = buildList {
                add(pkCapLine)
                if (pkGapLine != null) add(pkGapLine)
            },
        )
    )

    // 3. Hook 安装点覆盖：两侧进程各自记账、汇总后展示真实数量
    entries.add(buildCoverageEntry(runtimeStatus))

    // 4. 小米 Greeze 墓碑驱动服务
    val greezeLevel = when {
        runtimeStatus.greezeReady && greezeRegistered == true && hasPositiveCoverage(coverage.sys) -> HookDiagnosticLevel.PASSED
        runtimeStatus.binderAlive -> HookDiagnosticLevel.WARNING
        else -> HookDiagnosticLevel.FAILED
    }
    entries.add(
        HookDiagnosticEntry(
            id = "greeze_service",
            title = "小米 Greeze 墓碑驱动服务",
            targetClassOrMethod = "com.miui.server.greeze.GreezeManagerService",
            level = greezeLevel,
            summaryText = if (greezeLevel == HookDiagnosticLevel.PASSED) "句柄与 Hook 已注册" else "官方服务未确认",
            detailText = if (greezeLevel == HookDiagnosticLevel.PASSED)
                "GreezeManagerService 实例已捕获，关键 Hook 注册结果已上报；句柄存在不能证明驱动冻结成功，也不说明具体内核机制。"
            else
                "GreezeManagerService 实例或关键 Hook 注册结果尚未确认。先点「重新检测」；仍为未确认时用「热重载」重装 system_server 钩子（无需重启）。不依据第三方内核名称或 ROM 属性判定冻结能力。"
        )
    )

    // 5. Aurogon 神隐模式监听
    val aurogonLevel = if (runtimeStatus.aurogonReady) {
        HookDiagnosticLevel.PASSED
    } else if (runtimeStatus.binderAlive) {
        HookDiagnosticLevel.WARNING
    } else {
        HookDiagnosticLevel.FAILED
    }
    entries.add(
        HookDiagnosticEntry(
            id = "aurogon_mode",
            title = "Aurogon 神隐模式 (前台/音视频感知)",
            targetClassOrMethod = "com.miui.server.greeze.AurogonImmobulusMode",
            level = aurogonLevel,
            summaryText = if (aurogonLevel == HookDiagnosticLevel.PASSED) "已就绪" else "待捕获",
            detailText = if (aurogonLevel == HookDiagnosticLevel.PASSED)
                "Aurogon 实例已捕获；本项仅反映句柄就绪，不证明前台感知、豁免或冻结行为已验证。"
            else
                "Aurogon 实例尚未捕获；本项未验证音视频与前台服务免冻行为。"
        )
    )

    // 6. Doze 深度睡眠状态机与省电白名单
    val dozeLevel = if (runtimeStatus.dozeReady) {
        HookDiagnosticLevel.PASSED
    } else if (runtimeStatus.binderAlive) {
        HookDiagnosticLevel.WARNING
    } else {
        HookDiagnosticLevel.FAILED
    }
    val dozeObservation = runtimeStatus.hookCoverage.capability(HookCoverage.CAP_DOZE_OBS)
    val dozeMissing = runtimeStatus.hookCoverage.missingOf(RomCapabilities.DOZE_OBSERVATION_HOOKS)
    val dozeObservationText = when (dozeObservation) {
        true -> "深睡观测点 S14/S38/S39/S40 齐备。"
        false -> "深睡观测点缺 ${dozeMissing.joinToString("、") { describeHookId(it) }}，会话与维护次数可能漏记。"
        null -> "深睡观测点未上报。"
    }
    val dozeSummary = when {
        dozeLevel != HookDiagnosticLevel.PASSED -> "待捕获"
        dozeObservation == false -> "实例已捕获 · 观测点缺失 ${dozeMissing.size} 项"
        dozeObservation == true -> "实例已捕获 · 观测点齐备"
        else -> "实例已捕获"
    }
    // 最近一次主动深睡的结果由 system_server 经运行状态事务带回，
    // 失败时直接在检查页展示原因，无需读取 /data/system/XMillet/log。
    val dozeFailureHint = runtimeStatus.dozeDiagnostics
        ?.takeIf { it.lastAttemptFailed }
        ?.let { diagnostics ->
            val detail = diagnostics.failureDetail.ifBlank { "状态机拒绝了深睡" }
            val age = diagnostics.failureAgeText(System.currentTimeMillis())
            if (age == null) detail else "$detail（$age）"
        }
    // 仅在熄屏状态或存在阻塞约束时展示实时状态，亮屏且无阻塞时不追加提示。
    val liveDoze = runtimeStatus.liveDoze
    val liveDozeHint = liveDoze
        ?.takeIf { !it.screenOn || it.blockingConstraints > 0 }
        ?.summarySuffix()
    val liveDozeDetail = liveDoze?.let {
        buildString {
            append("实时：${it.stateName}")
            if (it.forced) append(" · 强制标记")
            append(if (it.screenOn) " · 亮屏" else " · 熄屏")
            append(if (it.charging) " · 充电" else " · 未充电")
            append(" · 阻塞 ${it.blockingConstraints}")
            if (it.blockerNames.isNotEmpty()) append("（${it.blockerNames.joinToString("、")}）")
        }
    }
    val dozeSummaryText = buildList {
        add(dozeSummary)
        liveDozeHint?.let { add(it) }
        if (dozeFailureHint != null) add("上次深睡未成功")
    }.joinToString(" · ")
    entries.add(
        HookDiagnosticEntry(
            id = "doze_controller",
            title = "Doze 状态机与省电优化白名单",
            targetClassOrMethod = "com.android.server.DeviceIdleController",
            level = dozeLevel,
            summaryText = dozeSummaryText,
            detailText = buildString {
                if (dozeLevel == HookDiagnosticLevel.PASSED) {
                    append("DeviceIdleController 实例已捕获，$dozeObservationText")
                    append("本项不证明状态迁移或名单同步行为已验证，也不能替代冻结证据。")
                } else {
                    append("DeviceIdleController 待捕获。")
                }
                liveDozeDetail?.let { append(it) }
                if (dozeFailureHint != null) append("最近一次深睡失败：$dozeFailureHint")
            },
            suggestion = when {
                dozeLevel != HookDiagnosticLevel.PASSED ->
                    "点「重新检测」；仍待捕获时用「热重载」重装 system_server 钩子，无需重启"
                dozeFailureHint != null ->
                    "最近一次深睡失败：$dozeFailureHint。10 分钟内有系统闹钟、正在充电或官方省电策略未放行都会拦下深睡，" +
                        "排除后点「重新检测」；反复被闹钟窗口拦下时可在「Doze 深度睡眠策略」开启「闹钟后补试深睡」。"
                liveDoze != null && liveDoze.blockingConstraints > 0 ->
                    "当前有 ${liveDoze.blockingConstraints} 项约束在拦深睡" +
                        if (liveDoze.blockerNames.isEmpty()) "，可到「Doze 深度睡眠策略」检查开关。"
                        else "（${liveDoze.blockerNames.joinToString("、")}），熄屏后仍被拦住时优先排查这几项，" +
                            "或在「Doze 深度睡眠策略」开启「闹钟后补试深睡」。"
                else -> null
            },
            suggestionWhenPassed = dozeFailureHint != null || (liveDoze?.blockingConstraints ?: 0) > 0,
        )
    )

    // 后端运行状态独立于模块 Hook 安装结果和启动加速冻结证据，始终是必要项。
    entries.add(
        HookDiagnosticEntry(
            id = "millet_backend",
            title = "官方 Millet 后端运行状态",
            targetClassOrMethod = "GreezeManagerService / Millet 配置与 monitor 注册",
            level = when (milletBackendState) {
                "READY" -> HookDiagnosticLevel.PASSED
                "DISABLED", "MONITORS_UNREADY" -> HookDiagnosticLevel.FAILED
                else -> HookDiagnosticLevel.WARNING
            },
            summaryText = when (milletBackendState) {
                "READY" -> "官方冻结路径已启用，所需 monitors 已注册"
                "DISABLED" -> "官方 Millet 后端未启用"
                "MONITORS_UNREADY" -> "所需 monitors 尚未完整注册"
                else -> "官方 Millet 后端运行状态未确认"
            },
            detailText = "按需读取 ROM 的 Millet 总开关、PowerMillet/传统 Greeze 独立路径开关及 mRegisteredMonitor 与 MILLET_MONITOR_ALL 掩码。" +
                "确认路径要求 Millet 与 PowerMillet 启用且所需 monitors 完整注册；不要求传统 Greeze 开关同时开启。" +
                "只有传统 Greeze 开关开启不能证明官方后台策略已运行，未核实的替代路径保持未确认。" +
                "缺字段、类型不符、旧端未上报或读取失败均不能通过；模块 Hook 安装、服务实例存在或启动加速能够冻结不能替代本项。" +
                "monitor 注册是用户空间运行状态，不宣称内核握手成功或后续调度必然生效；真实冻结能力仍须另项确认。",

            suggestion = when (milletBackendState) {
                "DISABLED" -> "在系统设置的省电与电池里开启官方省电策略后点「重新检测」"
                "MONITORS_UNREADY" -> "点「重新检测」；仍不齐备时结束「电量与性能」进程，让系统重新注册 monitors"
                else -> null
            },
            consoleLines = listOf("后端状态=$milletBackendState"),
        )
    )

    // 7. 内核证据须由检测器关联官方记账中的同一应用 PID；类型字段与注册结果不能替代证据。
    val confirmedKernelFreezer = kernelEvidenceConfirmed && when (kernelFreezer) {
        RootHelper.KernelFreezer.CGROUP_V2 -> kernelEvidenceSource in setOf("CGROUP_V2_FROZEN", "WCHAN_V2")
        RootHelper.KernelFreezer.CGROUP_V1 -> kernelEvidenceSource in setOf("CGROUP_V1_FROZEN", "WCHAN_V1")
        else -> false
    }
    val cgroupLevel = if (confirmedKernelFreezer) HookDiagnosticLevel.PASSED else HookDiagnosticLevel.WARNING
    entries.add(
        HookDiagnosticEntry(
            id = "kernel_freezer",
            title = "官方记账关联的内核冻结证据",
            targetClassOrMethod = when (kernelFreezer) {
                RootHelper.KernelFreezer.CGROUP_V2 -> "cgroup.events:frozen / do_freezer_trap"
                RootHelper.KernelFreezer.CGROUP_V1 -> "freezer.state:FROZEN / __refrigerator"
                else -> "成员 cgroup 完成状态 / 精确 freezer wchan"
            },
            level = cgroupLevel,
            summaryText = when {
                confirmedKernelFreezer -> "已读取官方记账关联的冻结证据 ($kernelEvidenceSource)"
                kernelFreezer == RootHelper.KernelFreezer.NONE -> "未发现接口，冻结能力未确认"
                else -> "真实冻结能力未确认"
            },
            detailText = when {
                confirmedKernelFreezer ->
                    "模块只读 Greeze 当前冻结记账中的应用 UID，核对同一 PID 的 UID、启动时刻与成员 cgroup；来源=$kernelEvidenceSource。" +
                        "这仅确认官方记账与该进程真实冻结状态共现，不证明冻结由 Millet 触发、后续会自动调度或所有目标应用已冻结；未进行主动冻结测试。"
                kernelFreezer == RootHelper.KernelFreezer.NONE ->
                    "类型报告未发现接口，但不能据此证明内核不支持或 Greeze 存在替代机制。本项没有可确认的真实冻结证据。"
                else ->
                    "本次未收到与 Greeze 官方冻结记账 UID 及同一应用 PID 关联的完成冻结状态或精确 freezer wchan。" +
                        "无关联样本、读取权限受限、采样未覆盖与旧模块缺少证据字段均保持未确认；不相关 AOSP 缓存应用冻结或内核线程不能替代关联证据；" +
                        "目录、控制文件存在及 cgroup.freeze=1 请求值不算冻结成功，也不能判定内核必然不支持。"
            },
            suggestion = if (confirmedKernelFreezer) null else "保持后台省电策略运行，出现官方记账中的下一次冻结后点「重新检测」；本项只读现有记账，不做主动冻结，也不必为此重启",
            consoleLines = listOf("接口=${kernelFreezer.name} | 证据来源=$kernelEvidenceSource | 已确认=$confirmedKernelFreezer"),
        )
    )

    // 8. 当前墓碑冻结 UID 监测
    val frozenCount = runtimeStatus.frozenUids.distinct().size
    val cloudSummary = runtimeStatus.cloudLists?.let {
        "云名单=${it.dozeWhiteList.size}/${it.frozenNewWhiteList.size}/${it.launchRestrict.size}"
    } ?: "云名单=未上报"
    entries.add(
        HookDiagnosticEntry(
            id = "frozen_monitor",
            title = "Greeze 冻结记账",
            targetClassOrMethod = "GreezeManagerService#getFrozenUids",
            level = if (runtimeStatus.greezeReady) HookDiagnosticLevel.PASSED else HookDiagnosticLevel.WARNING,
            summaryText = "$frozenCount 个 UID 在记账中",
            detailText = if (frozenCount > 0)
                "Greeze 返回 $frozenCount 个记账 UID，不等同于已确认冻结的应用数；本项未测量 CPU。实际进程状态以快照为准。"
            else
                "当前记账列表为空；不能据此判断后台进程未冻结或系统冻结功能异常。",
            consoleLines = listOf("记账UID=$frozenCount | $cloudSummary"),
        )
    )

    return entries
}

private fun hasPositiveCoverage(wire: HookInstallRegistry.Wire): Boolean {
    val uninstalled = wire.unsupported + wire.gated + wire.failed
    return wire.hasData && wire.installed > 0 && wire.total >= wire.installed &&
        uninstalled.size == uninstalled.toSet().size &&
        wire.installed.toLong() + uninstalled.size == wire.total.toLong()
}

/**
 * 生成「Hook 安装点覆盖」条目。
 *
 * 数量来自两侧进程的真实账本（[io.github.univers629.millet.core.HookInstallRegistry]），
 * 而不是写死的常量。要求模块与 App 都较新才会两者齐备：缺数据时显示「未上报」，
 * 不把 0 当作结果。
 */
private fun buildCoverageEntry(
    runtimeStatus: PowerKeeperBinderClient.RuntimeStatusSnapshot
): HookDiagnosticEntry {
    val coverage = runtimeStatus.hookCoverage
    if (!coverage.hasData) {
        return HookDiagnosticEntry(
            id = "hook_coverage",
            title = "Hook 安装点覆盖",
            targetClassOrMethod = "HookInstallRegistry (system_server + powerkeeper)",
            level = HookDiagnosticLevel.WARNING,
            summaryText = "未上报",
            detailText = "当前连接的模块尚未回传安装点账本（该字段随模块升级加入）。" +
                "这里保持未知而不是显示 0，避免把「查不到」误报成「一个都没装上」。",
            suggestion = "更新模块后点「重新检测」；目标进程仍在跑旧代代码时点「热重载」，无需重启"
        )
    }

    val total = coverage.totalCount
    val installed = coverage.installedCount
    val failed = coverage.failedIds
    val bothSidesReported = hasPositiveCoverage(coverage.sys) && hasPositiveCoverage(coverage.pk)
    val level = if (bothSidesReported && failed.isEmpty()) HookDiagnosticLevel.PASSED else HookDiagnosticLevel.WARNING

    val detail = buildString {
        if (coverage.sys.hasData) {
            append("系统侧 ").append(coverage.sys.installed).append('/').append(coverage.sys.total)
        } else {
            append("系统侧 未上报")
        }
        append("，电量与性能侧 ")
        if (coverage.pk.hasData) {
            append(coverage.pk.installed).append('/').append(coverage.pk.total)
        } else {
            append("未上报")
        }
        val unsupported = coverage.unsupportedIds
        if (unsupported.isNotEmpty()) {
            append("。本版本没有对应宿主类/方法而跳过：")
                .append(unsupported.joinToString("、") { describeHookId(it) })
        }
        val gated = coverage.gatedIds
        if (gated.isNotEmpty()) {
            append("。版本门控不满足而跳过：")
                .append(gated.joinToString("、") { describeHookId(it) })
        }
        if (failed.isNotEmpty()) {
            append("。安装时报错：").append(failed.joinToString("、") { describeHookId(it) })
        }
        if (bothSidesReported && installed == total && unsupported.isEmpty() && gated.isEmpty() && failed.isEmpty()) {
            append("。上报点位均已注册，尚未验证各项运行行为。")
        }
        if (!bothSidesReported) {
            append("。两侧账本未齐备、数量无效或有效注册数为零，不能按全部通过处理。")
        }
    }

    val coverageLines = buildList {
        add(formatWireBrief("系统侧", coverage.sys))
        add(formatWireBrief("电量侧", coverage.pk))
        if (failed.isNotEmpty()) {
            add("失败目标: " + failed.joinToString(", ") { describeHookId(it) })
        }
    }

    return HookDiagnosticEntry(
        id = "hook_coverage",
        title = "Hook 安装点覆盖",
        targetClassOrMethod = "HookInstallRegistry (system_server + powerkeeper)",
        level = level,
        summaryText = "$installed/$total 个安装点已注册",
        detailText = detail,
        suggestion = when {
            failed.isNotEmpty() -> "安装报错点位：${failed.joinToString("、") { describeHookId(it) }}，可点左上角复制报告或查看 /data/system/XMillet/log"
            !bothSidesReported -> "核对系统侧与电量与性能侧的完整安装账本（若刚装包，可点热重载或结束电量与性能重连）"
            else -> null
        },
        consoleLines = coverageLines,
    )
}

@Composable
internal fun OverallStatusHeader(
    level: HookDiagnosticLevel,
    passedCount: Int,
    totalCount: Int
) {
    val dark = isInDarkTheme()
    val containerColor = when (level) {
        HookDiagnosticLevel.PASSED -> if (dark) Color(0xFF1B3B24) else Color(0xFFE4F8E9)
        HookDiagnosticLevel.WARNING -> if (dark) Color(0xFF3E2F1B) else Color(0xFFFFF2DC)
        HookDiagnosticLevel.FAILED -> if (dark) Color(0xFF381515) else Color(0xFFFDE4E4)
    }

    val iconColor = when (level) {
        HookDiagnosticLevel.PASSED -> Color(0xFF34C759)
        HookDiagnosticLevel.WARNING -> Color(0xFFFF9500)
        HookDiagnosticLevel.FAILED -> Color(0xFFFF3B30)
    }

    // 浅色模式下深色卡片会显得突兀，正文改用与底色配套的深色调。
    val titleColor = if (dark) {
        Color.White
    } else {
        when (level) {
            HookDiagnosticLevel.PASSED -> Color(0xFF164A29)
            HookDiagnosticLevel.WARNING -> Color(0xFF7A4A00)
            HookDiagnosticLevel.FAILED -> Color(0xFF8C1D18)
        }
    }
    val subtitleColor = titleColor.copy(alpha = 0.75f)

    val titleText = when (level) {
        HookDiagnosticLevel.PASSED -> "必要检测项均已确认"
        HookDiagnosticLevel.WARNING -> "存在未确认或未支持项"
        HookDiagnosticLevel.FAILED -> "存在检测失败项"
    }

    val tagText = when (level) {
        HookDiagnosticLevel.PASSED -> "检测通过"
        HookDiagnosticLevel.WARNING -> "待确认"
        HookDiagnosticLevel.FAILED -> "需核对"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .border(1.dp, iconColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (level) {
                    HookDiagnosticLevel.PASSED -> MiuixIcons.Glass.Ok
                    HookDiagnosticLevel.WARNING -> MiuixIcons.Glass.Error
                    HookDiagnosticLevel.FAILED -> MiuixIcons.Glass.Close2
                },
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(32.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = titleText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor
                    )
                    StatusTagMiuix(
                        label = tagText,
                        backgroundColor = iconColor.copy(alpha = 0.25f),
                        contentColor = iconColor
                    )
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = "$totalCount 项必要检测中通过 $passedCount 项；辅助项单独列示，不代表所有应用已冻结",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = subtitleColor
                )
            }
        }
    }
}

