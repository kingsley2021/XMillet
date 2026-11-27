package io.github.univers629.millet.system

import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.univers629.millet.core.ActiveImeTracker
import io.github.univers629.millet.core.HookInstallRegistry
import io.github.univers629.millet.core.HookMode
import io.github.univers629.millet.core.HookSpec
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.ModernHookInstaller
import io.github.univers629.millet.core.NoRestrictSync
import io.github.univers629.millet.core.PrefGroup
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.core.RomCapabilities
import io.github.univers629.millet.ipc.MilletIpcContract
import io.github.univers629.millet.ipc.SystemBinderService

/**
 * 作用域 `"android"`（system_server）全部安装点总装入口（S1–S34，下表仅列 S1–S20）。
 *
 * 安装点分母核对：
 * - S1 : ActivityManagerService#registerReceiverWithFeature (before)
 * - S2 : GreezeManagerService#<init> (after) —— 捕获句柄 + UpdateDialog 初始化闸门（2 条构造器 Hook）
 * - S3 : AurogonImmobulusMode#<init> (after)
 * - S4 : ProcessList#addProcessNameLocked (before)
 * - S5 : ProcessErrorStateRecord#appNotResponding (before, 反射 + 10 参回退)
 * - S6 : GreezeManagerService#freezeUids (after)
 * - S7 : GreezeManagerService#freezeAction (before)
 * - S8 : AurogonImmobulusMode#checkAppStatusForFreeze (before)
 * - S9 : BroadcastSkipPolicy#shouldSkipMessage (replace, SDK_INT > 33)
 * - S10: BroadcastQueue#deliverToRegisteredReceiverLocked (replace, SDK_INT <= 33) —— 与 S9 二选一
 * - S11: ActivityManagerService#setSystemProcess (before)
 * - S12: DeviceIdleController#onStart (after)
 * - S13: DeviceIdleController#motionLocked (before)
 * - S14: DeviceIdleController#moveToStateLocked (before + after)
 * - S15: ProcessList#removeProcessNameLocked (after, 无条件)
 * - S16: ProcessList#handleProcessStartedLocked (after, simplifyLog 门控)
 * - S17: GreezeManagerService#freezeProcess (after, simplifyLog 门控)
 * - S18: GreezeManagerService#thawProcess (after, simplifyLog 门控)
 * - S19: AnrHelper#appNotResponding / deferAppNotResponding (before, 遍历 getDeclaredMethods())
 * - S20: GreezeManagerService#thawUid (before)
 * - S34: DeviceIdleController#isUpcomingAlarmClock (before, 强制推进期间全状态短路)
 * - S41: AlarmManagerService#adjustIdleUntilTime (before, 强制深睡期间跳过提前唤醒裁切)
 * - S42: DeviceIdleController#updateActiveConstraintsLocked (after, 强制推进前置阶段清零阻塞约束计数)
 */
object SystemHooks {

    private const val CLASS_AMS = "com.android.server.am.ActivityManagerService"
    private const val CLASS_IAPPLICATION_THREAD = "android.app.IApplicationThread"
    private const val CLASS_IINTENT_RECEIVER = "android.content.IIntentReceiver"

    private fun registerPrefReloadCallback() {
        PrefReader.onSystemGroupReloaded = { changedKey, group, snapshot ->
            when (group) {
                PrefGroup.MILLET_FLAGS -> {
                    if (changedKey == PrefsKeys.SIMPLIFY_LOG && !snapshot.simplifyLog) {
                        SystemState.clearAllPidFreezeRecords()
                    }
                    if (changedKey == PrefsKeys.IS_LOG || changedKey == PrefsKeys.BATTERY_CHANGE_OBSERVE) {
                        BroadcastAndDozeHooks.updateBatteryObservation(snapshot.isLog || snapshot.batteryChangeObserve)
                    }
                    if (changedKey == PrefsKeys.FREEZE_STATS) {
                        FreezeStatsRecorder.onEnabledChanged(snapshot.freezeStats)
                    }
                    if (changedKey == PrefsKeys.FREEZE_DUAL_APP) {
                        SystemState.syncDualAppSupportToAurogon(snapshot.freezeDualApp)
                        if (snapshot.freezeDualApp) {
                            ForceFreezeBridge.applyAsync(force = true)
                        }
                    }
                    if (changedKey == PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST) {
                        SystemState.syncCloudFrozenWhiteListToListAppsManager(snapshot.blockCloudFrozenWhitelist)
                    }
                    if (changedKey == "*") {
                        SystemState.syncDualAppSupportToAurogon(snapshot.freezeDualApp)
                        SystemState.syncCloudFrozenWhiteListToListAppsManager(snapshot.blockCloudFrozenWhitelist)
                    }
                }

                PrefGroup.DOZE_FLAGS -> BroadcastAndDozeHooks.onDozeConfigChanged()

                PrefGroup.APP_SETS -> {
                    when (changedKey) {
                        PrefsKeys.MESSAGE_APP_SET,
                        PrefsKeys.FORCE_FREEZE_SET,
                        PrefsKeys.WHITE_APP_SET,
                        PrefsKeys.BLOCK_THAW_SET,
                        PrefsKeys.IGNORE_NETWORK_WAKE_SET,
                        "*" -> SystemState.syncMessageAppsToAurogon()
                    }
                    if (changedKey == PrefsKeys.WHITE_APP_SET) {
                        // 白名单变化后把条目同步进系统免冻结名单，避免 millet 内核侧再次冻结它们
                        Thread(
                            {
                                NoRestrictSync.sync(SystemState.systemContext, snapshot.whiteAppSet)
                            },
                            "XMillet-NoRestrictSync"
                        ).start()
                    }
                    if (changedKey == PrefsKeys.FORCE_FREEZE_SET) {
                        // 名单变化后立刻重下发，不必等下一次亮灭屏或重启
                        ForceFreezeBridge.applyAsync()
                    }
                }

                else -> Unit
            }
        }
    }

