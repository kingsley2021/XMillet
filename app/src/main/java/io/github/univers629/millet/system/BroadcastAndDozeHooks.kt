package io.github.univers629.millet.system

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import io.github.univers629.millet.core.ActiveImeTracker
import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.ConditionalDozeLifecycle
import io.github.univers629.millet.core.DozeDiagnostics
import io.github.univers629.millet.core.DozeLiveState
import io.github.univers629.millet.core.DozeRetryPolicy
import io.github.univers629.millet.core.XReflect
import io.github.univers629.millet.core.HookDecisions
import io.github.univers629.millet.core.HookMode
import io.github.univers629.millet.core.HookSpec
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.ModernBridge
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsDefaults
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.core.VersionGate
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 广播唤醒拦截（S9 / S10 按 SDK_INT 二选一）与 Doze 状态机接管（S11 / S12 / S13 / S14）及电量统计。
 *
 * 覆盖主线：
 * - 广播唤醒拦截：S9（SDK > 33，BroadcastSkipPolicy#shouldSkipMessage）与
 *   S10（SDK <= 33，BroadcastQueue#deliverToRegisteredReceiverLocked）二选一安装。
 * - Doze 状态机：S12（onStart 抓句柄）、S13（motionLocked 运动不唤醒）、
 *   S14（moveToStateLocked 跳过 IDLE_PENDING 直进 IDLE_MAINTENANCE）。
 */
object BroadcastAndDozeHooks {
    private val skipBroadcastLogAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private const val CLASS_AMS = "com.android.server.am.ActivityManagerService"
    private const val CLASS_BROADCAST_SKIP_POLICY = "com.android.server.am.BroadcastSkipPolicy"
    private const val CLASS_BROADCAST_QUEUE = "com.android.server.am.BroadcastQueue"
    private const val CLASS_BROADCAST_RECORD = "com.android.server.am.BroadcastRecord"
    private const val CLASS_BROADCAST_FILTER = "com.android.server.am.BroadcastFilter"
    private const val CLASS_DEVICE_IDLE_CONTROLLER = "com.android.server.DeviceIdleController"
    private const val CLASS_DEVICE_IDLE_SHELL = "com.android.server.DeviceIdleController\$Shell"

    private const val REASON_XMILLET_FORCE = "XMilletForce"
    private const val REASON_XMILLET_SKIP = "XMilletSkip"

    /** DeviceIdleController 深度睡眠状态：5 = IDLE，6 = IDLE_MAINTENANCE */
    private const val STATE_IDLE = 5
    private const val STATE_IDLE_MAINTENANCE = 6
    private const val DEEP_DOZE_ENTER_DELAY_MS = 10_000L

    private const val EVENT_NORMAL = 1
    private const val EVENT_LIGHT_IDLE = 2
    private const val EVENT_LIGHT_MAINTENANCE = 3
    private const val EVENT_DEEP_IDLE = 4
    private const val EVENT_DEEP_MAINTENANCE = 5

    /** 熄屏后等待多久再接管 Doze（毫秒） */
    private const val DOZE_SCHEDULE_DELAY_MS = 10_000L

    private const val SKIP_FROZEN_BROADCAST_REASON = "Skip frozen process by XMillet"

    /** DeviceIdleController$Shell 实例缓存（用于执行深度睡眠 shell 指令） */
    @Volatile
    private var idleShellInstance: Any? = null
    private val idleShellLock = Any()

    private val dozeLifecycle = ConditionalDozeLifecycle()
    private var deepDozeAlarmListener: AlarmManager.OnAlarmListener? = null
    private var deepDozeAlarmToken: Long? = null

    /** 浅睡兜底后补试深睡的闹钟监听（与主闹钟分开保存，便于统一取消）。 */
    private var deepDozeRetryListener: AlarmManager.OnAlarmListener? = null

    /** 最近一次熄屏调度使用的 Handler，补试闹钟需要它把回调投递回 system_server。 */
    private var deepDozeHandler: Handler? = null

    /** 本次熄屏周期是否已经补试过，避免被闹钟窗口反复拖延时反复补试。 */
    private var dozeRetryUsed: Boolean = false

    /** 是否由本模块设置了浅睡强制（补试前需要先撤掉，否则 `permitsForce` 恒为 false）。 */
    private var dozeLightFallbackActive: Boolean = false

    private val receiverLifecycleLock = Any()

    @Volatile
    private var screenReceiver: BroadcastReceiver? = null

    @Volatile
    private var screenReceiverContext: Context? = null

    @Volatile
    private var screenReceiverRegistered: Boolean = false

    @Volatile
    private var batteryReceiverContext: Context? = null

    @Volatile
    private var batteryReceiverRegistered: Boolean = false

    @Volatile
    private var lastBatteryPercent: Int = -1

    @Volatile
    private var lastBatteryTimestamp: Long = -1L

    private val screenThreadLock = Any()

    @Volatile
    private var screenThread: HandlerThread? = null

    @Volatile
    private var screenHandlerInstance: Handler? = null

    @Volatile
    private var hotReloading: Boolean = false

    private fun ensureScreenHandler(): Handler? {
        if (hotReloading) return null
        screenHandlerInstance?.let { return it }
        synchronized(screenThreadLock) {
            if (hotReloading) return null
            screenHandlerInstance?.let { return it }
            val thread = HandlerThread("XMillet-Screen", Process.THREAD_PRIORITY_FOREGROUND)
            thread.start()
            val handler = Handler(thread.looper)
            screenThread = thread
            screenHandlerInstance = handler
            return handler
        }
    }

    /**
     * 热重载卸载前注销广播接收器、取消闹钟监听并停掉 `XMillet-Screen` 线程，避免旧代 ClassLoader 泄漏与回调叠加。
     */
    fun onHotReloading() {
        hotReloading = true
        withDozeLock {
            dozeLifecycle.stop()
            cancelDeepDozeAlarmLocked()
            releaseDeepDozeLocked("reload")
        }
        val reloadWallMs = System.currentTimeMillis()
        val reloadElapsedMs = SystemClock.elapsedRealtime()
        Logger.postIoTask {
            FreezeStatsRecorder.onDozeStateObserved(0, reloadWallMs, reloadElapsedMs)
        }
        val screenCtx: Context?
        val scrReceiver: BroadcastReceiver?
        val batCtx: Context?
        val wasBatRegistered: Boolean
        synchronized(receiverLifecycleLock) {
            screenCtx = screenReceiverContext
            scrReceiver = screenReceiver
            screenReceiver = null
            screenReceiverContext = null
            screenReceiverRegistered = false

            batCtx = batteryReceiverContext
            wasBatRegistered = batteryReceiverRegistered
            batteryReceiverContext = null
            batteryReceiverRegistered = false
        }
        if (screenCtx != null && scrReceiver != null) {
            runCatching { screenCtx.unregisterReceiver(scrReceiver) }
        }
        if (wasBatRegistered && batCtx != null) {
            runCatching { batCtx.unregisterReceiver(batteryChangeReceiver) }
        }
        val alarmCtx = screenCtx ?: batCtx ?: SystemState.systemContext
        alarmCtx?.let { ctx ->
            runCatching {
                val am = ctx.getSystemService(AlarmManager::class.java)
                am?.cancel(dualScreenOffFreezeListener)
            }
        }
        val thread: HandlerThread?
        synchronized(screenThreadLock) {
            screenHandlerInstance?.removeCallbacksAndMessages(null)
            screenHandlerInstance = null
            thread = screenThread
            screenThread = null
        }
        thread?.quitSafely()
    }

    private val dualScreenOffFreezeListener = AlarmManager.OnAlarmListener {
        if (!SystemState.hasAurogonIsUidValid && PrefReader.systemPrefs.freezeDualApp) {
            ForceFreezeBridge.freezeDualAppsOnScreenOffAsync()
        }
    }

