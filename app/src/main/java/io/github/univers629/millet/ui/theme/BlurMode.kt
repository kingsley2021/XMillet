package io.github.univers629.millet.ui.theme

import androidx.compose.runtime.compositionLocalOf

/**
 * 应用内背景模糊的统一档位，对应「外观个性化 → 背景模糊」的三个选项。
 *
 * 全应用只从这里读取模糊档位：顶栏 / 底栏 / 弹窗 / 卡片玻璃 / OOBE 等所有用到
 * blur、backdrop、glass 的地方都通过 [LocalBlurMode]（或派生的 [LocalEnableBlur]、
 * [LocalEnableProgressiveBlur]）决定是否绘制模糊，避免各处开关各管一段。
 */
enum class BlurMode(val preferenceIndex: Int) {
    /** 关闭：所有模糊回退为不透明或半透明纯色背景。 */
    Off(0),

    /** 高斯模糊：整块均匀毛玻璃（textureBlur）。 */
    Gaussian(1),

    /** 渐进模糊：能用边缘渐隐的地方（顶栏）走渐进模糊，其余保持均匀毛玻璃。 */
    Progressive(2);

    /** 是否绘制任何模糊。 */
    val enabled: Boolean
        get() = this != Off

    /** 是否允许使用渐进式模糊。 */
    val progressive: Boolean
        get() = this == Progressive

    companion object {
        /** 按设置项索引（0 关闭 / 1 高斯 / 2 渐进）解析，越界回退到高斯。 */
        fun fromPreferenceIndex(index: Int): BlurMode = when (index) {
            0 -> Off
            2 -> Progressive
            else -> Gaussian
        }
    }
}

/** 当前生效的模糊档位；默认渐进，与旧版两个布尔开关同时开启时的行为一致。 */
val LocalBlurMode = compositionLocalOf { BlurMode.Progressive }
