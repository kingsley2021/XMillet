package io.github.univers629.millet.ui.util
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import top.yukonga.miuix.kmp.blur.Backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import io.github.univers629.millet.ui.theme.LocalBlurMode
import io.github.univers629.millet.ui.theme.LocalEnableProgressiveBlur
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.material.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun rememberBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    if (!enableBlur || !isRenderEffectSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun Modifier.milletMiuixBlurEffect(
    backdrop: LayerBackdrop?,
    enabled: Boolean = true,
    blurRadius: Float = 25f,
    shape: Shape = RectangleShape,
): Modifier {
    // 统一模糊开关：档位为「关闭」时任何调用点都不再绘制毛玻璃。
    val mode = LocalBlurMode.current
    if (!mode.enabled || !enabled || backdrop == null) return this
    val blendColor = MiuixTheme.colorScheme.surface.copy(alpha = 0.8f)
    return this.then(
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = blurRadius,
            colors = BlurColors(
                blendColors = listOf(
                    BlendColorEntry(color = blendColor),
                ),
            ),
        ),
    )
}

/**
 * 判定顶栏/底栏外壳是否应绘制整块均匀毛玻璃（textureBlur）：
 * - 顶栏（progressiveSupported = true）在开启渐进式模糊时交由 GlassTopAppBar 自行绘制边缘渐隐；
 * - 经典非悬浮底栏（progressiveSupported = false）参照 Miuix 组件库规范不使用渐进式模糊，开启模糊时保持均匀毛玻璃。
 */
internal fun shouldApplyUniformBarBlur(
    blurActive: Boolean,
    progressiveSupported: Boolean,
    enableProgressiveBlur: Boolean,
    hasBackdrop: Boolean,
): Boolean {
    if (!blurActive || !hasBackdrop) return false
    val progressive = progressiveSupported && enableProgressiveBlur
    return !progressive
}

/**
 * 顶栏/底栏外壳：向内下发背景采样源（[LocalBarBackdrop]）。
 * 支持渐进式模糊的顶栏在开启渐进式模糊时由 GlassTopAppBar 自行绘制，这里不再叠整块 textureBlur；
 * 经典底栏传入 `progressiveSupported = false`，开启背景模糊时始终保持均匀毛玻璃。
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = true,
    progressiveSupported: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val uniformBlur = shouldApplyUniformBarBlur(
        blurActive = blurActive,
        progressiveSupported = progressiveSupported,
        enableProgressiveBlur = LocalEnableProgressiveBlur.current,
        hasBackdrop = backdrop != null,
    )
    CompositionLocalProvider(LocalBarBackdrop provides backdrop) {
        Box(
            modifier = modifier.then(
                if (uniformBlur && backdrop != null) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 25f,
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.87f)),
                            ),
                        ),
                    )
                } else {
                    Modifier
                }
            ),
        ) {
            content()
        }
    }
}

/**
 * 全局弹窗 / 对话框 / 底部面板的背景采样源（根布局录制，不含弹窗自身）。
 * 关闭背景模糊时为 null，Glass 组件回退为不透明底色。
 */
object PopupBackdropHolder {
    var current by mutableStateOf<Backdrop?>(null)
}
