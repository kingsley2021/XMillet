package io.github.univers629.millet.ui.util
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupAnchor
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import kotlinx.coroutines.delay

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.univers629.millet.ui.theme.LocalEnableBlur
import io.github.univers629.millet.ui.theme.LocalEnableProgressiveBlur
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 由 [BlurredBar] 下发给顶栏的背景采样源；为 null 时顶栏退回纯色渐变带。 */
val LocalBarBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * Miuix Glass 顶栏（HyperOS 4 液态玻璃）。参数与旧 TopAppBar 保持一致，各页面直接平替：
 * - 渐进式模糊开启且有采样源：progressiveBlur = true，背景自顶部向下渐隐模糊；
 * - 否则沿用外层 BlurredBar 的整块毛玻璃（color 透明时）或纯色渐变带。
 */
@Composable
fun MilletTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    titleColor: Color = Color.Unspecified,
    expandable: Boolean = true,
    largeTitle: String = title,
    subtitle: String = "",
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true,
    bottomContent: @Composable () -> Unit = {},
    /**
     * 静止时不铺背景带，只保留滚动后的内容遮挡效果。
     *
     * 用于不画顶栏背景的页面（例如配置向导的权限页），该页顶栏只承载返回按钮。
     * 默认实现在关闭模糊时铺一条纯白带、在高斯模糊档位由外层 [BlurredBar] 铺整块毛玻璃，
     * 使这类页面静止时多出一条顶栏；传 true 后：
     * - 渐进式模糊：色带只用自上而下的渐隐画刷，未滚动时不可见，内容滚上来后渐显；
     * - 高斯模糊 / 关闭模糊：色带完全透明，任何时候都不出现。
     */
    bandless: Boolean = false,
    /**
     * 内容是否已滑到顶栏下方的信号（必须传 `listState.canScrollBackward` 一类的页面级状态）。
     *
     * miuix 的官方建议就是用列表的 `canScrollBackward` 表示顶栏按钮的浮起态；只看
     * [ScrollBehavior] 的 `contentOffset`／`overscrollOffset` 时，这两个值是以 px 计的累计量，
     * 系统「最小宽度 / DPI」改变后 density 随之变化，轻量滚动可能凑不满原先写死的 1px 阈值
     * （位移还会被 overscroll / PullToRefresh 吸收），圆形液态玻璃不再浮起。
     *
     * 这里不给默认值：任何顶栏都必须显式说明自己的滚动来源，避免新页面再漏接。
     */
    contentScrolledSignal: () -> Boolean,
) {
    val backdrop = LocalBarBackdrop.current
    val progressive = LocalEnableProgressiveBlur.current && backdrop != null
    val blurEnabled = LocalEnableBlur.current
    val band: Brush = when {
        // 该页不画顶栏背景：静止时任何模糊档位都不铺色带。
        // 渐进式模糊下仍需自上而下的渐隐画刷——内容滚到顶栏下面时显现，
        // 否则该页没有内容遮挡顶栏时的模糊。
        bandless && !progressive -> SolidColor(Color.Transparent)
        bandless -> GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface)
        // 背景模糊关闭时顶栏保持纯白，不画渐变也不做任何模糊。
        !blurEnabled -> SolidColor(MiuixTheme.colorScheme.surface)
        progressive -> GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface)
        color == Color.Transparent -> SolidColor(Color.Transparent)
        color.isSpecified -> GlassTopAppBarDefaults.bandBrush(color)
        else -> GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface)
    }
    // 与 Miuix 一致：顶栏被内容遮挡时，按钮才浮起圆形液态玻璃。
    // 「被遮挡」含三种情形：列表已滚过顶部、大标题已折叠（正文压在顶栏下方）、滚动或回弹偏移越过顶边。
    // 三者都为 0 时页面静止且无遮挡，按钮保持纯图标；判定不随窗口宽度或屏幕密度变化。
    // 菜单展开只抬该按钮的 popup 锚点，不改变整条顶栏的浮起态。
    val scrollState = scrollBehavior?.state
    val collapsedFraction = scrollState?.collapsedFraction ?: 0f
    val contentScrolled = contentScrolledSignal() ||
        collapsedFraction > 0f ||
        (scrollState != null && (scrollState.contentOffset < 0f || scrollState.overscrollOffset < 0f))
    val floating = progressive && contentScrolled
    CompositionLocalProvider(
        LocalBarGlassFloating provides floating,
    ) {
        GlassTopAppBar(
            title = title,
            isContentScrolled = floating,
            modifier = modifier,
            expandable = expandable,
            progressiveBlur = progressive,
            backdrop = backdrop?.takeIf { progressive },
            largeTitle = largeTitle,
            subtitle = subtitle,
            scrollBehavior = scrollBehavior,
            bandBrush = band,
            // 纯色带（关闭模糊 / 整块毛玻璃）不能再向下延伸渐隐带：
            // GlassTopAppBar 对非渐变画刷会把背景按 overhang 纵向拉伸，导致关闭模糊时顶栏比模糊模式高出一截。
            bandOverhang = if (band is SolidColor) 0.dp else GlassTopAppBarDefaults.BandOverhang,
            defaultWindowInsetsPadding = defaultWindowInsetsPadding,
            navigationIcon = navigationIcon,
            actions = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MilletTopAppBarActionSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                }
            },
            bottomContent = bottomContent,
        )
    }
}

/** 顶栏是否处于浮起态（渐进模糊 + 内容已滚过顶部），供顶栏按钮同步菜单锚点。 */
private val LocalBarGlassFloating = compositionLocalOf { false }

