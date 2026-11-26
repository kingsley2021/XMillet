package io.github.univers629.millet.ui.screen.tombstats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 墓碑统计页里「摘要行」的统一字号与字重。
 *
 * 这一组值最初只写在「息屏时系统解冻记录 x 次」这一行上，其它摘要（解冻记录、峰值时段、排行 Tab）
 * 各自抄了一份 16sp/Bold，改动后互相漂移。现在统一从这里取，别再在调用点硬编码。
 */
internal val StatsHeadlineFontSize: TextUnit = 16.sp

/** 见 [StatsHeadlineFontSize]。 */
internal val StatsHeadlineFontWeight: FontWeight = FontWeight.SemiBold

/**
 * 摘要行完整样式：字号、字重、颜色与「息屏时系统解冻记录 x 次」完全一致
 * （16sp / SemiBold / onSurface）。需要按选中态换色的地方（排行 Tab）
 * 用 [StatsHeadlineFontSize] + [StatsHeadlineFontWeight] 另配颜色。
 */
@Composable
internal fun rememberStatsHeadlineStyle(): TextStyle {
    val color = MiuixTheme.colorScheme.onSurface
    return remember(color) {
        TextStyle(
            fontSize = StatsHeadlineFontSize,
            fontWeight = StatsHeadlineFontWeight,
            color = color,
        )
    }
}
