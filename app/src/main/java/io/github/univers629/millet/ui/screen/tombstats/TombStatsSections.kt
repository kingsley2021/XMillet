package io.github.univers629.millet.ui.screen.tombstats

import io.github.univers629.millet.ui.component.ProgressiveDialogLayout
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.InputField
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.millet.core.StatsEventKind
import io.github.univers629.millet.core.StatsTimeBucket
import io.github.univers629.millet.core.StatsWindow
import io.github.univers629.millet.core.WakeCategory
import io.github.univers629.millet.ui.component.StatsAppIcon
import io.github.univers629.millet.ui.component.StatusTagMiuix
import io.github.univers629.millet.ui.component.WarningCard
import io.github.univers629.millet.ui.component.WarningLevel
import io.github.univers629.millet.ui.model.TombSection
import io.github.univers629.millet.ui.model.AppFreezeStats
import io.github.univers629.millet.ui.model.DozeSummaryUiModel
import io.github.univers629.millet.ui.model.FreezeStatsSnapshot
import io.github.univers629.millet.ui.model.RankMode
import io.github.univers629.millet.ui.model.StatsRecentEvent
import io.github.univers629.millet.ui.model.originLabel
import io.github.univers629.millet.ui.theme.isInDarkTheme
import io.github.univers629.millet.ui.util.PopupBackdropHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Filter
import top.yukonga.miuix.kmp.icon.glass.QuietMode
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 排行搜索收放的统一曲线：搜索行高度、输入框宽度与下方排行卡片的内容高度都走同一个 spring，
 * 收尾时不会出现两条曲线相撞的顿挫。
 */
private val RankSearchSizeSpec: SpringSpec<IntSize> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntSize.VisibilityThreshold,
)

/** 搜索收放进度（0 = 收起，1 = 展开）的动画曲线，与 [RankSearchSizeSpec] 同参。 */
private val RankSearchProgressSpec: SpringSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** 搜索行（Miuix InputField）的固有高度，行高按进度比例裁切。 */
private val RankSearchFieldHeight = 45.dp

/** 搜索行与上方 Tab 栏、下方排行卡片之间的统一间距。 */
private val RankSearchRowSpacing = 10.dp

/** 搜索行连同它与下方卡片的间距：一起随进度收起，收起结束不会留下空隙。 */
private val RankSearchRowTotalHeight = RankSearchFieldHeight + RankSearchRowSpacing

/** 低于这个进度就当作完全收起：剩余高度不足 0.1dp，直接移出组合。 */
internal const val RankSearchRowEpsilon = 0.002f

/**
 * 搜索行的高度 / 宽度比例。progress 归零（含动画尾部的极小值）时严格返回 0，
 * 保证收起完成后这一行既不占位也不绘制。
 */
internal fun rankSearchRowFraction(progress: Float): Float = when {
    progress.isNaN() || progress <= RankSearchRowEpsilon -> 0f
    progress >= 1f -> 1f
    else -> progress
}

/**
 * 搜索行的整行透明度：前 [RankSearchFadeInFraction] 段完成淡入。
 *
 * 只裁高度的话，收起尾部会留下一条被裁白的输入框顶边（InputField 的 capsule 背景），
 * 看起来就是「闪一条白边」；按同一进度淡出后，行高只剩几 px 时已经完全透明。
 */
internal fun rankSearchRowAlpha(progress: Float): Float =
    (rankSearchRowFraction(progress) / RankSearchFadeInFraction).coerceIn(0f, 1f)

/** 搜索行淡入淡出所占的进度区间。 */
internal const val RankSearchFadeInFraction = 0.35f

/** 搜索行的占位高度（含它与下方卡片的间距）；收起完成后严格为 0，这一行不占位。 */
internal fun rankSearchRowHeight(progress: Float): Dp =
    RankSearchRowTotalHeight * rankSearchRowFraction(progress)

/** 排行模式 Tab 栏的几何参数。 */
private val RankTabHeight = 42.dp
private val RankTabPadding = 3.dp
private val RankTabGap = 3.dp
private val RankTabCornerRadius = 12.dp
private val RankTabIndicatorCornerRadius = 10.dp

/** 排行榜 Tab 指示器的滑动曲线：轻微回弹的 spring，切换干脆但不生硬。 */
private val RankTabIndicatorSpec = spring<Dp>(dampingRatio = 0.78f, stiffness = 420f)

/** 排行榜 Tab 的按压反馈曲线。 */
private val RankTabPressSpec = spring<Float>(dampingRatio = 0.55f, stiffness = 900f)

@Composable
private fun WakeCategory.displayColor(): Color {
    val dark = isInDarkTheme()
    return when (this) {
        WakeCategory.USER_OPEN -> if (dark) Color(0xFF4D94FF) else Color(0xFF2979FF)
        WakeCategory.NETWORK -> if (dark) Color(0xFFFF9F0A) else Color(0xFFF57C00)
        WakeCategory.BINDER -> if (dark) Color(0xFFBF5AF2) else Color(0xFF9C27B0)
        WakeCategory.BROADCAST -> if (dark) Color(0xFF5AC8FA) else Color(0xFF0288D1)
        WakeCategory.SCREEN_ON -> if (dark) Color(0xFFFFD60A) else Color(0xFFD99B00)
        WakeCategory.CHARGING -> if (dark) Color(0xFF30D158) else Color(0xFF2E9E49)
        WakeCategory.OTHER -> if (dark) Color(0xFF98989D) else Color(0xFF787880)
        WakeCategory.MODULE_OPERATION -> if (dark) Color(0xFF64D2FF) else Color(0xFF007B9E)
        WakeCategory.LAUNCH_MODE -> if (dark) Color(0xFFFFB86C) else Color(0xFFB36B00)
    }
}

@Composable
private fun screenOffAccentColor(): Color = Color(0xFF7D7AFF)

@Composable
private fun statsSuccessColor(): Color = if (isInDarkTheme()) Color(0xFF30D158) else Color(0xFF2E9E49)

@Composable
private fun statsProtectColor(): Color = if (isInDarkTheme()) Color(0xFF5AC8FA) else Color(0xFF0288D1)

@Composable
private fun statsWarningTextColor(): Color = if (isInDarkTheme()) Color(0xFFFF9F0A) else Color(0xFFD97706)