    private val dualScreenOffPostRunnable = Runnable {
        if (!SystemState.hasAurogonIsUidValid && PrefReader.systemPrefs.freezeDualApp) {
            ForceFreezeBridge.freezeDualAppsOnScreenOffAsync()
        }
    }

    /**
     * S9（PA(1)）：BroadcastSkipPolicy#shouldSkipMessage(BroadcastRecord, BroadcastFilter)
     * 门控：`SDK_INT > 33`（Android 14+ 新广播管线）。
     * 冻结中的非系统、非白名单应用直接返回非空跳过原因字符串。
     */
    fun buildS9BroadcastSkipPolicyHook(): HookSpec = HookSpec(
        id = "S9",
        name = "BroadcastSkipPolicy#shouldSkipMessage",
        targetClass = CLASS_BROADCAST_SKIP_POLICY,
        methodName = "shouldSkipMessage",
        paramTypes = listOf(CLASS_BROADCAST_RECORD, CLASS_BROADCAST_FILTER),
        paramResolver = { _, clazz ->
            // 澎湃 1.0/2.0：(Record, BroadcastFilter)；
            // 澎湃 3.0（Android 16）保留的是 (Record, BroadcastFilter, boolean)，
            // 另有两个 (Record, ResolveInfo/Object, boolean) 的重载，这里按第二参类型挑。
            clazz.declaredMethods
                .filter { method ->
                    method.name == "shouldSkipMessage" &&
                        method.parameterCount >= 2 &&
                        method.parameterTypes[1].name == CLASS_BROADCAST_FILTER
                }
                .minByOrNull { it.parameterCount }
                ?.parameterTypes?.toList()
        },
        mode = HookMode.REPLACE,
        gate = { VersionGate.isSdkAfter33 },
        successLog = "获取广播成功",
        replace = { param ->
            if (!SystemState.isGreezeReady) {
                return@HookSpec param.invokeOriginal()
            }
            val filter = param.args.getOrNull(1) ?: return@HookSpec param.invokeOriginal()
            val receiverList = try {
                XReflect.getObjectField(filter, "receiverList")
            } catch (_: Throwable) {
                null
            } ?: return@HookSpec param.invokeOriginal()

            val appProcessRecord = try {
                XReflect.getObjectField(receiverList, "app")
            } catch (_: Throwable) {
                null
            } ?: return@HookSpec param.invokeOriginal()

            val procState = SystemState.extractProcessStateOrNull(appProcessRecord)
                ?: return@HookSpec param.invokeOriginal()

            val prefs = PrefReader.systemPrefs
            if (procState.isSystemApp ||
                AppUidUtils.containsPolicyTarget(prefs.whiteAppSet, procState.packageName, procState.uid) ||
                SystemState.greezeService == null ||
                !SystemState.isUidFrozen(procState.uid)
            ) {
                return@HookSpec param.invokeOriginal()
            }

            if (prefs.isLog) {
                val now = android.os.SystemClock.uptimeMillis()
                val last = skipBroadcastLogAt[procState.processName]
                if (last == null || now - last !in 0..60_000L) {
                    skipBroadcastLogAt[procState.processName] = now
                    Logger.info("修改 ${procState.processName} ,跳过广播（60 秒内同进程不再重复记录）")
                }
            }
            SKIP_FROZEN_BROADCAST_REASON
        }
    )

    /**
     * S10（PA(0)）：BroadcastQueue#deliverToRegisteredReceiverLocked(BroadcastRecord, BroadcastFilter, boolean, int)
     * 门控：`SDK_INT <= 33`（Android 13 旧广播管线，与 S9 严格二选一安装）。
     */
    fun buildS10BroadcastQueueDeliverHook(): HookSpec = HookSpec(
        id = "S10",
        name = "BroadcastQueue#deliverToRegisteredReceiverLocked",
        targetClass = CLASS_BROADCAST_QUEUE,
        methodName = "deliverToRegisteredReceiverLocked",
        paramTypes = listOf(
            CLASS_BROADCAST_RECORD,
            CLASS_BROADCAST_FILTER,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        ),
        mode = HookMode.REPLACE,
        gate = { !VersionGate.isSdkAfter33 },
        successLog = "监听广播成功",
        replace = { param ->
            if (!SystemState.isGreezeReady) {
                return@HookSpec param.invokeOriginal()
            }
            val broadcastRecord = param.args.getOrNull(0)
            val filter = param.args.getOrNull(1) ?: return@HookSpec param.invokeOriginal()
            val receiverList = try {
                XReflect.getObjectField(filter, "receiverList")
            } catch (_: Throwable) {
                null
            } ?: return@HookSpec param.invokeOriginal()

            val appProcessRecord = try {
                XReflect.getObjectField(receiverList, "app")
            } catch (_: Throwable) {
                null
            } ?: return@HookSpec param.invokeOriginal()

            val procState = SystemState.extractProcessStateOrNull(appProcessRecord)
                ?: return@HookSpec param.invokeOriginal()

            val prefs = PrefReader.systemPrefs
            if (procState.isSystemApp ||
                AppUidUtils.containsPolicyTarget(prefs.whiteAppSet, procState.packageName, procState.uid)
            ) {
                return@HookSpec param.invokeOriginal()
            }

            procState.lock.lock()
            try {
                if (SystemState.greezeService != null && SystemState.isUidFrozen(procState.uid)) {
                    XReflect.setObjectField(receiverList, "app", null)
                    if (prefs.isLog) {
                        val action = try {
                            val intent = XReflect.getObjectField(broadcastRecord, "intent") as? Intent
                            intent?.action ?: "unknown"
                        } catch (_: Throwable) {
                            "unknown"
                        }
                        Logger.info("屏蔽进程[${procState.processName}]冻结时收到的广播[$action]")
                    }
                }
                param.invokeOriginal()
            } finally {
                procState.lock.unlock()
            }
        }
    )

    /**
     * S11（PA(9)）：ActivityManagerService#setSystemProcess()
     * 捕获 AMS 实例句柄，供 `SystemState.systemContext` 提取系统 Context。
     */
    fun buildS11AmsSetSystemProcessHook(): HookSpec = HookSpec(
        id = "S11",
        name = "ActivityManagerService#setSystemProcess",
        targetClass = CLASS_AMS,
        methodName = "setSystemProcess",
        paramTypes = emptyList(),
        mode = HookMode.BEFORE,
        successLog = "获取安卓核心服务成功",
        before = { param ->
            if (param.thisObject != null) {
                SystemState.amsInstance = param.thisObject
            }
        }
    )

    /**
     * S12（PA(10)）：DeviceIdleController#onStart()
     * 捕获 Doze 控制器句柄并初始化 DeviceIdleController$Shell，同时同步启动期省电白/黑名单与电量观测。
     */
    fun buildS12DeviceIdleOnStartHook(): HookSpec = HookSpec(
        id = "S12",
        name = "DeviceIdleController#onStart",
        targetClass = CLASS_DEVICE_IDLE_CONTROLLER,
        methodName = "onStart",
        paramTypes = emptyList(),
        mode = HookMode.AFTER,
        successLog = "获取IDLE控制服务成功",
        after = { param ->
            val controller = param.thisObject ?: return@HookSpec
            if (SystemState.deviceIdleController == null) {
                synchronized(BroadcastAndDozeHooks::class.java) {
                    if (SystemState.deviceIdleController == null) {
                        SystemState.deviceIdleController = controller
                        initIdleShell(controller)
                    }
                }
            }
            applyStartupSync()
        }
    )

