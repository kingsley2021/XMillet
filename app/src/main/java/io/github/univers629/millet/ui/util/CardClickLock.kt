package io.github.univers629.millet.ui.util

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 全局卡片点击锁：卡片的按压、展开或跳转动画尚未播完时，忽略其它卡片的点击。
 *
 * 只有「拿到锁」的那一次点击会真正触发动作，窗口期内的后续点击直接被丢弃；
 * 窗口用 [SystemClock.elapsedRealtime] 判定，到点自动失效，因此不依赖协程或转场回调，
 * 任何异常路径（页面被销毁、转场被取消）都不会把界面永久锁死。
 *
 * 锁实例由 `XMilletMiuixTheme` 通过 [LocalCardClickLock] 下发，全应用共享一个。
 */
@Stable
class CardClickLock {

    private var locked = false
    private var lockedUntilMillis = 0L

    /** 申请一次点击窗口；返回 false 表示当前仍在上一段动画的窗口期内，应忽略本次点击。 */
    fun tryAcquire(windowMillis: Long = DefaultWindowMillis): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (locked && now < lockedUntilMillis) return false
        locked = true
        lockedUntilMillis = now + windowMillis
        return true
    }

    /** 主动释放（例如跳转被取消时立即恢复可点）。 */
    fun release() {
        locked = false
        lockedUntilMillis = 0L
    }

    companion object {
        /** 默认窗口：覆盖 Miuix Card 的按压回弹。 */
        const val DefaultWindowMillis: Long = 240L

        /** 页面跳转窗口：覆盖 NavDisplay 一次完整的入栈转场（500ms 程序化时长 + 余量）。 */
        const val NavigationWindowMillis: Long = 520L

        /** 卡片展开/收起窗口：覆盖折叠卡片的 AnimatedVisibility 过渡。 */
        const val ExpandWindowMillis: Long = 320L
    }
}

/** 全应用共享的卡片点击锁；未显式提供时退化为一个默认实例。 */
val LocalCardClickLock = staticCompositionLocalOf { CardClickLock() }
