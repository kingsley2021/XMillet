package io.github.univers629.millet.ui.screen.tombstats

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排行搜索行收放的几何约束：
 * - 收起完成后行高必须严格为 0（这一行完全不占位）；
 * - 尾部必须淡出到 0，否则被裁白的输入框顶边会闪成一条白边；
 * - 展开完成时行高为输入框高度 + 间距。
 */
class RankSearchCollapseTest {

    @Test
    fun fraction_isZeroAtAndBelowEpsilon() {
        assertEquals(0f, rankSearchRowFraction(0f), 0f)
        assertEquals(0f, rankSearchRowFraction(RankSearchRowEpsilon), 0f)
        assertEquals(0f, rankSearchRowFraction(RankSearchRowEpsilon / 2f), 0f)
        assertEquals(0f, rankSearchRowFraction(-1f), 0f)
        assertEquals(0f, rankSearchRowFraction(Float.NaN), 0f)
    }

    @Test
    fun fraction_isFullAtAndAboveOne() {
        assertEquals(1f, rankSearchRowFraction(1f), 0f)
        assertEquals(1f, rankSearchRowFraction(1.4f), 0f)
    }

    @Test
    fun fraction_isLinearInBetween() {
        assertEquals(0.25f, rankSearchRowFraction(0.25f), 1e-6f)
        assertEquals(0.5f, rankSearchRowFraction(0.5f), 1e-6f)
    }

    @Test
    fun height_collapsesCompletelyAndExpandsToFieldPlusSpacing() {
        assertEquals(0.dp, rankSearchRowHeight(0f))
        assertEquals(0.dp, rankSearchRowHeight(RankSearchRowEpsilon))
        // 45dp 输入框 + 10dp 与下方卡片的间距
        assertEquals(55.dp, rankSearchRowHeight(1f))
        assertEquals(27.5.dp, rankSearchRowHeight(0.5f))
    }

    @Test
    fun alpha_isZeroWhenCollapsedAndFullBeforeHalfway() {
        assertEquals(0f, rankSearchRowAlpha(0f), 0f)
        assertEquals(0f, rankSearchRowAlpha(RankSearchRowEpsilon), 0f)
        assertEquals(1f, rankSearchRowAlpha(RankSearchFadeInFraction), 0f)
        assertEquals(1f, rankSearchRowAlpha(1f), 0f)
        // 尾部只剩几 px 时已经几乎全透明，不会再闪出一条白边。
        assertTrue(rankSearchRowAlpha(RankSearchFadeInFraction / 4f) <= 0.25f)
    }

    @Test
    fun alpha_isMonotonic() {
        var previous = rankSearchRowAlpha(0f)
        var progress = 0f
        while (progress < 1f) {
            progress += 0.01f
            val current = rankSearchRowAlpha(progress)
            assertTrue("alpha 必须随进度单调不减：$progress -> $current", current >= previous)
            previous = current
        }
    }
}