    /**
     * S13（PA(11)）：DeviceIdleController#motionLocked()
     * 运动不再把手机踢出 Doze（将 `mNotMoving` 置为 true 并吞掉原方法）。
     */
    fun buildS13DeviceIdleMotionLockedHook(): HookSpec = HookSpec(
        id = "S13",
        name = "DeviceIdleController#motionLocked",
        targetClass = CLASS_DEVICE_IDLE_CONTROLLER,
        methodName = "motionLocked",
        paramTypes = emptyList(),
        mode = HookMode.BEFORE,
        successLog = "获取设备运动状态监测服务成功",
        before = { param ->
            if (!SystemState.isGreezeReady) return@HookSpec
            val prefs = PrefReader.systemPrefs
            if (prefs.disableMotionListener) {
                if (prefs.dozeLog) {
                    Logger.doze(Logger.Level.INFO, "[Doze]:监测到设备运动，但是阻止唤醒Doze")
                }
                param.result = null
                val controller = SystemState.deviceIdleController ?: param.thisObject
                if (controller != null) {
                    try {
                        XReflect.setBooleanField(controller, "mNotMoving", true)
                    } catch (_: Throwable) {
                    }
                }
            } else if (prefs.dozeLog) {
                Logger.doze(Logger.Level.INFO, "[Doze]:监测到设备运动")
            }
        }
    )

    /**
     * S14（PA(12)）：DeviceIdleController#moveToStateLocked(int state, String reason)
     * 按 `enableDoze` 与 `skipStateCheck` 双向接管 Doze 状态机：
     * - before：当进入 `IDLE_PENDING(2)` 时直接改写目标状态为 `IDLE_MAINTENANCE(6)`，跳过前置传感检查。
     * - after：进入 `IDLE(5)` 或 `IDLE_MAINTENANCE(6)` 时取消尚未执行的息屏延时 Alarm。
     */
    fun buildS14DeviceIdleMoveToStateHook(): HookSpec = HookSpec(
        id = "S14",
        name = "DeviceIdleController#moveToStateLocked",
        targetClass = CLASS_DEVICE_IDLE_CONTROLLER,
        methodName = "moveToStateLocked",
        paramTypes = listOf(Int::class.javaPrimitiveType!!, String::class.java),
        mode = HookMode.BEFORE_AND_AFTER,
        before = { param ->
            if (!SystemState.isGreezeReady) return@HookSpec
            val prefs = PrefReader.systemPrefs
            if (prefs.enableDoze && prefs.skipStateCheck) {
                val state = (param.args.getOrNull(0) as? Int) ?: return@HookSpec
                val reason = (param.args.getOrNull(1) as? String) ?: ""
                if (state == 2 && reason != REASON_XMILLET_FORCE) {
                    param.args[0] = 6
                    val controller = SystemState.deviceIdleController ?: param.thisObject
                    if (controller != null) {
                        try {
                            XReflect.callMethod(controller, "resetIdleManagementLocked")
                        } catch (_: Throwable) {
                        }
                    }
                    if (prefs.dozeLog) {
                        param.args[1] = REASON_XMILLET_SKIP
                        Logger.doze(Logger.Level.INFO, "[Doze]:状态IDLE_PENDING,跳过其他进入IDLE")
                    }
                }
            }
        },
        after = { param ->
            val state = (param.args.getOrNull(0) as? Int) ?: return@HookSpec
            val reason = (param.args.getOrNull(1) as? String) ?: ""
            val prefs = PrefReader.systemPrefs

            if (state == 5 || state == 6) {
                withDozeLock {
                    dozeLifecycle.cancelPendingForIdle()
                    cancelDeepDozeAlarmLocked()
                }
            }
            if (SystemState.isGreezeReady && prefs.freezeStats) {
                val wallMs = System.currentTimeMillis()
                val elapsedMs = SystemClock.elapsedRealtime()
                Logger.postIoTask {
                    FreezeStatsRecorder.onDozeStateObserved(state, wallMs, elapsedMs)
                }
            }

            if (prefs.dozeLog) {
                val stateName = when (state) {
                    0 -> "ACTIVE"
                    1 -> "INACTIVE"
                    2 -> "IDLE_PENDING"
                    3 -> "SENSING"
                    4 -> "LOCATING"
                    5 -> "IDLE"
                    6 -> "IDLE_MAINTENANCE"
                    7 -> "QUICK_DOZE_DELAY"
                    else -> "UNKNOWN($state)"
                }
                Logger.doze(Logger.Level.WARN, "[Doze状态监测]:由于$reason,切换到:$stateName")
            }
        }
    )

    /**
     * S34：DeviceIdleController#isUpcomingAlarmClock()
     *
     * `stepIdleStateLocked` 开头只要判定「一次从 idle 唤醒的闹钟即将到来」就先
     * `becomeActiveLocked("alarm")` 再 `becomeInactiveIfAppropriateLocked()`，强制推进被原地打断：
     *
     * - OS4 在 QUICK_DOZE_DELAY(7) 下自锁：`becomeInactiveIfAppropriateLocked()` 在 QuickDoze
     *   打开时把状态推回 7，推进循环无法前进，最终 `stopped at QUICK_DOZE_DELAY`。
     * - OS3 在 INACTIVE(1) 下直接返回，最终 `stopped at INACTIVE`（微信等应用挂
     *   `FLAG_WAKE_FROM_IDLE` 周期闹钟时长期成立）。
     *
     * 强制进入深睡是本模块的明确意图，此时跳过「让闹钟先响」这条保护，状态机才能继续推进。
     * 只在 `mForceIdle == true` 且状态为 7 或 1 时短路，其余路径保持 ROM 原样（该判定还决定
     * 闹钟重排时间，短路后按 QUICK_DOZE_DELAY_TIMEOUT 重排，不影响其他行为）。
     */
    fun buildS34DeviceIdleUpcomingAlarmHook(): HookSpec = HookSpec(
        id = "S34",
        name = "DeviceIdleController#isUpcomingAlarmClock",
        targetClass = CLASS_DEVICE_IDLE_CONTROLLER,
        methodName = "isUpcomingAlarmClock",
        paramTypes = emptyList(),
        mode = HookMode.BEFORE,
        successLog = "获取Doze闹钟判定成功",
        before = { param ->
            if (!SystemState.isGreezeReady) return@HookSpec
            val controller = SystemState.deviceIdleController ?: param.thisObject ?: return@HookSpec
            val forced = runCatching {
                XReflect.getObjectField(controller, "mForceIdle") as? Boolean
            }.getOrNull()
            // 只在「强制推进 + 卡在 QUICK_DOZE_DELAY(7) 或 INACTIVE(1)」时短路，其余路径保持 ROM 原行为。
            val state = runCatching {
                XReflect.getIntField(controller, "mState").takeIf { it in 0..7 }
            }.getOrNull()
            if (!HookDecisions.bypassUpcomingAlarmWhileForced(forced, state)) return@HookSpec
            param.result = false
            if (PrefReader.systemPrefs.dozeLog) {
                Logger.doze(Logger.Level.INFO, "[Doze]:强制推进时跳过即将到来的闹钟判定 state=$state")
            }
        }
    )

    /**
     * S38：DeviceIdleController#becomeActiveLocked(String, int, long, boolean)
     *
     * AOSP / MIUI 的 `becomeActiveLocked` 会直接将 `mState = 0`（ACTIVE）而不经过
     * `moveToStateLocked(0, ...)`，导致仅 Hook `S14` 时会漏掉深睡退出事件。
     */
    fun buildS38DeviceIdleBecomeActiveHook(): HookSpec = HookSpec(
        id = "S38_DOZE_BECOME_ACTIVE",
        name = "DeviceIdleController#becomeActiveLocked",
        targetClass = CLASS_DEVICE_IDLE_CONTROLLER,
        methodName = "becomeActiveLocked",
        paramTypes = listOf(
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        ),
        paramResolver = { _, clazz ->
            clazz.declaredMethods
                .filter { it.name == "becomeActiveLocked" }
                .maxByOrNull { it.parameterCount }
                ?.parameterTypes
                ?.toList()
        },
        mode = HookMode.AFTER,
        after = { param ->
            if (SystemState.deviceIdleController == null && param.thisObject != null) {
                SystemState.deviceIdleController = param.thisObject
            }
            val prefs = PrefReader.systemPrefs
            if (!SystemState.isGreezeReady || !prefs.freezeStats) return@HookSpec
            val state = idleStateLocked() ?: 0
            if (state != STATE_IDLE && state != STATE_IDLE_MAINTENANCE) {
                val wallMs = System.currentTimeMillis()
                val elapsedMs = SystemClock.elapsedRealtime()
                Logger.postIoTask {
                    FreezeStatsRecorder.onDozeStateObserved(state, wallMs, elapsedMs)
                }
            }
        }
    )

