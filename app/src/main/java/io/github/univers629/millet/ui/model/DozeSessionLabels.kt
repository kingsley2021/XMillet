package io.github.univers629.millet.ui.model

/**
 * 这条深睡会话是否来自开机事件回填。
 *
 * 回填只还原起止时间与维护次数，电量快照与 CPU 明细都不存在，因此两端电量都是 -1、
 * 且没有任何应用用时记录；实时观测到的会话即便掉电为 0 也带着真实电量。
 */
fun DozeSessionUiModel.isBackfilled(): Boolean =
    startBatteryPct < 0 && endBatteryPct < 0 && topApps.isEmpty()

/**
 * 会话右半侧的补充说明，按优先级依次为：充电状态、电量变化区间、开机回填、电量未知。
 *
 * 充电会话本身也可能缺电量，因此充电优先于电量区间；回填判断放在电量区间之后，
 * 避免把异常电量值误标成回填。
 */
fun DozeSessionUiModel.batteryLabel(): String = when {
    chargedDuringSession -> "充电中"
    startBatteryPct in 0..100 && endBatteryPct in 0..100 -> "$startBatteryPct%→$endBatteryPct%"
    isBackfilled() -> "开机回填"
    else -> "电量未知"
}

/** 会话行右侧完整文本 */
fun DozeSessionUiModel.summaryLabel(): String = "维护 $maintenanceCount 次 · ${batteryLabel()}"

/**
 * 列表里的会话是否全部来自开机回填。
 *
 * 全部回填说明该时间段未记录到实时状态迁移：仅还原时长与维护次数，电量与 CPU 明细
 * 均缺失，需要在界面说明原因。
 */
fun DozeSummaryUiModel.allSessionsBackfilled(): Boolean =
    recentSessions.isNotEmpty() && recentSessions.all { it.isBackfilled() }