/** 统计主体：概览 → 息屏 → 原因 → 时段 → 拦截 → 排行 → 最近动态 → 页脚 */
internal fun LazyListScope.statsContent(
    snap: FreezeStatsSnapshot,
    visibleSections: Set<String> = TombSection.allIds,
    rankModeIndex: Int,
    onRankModeChange: (Int) -> Unit,
    onOpenDetail: (AppFreezeStats) -> Unit,
) {
    item(key = "summary_grid") {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    title = "冻结次数",
                    value = formatCount(snap.freezeCount),
                    unit = "次",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    title = "后台唤醒",
                    value = formatCount(snap.backgroundWakeCount),
                    unit = "次",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    title = "拦截唤醒",
                    value = formatCount(snap.interceptCount),
                    unit = "次",
                    modifier = Modifier.weight(1f),
                    accent = true,
                )
                val backgroundWakeAppCount = snap.apps.asSequence()
                    .filter { it.backgroundWakeCount > 0 }
                    .map { it.uid }
                    .distinct()
                    .count()
                StatTile(
                    title = "后台唤醒应用数",
                    value = formatCount(backgroundWakeAppCount),
                    unit = "个",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    item(key = "screen_off_banner") {
        ScreenOffBanner(snap)
    }

    if ("wake_reasons" in visibleSections) item(key = "wake_reasons") {
        SmallTitle(text = "系统解冻记录 · 原因")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(16.dp),
        ) {
            WakeReasonSection(snap.wakeByCategory)
        }
    }

    if (snap.buckets.isNotEmpty() && "time_distribution" in visibleSections) {
        item(key = "time_distribution") {
            SmallTitle(
                text = if (snap.window == StatsWindow.TODAY) "系统解冻记录 · 每小时" else "系统解冻记录 · 每天",
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                TimeDistributionSection(
                    window = snap.window,
                    buckets = snap.buckets,
                )
            }
        }
    }

    if ("doze" in visibleSections) item(key = "doze_attribution") {
        DozeAttributionCard(
            summary = snap.dozeSummary,
            apps = snap.apps,
            onOpenDetail = onOpenDetail,
        )
    }

    if ("intervention" in visibleSections) item(key = "interventions") {        SmallTitle(text = "XMillet 干预")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(16.dp),
        ) {
            InterventionSection(snap)
        }
    }

    if ("rank" in visibleSections) item(key = "app_ranking") {
        AppRankSection(
            apps = snap.apps,
            modeIndex = rankModeIndex,
            onModeIndexChange = onRankModeChange,
            onOpenDetail = onOpenDetail,
        )
    }

    if (snap.recent.isNotEmpty() && "recent" in visibleSections) {
        item(key = "recent_events") {
            RecentEventsSection(
                events = snap.recent,
                apps = snap.apps,
                onOpenDetail = onOpenDetail,
            )
        }
    }

    item(key = "footer") {
        StatsFooter(snap)
    }
}