    /**
     * S39：DeviceIdleController#stepIdleStateLocked(String)
     *
     * `moveToStateLocked(int, String)`（S14）在部分 ROM 上只有 11 条指令且为 `private`，
     * 会被 `dex2oat` AOT 内联进 `stepIdleStateLocked(String)`，导致进入 `IDLE(5)` 时 S14 静默跳过。
     * 这里在不会被内联的 `stepIdleStateLocked` 执行后直接读取 `mState`，确保深度睡眠进入与维护切换必达统计。
     */
    fun buildS39DeviceIdleStepStateHook(): HookSpec = HookSpec(
        id = "S39_DOZE_STEP_IDLE",
        name = "DeviceIdleController#stepIdleStateLocked",
        targetClass = CLASS_DEVICE_IDLE_CONTROLLER,
        methodName = "stepIdleStateLocked",
        paramTypes = listOf(String::class.java),
        mode = HookMode.AFTER,
        after = { param ->
            val controller = param.thisObject ?: SystemState.deviceIdleController ?: return@HookSpec
            if (SystemState.deviceIdleController == null) {
                SystemState.deviceIdleController = controller
            }
            val state = runCatching {
                XReflect.getIntField(controller, "mState").takeIf { it in 0..7 }
            }.getOrNull() ?: return@HookSpec

            if (state == STATE_IDLE || state == STATE_IDLE_MAINTENANCE) {
                withDozeLock {
                    dozeLifecycle.cancelPendingForIdle()
                    cancelDeepDozeAlarmLocked()
                }
            }
            val prefs = PrefReader.systemPrefs
            if (SystemState.isGreezeReady && prefs.freezeStats) {
                val wallMs = System.currentTimeMillis()
                val elapsedMs = SystemClock.elapsedRealtime()
                Logger.postIoTask {
                    FreezeStatsRecorder.onDozeStateObserved(state, wallMs, elapsedMs)
                }
            }
        }
    )

    /**
     * S40：DeviceIdleController#addEvent(int cmd, String reason)
     *
     * `stepIdleStateLocked`（S39）在部分 ROM 上被 `dex2oat` 内联或方法体过大而无法稳定重装，进入深睡与
     * 维护切换仍可能漏观测。`addEvent` 是状态机每记录一次迁移都会调用的 5 条指令私有方法，直接读取
     * 其命令码即可拿到 ROM 自己认可的深度事件：
     *
     * - `EVENT_DEEP_IDLE(4)` → `IDLE(5)` 进入或维护结束
     * - `EVENT_DEEP_MAINTENANCE(5)` → `IDLE_MAINTENANCE(6)` 维护开始
     * - `EVENT_NORMAL(1)` → 回到 `ACTIVE(0)`，深度会话结束
     *
     * 浅睡事件（`EVENT_LIGHT_IDLE` / `EVENT_LIGHT_MAINTENANCE`）不参与深度统计，直接忽略，
     * 避免浅睡维护把深度会话提前截断。
     */
    fun buildS40DeviceIdleEventHook(): HookSpec = HookSpec(
        id = "S40_DOZE_EVENT",
        name = "DeviceIdleController#addEvent",
        targetClass = CLASS_DEVICE_IDLE_CONTROLLER,
        methodName = "addEvent",
        paramTypes = listOf(Int::class.javaPrimitiveType!!, String::class.java),
        mode = HookMode.AFTER,
        successLog = "Doze 事件记录点订阅成功",
        after = { param ->
            val cmd = (param.args.getOrNull(0) as? Int) ?: return@HookSpec
            val state = HookDecisions.dozeEventToState(cmd) ?: return@HookSpec
            val prefs = PrefReader.systemPrefs
            if (!prefs.freezeStats || !SystemState.isGreezeReady) return@HookSpec
            if (param.thisObject != null && SystemState.deviceIdleController == null) {
                SystemState.deviceIdleController = param.thisObject
            }
            val wallMs = System.currentTimeMillis()
            val elapsedMs = SystemClock.elapsedRealtime()
            Logger.postIoTask {
                FreezeStatsRecorder.onDozeStateObserved(state, wallMs, elapsedMs)
            }
        }
    )

    /**
     * 从 `system_server` 内存中的 `DeviceIdleController` 事件环形缓冲区（`mEventCmds`/`mEventTimes`）
     * 按需提取本次开机以来已结束的深度睡眠区间；零轮询、零 Root，仅在查询统计快照时按需读取。
     */
    fun readCompletedBootDozeIntervals(): List<FreezeStatsEngine.BootDozeInterval> {
        val controller = SystemState.deviceIdleController ?: return emptyList()
        val (cmds, times) = runCatching {
            synchronized(controller) {
                val rawCmds = (XReflect.getObjectField(controller, "mEventCmds") as? IntArray)?.clone()
                val rawTimes = (XReflect.getObjectField(controller, "mEventTimes") as? LongArray)?.clone()
                rawCmds to rawTimes
            }
        }.getOrNull() ?: return emptyList()
        if (cmds == null || times == null) return emptyList()

        val count = minOf(cmds.size, times.size)
        if (count == 0) return emptyList()

        val result = ArrayList<FreezeStatsEngine.BootDozeInterval>()
        var inDeep = false
        var startElapsed = 0L
        var maintCount = 0
        var lastCmd = 0

        for (i in count - 1 downTo 0) {
            val cmd = cmds[i]
            val time = times[i]
            if (cmd == 0 || time <= 0L) continue
            when (cmd) {
                EVENT_DEEP_IDLE, EVENT_DEEP_MAINTENANCE -> {
                    if (!inDeep) {
                        inDeep = true
                        startElapsed = time
                        maintCount = if (cmd == EVENT_DEEP_MAINTENANCE) 1 else 0
                    } else if (lastCmd == EVENT_DEEP_IDLE && cmd == EVENT_DEEP_MAINTENANCE) {
                        maintCount++
                    }
                    lastCmd = cmd
                }
                EVENT_NORMAL, EVENT_LIGHT_IDLE, EVENT_LIGHT_MAINTENANCE -> {
                    if (inDeep) {
                        val dur = time - startElapsed
                        if (dur >= FreezeStatsEngine.MIN_DOZE_SESSION_MS) {
                            result.add(
                                FreezeStatsEngine.BootDozeInterval(
                                    startElapsedMs = startElapsed,
                                    endElapsedMs = time,
                                    maintenanceCount = maintCount,
                                )
                            )
                        }
                        inDeep = false
                    }
                    lastCmd = cmd
                }
            }
        }
        return result
    }

    // ==================== 亮灭屏广播、DeepDoze Shell 与省电白名单同步 ====================

    private fun <T> withDozeLock(block: () -> T): T {
        val controller = SystemState.deviceIdleController
        // Shell commands and S14 callbacks use the same controller -> shell lock order.
        return if (controller != null) {
            synchronized(controller) { synchronized(idleShellLock) { block() } }
        } else {
            synchronized(idleShellLock) { block() }
        }
    }

    private fun controllerBoolean(field: String): Boolean? = runCatching {
        XReflect.getObjectField(SystemState.deviceIdleController, field) as? Boolean
    }.getOrNull()