/** 顶栏玻璃按钮之间的间距，与 Miuix Glass 示例页保持一致。 */
private val MilletTopAppBarActionSpacing = 8.dp

/** 不可折叠的小标题版本（替代 SmallTopAppBar）。 */
@Composable
fun MilletSmallTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    titleColor: Color = Color.Unspecified,
    subtitle: String = "",
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true,
    contentScrolledSignal: () -> Boolean,
    bandless: Boolean = false,
) = MilletTopAppBar(
    title = title,
    modifier = modifier,
    color = color,
    titleColor = titleColor,
    expandable = false,
    subtitle = subtitle,
    navigationIcon = navigationIcon,
    actions = actions,
    scrollBehavior = scrollBehavior,
    defaultWindowInsetsPadding = defaultWindowInsetsPadding,
    contentScrolledSignal = contentScrolledSignal,
    bandless = bandless,
)

/** 顶栏按钮与紧随其后的菜单之间共享的 Miuix 锚点（首次组合时按调用顺序配对）。 */
internal class BarPopupAnchorEntry(val anchor: GlassPopupAnchor) {
    var icon: @Composable () -> Unit by mutableStateOf({})
}

internal object BarPopupAnchorRegistry {
    var last: BarPopupAnchorEntry? = null
}

/**
 * 顶栏玻璃按钮（替代 IconButton，保留旧参数签名）。挂 Miuix [glassPopupAnchor]，菜单展开/收起由库负责。
 *
 * [holdDownState] 表示该按钮的菜单正展开：既让顶栏把它算作浮起态（圆形液态玻璃），也同步给
 * 菜单锚点，使玻璃菜单从一个可见的圆形锚点变形展开，而不是从一片透明里长出来。
 */
@Composable
fun BarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    backgroundColor: Color = Color.Unspecified,
    cornerRadius: Dp = 0.dp,
    minHeight: Dp = 0.dp,
    minWidth: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val anchor = rememberGlassPopupAnchor()
    val entry = remember(anchor) { BarPopupAnchorEntry(anchor) }
    entry.icon = content
    BarPopupAnchorRegistry.last = entry

    // 菜单展开只抬该按钮自己的锚点，同栏其他按钮保持各自的浮起状态。
    val floating = holdDownState || LocalBarGlassFloating.current

    GlassIconButton(
        onClick = { if (enabled) onClick() },
        modifier = modifier.glassPopupAnchor(
            anchor,
            cornerRadius = GlassTopAppBarDefaults.ButtonSize / 2,
            floating = floating,
        ),
    ) {
        Box(modifier = Modifier.glassPopupAnchorContent(anchor)) { content() }
    }
}

/** 顶栏玻璃按钮的图标版本：统一 Miuix Glass 图标的 28dp 尺寸与 onSurface 着色。 */
@Composable
fun BarIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    tint: Color = MiuixTheme.colorScheme.onSurface,
) {
    BarIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        holdDownState = holdDownState,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * OS4 玻璃菜单：替代 OverlayListPopup，保留原调用签名（定位参数忽略）。
 * 使用 Miuix [GlassTransformPopup]：从触发按钮变形展开，收起时缩回按钮本身。
 * 必须紧跟在 [BarIconButton] 之后调用，以取得该按钮的锚点。
 */
@Composable
fun GlassOverlayListPopup(
    show: Boolean,
    popupPositionProvider: Any? = null,
    alignment: Any? = null,
    onDismissRequest: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // 锚点在「菜单真正展开」的这一刻再取：顶栏按钮每次组合都会刷新注册表，
    // 若在首次组合时就 remember 下来，配置变更/重组顺序变化后会绑定到上一次的按钮上。
    val fallbackEntry = remember { BarPopupAnchorEntry(GlassPopupAnchor()) }
    var entry by remember { mutableStateOf<BarPopupAnchorEntry?>(null) }
    val mounted = remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) {
            entry = BarPopupAnchorRegistry.last ?: fallbackEntry
            mounted.value = true
        } else if (mounted.value) {
            // 等库内收起弹簧完全结束再卸载，避免截断。
            delay(1200)
            mounted.value = false
        }
    }
    if (!mounted.value) return
    val activeEntry = entry ?: fallbackEntry
    PopupLayout(
        visible = mounted,
        enableWindowDim = false,
        enableBackHandler = false,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
    ) {
        // 库的 updateTransition 首次组合即取目标值；先以关闭态挂载一帧，展开才会走动画。
        val entered = remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered.value = true }
        Box(modifier = Modifier.fillMaxSize()) {
            GlassTransformPopup(
                show = show && entered.value,
                onDismissRequest = { onDismissRequest?.invoke() },
                anchor = activeEntry.anchor,
                backdrop = PopupBackdropHolder.current,
                anchorContent = { activeEntry.icon() },
            ) {
                content()
            }
        }
    }
}

/** 玻璃菜单内的列容器（替代 Miuix ListPopupColumn，避免其固有尺寸测量与玻璃面板冲突）。 */
@Composable
fun ListPopupColumn(content: @Composable () -> Unit) {
    Column { content() }
}

/** 带勾选态的玻璃菜单项（替代 DropdownImpl）。 */
@Composable
fun GlassMenuItem(
    text: String,
    isSelected: Boolean = false,
    optionSize: Int = 1,
    index: Int = 0,
    onSelectedIndexChange: (Int) -> Unit,
) {
    GlassPopupItem(text = text, selected = isSelected, onClick = { onSelectedIndexChange(index) })
}
