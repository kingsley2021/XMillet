package io.github.univers629.millet.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.millet.ui.theme.LocalEnableBlur
import io.github.univers629.millet.ui.theme.LocalEnableFloatingBottomBarBlur
import io.github.univers629.millet.ui.theme.LocalFloatingBottomBarStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.DigitalStatistics
import top.yukonga.miuix.kmp.icon.glass.HomeFill
import top.yukonga.miuix.kmp.icon.glass.HomeOutline
import top.yukonga.miuix.kmp.icon.glass.NotesFill
import top.yukonga.miuix.kmp.icon.glass.NotesOutline
import top.yukonga.miuix.kmp.icon.glass.SettingsFill
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.springAnimateToPage

class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
    private val animatePageChanges: Boolean = true,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    fun animateToPage(targetIndex: Int) {
        if (targetIndex == selectedPage) return

        navJob?.cancel()

        selectedPage = targetIndex
        isNavigating = true

        navJob = coroutineScope.launch {
            val myJob = coroutineContext.job
            try {
                if (animatePageChanges) {
                    pagerState.springAnimateToPage(targetIndex)
                } else {
                    pagerState.scrollToPage(targetIndex)
                }
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != targetIndex) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }

    /** 当可见 Tab 列表动态增删导致页序重排时，取消进行中的翻页任务并立即对齐当前页下标。 */
    fun cancelAndSyncPage() {
        navJob?.cancel()
        navJob = null
        isNavigating = false
        if (selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}

@Composable
fun rememberMainPagerState(
    pagerState: PagerState,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    animatePageChanges: Boolean = true,
): MainPagerState {
    return remember(pagerState, coroutineScope, animatePageChanges) {
        MainPagerState(pagerState, coroutineScope, animatePageChanges)
    }
}

enum class BottomBarDestination(
    val label: String,
    val iconOutline: ImageVector,
    val iconFill: ImageVector,
) {
    Home("主页", MiuixIcons.Glass.HomeOutline, MiuixIcons.Glass.HomeFill),
    Processes("进程列表", MiuixIcons.Glass.NotesOutline, MiuixIcons.Glass.NotesFill),
    // DigitalStatistics 没有 Fill 变体，选中态改用同一图标的 Demibold 字重变体（其余 Tab 沿用 Outline/Fill）。
    TombStats("墓碑统计", MiuixIcons.Glass.DigitalStatistics, MiuixIcons.Glass.Demibold.DigitalStatistics),
    Settings("设置", MiuixIcons.Glass.SettingsOutline, MiuixIcons.Glass.SettingsFill);

    fun icon(selected: Boolean): ImageVector = if (selected) iconFill else iconOutline

    companion object {
        /**
         * 当前可见的底栏目的地（顺序即 Pager 页序）。页面下标一律经由此列表换算，禁止硬编码。
         *
         * @param showTombStats 用户是否在设置里开启了「墓碑统计」页。
         * @param isActivated 系统服务是否已激活；未激活时只剩「主页」「设置」两个 Tab
         *   （进程列表与墓碑统计都依赖服务侧的冻结/唤醒数据，未激活时没有内容可看）。
         */
        fun visible(
            showTombStats: Boolean,
            isActivated: Boolean = true,
        ): List<BottomBarDestination> = when {
            !isActivated -> listOf(Home, Settings)
            showTombStats -> entries.toList()
            else -> entries.filter { it != TombStats }
        }

        /** 按目的地枚举解析在新可见列表中的目标页码；目标已被隐藏时回退至首页（0）。 */
        fun resolvePageIndex(
            destinations: List<BottomBarDestination>,
            activeDestination: BottomBarDestination,
        ): Int = destinations.indexOf(activeDestination).let { if (it >= 0) it else 0 }
    }
}

/**
 * XMillet 底部导航栏（复刻 KernelSU BottomBarMiuix）：
 * - 悬浮模式：iOS 液态玻璃悬浮底栏 (FloatingBottomBar)，宽度随 Tab 数自适应；
 * - Miuix 玻璃模式：GlassNavigationBar 液态玻璃悬浮底栏（同 miuix example 的 Glass 样式）；
 * - 经典模式：全宽 NavigationBar；开启背景模糊时整块 textureBlur(surface 0.8)，与 miuix example 一致。
 */
@Composable
fun BoxScope.BottomBarMiuix(
    mainState: MainPagerState,
    blurBackdrop: LayerBackdrop?,
    backdrop: Backdrop,
    destinations: List<BottomBarDestination> = BottomBarDestination.entries,
    modifier: Modifier = Modifier,
) {
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val floatingBottomBarStyle = LocalFloatingBottomBarStyle.current
    val bottomBlurActive = enableBlur && blurBackdrop != null

    val items = destinations.map { destination ->
        NavigationItem(
            label = destination.label,
            icon = destination.icon(false),
        )
    }
    // 5 个 Tab 时收窄：5×60 + 8 + 2×16 = 340dp，紧凑屏 (360dp) 也有余量且不挤压文字；4 个 Tab 保持原尺寸
    val dense = items.size >= 5
    val itemMinWidth = if (dense) 60.dp else 68.dp
    val sideMargin = if (dense) 16.dp else 28.dp
    val safeSelectedIndex = mainState.selectedPage.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    // 与 miuix example 一致：仅在开启模糊、存在采样源且设备支持 RuntimeShader 时才走毛玻璃，否则回退为不透明底色
    val blurActive = bottomBlurActive && isRuntimeShaderSupported()

    when (floatingBottomBarStyle) {
        0 -> {
            val barColor = if (blurActive) Color.Transparent else colorScheme.surface
            Box(
                modifier = modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(
                        if (blurActive) {
                            Modifier.textureBlur(
                                backdrop = blurBackdrop,
                                shape = RectangleShape,
                                blurRadius = 25f,
                                colors = BlurDefaults.blurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(color = colorScheme.surface.copy(0.8f)),
                                    ),
                                ),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .background(barColor)
                    // 吞掉底栏空白处的点击，避免穿透到下方列表
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                NavigationBar(color = barColor) {
                    destinations.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = safeSelectedIndex == index,
                            onClick = { mainState.animateToPage(index) },
                            icon = destination.icon(safeSelectedIndex == index),
                            label = destination.label,
                        )
                    }
                }
            }
        }

        1 -> {
            // 同 miuix example 的 Glass 悬浮底栏：GlassNavigationBar 自带液态玻璃材质、描边、阴影与底部 insets
            val glassItems = remember(destinations, safeSelectedIndex) {
                destinations.mapIndexed { index, destination ->
                    GlassNavigationItem(
                        icon = destination.icon(safeSelectedIndex == index),
                        label = destination.label,
                    )
                }
            }
            Box(
                modifier = modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                GlassNavigationBar(
                    items = glassItems,
                    selectedIndex = safeSelectedIndex,
                    onSelect = { mainState.animateToPage(it) },
                    // 统一模糊开关：关闭背景模糊时不再采样背景，玻璃底栏回退为纯色底
                    backdrop = if (enableBlur && isRuntimeShaderSupported()) backdrop else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )
            }
        }

        else -> {
            val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() +
                24.dp

            FloatingBottomBar(
                modifier = modifier
                    .align(Alignment.BottomCenter)
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    }
                    .padding(start = sideMargin, end = sideMargin, bottom = bottomPadding),
                selectedIndex = safeSelectedIndex,
                onSelected = { mainState.animateToPage(it) },
                backdrop = backdrop,
                tabsCount = items.size,
                isBlurEnabled = enableBlur && isRuntimeShaderSupported(),
                liquidGlass = enableFloatingBottomBarBlur && enableBlur,
            ) { activateTab ->
                items.forEachIndexed { index, item ->
                    val destination = destinations[index]
                    FloatingBottomBarItem(
                        selected = safeSelectedIndex == index,
                        onClick = { activateTab(index) },
                        modifier = Modifier.defaultMinSize(minWidth = itemMinWidth)
                    ) {
                        Icon(
                            imageVector = destination.icon(safeSelectedIndex == index),
                            contentDescription = destination.label,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = destination.label,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