    /**
     * 当前 Doze 状态机的实时快照，供环境检查页显示「此刻为什么进不去深睡」。
     *
     * 编码格式见 [DozeLiveState.encode]；控制器未捕获或读取失败时返回空串，
     * 界面按「未知」处理，不会把读取失败误报成「已进入深睡」。
     */
    fun describeLiveDoze(): String {
        val controller = SystemState.deviceIdleController ?: return ""
        return runCatching {
            DozeLiveState(
                state = XReflect.getIntField(controller, "mState"),
                forced = controllerBoolean("mForceIdle") == true,
                charging = controllerBoolean("mCharging") == true,
                screenOn = controllerBoolean("mScreenOn") == true,
                blockingConstraints = XReflect.getIntField(controller, "mNumBlockingConstraints"),
                blockerNames = activeConstraintNames(controller),
            ).encode()
        }.onFailure { Logger.debug("Doze 实时状态读取失败 ${it.javaClass.simpleName}") }
            .getOrDefault("")
    }

    /** 正在生效的约束名（active=true），最多取前 4 个，避免提示过长。 */
    private fun activeConstraintNames(controller: Any): List<String> {
        val map = XReflect.getObjectField(controller, "mConstraints") as? Map<*, *> ?: return emptyList()
        return map.values.mapNotNull { tracker ->
            if (tracker == null) return@mapNotNull null
            val active = runCatching { XReflect.getObjectField(tracker, "active") as? Boolean }.getOrNull()
            if (active != true) return@mapNotNull null
            runCatching { XReflect.getObjectField(tracker, "name") as? String }.getOrNull()
        }.filter { it.isNotBlank() }.distinct().take(4)
    }