    private fun buildPreAnrSpecs(): List<HookSpec> = listOf(
        buildS1RegisterReceiverHook(),
        FreezeGateHooks.buildS2GreezeInitHook(),
        FreezeGateHooks.buildS3AurogonInitHook(),
        AnrAndProcessHooks.buildS4AddProcessNameHook(),
        AnrAndProcessHooks.buildS15RemoveProcessNameHook(),
        AnrAndProcessHooks.buildS16HandleProcessStartedHook(),
        FreezeGateHooks.buildS17FreezeProcessHook(),
        FreezeGateHooks.buildS18ThawProcessHook()
    )

    private fun buildPostAnrSpecs(classLoader: ClassLoader): List<HookSpec> = listOf(
        AnrAndProcessHooks.buildS5ProcessErrorAnrHook(),
        FreezeGateHooks.buildS6FreezeUidsHook(),
        FreezeGateHooks.buildS23FreezePidsHook(),
        FreezeGateHooks.buildS20ThawUidHook(),
        FreezeGateHooks.buildS21ThawUidsProbeHook(),
        FreezeGateHooks.buildS22DispatchThawProbeHook(),
        FreezeGateHooks.buildS7FreezeActionHook(),
        FreezeGateHooks.buildS8CheckAppStatusForFreezeHook(),
        FreezeGateHooks.buildS28DoubleAppUidValidHook(),
        FreezeGateHooks.buildS29ScrOffStateHook(),
        FreezeGateHooks.buildS30NotifyExecuteServicesHook(classLoader),
        FreezeGateHooks.buildS31AurogonUpdateTargetListHook(classLoader),
        FreezeGateHooks.buildS32CloudFrozenWhiteListHook(),
        FreezeGateHooks.buildS33PolicyMakerAllowFreezeHook(),
        BroadcastAndDozeHooks.buildS9BroadcastSkipPolicyHook(),
        BroadcastAndDozeHooks.buildS10BroadcastQueueDeliverHook(),
        FreezeGateHooks.buildS24AllowBroadcastHook(),
        FreezeGateHooks.buildS25DeferBroadcastHook(),
        FreezeGateHooks.buildS26ThawUidAsyncHook(),
        FreezeGateHooks.buildS27ThawAllHook(),
        BroadcastAndDozeHooks.buildS11AmsSetSystemProcessHook(),
        BroadcastAndDozeHooks.buildS12DeviceIdleOnStartHook(),
        BroadcastAndDozeHooks.buildS13DeviceIdleMotionLockedHook(),
        BroadcastAndDozeHooks.buildS14DeviceIdleMoveToStateHook(),
        BroadcastAndDozeHooks.buildS34DeviceIdleUpcomingAlarmHook(),
        BroadcastAndDozeHooks.buildS38DeviceIdleBecomeActiveHook(),
        BroadcastAndDozeHooks.buildS39DeviceIdleStepStateHook(),
        BroadcastAndDozeHooks.buildS40DeviceIdleEventHook(),
        BroadcastAndDozeHooks.buildS41AlarmAdjustIdleUntilHook(),
        BroadcastAndDozeHooks.buildS42DeviceIdleConstraintsHook(),
        FreezeGateHooks.buildS2GreezeLifecycleReadyHook(),
        FreezeGateHooks.buildWakeLockHook(),
        FreezeGateHooks.buildSystemProcessManagerKillHook()
    )

