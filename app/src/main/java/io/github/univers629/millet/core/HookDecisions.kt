package io.github.univers629.millet.core

/**
 * 钩子里纯判定的集中处。
 *
 * Hook 主体只能在设备上跑（反射、系统字段、无 Android 单测环境），
 * 把「要不要短路、短路成什么」这类不含副作用的判断抽出来，
 * 才能在 JVM 单元测试里锁住行为，避免回归时只能靠真机复现。
 */
object HookDecisions {

    /** DeviceIdleController 的 QUICK_DOZE_DELAY 状态值（OS3/OS4 独有，AOSP 只有 0..6）。 */
    const val STATE_QUICK_DOZE_DELAY = 7

    /** DeviceIdleController 的 INACTIVE 状态值（熄屏后进入浅睡前的等待态）。 */
    const val STATE_INACTIVE = 1

    /** DeviceIdleController 的 LOCATING 状态值（进入 IDLE 前的最后前置态）。 */
    const val STATE_LOCATING = 4

    /** DeviceIdleController 的 IDLE_MAINTENANCE 状态值（深睡维护态）。 */
    const val STATE_IDLE_MAINTENANCE = 6

    /** AlarmManagerService 中标记 `DeviceIdleController.deep` 的 `FLAG_IDLE_UNTIL` 位（1 << 4）。 */
    const val ALARM_FLAG_IDLE_UNTIL = 16

    /**
     * S14：`moveToStateLocked(IDLE_PENDING)` 的跳过目标状态。
     *
     * `resetIdleManagementLocked()` 会把 `mNextIdleDelay` 与 `mNextIdlePendingDelay` 清零。
     * 强制推进期间改写到 `LOCATING(4)`，后续 `stepIdleStateLocked` 经 `case 4` 重新初始化
     * `IDLE_TIMEOUT` 再进入 `IDLE(5)`，避免从 `case 6` 以 0ms 立即弹回 `IDLE_MAINTENANCE`。
     * 非强制路径仍改写到 `IDLE_MAINTENANCE(6)`，由调用方补回超时字段。
     */
    fun skipIdlePendingTargetState(forceIdle: Boolean?): Int =
        if (forceIdle == true) STATE_LOCATING else STATE_IDLE_MAINTENANCE

    /**
     * S34：`DeviceIdleController#isUpcomingAlarmClock` 是否短路为 false。
     *
     * `force-idle deep` 在循环中反复调用 `stepIdleStateLocked` 推进状态机（1 → 2/6 → … → 5）。
     * 仅在单个状态短路会导致后续迭代再次命中 `isUpcomingAlarmClock()`、将状态打回 `ACTIVE → INACTIVE`
     * 并在推进循环内死锁。强制推进（`mForceIdle == true`）期间全状态短路可消除该循环；
     * 紧急呼叫判定位于此方法之前不受影响，且系统闹钟携带 `FLAG_WAKE_FROM_IDLE`，在深睡状态下仍按时触发。
     * 非强制路径保持 ROM 原逻辑。
     */
    fun bypassUpcomingAlarmWhileForced(forceIdle: Boolean?, state: Int? = null): Boolean =
        forceIdle == true

    /**
     * S41：`AlarmManagerService#adjustIdleUntilTime` 是否跳过对 `FLAG_IDLE_UNTIL` 的提前裁切。
     *
     * 进入深睡后，`AlarmManagerService` 会在距下一次 `FLAG_WAKE_FROM_IDLE` 闹钟 2～15 分钟时
     * 将 `mPendingIdleUntil` 提前触发（≤ 2 分钟时设为立即触发），使设备很快切入 `IDLE_MAINTENANCE`。
     * 仅在模块强制深睡且启用 Doze 增强时跳过该裁切，保持 `IDLE(5)` 直至闹钟按时触发。
     */
    fun bypassAlarmIdleUntilFuzz(
        forceIdle: Boolean?,
        dozeEnabled: Boolean,
        alarmFlags: Int?,
    ): Boolean = forceIdle == true &&
        dozeEnabled &&
        alarmFlags != null &&
        (alarmFlags and ALARM_FLAG_IDLE_UNTIL) != 0

