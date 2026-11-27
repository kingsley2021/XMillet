package io.github.univers629.millet.core

/**
 * 深睡补试策略：浅睡兜底后是否再排一次延迟深睡。
 *
 * 存在即将到来的唤醒闹钟时，ROM 的 `stepIdleStateLocked` 会在 INACTIVE 直接返回，
 * 单次熄屏只能停在浅睡。这里在浅睡兜底后延迟再试一次，
 * 等待 ROM 的 `MIN_TIME_TO_ALARM`（10 分钟）闹钟预唤醒窗口结束。
 *
 * 该行为会额外执行一次 `force-idle deep`，属于新增后台行为，因此默认关闭，由用户显式开启。
 */
object DozeRetryPolicy {

    /**
     * 补试延迟。
     *
     * 取 11 分钟：比 ROM 的 `MIN_TIME_TO_ALARM`（10 分钟）多留一分钟余量，
     * 避免补试恰好又落进同一个闹钟窗口。
     */
    const val RETRY_DELAY_MILLIS = 11 * 60 * 1000L

    /**
     * 判断是否应排一次补试。
     *
     * @param optIn 用户是否开启「闹钟后补试深睡」
     * @param dozeEnabled 模块的 Doze 增强是否开启
     * @param screenOn 当前是否亮屏（未知按不允许处理）
     * @param charging 当前是否充电（未知按不允许处理）
     * @param deepEntered 本次是否已进入深睡
     * @param retriedThisCycle 本次熄屏周期是否已经补试过
     */
    fun shouldSchedule(
        optIn: Boolean,
        dozeEnabled: Boolean,
        screenOn: Boolean?,
        charging: Boolean?,
        deepEntered: Boolean,
        retriedThisCycle: Boolean,
    ): Boolean = optIn && dozeEnabled && !deepEntered && !retriedThisCycle &&
        screenOn == false && charging == false
}