@Composable
private fun StatTile(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Card(
        modifier = modifier.height(IntrinsicSize.Min),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = value,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (accent) colorScheme.primary else colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = unit,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun ScreenOffBanner(snap: FreezeStatsSnapshot) {
    val count = snap.screenOffWakeCount
    val interval = if (count > 0 && snap.screenOffMillis > 0) snap.screenOffMillis / count else 0L
    val accent = screenOffAccentColor()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Glass.QuietMode,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "息屏时系统解冻记录 ${formatCount(count)} 次",
                    style = rememberStatsHeadlineStyle(),
                )
                Text(
                    text = when {
                        count == 0 -> "暂时没有记录，不代表所有应用一直冻结"
                        interval > 0 -> "所有应用合计平均每 ${formatInterval(interval)} 一条记录，不是单个应用的唤醒间隔"
                        else -> "这是系统记录，不能直接换算成消息条数或耗电量"
                    },
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun DozeAttributionCard(
    summary: DozeSummaryUiModel,
    apps: List<AppFreezeStats>,
    onOpenDetail: (AppFreezeStats) -> Unit,
) {
    var showSessions by rememberSaveable { mutableStateOf(false) }
    val appsByUid = remember(apps) { apps.associateBy { it.uid } }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        if (summary.sessionCount == 0) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "暂无深度待机归因数据",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = "息屏进入深度 Doze（IDLE / MAINTENANCE）连续满 30 秒并退出后，将自动汇总待机时长、纯净掉电速率与后台 CPU 耗时排行。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            return@Card
        }

        val dropPerHourText = when {
            summary.unpluggedDurationMillis >= 180_000L -> {
                val hours = summary.unpluggedDurationMillis / 3_600_000f
                "%.1f%%/h".format(Locale.ROOT, summary.netBatteryDropPct / hours)
            }
            summary.unpluggedDurationMillis > 0L -> "未满3分钟"
            summary.recentSessions.any { it.chargedDuringSession } -> "充电会话"
            else -> "待积累"
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("深睡时长", fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                    Text(
                        text = formatDuration(summary.totalDurationMillis),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = "${summary.sessionCount} 次 · 维护 ${summary.maintenanceCount} 次",
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("纯净待机掉电", fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                    Text(
                        text = dropPerHourText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = if (summary.cpuSource == io.github.univers629.millet.core.CpuStatSource.NONE) {
                            "来源: 系统历史记录"
                        } else {
                            "来源: ${summary.cpuSource.label}"
                        },
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (summary.apps.isNotEmpty()) {
                val topApps = summary.apps.take(5)
                val maxCpu = topApps.maxOfOrNull { it.cpuMillis }?.coerceAtLeast(1L) ?: 1L
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "深睡期后台 CPU 消耗 Top ${topApps.size}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    topApps.forEach { app ->
                        val matched = appsByUid[app.uid]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .then(if (matched != null) Modifier.clickable { onOpenDetail(matched) } else Modifier)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatsAppIcon(
                                iconSource = app.iconSource,
                                members = app.members,
                                label = app.label,
                                modifier = Modifier.padding(end = 10.dp).size(30.dp),
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = app.label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "%.1fs".format(Locale.ROOT, app.cpuMillis / 1000f),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colorScheme.onSurface,
                                    )
                                }
                                ProgressLine(
                                    fraction = (app.cpuMillis.toFloat() / maxCpu).coerceIn(0.05f, 1f),
                                    color = screenOffAccentColor(),
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = if (summary.cpuSource == io.github.univers629.millet.core.CpuStatSource.NONE) {
                        "历史补齐会话不含单应用 CPU 明细，后续新进入的深睡将自动统计排行。"
                    } else {
                        "本次深睡期间未检测到 ≥100ms 的应用 CPU 增量。"
                    },
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }

            if (summary.recentSessions.isNotEmpty()) {
                // 与排行「查看全部 n 个应用」行同一套样式：不要底色、不要按压层，
                // 只有居中的标题色文字 + 灰色 chevron，展开态靠 chevron 旋转表达。
                val sessionArrowRotation by animateFloatAsState(
                    targetValue = if (showSessions) 90f else 0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                    label = "dozeSessionArrow",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { showSessions = !showSessions }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (showSessions) "收起最近待机分段" else "最近待机分段 (${summary.recentSessions.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = MiuixIcons.Glass.ChevronForward,
                        contentDescription = if (showSessions) "收起最近待机分段" else "展开最近待机分段",
                        tint = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = sessionArrowRotation },
                    )
                }
                // 高度伸缩同样走 spring（与折叠卡片一致），展开与收起都不再出现一帧跳变。
                AnimatedVisibility(
                    visible = showSessions,
                    enter = expandVertically(
                        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, IntSize.VisibilityThreshold),
                        expandFrom = Alignment.Top,
                    ),
                    exit = shrinkVertically(
                        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, IntSize.VisibilityThreshold),
                        shrinkTowards = Alignment.Top,
                    ),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        summary.recentSessions.take(6).forEach { s ->
                            val batText = when {
                                s.chargedDuringSession -> "充电中"
                                s.startBatteryPct in 0..100 && s.endBatteryPct in 0..100 ->
                                    "${s.startBatteryPct}%→${s.endBatteryPct}%"
                                else -> "电量未知"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${formatClock(s.startMillis)}–${formatClock(s.endMillis)} · ${formatDuration(s.durationMillis)}",
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "维护 ${s.maintenanceCount} 次 · $batText",
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WakeReasonSection(wake: Map<WakeCategory, Int>) {
    val total = wake.values.sum()
    val safeTotal = total.coerceAtLeast(1)
    val bgCount = remember(wake) { wake.entries.sumOf { if (it.key.isBackground) it.value else 0 } }
    val userCount = wake[WakeCategory.USER_OPEN] ?: 0
    // 排序只跟入参有关，缓存下来避免每次重组都重排。
    val ordered = remember(wake) { wake.entries.filter { it.value > 0 }.sortedByDescending { it.value } }
    // displayColor() 是 @Composable，不能塞进 remember 里，只能每次重组现算。
    val segments = ordered.map { it.key.displayColor() to it.value.toFloat() }
    // 进度条整段只播一次生长动画：700ms 后新进入组合的行直接显示满值，
    // 否则滚动中每行都重播 550ms 补间，叠加起来就是下滑掉帧。
    var animateBars by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(700)
        animateBars = false
    }

    Column(Modifier.fillMaxWidth()) {
        if (total > 0) {
            // 两行排布：摘要与「解冻记录」不再各占一半争宽，16sp 也不会折行。
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "解冻记录 ${formatCount(total)} 次",
                    style = rememberStatsHeadlineStyle(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "后台活动 ${formatCount(bgCount)} · 前台活动 ${formatCount(userCount)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        SegmentedBar(
            segments = segments,
            animateOnEnter = animateBars,
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
        )
        Spacer(Modifier.height(14.dp))
        ordered.forEach { (cat, n) ->
            val pct = n * 100f / safeTotal
            val pctText = if (pct < 1f) "<1%" else "${"%.0f".format(Locale.ROOT, pct)}%"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(cat.displayColor()),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = cat.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatCount(n),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = pctText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.width(42.dp),
                )
            }
        }
        if (ordered.isEmpty()) {
            Text("暂无系统解冻记录", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun TimeDistributionSection(
    window: StatsWindow,
    buckets: List<StatsTimeBucket>,
) {
    var selected by remember(window) { mutableIntStateOf(-1) }
    val onColor = colorScheme.primary
    val offColor = screenOffAccentColor()
    // 峰值与峰值下标只跟分桶数据有关，缓存起来避免每次重组都重算一遍。
    val maxTotal = remember(buckets) { buckets.maxOfOrNull { it.total } ?: 0 }
    val peakIndex = remember(buckets, maxTotal) {
        if (maxTotal > 0) buckets.indices.maxByOrNull { buckets[it].total } ?: -1 else -1
    }
    val showIndex = if (selected in buckets.indices) selected else peakIndex

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                if (showIndex >= 0) {
                    val b = buckets[showIndex]
                    val prefix = if (selected in buckets.indices) "已选 · " else "峰值 · "
                    val suffix = if (buckets.size == 24) " 时" else ""
                    Text(
                        text = "$prefix${b.label}$suffix  共 ${formatCount(b.total)} 次",
                        style = rememberStatsHeadlineStyle(),
                    )
                    Text(
                        text = "亮屏 ${formatCount(b.screenOnWakes)} · 息屏 ${formatCount(b.screenOffWakes)}",
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    Text(
                        text = "本时段暂无系统解冻高峰",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "点按柱体可查看该时段亮屏与息屏唤醒明细",
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (maxTotal > 0) {
                Text(
                    text = "峰 ${formatCount(maxTotal)}",
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        StackedBarChart(
            window = window,
            buckets = buckets,
            selectedIndex = selected,
            onSelect = { selected = if (selected == it) -1 else it },
            onColor = onColor,
            offColor = offColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        )
        Spacer(Modifier.height(6.dp))
        if (buckets.size == 24) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("00", "06", "12", "18", "23").forEach { h ->
                    Text(
                        text = h,
                        fontSize = 10.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                buckets.forEach { b ->
                    Text(
                        text = b.label,
                        fontSize = 10.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(onColor, "亮屏")
            Spacer(Modifier.width(16.dp))
            LegendDot(offColor, "息屏")
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun InterventionSection(snap: FreezeStatsSnapshot) {
    val rules = snap.interceptByRule.entries.filter { it.value > 0 }.sortedByDescending { it.value }
    val maxRule = rules.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rules.isEmpty()) {
            Text("本时段内没有拦截唤醒", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
        } else {
            rules.forEach { (rule, n) ->
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            rule.label,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(formatCount(n), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(fraction = n.toFloat() / maxRule, color = colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniMetric("保护免冻", snap.protectedCount, Modifier.weight(1f))
            MiniMetric("阻止查杀", snap.killBlockedCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniMetric(
    label: String,
    value: Int?,
    modifier: Modifier = Modifier,
    containerColor: Color = colorScheme.surfaceContainerHighest,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
        Text(value?.let(::formatCount) ?: "—", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface)
    }
}

@Composable
private fun AppRankSection(
    apps: List<AppFreezeStats>,
    modeIndex: Int,
    onModeIndexChange: (Int) -> Unit,
    onOpenDetail: (AppFreezeStats) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var hideSystemApps by rememberSaveable { mutableStateOf(false) }
    val mode = RankMode.entries.getOrElse(modeIndex) { RankMode.WAKE }
    val hasSystemApps = remember(apps) { apps.any { it.isSystem } }
    val focusManager = LocalFocusManager.current

    val baseSorted = remember(apps, mode) {
        when (mode) {
            RankMode.WAKE -> apps.filter { it.backgroundWakeCount > 0 }
                .sortedByDescending { it.backgroundWakeCount }
            RankMode.FROZEN -> apps.filter { it.frozenMillis > 0 }.sortedByDescending { it.frozenMillis }
            RankMode.INTERCEPT -> apps.filter { it.interceptCount > 0 }.sortedByDescending { it.interceptCount }
            RankMode.SYSTEM_RECORDS -> apps.filter { it.freezeCount > 0 || it.wakeByCategory.values.any { count -> count > 0 } }
                .sortedByDescending { it.freezeCount.toLong() + it.wakeByCategory.values.sumOf { count -> count.toLong() } }
        }
    }
    val sorted = remember(baseSorted, hideSystemApps) {
        if (hideSystemApps) baseSorted.filter { !it.isSystem } else baseSorted
    }
    // 搜索收放只用一个进度值：它同时驱动搜索行高度、输入框宽度与下方排行卡片位移。
    // 搜索按钮固定在标题行里，不参与动画、也不再 Crossfade 互换图标，所以收尾不会出现图标复位或跳动。
    val searchProgress by animateFloatAsState(
        targetValue = if (searchVisible) 1f else 0f,
        animationSpec = RankSearchProgressSpec,
        label = "rankSearchProgress",
    )
    // 收起动画期间继续保留过滤结果，等进度真正归零后才清空查询词并恢复完整排行，
    // 避免动画进行到一半时列表先跳变。
    val searchSettledCollapsed = !searchVisible && searchProgress <= 0.001f
    val activeQuery = if (searchSettledCollapsed) "" else searchQuery
    LaunchedEffect(searchSettledCollapsed) {
        if (searchSettledCollapsed && searchQuery.isNotEmpty()) {
            searchQuery = ""
        }
    }
    val filtered = remember(sorted, activeQuery) {
        val q = activeQuery.trim().lowercase()
        if (q.isEmpty()) sorted
        else sorted.filter {
            it.label.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q) ||
                it.uid.toString().contains(q)
        }
    }
    val maxValue = remember(sorted, mode) {
        sorted.maxOfOrNull { rankValue(it, mode) }?.coerceAtLeast(1f) ?: 1f
    }
    val shown = if (expanded || activeQuery.isNotBlank()) filtered else filtered.take(10)
    // 排行进度条整段只播一次生长动画：700ms 后新进入组合的行直接显示满值，
    // 避免滚动时每行都重播 550ms 补间。
    var animateBars by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(700)
        animateBars = false
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "应用排行",
                style = MiuixTheme.textStyles.subtitle,
                color = colorScheme.onBackgroundVariant,
                modifier = Modifier.weight(1f),
            )
            if (hasSystemApps) {
                IconButton(
                    onClick = { hideSystemApps = !hideSystemApps },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Glass.Filter,
                        contentDescription = if (hideSystemApps) "显示系统应用" else "隐藏系统应用",
                        tint = if (hideSystemApps) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (baseSorted.isNotEmpty()) {
                IconButton(
                    onClick = {
                        val next = !searchVisible
                        searchVisible = next
                        if (!next) focusManager.clearFocus()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    // 图标固定在标题行末尾，只用配色表达开合状态，避免切换图标造成的位置跳动。
                    Icon(
                        imageVector = MiuixIcons.Basic.Search,
                        contentDescription = if (searchVisible) "收起搜索" else "搜索应用排行",
                        tint = if (searchVisible) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(RankSearchRowSpacing))

        RankModeTabRow(
            tabs = RankMode.entries.map { it.label },
            selectedIndex = modeIndex,
            onSelect = onModeIndexChange,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        Spacer(Modifier.height(RankSearchRowSpacing))

        // 搜索行高度与输入框宽度共用同一个 progress，收起结束时行高为 0 才移出组合，因此不会跳变。
        RankSearchField(
            progress = searchProgress,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .animateContentSize(RankSearchSizeSpec),
        ) {
            if (shown.isEmpty()) {
                if (activeQuery.isNotBlank()) {
                    BasicComponent(
                        title = "未找到匹配的应用",
                        summary = "点击清空搜索词",
                        onClick = { searchQuery = "" },
                    )
                } else {
                    BasicComponent(title = "暂无数据", summary = "本时段内没有符合条件的应用")
                }
            }
            shown.forEachIndexed { i, app ->
                RankRow(
                    rank = i + 1,
                    app = app,
                    mode = mode,
                    fraction = rankValue(app, mode) / maxValue,
                    animateOnEnter = animateBars,
                    onClick = { onOpenDetail(app) },
                )
            }
            if (filtered.size > 10 && activeQuery.isBlank()) {
                val moreArrowRotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                    label = "rankMoreArrow",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (expanded) "收起" else "查看全部 ${filtered.size} 个应用",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = MiuixIcons.Glass.ChevronForward,
                        contentDescription = if (expanded) "收起" else "查看全部",
                        tint = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = moreArrowRotation },
                    )
                }
            }
        }
    }
}

/**
 * 排行搜索行：行高 = [RankSearchRowTotalHeight] × [rankSearchRowFraction]，
 * 输入框宽度 = 可用宽度 × 同一比例，整行透明度按 [rankSearchRowAlpha] 收尾。
 *
 * 收起尾部只裁高度会残留一条被裁白的输入框顶边，所以最后一段同时把整行淡出；
 * 高度里已经包含它与下方卡片的间距，比例归零后直接移出组合，这一行完全不留位。
 */
@Composable
private fun RankSearchField(
    progress: Float,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = rankSearchRowFraction(progress)
    if (fraction <= 0f) return
    val rowAlpha = rankSearchRowAlpha(progress)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rankSearchRowHeight(progress))
            .graphicsLayer { alpha = rowAlpha }
            .clipToBounds(),
        contentAlignment = Alignment.TopStart,
    ) {
        // 输入框保持固有高度与展开态，由外层同时按比例裁切高度、收缩宽度。
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                .height(RankSearchFieldHeight),
        ) {
            InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { },
                expanded = true,
                onExpandedChange = { },
                label = "搜索应用名、包名或 UID",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 排行模式的等分 Tab 栏：Miuix 轨道 + 单个滑动指示器。
 *
 * - 指示器用 spring 平滑滑到目标项，不再硬切；
 * - 四项等宽由 [BoxWithConstraints] 现场计算，窄屏下四个汉字也不会被截断；
 * - 每个 Tab 持有自己的 interactionSource，按下时轻微缩放作为按压反馈；
 * - 不使用 Material TabRow 的下划线指示器。
 */
@Composable
private fun RankModeTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(RankTabHeight)
            .squircleBackground(color = colorScheme.surface, cornerRadius = RankTabCornerRadius),
    ) {
        val safeIndex = selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        val tabWidth = if (tabs.isEmpty()) {
            0.dp
        } else {
            ((maxWidth - RankTabPadding * 2 - RankTabGap * (tabs.size - 1)) / tabs.size).coerceAtLeast(0.dp)
        }
        val indicatorOffset by animateDpAsState(
            targetValue = RankTabPadding + (tabWidth + RankTabGap) * safeIndex,
            animationSpec = RankTabIndicatorSpec,
            label = "rankTabIndicator",
        )
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset, y = RankTabPadding)
                .size(width = tabWidth, height = RankTabHeight - RankTabPadding * 2)
                .squircleBackground(color = colorScheme.surfaceContainer, cornerRadius = RankTabIndicatorCornerRadius),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(RankTabPadding),
            horizontalArrangement = Arrangement.spacedBy(RankTabGap),
        ) {
            tabs.forEachIndexed { index, label ->
                val selected = index == safeIndex
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val pressScale by animateFloatAsState(
                    targetValue = if (pressed) 0.93f else 1f,
                    animationSpec = RankTabPressSpec,
                    label = "rankTabPress$index",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        }
                        .squircleClip(cornerRadius = RankTabIndicatorCornerRadius)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = StatsHeadlineFontSize,
                        fontWeight = StatsHeadlineFontWeight,
                        color = if (selected) colorScheme.onBackground else colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

private fun rankValue(app: AppFreezeStats, mode: RankMode): Float = when (mode) {
    RankMode.WAKE -> app.backgroundWakeCount.toFloat()
    RankMode.FROZEN -> app.frozenMillis.toFloat()
    RankMode.INTERCEPT -> app.interceptCount.toFloat()
    RankMode.SYSTEM_RECORDS -> app.freezeCount.toFloat() + app.wakeByCategory.values.sumOf { it.toDouble() }.toFloat()
}

@Composable
private fun RankRow(
    rank: Int,
    app: AppFreezeStats,
    mode: RankMode,
    fraction: Float,
    animateOnEnter: Boolean,
    onClick: () -> Unit,
) {
    val barFraction = fraction.coerceIn(0.03f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (rank <= 3) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(22.dp),
        )
        StatsAppIcon(
            iconSource = app.iconSource,
            members = app.members,
            label = app.label,
            modifier = Modifier
                .padding(end = 12.dp)
                .size(38.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = app.label,
                        fontSize = 15.sp,
                        fontWeight = FontWeight(550),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (app.members.size > 1) SharedUidTag()
                    if (app.isDualApp) {
                        StatusTagMiuix(
                            label = "双开",
                            contentColor = colorScheme.primary,
                            backgroundColor = colorScheme.primary.copy(alpha = 0.12f),
                            fontSize = 10.sp,
                            paddingValues = PaddingValues(horizontal = 5.dp, vertical = 1.dp),
                        )
                    } else if (app.isSystem) {
                        StatusTagMiuix(
                            label = "系统",
                            contentColor = colorScheme.onSurfaceVariantSummary,
                            backgroundColor = colorScheme.surfaceContainerHigh,
                            fontSize = 10.sp,
                            paddingValues = PaddingValues(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (mode) {
                        RankMode.WAKE -> "${formatCount(app.backgroundWakeCount)} 次"
                        RankMode.FROZEN -> formatDuration(app.frozenMillis)
                        RankMode.INTERCEPT -> "${formatCount(app.interceptCount)} 次"
                        RankMode.SYSTEM_RECORDS -> "冻结 ${formatCount(app.freezeCount)} · 解冻 ${formatCount(app.wakeByCategory.values.sum())}"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(5.dp))
            if (mode == RankMode.WAKE) {
                // 过滤与排序只跟 app 有关，缓存下来；配色依赖主题色（@Composable），只能每次现算。
                val wakeEntries = remember(app) {
                    app.wakeByCategory.entries
                        .filter { it.key.isBackground && it.value > 0 }
                        .sortedByDescending { it.value }
                }
                val segs = wakeEntries.map { it.key.displayColor() to it.value.toFloat() }
                SegmentedBar(
                    segments = segs,
                    fillFraction = barFraction,
                    animateOnEnter = animateOnEnter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
            } else {
                ProgressLine(fraction = barFraction, color = colorScheme.primary)
            }
            if (mode == RankMode.FROZEN && app.observedMillis > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "记账冻结占比 ${"%.0f".format(Locale.ROOT, app.frozenRatio * 100)}%",
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun SharedUidTag() {
    StatusTagMiuix(
        label = "共用统计",
        contentColor = colorScheme.primary,
        backgroundColor = colorScheme.primary.copy(alpha = 0.12f),
        fontSize = 10.sp,
        paddingValues = PaddingValues(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun RecentEventsSection(
    events: List<StatsRecentEvent>,
    apps: List<AppFreezeStats>,
    onOpenDetail: (AppFreezeStats) -> Unit,
) {
    val visibleEvents = remember(events) { events.sortedByDescending { it.timeMillis }.take(15) }
    val appsByUid = remember(apps) { apps.associateBy { it.uid } }
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(text = "最近系统记录 (${visibleEvents.size})")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 6.dp),
            insideMargin = PaddingValues(vertical = 6.dp),
        ) {
            visibleEvents.forEach { e ->
                val matchedApp = appsByUid[e.uid]
                RecentEventRow(
                    e = e,
                    onClick = matchedApp?.let { app -> { onOpenDetail(app) } },
                )
            }
        }
    }
}

@Composable
private fun RecentEventRow(
    e: StatsRecentEvent,
    onClick: (() -> Unit)? = null,
) {
    val (tagText, tagColor) = when (e.kind) {
        StatsEventKind.FREEZE -> "冻结记录" to colorScheme.primary
        StatsEventKind.THAW -> (e.category?.label ?: "解冻记录") to (e.category?.displayColor() ?: colorScheme.onSurfaceVariantSummary)
        StatsEventKind.INTERCEPT -> "已拦截" to statsSuccessColor()
        StatsEventKind.PROTECT -> "保护免冻" to statsProtectColor()
        StatsEventKind.KILL_BLOCKED -> "阻止查杀" to colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatsAppIcon(
            iconSource = e.iconSource,
            members = e.members,
            label = e.label,
            modifier = Modifier
                .padding(end = 10.dp)
                .size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = e.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (e.members.size > 1) SharedUidTag()
                if (e.isDualApp) {
                    StatusTagMiuix(
                        label = "双开",
                        contentColor = colorScheme.primary,
                        backgroundColor = colorScheme.primary.copy(alpha = 0.12f),
                        fontSize = 10.sp,
                        paddingValues = PaddingValues(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            val subText = buildString {
                append(formatClockSeconds(e.timeMillis))
                if (e.rawReason.isNotBlank()) append(" · ").append(e.rawReason)
            }
            Text(
                text = subText,
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        val detail = buildString {
            append(originLabel(e.origin)).append(" · ")
            append(tagText)
            if (e.kind == StatsEventKind.INTERCEPT && e.rule != null) append(" · ").append(e.rule.label)
        }
        StatusTagMiuix(
            label = detail,
            contentColor = tagColor,
            backgroundColor = tagColor.copy(alpha = 0.14f),
        )
    }
}

@Composable
private fun StatsFooter(snap: FreezeStatsSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val mem = if (snap.memoryBytes > 0) " · 占用约 ${(snap.memoryBytes + 1023) / 1024} KB" else ""
        Text(
            text = "统计自 ${formatDateTime(snap.sinceMillis)} · 更新于 ${formatClock(snap.generatedAtMillis)}$mem",
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = "冻结与唤醒次数取自系统冻解入口，按应用汇总；全量原始记录见下方「系统记录」。" +
                "冻结时长是多个应用加在一起的时间，可能超过开机时长。" +
                "共用统计的应用按一个计算，不重复累加成员。" +
                "想看应用现在是否冻结，请看「进程列表」。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        snap.sourceNote?.let {
            Text(text = it, fontSize = 12.sp, color = statsWarningTextColor())
        }
    }
}

@Composable
internal fun LoadingStateCard(active: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                InfiniteProgressIndicator(color = colorScheme.primary)
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(
                    text = if (active) "正在读取墓碑统计…" else "统计将在进入本页时加载",
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = if (active) "正在向系统读取应用次数和系统记录" else "离开本页后停止刷新，避免后台空转",
                    style = MiuixTheme.textStyles.body2,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
internal fun StatsStateCard(
    title: String,
    summary: String,
    primaryLabel: String? = null,
    onPrimaryClick: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondaryClick: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = colorScheme.onSurfaceVariantSummary,
            )
            if (primaryLabel != null || secondaryLabel != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (secondaryLabel != null) {
                        TextButton(
                            text = secondaryLabel,
                            onClick = onSecondaryClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (primaryLabel != null) {
                        TextButton(
                            text = primaryLabel,
                            onClick = onPrimaryClick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SampleBanner(onExit: () -> Unit) {
    WarningCard(
        message = "当前显示的是示例数据（随机生成，仅用于界面预览）",
        level = WarningLevel.Notice,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        onClick = onExit,
        action = {
            Spacer(Modifier.width(8.dp))
            StatusTagMiuix(
                label = "点按退出",
                backgroundColor = statsWarningTextColor().copy(alpha = 0.16f),
                contentColor = statsWarningTextColor(),
                fontSize = 10.sp,
                paddingValues = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            )
        },
    )
}

@Composable
internal fun MethodologyDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        onDismissRequest = onDismiss,
    ) {
        val dismissState = LocalDismissState.current
        ProgressiveDialogLayout(
            title = "这些数字怎么看",
            footer = {
                TextButton(
                    text = "知道了",
                    onClick = {
                        onDismiss()
                        dismissState?.invoke()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            },
        ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "• 应用次数：按应用（UID）汇总系统冻结与解冻入口的次数；同一应用短时间内多次冻解会累加，不等于消息条数。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "• 系统记录：系统侧冻结与解冻入口的全量次数，含重复与旧版本数据，仅供参考。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "• 原因与来源：网络活动不等于新消息，前台活动不等于点击。已知模块操作另列；系统记录标签只说明记录来路，不代表已查明谁触发。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "• 冻结时间：这是系统记账时间，多个应用加在一起，可能超过开机时长。想看应用现在是否冻结，请看「进程列表」，不要只看次数。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "• Doze 待机归因：仅在进入深睡（IDLE/MAINTENANCE）连续满 30 秒且退出后汇总一次，过滤 <100ms 微小波动；充电会话不计入掉电率。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
        }
    }
}

@Composable
internal fun AppStatsDetailSheet(
    app: AppFreezeStats?,
    updatedAtMillis: Long?,
    refreshFailed: Boolean,
    onDismiss: () -> Unit,
    onOpenAppProfile: (AppFreezeStats) -> Unit,
) {
    var last by remember { mutableStateOf<AppFreezeStats?>(null) }
    if (app != null) last = app
    val a = last ?: return

    val navBottomPadding: Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()

    GlassBottomSheet(
        backdrop = PopupBackdropHolder.current,
        show = app != null,
        insideMargin = DpSize(16.dp, 0.dp),
        progressiveBlur = false,
        endAction = {
            val dismissState = LocalDismissState.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(48.dp))
                Text(
                    text = a.label,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = {
                        onDismiss()
                        dismissState?.invoke()
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = "关闭",
                        tint = colorScheme.onBackground,
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = navBottomPadding + 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatsAppIcon(
                        iconSource = a.iconSource,
                        members = a.members,
                        label = a.label,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(44.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (a.members.size > 1) "共享 UID · ${a.members.size} 个成员" else {
                                a.packageName.ifBlank { "包名未解析" }
                            },
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "UID ${a.uid}",
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                            if (a.members.size > 1) SharedUidTag()
                            if (a.isDualApp) {
                                StatusTagMiuix(
                                    label = "双开",
                                    contentColor = colorScheme.primary,
                                    backgroundColor = colorScheme.primary.copy(alpha = 0.12f),
                                    fontSize = 10.sp,
                                    paddingValues = PaddingValues(horizontal = 5.dp, vertical = 1.dp),
                                )
                            } else if (a.isSystem) {
                                StatusTagMiuix(
                                    label = "系统",
                                    contentColor = colorScheme.onSurfaceVariantSummary,
                                    backgroundColor = colorScheme.surfaceContainerHigh,
                                    fontSize = 10.sp,
                                    paddingValues = PaddingValues(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }

                if (a.members.size > 1) {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("这些应用共用统计，不重复计算", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                            a.members.forEach { member ->
                                Column {
                                    Text(member.label, fontSize = 14.sp, color = colorScheme.onSurface)
                                    Text(member.packageName, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    }
                }

                updatedAtMillis?.let {
                    Text(
                        text = if (refreshFailed) "更新失败，仍显示 ${formatClock(it)} 的数据" else "更新于 ${formatClock(it)}",
                        fontSize = 12.sp,
                        color = if (refreshFailed) statsWarningTextColor() else colorScheme.onSurfaceVariantSummary,
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMetric("应用冻结", a.freezeCount, Modifier.weight(1f), containerColor = colorScheme.surfaceContainer)
                    MiniMetric("应用唤醒", a.backgroundWakeCount, Modifier.weight(1f), containerColor = colorScheme.surfaceContainer)
                    MiniMetric("拦截", a.interceptCount, Modifier.weight(1f), containerColor = colorScheme.surfaceContainer)
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("系统记录", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                        Text(
                            "冻结 ${formatCount(a.freezeCount)} 次 · 解冻 ${formatCount(a.wakeByCategory.values.sum())} 次",
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        Text("解冻次数含前台活动；应用唤醒只统计后台唤醒。", fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("系统记账冻结时长", fontSize = 14.sp, color = colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text(
                                text = formatDuration(a.frozenMillis),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurface,
                            )
                        }
                        if (a.observedMillis > 0) {
                            ProgressLine(fraction = a.frozenRatio, color = colorScheme.primary)
                            Text(
                                text = "记账冻结占比 ${"%.0f".format(Locale.ROOT, a.frozenRatio * 100)}% · 前台活动记录 ${formatCount(a.userOpenCount)} 次",
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        } else if (a.userOpenCount > 0) {
                            Text(
                                text = "前台活动记录 ${formatCount(a.userOpenCount)} 次，不代表点击次数",
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (a.protectedCount > 0 || a.killBlockedCount > 0) {
                            Text(
                                text = "保护免冻 ${formatCount(a.protectedCount)} 次 · 阻止查杀 ${formatCount(a.killBlockedCount)} 次",
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("系统解冻记录 · 原因", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                        WakeReasonSection(a.wakeByCategory)
                    }
                }

                val rules = a.interceptByRule.entries.filter { it.value > 0 }.sortedByDescending { it.value }
                if (rules.isNotEmpty() || a.topRawReasons.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            rules.forEach { (r, n) ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text("拦截 · ${r.label}", fontSize = 13.sp, color = colorScheme.onSurface, modifier = Modifier.weight(1f))
                                    Text(formatCount(n), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                                }
                            }
                            if (a.topRawReasons.isNotEmpty()) {
                                Text(
                                    text = "系统记录最多的原因",
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = if (rules.isNotEmpty()) 6.dp else 0.dp),
                                )
                                a.topRawReasons.forEach { (reason, n) ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = formatRawThawReason(reason),
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = formatCount(n),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            val canOpenProfile = a.packageName.isNotBlank() && !a.packageName.startsWith("uid:")
            Button(
                onClick = { onOpenAppProfile(a) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canOpenProfile,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    text = when {
                        a.members.size > 1 -> "多个应用共用统计，请分别设置策略"
                        canOpenProfile -> "前往应用策略"
                        else -> "暂无应用包名 (UID ${a.uid})"
                    },
                    style = MiuixTheme.textStyles.button,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SegmentedBar(
    segments: List<Pair<Color, Float>>,
    modifier: Modifier = Modifier,
    fillFraction: Float = 1f,
    animateOnEnter: Boolean = true,
) {
    val track = colorScheme.surfaceContainerHighest
    // 生长动画只给「首次进入组合」用：调用方在整段动画结束后把 animateOnEnter 置 false，
    // 之后滚动中新进入组合的行直接以满值绘制，不再各自重播 550ms 补间。
    val progress = remember { Animatable(if (animateOnEnter) 0f else 1f) }
    LaunchedEffect(animateOnEnter) {
        if (progress.value < 1f) {
            if (animateOnEnter) {
                progress.animateTo(1f, tween(550))
            } else {
                // 动画途中被要求停止时快速收尾，避免整条进度条硬跳。
                progress.animateTo(1f, tween(80))
            }
        }
    }
    val clampedFill = fillFraction.coerceIn(0f, 1f)

    Canvas(modifier = modifier.clip(RoundedCornerShape(50))) {
        drawRect(track)
        val total = segments.sumOf { it.second.toDouble() }.toFloat()
        if (total <= 0f || clampedFill <= 0f) return@Canvas
        val gap = 2.dp.toPx()
        var x = 0f
        val activeW = size.width * clampedFill * progress.value
        segments.forEachIndexed { i, (color, value) ->
            val segW = activeW * value / total
            val drawW = if (i < segments.lastIndex) (segW - gap).coerceAtLeast(0f) else segW
            if (drawW > 0f) {
                drawRect(color = color, topLeft = Offset(x, 0f), size = Size(drawW, size.height))
            }
            x += segW
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val track = colorScheme.surfaceContainerHighest
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp),
    ) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(color = track, cornerRadius = r)
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) {
            drawRoundRect(
                color = color,
                size = Size(w, size.height),
                cornerRadius = r,
            )
        }
    }
}

@Composable
private fun StackedBarChart(
    window: StatsWindow,
    buckets: List<StatsTimeBucket>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onColor: Color,
    offColor: Color,
    modifier: Modifier = Modifier,
) {
    val grid = colorScheme.dividerLine
    val progress = remember(window) { Animatable(0f) }
    LaunchedEffect(window) { progress.animateTo(1f, tween(650)) }
    val maxTotal = (buckets.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1)

    Canvas(
        modifier = modifier.pointerInput(buckets.size) {
            detectTapGestures { pos ->
                if (buckets.isEmpty()) return@detectTapGestures
                val idx = (pos.x / (size.width.toFloat() / buckets.size)).toInt().coerceIn(0, buckets.lastIndex)
                onSelect(idx)
            }
        },
    ) {
        for (i in 0..2) {
            val y = size.height * i / 2f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (buckets.isEmpty()) return@Canvas
        val slot = size.width / buckets.size
        val barW = (slot * 0.6f).coerceAtMost(28.dp.toPx())
        val corner = CornerRadius(barW / 3f, barW / 3f)
        val clipPath = Path()

        buckets.forEachIndexed { i, b ->
            val offH = size.height * b.screenOffWakes / maxTotal * progress.value
            val onH = size.height * b.screenOnWakes / maxTotal * progress.value
            val totalH = offH + onH
            if (totalH <= 0f) return@forEachIndexed

            val alpha = if (selectedIndex == -1 || selectedIndex == i) 1f else 0.35f
            val left = slot * i + (slot - barW) / 2f
            val top = size.height - totalH

            clipPath.rewind()
            clipPath.addRoundRect(
                RoundRect(
                    rect = Rect(left, top, left + barW, size.height),
                    topLeft = corner,
                    topRight = corner,
                    bottomLeft = CornerRadius.Zero,
                    bottomRight = CornerRadius.Zero,
                ),
            )
            clipPath(clipPath) {
                if (offH > 0f) {
                    drawRect(
                        color = offColor.copy(alpha = alpha),
                        topLeft = Offset(left, size.height - offH),
                        size = Size(barW, offH),
                    )
                }
                if (onH > 0f) {
                    drawRect(
                        color = onColor.copy(alpha = alpha),
                        topLeft = Offset(left, top),
                        size = Size(barW, onH),
                    )
                }
            }
        }
    }
}

private fun formatRawThawReason(reason: String): String {
    if (reason.isBlank() || '（' in reason || '(' in reason) return reason
    val name = reason.trim().lowercase(Locale.ROOT)
    val label = when (name) {
        "packet" -> "网络数据"
        "check binder" -> "Binder检查"
        "sync binder", "sync binder4" -> "同步Binder"
        "net change" -> "网络变化"
        "network connect" -> "网络连接"
        "signal iso" -> "隔离信号"
        "signal other" -> "其他信号"
        "broadcast" -> "广播"
        "notifi" -> "通知"
        "pendingnotify" -> "待处理通知"
        "activity front", "tofront", "tofrontactivity", "movetofront", "notifymovetofront" -> "活动前台"
        "activity start", "activity start!" -> "活动启动"
        "activity resume" -> "活动恢复"
        "fg activity" -> "前台活动"
        "ui bar" -> "界面栏"
        "launch_mode" -> "启动模式"
        "cmd: thuid" -> "UID解冻命令"
        "mediabutton", "mediabuttoncmd" -> "媒体按键"
        "receiver pkg" -> "广播接收器"
        "alarm" -> "定时器"
        "excute service" -> "服务执行"
        "startservice" -> "服务启动"
        "provider" -> "内容提供器"
        "audiofocus" -> "音频焦点"
        "bluetooth" -> "蓝牙"
        "keycode" -> "按键代码"
        "adj" -> "进程优先级"
        "fromsystem", "from system" -> "系统来源"
        else -> return reason
    }
    return "$reason（$label）"
}

private fun formatCount(n: Int): String = when {
    n >= 100_000 -> "%.1f万".format(Locale.ROOT, n / 10_000f)
    n >= 10_000 -> "%.2f万".format(Locale.ROOT, n / 10_000f)
    else -> "%,d".format(Locale.ROOT, n)
}

private fun formatDurationParts(ms: Long): Pair<String, String> {
    if (ms <= 0L) return "0" to "分钟"
    val h = ms / 3_600_000f
    return when {
        h >= 100f -> "%.0f".format(Locale.ROOT, h) to "小时"
        h >= 1f -> "%.1f".format(Locale.ROOT, h) to "小时"
        ms >= 60_000L -> "%.0f".format(Locale.ROOT, ms / 60_000f) to "分钟"
        else -> "${(ms / 1000L).coerceAtLeast(1)}" to "秒"
    }
}

private fun formatDuration(ms: Long): String {
    val (n, u) = formatDurationParts(ms)
    return "$n $u"
}

private fun formatInterval(ms: Long): String {
    val min = ms / 60_000L
    return when {
        min >= 60 -> "%.1f 小时".format(Locale.ROOT, ms / 3_600_000f)
        min >= 1 -> "$min 分钟"
        else -> "${(ms / 1000L).coerceAtLeast(1)} 秒"
    }
}

internal fun formatClock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ms))

private fun formatClockSeconds(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(ms))

internal fun formatDateTime(ms: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