    /**
     * S42：强制推进经过前置阶段（`INACTIVE`～`LOCATING`，状态 1..4）时清零阻塞约束计数。
     *
     * `stepIdleStateLocked` 在无运动传感器回退分支检查 `mNumBlockingConstraints != 0` 且未豁免
     * `mForceIdle`，强制推进期间清零该计数可避免状态机停滞在前置阶段；非强制路径与已进入 `IDLE` 后不干预。
     */
    fun clearBlockingConstraintsWhileForced(
        forceIdle: Boolean?,
        dozeEnabled: Boolean,
        state: Int?,
    ): Boolean = forceIdle == true && dozeEnabled && state != null && state in 1..4

    /**
     * S40：`DeviceIdleController#addEvent` 的事件码折算成深度睡眠状态。
     *
     * `EVENT_DEEP_IDLE(4)` 与 `EVENT_DEEP_MAINTENANCE(5)` 是 ROM 自己记录的深度事件，
     * `EVENT_NORMAL(1)` 表示回到活动态；浅睡事件（2/3）与空事件（0）不需要参与深度统计，
     * 返回 null 让调用方直接丢弃。
     */
    fun dozeEventToState(eventCode: Int): Int? = when (eventCode) {
        4 -> 5
        5 -> 6
        1 -> 0
        else -> null
    }

    /**
     * P7/P8：PowerKeeper 同步 Doze 白名单时，是否放行这一次改动。
     *
     * `unrestrictedInDatabase` 取自 PowerKeeper `userTable.bgControl`，是系统设置里「省电策略」
     * 显示的真值。方向一致才放行（库中「无限制」时放行添加、非「无限制」时放行移除），
     * 方向相反说明 PowerKeeper 在自动改动用户的选择，仍然拦截。
     *
     * `null` 表示读不到该包的配置行（进程内读库失败、或库里确实没有这一行）：
     * 此时按「未知」拦截，与旧行为一致，避免在没有依据时放开自动改动。
     *
     * 只在「短时租约已过期」后才需要这个判断：租约覆盖的是设置刚改完的那一次同步。
     */
    fun allowPowerSaveMutation(isAdd: Boolean, unrestrictedInDatabase: Boolean?): Boolean =
        unrestrictedInDatabase != null && unrestrictedInDatabase == isAdd

    /**
     * P34：`AppClusterController#isControlApp(int)` 的接管结果。
     *
     * 返回 null 表示不接管、交回 ROM 判定（主用户应用都是这种情况）；
     * 双开 uid（999xxxxx）恒被 ROM 的 `uid <= 19999` 拒绝，这里改由偏好开关决定。
     */
    fun clusterControlResult(uid: Int, freezeDualApp: Boolean): Boolean? =
        if (AppUidUtils.isDualAppUid(uid)) freezeDualApp else null

    /**
     * powerkeeper 上报的 Hook 账本是否该覆盖服务端已缓存的那一份。
     *
     * 热重载时 `HookInstallRegistry.clear()` 之后账本是一条条重新写回的，此刻读出来的
     * [HookInstallRegistry.Wire.total] 只是「已记到第几条」，比完整账本小。而服务端
     * `restoreRegistrationSnapshot` 会把上一代的账本塞回去并触发一次重绑上报，
     * 两条上报的先后顺序不确定 —— 先到完整、后到残缺时，界面显示的点位数会偏少。
     *
     * 因此按 total 单调递增采纳：残缺账本的 total 必然小于完整账本，丢弃它即可；
     * total 相同说明规格表已经走完，此时 installed 的差异是真实的安装结果，照常采纳。
     */
    fun acceptLedgerReport(
        incoming: HookInstallRegistry.Wire,
        current: HookInstallRegistry.Wire
    ): Boolean {
        if (!incoming.hasData) return true
        if (!current.hasData) return true
        return incoming.total >= current.total
    }

    /** 息屏快速冻结钳位阈值：将 OS1–OS3 原生 62,000ms 延迟缩短至 5,000ms。 */
    const val FAST_SCREEN_OFF_FREEZE_MS = 5_000

    /**
     * P35/P36：将超过 5,000ms 的息屏延迟钳位至 5,000ms，更短的延迟原样返回。
     *
     * [fastScreenOffFreeze] 为 false 时直接返回原延迟。
     */
    fun clampScreenOffFreezeDelayMs(delayMs: Int, fastScreenOffFreeze: Boolean = true): Int =
        if (fastScreenOffFreeze && delayMs > FAST_SCREEN_OFF_FREEZE_MS) FAST_SCREEN_OFF_FREEZE_MS else delayMs
}