    private fun queryDozeEnabled(): Boolean {
        if (!PrefReader.systemPrefs.enableDoze) return false
        return runCatching {
            val module = ModernBridge.xposedModule ?: return@runCatching false
            val raw = module.getRemotePreferences(PrefsKeys.PREFS_FILE_NAME).all
                ?: return@runCatching false
            if (!raw.containsKey(PrefsKeys.ENABLE_DOZE)) PrefsDefaults.ENABLE_DOZE
            else raw[PrefsKeys.ENABLE_DOZE] as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun readDozeConditions(screenOnOverride: Boolean? = null, chargingOverride: Boolean? = null): ConditionalDozeLifecycle.Conditions {
        val controller = SystemState.deviceIdleController
        val ctx = SystemState.systemContext
        val interactive = runCatching {
            ctx?.getSystemService(PowerManager::class.java)?.isInteractive
        }.getOrNull()
        val plugged = runCatching {
            val battery = ctx?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return@runCatching null
            if (!battery.hasExtra(BatteryManager.EXTRA_PLUGGED)) return@runCatching null
            battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1).takeIf { it >= 0 }?.let { it != 0 }
        }.getOrNull()
        fun combine(first: Boolean?, second: Boolean?): Boolean? = when {
            first == true || second == true -> true
            first == false && second == false -> false
            else -> null
        }
        val deepIdle = runCatching {
            if (controller == null) return@runCatching null
            XReflect.getIntField(controller, "mState").takeIf { it in 0..7 }?.let { it == 5 || it == 6 }
        }.getOrNull()
        return ConditionalDozeLifecycle.Conditions(
            screenOn = screenOnOverride ?: combine(interactive, controllerBoolean("mScreenOn")),
            charging = chargingOverride ?: combine(plugged, controllerBoolean("mCharging")),
            enabled = queryDozeEnabled(),
            ready = SystemState.isGreezeReady && controller != null && Thread.holdsLock(controller),
            stopped = hotReloading,
            forced = controllerBoolean("mForceIdle"),
            deepIdle = deepIdle,
        )
    }

    private fun cancelDeepDozeAlarmLocked() {
        val listener = deepDozeAlarmListener
        deepDozeAlarmListener = null
        deepDozeAlarmToken = null
        val retryListener = deepDozeRetryListener
        deepDozeRetryListener = null
        if (listener != null || retryListener != null) {
            runCatching {
                val ctx = screenReceiverContext ?: SystemState.systemContext
                val am = ctx?.getSystemService(AlarmManager::class.java)
                listener?.let { am?.cancel(it) }
                retryListener?.let { am?.cancel(it) }
            }
        }
    }

    private fun idleStateLocked(): Int? = runCatching {
        val controller = SystemState.deviceIdleController ?: return@runCatching null
        XReflect.getIntField(controller, "mState").takeIf { it in 0..7 }
    }.getOrNull()

    /**
     * 强制进入深度睡眠。
     *
     * ROM 的 stepIdleStateLocked 在 `mNumBlockingConstraints != 0 && mForceIdle` 时会直接返回，
     * 使 force-idle 的推进循环原地失败（Unable to go deep idle; stopped at ...）。此时先以无强制
     * 身份 step 推进到 IDLE，再补一次 force-idle 只登记强制标记（该路径不推进状态机，不会被拦截）。
     */
    private fun enterDeepDozeLocked(): Boolean {
        val attemptWall = System.currentTimeMillis()
        DozeDiagnostics.onAttempt(attemptWall)
        val (code, out) = execIdleShellCommand("force-idle", "deep")
        if (code == 0) {
            DozeDiagnostics.onSuccess(System.currentTimeMillis())
            return true
        }
        Logger.alwaysWarn("深度睡眠失败 $out")
        val blockers = logIdleConstraintsLocked()
        var state = idleStateLocked()
        for (attempt in 1..4) {
            if (state == null || state == STATE_IDLE) break
            val (stepCode, stepOut) = execIdleShellCommand("step", "deep")
            if (stepCode != 0) {
                Logger.alwaysWarn("Doze 推进失败 $stepOut")
                break
            }
            state = idleStateLocked()
            Logger.alwaysWarn("Doze 推进 $attempt 次: state=$state")
        }
        if (state == STATE_IDLE) {
            val (retryCode, retryOut) = execIdleShellCommand("force-idle", "deep")
            if (retryCode == 0) {
                Logger.alwaysWarn("深度睡眠成功")
                DozeDiagnostics.onSuccess(System.currentTimeMillis())
                return true
            }
            Logger.alwaysWarn("深度睡眠强制标记补登失败 $retryOut")
        }
        if (state == STATE_IDLE || state == STATE_IDLE_MAINTENANCE) {
            DozeDiagnostics.onSuccess(System.currentTimeMillis())
            return true
        }
        DozeDiagnostics.onFailure(System.currentTimeMillis(), deepDozeFailureText(out, state, blockers))
        // 深睡被 ROM 状态机拒绝（典型是 10 分钟内有时钟闹钟）：退化为浅睡兜底，稍后由重试闹钟再升级。
        val (lightCode, lightOut) = execIdleShellCommand("force-idle", "light")
        if (lightCode == 0) {
            dozeLightFallbackActive = true
            Logger.alwaysWarn("浅度睡眠成功（深睡被系统拒绝）")
            return true
        }
        Logger.alwaysWarn("浅度睡眠失败 $lightOut")
        return false
    }

    /**
     * 把 ROM 的失败输出压成一行短原因，供环境检查页直接显示。
     *
     * dumpsys 输出通常形如 `Unable to go deep idle; stopped at INACTIVE`，
     * 这里保留状态值、阻塞约束与 ROM 原文，去掉换行并截断，避免占满提示位。
     */
    private fun deepDozeFailureText(out: String, state: Int?, blockers: String): String {
        val reason = out.trim().replace(Regex("[\\r\\n]+"), " ").take(110)
        val stateText = if (state == null) "状态未知" else "停在 state=$state"
        val head = if (reason.isEmpty()) stateText else "$stateText：$reason"
        return if (blockers.isEmpty()) head else "$head｜约束：$blockers"
    }

    /** 记录当前阻塞约束并返回可读摘要，便于定位 ROM 侧是谁拦住了 force-idle。 */
    private fun logIdleConstraintsLocked(): String {
        val controller = SystemState.deviceIdleController ?: return ""
        return runCatching {
            val blocking = XReflect.getIntField(controller, "mNumBlockingConstraints")
            val map = XReflect.getObjectField(controller, "mConstraints") as? Map<*, *>
            val detail = map?.values?.mapNotNull { tracker ->
                if (tracker == null) return@mapNotNull null
                val name = runCatching { XReflect.getObjectField(tracker, "name") as? String }.getOrNull()
                val active = runCatching { XReflect.getObjectField(tracker, "active") as? Boolean }.getOrNull()
                val monitoring = runCatching { XReflect.getObjectField(tracker, "monitoring") as? Boolean }.getOrNull()
                val minState = runCatching { XReflect.getIntField(tracker, "minState") }.getOrNull()
                "$name(active=$active,monitoring=$monitoring,minState=$minState)"
            }?.joinToString(" | ") ?: ""
            Logger.alwaysWarn("Doze约束: blocking=$blocking total=${map?.size ?: -1} [$detail]")
            // 只把正在生效的约束带进诊断，避免把全部约束名塞进一行提示。
            val activeNames = map?.values?.mapNotNull { tracker ->
                if (tracker == null) return@mapNotNull null
                val active = runCatching { XReflect.getObjectField(tracker, "active") as? Boolean }.getOrNull()
                if (active != true) null
                else runCatching { XReflect.getObjectField(tracker, "name") as? String }.getOrNull()
            }.orEmpty()
            if (activeNames.isEmpty()) {
                if (blocking > 0) "blocking=$blocking" else ""
            } else {
                "blocking=$blocking ${activeNames.joinToString("、")}"
            }
        }.onFailure { Logger.debug("Doze约束读取失败 ${it.javaClass.simpleName}") }.getOrDefault("")
    }

    /** 清除强制标记；残留的 mForceIdle 会让控制器立刻重新进入 INACTIVE。 */
    private fun clearForceIdleLocked(): Boolean {
        if (controllerBoolean("mForceIdle") != true) return true
        val (code, out) = execIdleShellCommand("unforce")
        if (code != 0) Logger.debug("退出深度睡眠未确认 $out")
        if (code == 0) dozeLightFallbackActive = false
        return code == 0
    }

    private fun exitDozeStateLocked(reason: String) {
        val state = idleStateLocked() ?: return
        if (state != STATE_IDLE && state != STATE_IDLE_MAINTENANCE) return
        clearForceIdleLocked()
        val controller = SystemState.deviceIdleController ?: return
        runCatching {
            XReflect.callMethod(controller, "becomeActiveLocked", reason, Process.myUid())
            Logger.alwaysWarn("退出深度睡眠:$reason state=$state")
        }.onFailure { Logger.alwaysWarn("退出深度睡眠失败:$reason ${it.javaClass.simpleName}") }
    }

    private fun releaseDeepDozeLocked(reason: String) {
        dozeLifecycle.release({ controllerBoolean("mForceIdle") }) {
            val cleared = clearForceIdleLocked()
            if (cleared && controllerBoolean("mForceIdle") == false) {
                Logger.alwaysWarn("退出深度睡眠")
            }
            cleared
        }
        // ROM 在 mScreenLocked && WAIT_FOR_UNLOCK 时不会因亮屏自行退出，这里显式结束 Doze。
        exitDozeStateLocked(reason)
    }

    private fun cancelAndReleaseDoze(reason: String) {
        val postState = withDozeLock {
            dozeLifecycle.cancel()
            cancelDeepDozeAlarmLocked()
            releaseDeepDozeLocked(reason)
            idleStateLocked() ?: 0
        }
        if (SystemState.isGreezeReady && PrefReader.systemPrefs.freezeStats &&
            postState != STATE_IDLE && postState != STATE_IDLE_MAINTENANCE
        ) {
            val wallMs = System.currentTimeMillis()
            val elapsedMs = SystemClock.elapsedRealtime()
            Logger.postIoTask {
                FreezeStatsRecorder.onDozeStateObserved(postState, wallMs, elapsedMs)
            }
        }
    }

    /** Called after the current system preference snapshot has been published. */
    fun onDozeConfigChanged() = withDozeLock {
        dozeLifecycle.cancel()
        cancelDeepDozeAlarmLocked()
        if (!PrefReader.systemPrefs.enableDoze || hotReloading) releaseDeepDozeLocked("config")
    }

    /**
     * 排期进入深睡。
     *
     * @param delayMillis 触发延迟；正常路径是 [DEEP_DOZE_ENTER_DELAY_MS]，补试路径用 [DozeRetryPolicy.RETRY_DELAY_MILLIS]
     * @param isRetry 是否为补试调度；非补试（熄屏 / 停止充电）会重置本周期的补试标记
     */
    private fun scheduleDeepDoze(
        handler: Handler,
        screenOnOverride: Boolean? = null,
        chargingOverride: Boolean? = null,
        delayMillis: Long = DEEP_DOZE_ENTER_DELAY_MS,
        isRetry: Boolean = false,
    ) = withDozeLock {
        if (!isRetry) dozeRetryUsed = false
        deepDozeHandler = handler
        cancelDeepDozeAlarmLocked()
        val conditions = readDozeConditions(screenOnOverride, chargingOverride)
        Logger.alwaysWarn(
            "Doze条件: idle=${conditions.permitsIdle} forcePermit=${conditions.permitsForce} " +
                "screenOn=${conditions.screenOn} charging=${conditions.charging} enabled=${conditions.enabled} " +
                "ready=${conditions.ready} forced=${conditions.forced} deepIdle=${conditions.deepIdle}"
        )
        if (!conditions.permitsIdle) releaseDeepDozeLocked("condition")
        val token = dozeLifecycle.schedule(conditions) ?: return@withDozeLock
        val listener = AlarmManager.OnAlarmListener { runDeepDozeAlarm(token) }
        try {
            val am = SystemState.systemContext?.getSystemService(AlarmManager::class.java)
                ?: return@withDozeLock dozeLifecycle.cancel()
            deepDozeAlarmListener = listener
            deepDozeAlarmToken = token
            val triggerAt = SystemClock.elapsedRealtime() + delayMillis
            val exact = runCatching {
                am.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    "XMillet.Screen",
                    listener,
                    handler,
                )
                true
            }.getOrDefault(false)
            if (!exact) {
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    "XMillet.Screen",
                    listener,
                    handler,
                )
            }
            Logger.alwaysWarn("已调度 DeepDoze 闹钟 token=$token exact=$exact")
        } catch (_: Throwable) {
            dozeLifecycle.cancel()
            cancelDeepDozeAlarmLocked()
        }
    }

    private fun runDeepDozeAlarm(token: Long) = withDozeLock {
        Logger.alwaysWarn("DeepDoze 闹钟触发 token=$token")
        if (deepDozeAlarmToken == token) {
            deepDozeAlarmListener = null
            deepDozeAlarmToken = null
        }
        val retained = dozeLifecycle.run(
            token = token,
            read = ::readDozeConditions,
            enable = {
                val (code, out) = execIdleShellCommand("enable", "deep")
                if (code != 0) Logger.alwaysWarn("深度睡眠启用失败 $out")
                code == 0
            },
            force = {
                if (hotReloading || !PrefReader.systemPrefs.enableDoze) {
                    false
                } else {
                    enterDeepDozeLocked()
                }
            },
            unforce = {
                val (code, out) = execIdleShellCommand("unforce")
                if (code != 0) Logger.alwaysWarn("退出深度睡眠失败 $out")
                code == 0
            },
        )
        if (retained) {
            val state = idleStateLocked()
            val deepEntered = state == STATE_IDLE || state == STATE_IDLE_MAINTENANCE
            if (deepEntered) {
                Logger.alwaysWarn("深度睡眠成功")
                if (SystemState.isGreezeReady && PrefReader.systemPrefs.freezeStats) {
                    val observedState = state
                    val wallMs = System.currentTimeMillis()
                    val elapsedMs = SystemClock.elapsedRealtime()
                    Logger.postIoTask {
                        FreezeStatsRecorder.onDozeStateObserved(observedState, wallMs, elapsedMs)
                    }
                }
            } else {
                scheduleDozeRetryLocked()
            }
        }
    }

    /**
     * 浅睡兜底后排一次深睡补试。
     *
     * 命中条件见 [DozeRetryPolicy]：仅在用户开启该开关、熄屏且未充电、本次熄屏尚未补试时排期。
     * 补试本身仍走 [scheduleDeepDoze] 的完整条件检查，亮屏 / 开始充电会提前放弃。
     */
    private fun scheduleDozeRetryLocked() {
        val handler = deepDozeHandler
        val conditions = readDozeConditions()
        val allow = DozeRetryPolicy.shouldSchedule(
            optIn = PrefReader.systemPrefs.dozeRetryAfterAlarm,
            dozeEnabled = PrefReader.systemPrefs.enableDoze,
            screenOn = conditions.screenOn,
            charging = conditions.charging,
            deepEntered = false,
            retriedThisCycle = dozeRetryUsed,
        )
        val ctx = screenReceiverContext ?: SystemState.systemContext
        val am = ctx?.getSystemService(AlarmManager::class.java)
        if (!allow || handler == null || am == null) {
            Logger.alwaysWarn("浅睡兜底生效，下次熄屏时再尝试进入深睡")
            return
        }
        dozeRetryUsed = true
        val triggerAt = SystemClock.elapsedRealtime() + DozeRetryPolicy.RETRY_DELAY_MILLIS
        val listener = AlarmManager.OnAlarmListener { runDozeRetry() }
        deepDozeRetryListener = listener
        val exact = runCatching {
            am.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                "XMillet.DozeRetry",
                listener,
                handler,
            )
            true
        }.getOrDefault(false)
        if (!exact) {
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                "XMillet.DozeRetry",
                listener,
                handler,
            )
        }
        Logger.alwaysWarn(
            "浅睡兜底生效，已排期 ${DozeRetryPolicy.RETRY_DELAY_MILLIS / 60_000} 分钟后补试深睡 exact=$exact"
        )
    }

    /**
     * 补试闹钟触发：先撤掉本模块设置的浅睡强制，再按正常路径重新调度深睡。
     *
     * 不直接 `force-idle deep` 的原因：浅睡阶段 `mForceIdle` 为 true，
     * ROM 的 `stepIdleStateLocked` 会直接返回，必须先解除强制再重新调度。
     */
    private fun runDozeRetry() = withDozeLock {
        deepDozeRetryListener = null
        Logger.alwaysWarn("DeepDoze 补试闹钟触发")
        if (dozeLightFallbackActive) {
            val (code, out) = execIdleShellCommand("unforce")
            if (code == 0) {
                dozeLightFallbackActive = false
                Logger.alwaysWarn("补试前解除浅睡强制")
            } else {
                Logger.alwaysWarn("补试前解除浅睡强制失败 $out")
            }
        }
        val handler = deepDozeHandler
        if (handler == null) {
            Logger.alwaysWarn("补试跳过：Handler 已丢失")
            return@withDozeLock
        }
        scheduleDeepDoze(handler, isRetry = true)
    }

    private fun initIdleShell(controller: Any) {
        try {
            val shellClass = XReflect.findClassIfExists(
                CLASS_DEVICE_IDLE_SHELL,
                controller.javaClass.classLoader
            ) ?: XReflect.findClassIfExists(CLASS_DEVICE_IDLE_SHELL, null) ?: return
            val constructors = shellClass.declaredConstructors
            var created: Any? = null
            for (constructor in constructors) {
                val args = buildShellConstructorArgs(constructor.parameterTypes, controller) ?: continue
                created = runCatching {
                    constructor.isAccessible = true
                    constructor.newInstance(*args)
                }.getOrNull()
                if (created != null) break
            }
            if (created == null) {
                val detail = constructors.joinToString(" | ") { constructor ->
                    "(" + constructor.parameterTypes.joinToString(",") { it.name } + ")"
                }
                throw NoSuchMethodException(
                    "No matching constructor for ${shellClass.name} ctors=[$detail] " +
                        "controller=${controller.javaClass.name}"
                )
            }
            idleShellInstance = created
            Logger.alwaysWarn("IDLE Shell 初始化成功: $CLASS_DEVICE_IDLE_SHELL")
        } catch (t: Throwable) {
            Logger.error("初始化Idle出错", t)
        }
    }

    /**
     * 按构造器参数类型装配 IDLE Shell：优先传入控制器实例，基本类型给默认值。
     * Android 16 的部分 ROM 把 Shell 改成无参/静态构造，不能假设只有一个控制器参数。
     */
    private fun buildShellConstructorArgs(
        parameterTypes: Array<Class<*>>,
        controller: Any,
    ): Array<Any?>? {
        val args = arrayOfNulls<Any?>(parameterTypes.size)
        for (index in parameterTypes.indices) {
            val type = parameterTypes[index]
            when {
                type.isInstance(controller) -> args[index] = controller
                !type.isPrimitive -> args[index] = outerInstance(controller, type) ?: return null
                type == Int::class.javaPrimitiveType -> args[index] = 0
                type == Boolean::class.javaPrimitiveType -> args[index] = false
                type == Long::class.javaPrimitiveType -> args[index] = 0L
                type == Float::class.javaPrimitiveType -> args[index] = 0f
                type == Double::class.javaPrimitiveType -> args[index] = 0.0
                type == Short::class.javaPrimitiveType -> args[index] = 0.toShort()
                type == Byte::class.javaPrimitiveType -> args[index] = 0.toByte()
                type == Char::class.javaPrimitiveType -> args[index] = '\u0000'
                else -> return null
            }
        }
        return args
    }

    /** 从内层实例（如 `DeviceIdleController$BinderService`）上找出参数类型对应的外层实例。 */
    private fun outerInstance(owner: Any, type: Class<*>): Any? {
        for (field in owner.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            if (!type.isAssignableFrom(field.type)) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(owner)
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun execIdleShellCommand(vararg args: String): Pair<Int, String> {
        val controller = SystemState.deviceIdleController ?: return -1 to "controller is null"
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        synchronized(idleShellLock) {
            // 热重载后旧 Shell 实例随旧类加载器失效，这里按需重建，避免 enable/force 静默失败
            val shell = idleShellInstance ?: run {
                initIdleShell(controller)
                idleShellInstance
            } ?: return -1 to "shell is null"
            return try {
                XReflect.setObjectField(shell, "mOutPrintWriter", pw)
                XReflect.setObjectField(shell, "mArgs", arrayOf(*args))
                XReflect.setIntField(shell, "mArgPos", 1)
                val code = (XReflect.callMethod(controller, "onShellCommand", shell, args[0]) as? Int) ?: -1
                code to sw.toString()
            } catch (t: Throwable) {
                -1 to (t.message ?: "error")
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerScreenStatusReceiver(context: Context) {
        if (hotReloading) return
        synchronized(receiverLifecycleLock) {
            if (hotReloading || screenReceiverRegistered) return
            val targetCtx = context.applicationContext ?: context
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        handleScreenBroadcast(intent?.action)
                    }
                }
                targetCtx.registerReceiver(receiver, filter)
                screenReceiver = receiver
                screenReceiverContext = targetCtx
                screenReceiverRegistered = true
                Logger.alwaysWarn("registerReceiverScreenStatus")
            } catch (t: Throwable) {
                Logger.error("registerScreenStatusReceiver failed", t)
            }
        }
    }

    private fun handleScreenBroadcast(action: String?) {
        if (action == null) return
        Logger.alwaysWarn("屏幕广播:$action hot=$hotReloading ready=${SystemState.isGreezeReady}")
        when (action) {
            Intent.ACTION_SCREEN_ON -> ForegroundState.setScreenState(true)
            Intent.ACTION_SCREEN_OFF -> ForegroundState.setScreenState(false)
        }
        if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_POWER_CONNECTED) {
            cancelAndReleaseDoze(if (action == Intent.ACTION_SCREEN_ON) "screen" else "charging")
        }
        if (hotReloading || !SystemState.isGreezeReady) return
        if (action == Intent.ACTION_POWER_CONNECTED) return
        val prefs = PrefReader.systemPrefs
        val handler = ensureScreenHandler() ?: return
        when (action) {
            Intent.ACTION_SCREEN_OFF -> {
                if (!SystemState.hasAurogonIsUidValid && prefs.freezeDualApp) {
                    handler.removeCallbacks(dualScreenOffPostRunnable)
                    val ctx = SystemState.systemContext
                    var alarmScheduled = false
                    if (ctx != null) {
                        try {
                            val am = ctx.getSystemService(AlarmManager::class.java)
                            am?.cancel(dualScreenOffFreezeListener)
                            // Android 派发 ACTION_SCREEN_OFF 时部分后台进程短暂抬升到 adj=0 约 3s，使用 wakeup 闹钟避开瞬态窗口
                            am?.setExact(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                SystemClock.elapsedRealtime() + 4_500L,
                                "XMillet.DualScrOff",
                                dualScreenOffFreezeListener,
                                handler
                            )
                            alarmScheduled = am != null
                        } catch (t: Throwable) {
                            Logger.warn("dualScrOff setExact 回退 postDelayed: ${t.javaClass.simpleName}:${t.message}")
                        }
                    }
                    if (!alarmScheduled) {
                        handler.postDelayed(dualScreenOffPostRunnable, 4_500L)
                    }
                } else {
                    Logger.info(
                        "dualScrOff 未触发: hasUidValid=${SystemState.hasAurogonIsUidValid}, freezeDual=${prefs.freezeDualApp}"
                    )
                }
                if (prefs.isLog || prefs.dozeLog) {
                    val batteryPct = queryCurrentBatteryPercent()
                    Logger.doze(Logger.Level.WARN, "========[屏幕关闭,电量$batteryPct%]========")
                } else if (prefs.batteryChangeObserve && prefs.logToLocal) {
                    Logger.battery(Logger.Level.WARN, "========[屏幕关闭]========")
                }
                // SCREEN_OFF 广播比 PowerManager 状态更新更早到达，这里显式按熄屏计算条件
                scheduleDeepDoze(handler, screenOnOverride = false)
            }

            Intent.ACTION_SCREEN_ON -> {
                handler.removeCallbacks(dualScreenOffPostRunnable)
                SystemState.systemContext?.let { ctx ->
                    try {
                        ctx.getSystemService(AlarmManager::class.java)?.cancel(dualScreenOffFreezeListener)
                    } catch (_: Throwable) {
                    }
                }
                if (prefs.disableScreenOnThaw) {
                    val protectedPkgs = buildList {
                        ActiveImeTracker.resolveDefaultImePackage(SystemState.systemContext)?.let(::add)
                        addAll(ActiveImeTracker.resolveAccessibilityPackages(SystemState.systemContext))
                    }
                    for (svcPkg in protectedPkgs.distinct()) {
                        val svcUid = SystemState.getPackageUid(svcPkg)
                        if (svcUid <= 0 ||
                            !ActiveImeTracker.isProtectedSystemServiceApp(SystemState.systemContext, svcPkg, prefs, svcUid)
                        ) {
                            continue
                        }
                        if (SystemState.isUidFrozen(svcUid)) {
                            handler.post {
                                SystemState.greezeService?.let { greeze ->
                                    runCatching { XReflect.callMethod(greeze, "thawUid", svcUid, 1000, "screen on") }
                                }
                            }
                        }
                    }
                }
                if (prefs.isLog || prefs.dozeLog) {
                    val batteryPct = queryCurrentBatteryPercent()
                    Logger.doze(Logger.Level.WARN, "========[屏幕开启,电量$batteryPct%]========")
                } else if (prefs.batteryChangeObserve && prefs.logToLocal) {
                    Logger.battery(Logger.Level.WARN, "========[屏幕开启]========")
                }
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                // 熄屏状态下拔掉电源后条件重新成立；断电广播先于充电状态刷新，显式按未充电计算
                scheduleDeepDoze(handler, chargingOverride = false)
            }
        }
    }

    private fun queryCurrentBatteryPercent(): Int {
        val ctx = SystemState.systemContext ?: return -1
        return try {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return -1
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", -1)
            if (level >= 0 && scale > 0) {
                ((level.toFloat() / scale.toFloat()) * 100f).toInt()
            } else {
                -1
            }
        } catch (_: Throwable) {
            -1
        }
    }

    // ==================== 启动期与热更新联动 ====================

    /**
     * 启动期全量配置同步：
     * 1. 刷新 AurogonImmobulusMode.mMessageApp
     * 2. 若开启 batteryChangeObserve，启动电量变化广播监听
     *
     * 省电白/黑名单不在这里处理：现在由 `BatteryPolicyApplier` 按应用逐个写
     * `DeviceIdleController` / `AppOps` / PowerKeeper 配置库 / 系统无限制名单，不再维护自建集合。
     */
    fun applyStartupSync() {
        if (hotReloading || !SystemState.isGreezeReady) return
        withDozeLock {
            if (!hotReloading && idleShellInstance == null) {
                SystemState.deviceIdleController?.let(::initIdleShell)
            }
        }
        SystemState.systemContext?.let { registerScreenStatusReceiver(it) }
        SystemState.syncMessageAppsToAurogon()
        if (PrefReader.systemPrefs.isLog || PrefReader.systemPrefs.batteryChangeObserve) {
            updateBatteryObservation(true)
        }
    }

    private val batteryChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", -1)
            val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1
            if (pct < 0 || pct == lastBatteryPercent) return

            val now = SystemClock.elapsedRealtime()
            if (lastBatteryPercent != -1 && lastBatteryTimestamp > 0) {
                val elapsedMs = now - lastBatteryTimestamp
                val durationStr = formatDuration(elapsedMs)
                val diff = lastBatteryPercent - pct
                if (diff > 0) {
                    Logger.battery(Logger.Level.INFO, "电量统计:消耗 $lastBatteryPercent% --> $pct% ,耗时$durationStr")
                } else if (diff < 0) {
                    Logger.battery(Logger.Level.INFO, "电量统计:充电 $lastBatteryPercent% --> $pct% ,耗时$durationStr")
                }
            }
            lastBatteryPercent = pct
            lastBatteryTimestamp = now
        }
    }

    fun updateBatteryObservation(enabled: Boolean) {
        synchronized(receiverLifecycleLock) {
            if (enabled && !hotReloading && !batteryReceiverRegistered) {
                val ctx = SystemState.systemContext ?: return
                try {
                    val targetCtx = ctx.applicationContext ?: ctx
                    targetCtx.registerReceiver(batteryChangeReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    batteryReceiverContext = targetCtx
                    batteryReceiverRegistered = true
                    Logger.battery(Logger.Level.INFO, "开启电量记录成功")
                } catch (t: Throwable) {
                    Logger.error("updateBatteryObservation(true) failed", t)
                }
            } else if (!enabled && batteryReceiverRegistered) {
                val ctx = batteryReceiverContext ?: SystemState.systemContext ?: return
                try {
                    ctx.unregisterReceiver(batteryChangeReceiver)
                    batteryReceiverContext = null
                    batteryReceiverRegistered = false
                    Logger.battery(Logger.Level.INFO, "关闭电量记录成功")
                } catch (t: Throwable) {
                    Logger.error("updateBatteryObservation(false) failed", t)
                }
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        if (totalSec < 60) return "${totalSec}秒"
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours < 1) {
            "${minutes}分${seconds}秒"
        } else {
            "${hours}小时${minutes}分钟"
        }
    }
}