    /**
     * 在 system_server 中通过 Modern LibXposed (API 102) 安装全部系统侧 Hook。
     */
    fun installAllModern(xposed: XposedInterface, classLoader: ClassLoader) {
        SystemState.hasAurogonIsUidValid = RomCapabilities.hasMethod(
            "com.miui.server.greeze.AurogonImmobulusMode",
            "isUidValid",
            classLoader
        )
        registerPrefReloadCallback()

        // 1. 安装 S1..S4, S15, S16..S18
        ModernHookInstaller.installAll(
            xposed,
            classLoader,
            RomCapabilities.filterInstallable(buildPreAnrSpecs(), classLoader, "系统侧")
        )

        // 2. 安装 S19
        AnrAndProcessHooks.installS19AnrHelperHooksModern(xposed, classLoader)

        // 3. 安装 S5..S14, S20, S28..S31, 以及 S2_Gate
        ModernHookInstaller.installAll(
            xposed,
            classLoader,
            RomCapabilities.filterInstallable(buildPostAnrSpecs(classLoader), classLoader, "系统侧")
        )

        val s22Installed = HookInstallRegistry.snapshot()
            .any { it.id == "S22" && it.state == HookInstallRegistry.State.INSTALLED }
        FreezeStatsRecorder.setDispatchThawEventActive(s22Installed)

        if (PrefReader.systemPrefs.blockCloudFrozenWhitelist) {
            SystemState.syncCloudFrozenWhiteListToListAppsManager(block = true, classLoader = classLoader)
        }
        if (SystemState.isGreezeReady) {
            SystemState.syncMessageAppsToAurogon()
        }

        Logger.alwaysWarn("Hook 安装结果[系统侧]: ${HookInstallRegistry.summary()}")
    }

    /**
     * 热重载卸载前回收 system_server 侧的输入法观察者、亮灭屏/电量广播接收器、定时统计及 Binder 客户端回调。
     */
    fun onHotReloading() {
        runCatching { FreezeStatsRecorder.onHotReloading() }
        runCatching { ActiveImeTracker.onHotReloading() }
        runCatching { BroadcastAndDozeHooks.onHotReloading() }
        runCatching { SystemBinderService.onHotReloading() }
        runCatching { ForegroundState.stop() }
        runCatching { io.github.univers629.millet.core.UidPackageCache.shutdown() }
        PrefReader.onSystemGroupReloaded = null
    }

    /**
     * S1（PA(6)）：ActivityManagerService#registerReceiverWithFeature
     * 真机签名（9 参，返回 Intent）：
     * `(IApplicationThread, String, String, String, IIntentReceiver, IntentFilter, String, int, int) -> Intent`
     * 当调用方注册的 IntentFilter 包含 `"XMillet-Binder"` 时，构造伪粘性 Intent 并将
     * `SystemBinderService.binderMap` 中的 `"system"` Binder 塞进 extras 返回。
     */
    private fun buildS1RegisterReceiverHook(): HookSpec = HookSpec(
        id = "S1",
        name = "ActivityManagerService#registerReceiverWithFeature",
        targetClass = CLASS_AMS,
        methodName = "registerReceiverWithFeature",
        paramTypes = listOf(
            CLASS_IAPPLICATION_THREAD,
            String::class.java,
            String::class.java,
            String::class.java,
            CLASS_IINTENT_RECEIVER,
            IntentFilter::class.java,
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        ),
        paramResolver = { _, clazz ->
            val match = clazz.declaredMethods.firstOrNull {
                it.name == "registerReceiverWithFeature" &&
                    it.parameterTypes.any { pt -> pt == IntentFilter::class.java }
            }
            match?.parameterTypes?.toList()
        },
        mode = HookMode.BEFORE,
        before = { param ->
            val filter = param.args.firstNotNullOfOrNull { it as? IntentFilter } ?: return@HookSpec
            if (filter.hasAction(MilletIpcContract.ACTION_XMILLET_BINDER)) {
                val bundle = Bundle()
                for ((key, binder) in SystemBinderService.binderMap) {
                    bundle.putBinder(key, binder)
                }
                val replyIntent = Intent().apply {
                    putExtras(bundle)
                }
                Logger.alwaysInfo("S1: 命中 Binder 注册握手请求, 返回 binders: " + SystemBinderService.binderMap.keys)
                param.result = replyIntent
            }
        }
    )
}
