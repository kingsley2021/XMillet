package io.github.univers629.millet.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.univers629.millet.BuildConfig
import io.github.univers629.millet.core.StatsWindow
import io.github.univers629.millet.ui.component.DropdownItem
import io.github.univers629.millet.ui.component.ListPopupDefaults
import io.github.univers629.millet.ui.component.MiuixConfirmDialog
import io.github.univers629.millet.ui.di.appUiContainer
import io.github.univers629.millet.ui.model.TombSection
import io.github.univers629.millet.ui.model.AppFreezeStats
import io.github.univers629.millet.ui.model.StatsLoadState
import io.github.univers629.millet.ui.screen.tombstats.AppStatsDetailSheet
import io.github.univers629.millet.ui.screen.tombstats.LoadingStateCard
import io.github.univers629.millet.ui.screen.tombstats.MethodologyDialog
import io.github.univers629.millet.ui.screen.tombstats.SampleBanner
import io.github.univers629.millet.ui.screen.tombstats.StatsStateCard
import io.github.univers629.millet.ui.screen.tombstats.formatClock
import io.github.univers629.millet.ui.screen.tombstats.formatDateTime
import io.github.univers629.millet.ui.screen.tombstats.statsContent
import io.github.univers629.millet.ui.theme.LocalEnableBlur
import io.github.univers629.millet.ui.util.MilletRefreshTexts
import io.github.univers629.millet.ui.util.BarIconButton
import io.github.univers629.millet.ui.util.BlurredBar
import io.github.univers629.millet.ui.util.GlassOverlayListPopup
import io.github.univers629.millet.ui.util.ListPopupColumn
import io.github.univers629.millet.ui.util.MilletTopAppBar
import io.github.univers629.millet.ui.util.StatsQueryScope
import io.github.univers629.millet.ui.util.rememberBlurBackdrop
import io.github.univers629.millet.ui.util.resolveStatsDetail
import io.github.univers629.millet.ui.vm.TombStatsUiState
import io.github.univers629.millet.ui.vm.TombStatsViewModel
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.MoreCircle
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.runtime.derivedStateOf
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow

/**
 * 墓碑统计页入口：连接 [TombStatsViewModel] 与纯展示 [TombStatsContent]。
 */
@Composable
fun TombStatsScreenMiuix(
    isActive: Boolean,
    serviceAlive: Boolean,
    statsEnabled: Boolean = true,
    visibleSections: Set<String> = TombSection.allIds,
    bottomInnerPadding: Dp,
    onEnableStats: (() -> Unit)? = null,
    onOpenAppProfile: (packageName: String, uid: Int) -> Unit,
) {
    val context = LocalContext.current
    val dataSource = remember(context) { context.appUiContainer.freezeStatsDataSource }
    val vm: TombStatsViewModel = viewModel { TombStatsViewModel(dataSource) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(isActive, serviceAlive, statsEnabled) {
        vm.updateEnvironment(
            isActive = isActive,
            serviceAlive = serviceAlive,
            statsEnabled = statsEnabled,
        )
    }

    TombStatsContent(
        uiState = uiState,
        visibleSections = visibleSections,
        bottomInnerPadding = bottomInnerPadding,
        onSelectWindow = vm::selectWindow,
        onSelectRankMode = vm::selectRankMode,
        onSetUseSample = vm::setUseSample,
        onSelectDetail = vm::selectDetail,
        onManualRefresh = vm::refreshManually,
        onEnableStats = onEnableStats?.let { callback -> { vm.enableStatsAndRefresh(callback) } },
        onResetStats = {
            vm.resetStats { ok ->
                Toast.makeText(
                    context,
                    if (ok) "已重置墓碑统计数据" else "重置失败：请确认系统服务连接",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        onOpenAppProfile = onOpenAppProfile,
    )
}

@Composable
fun TombStatsContent(
    uiState: TombStatsUiState,
    visibleSections: Set<String> = TombSection.allIds,
    bottomInnerPadding: Dp,
    onSelectWindow: (Int) -> Unit,
    onSelectRankMode: (Int) -> Unit,
    onSetUseSample: (Boolean) -> Unit,
    onSelectDetail: (Int?) -> Unit,
    onManualRefresh: () -> Unit,
    onEnableStats: (() -> Unit)?,
    onResetStats: () -> Unit,
    onOpenAppProfile: (packageName: String, uid: Int) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val pullToRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val fallbackTabBackdrop = rememberLayerBackdrop()
    // 顶栏的模糊/浮起信号：除了「列表已滚过顶部」，还要算上输入法展开。
    // 排行搜索框被键盘顶起吸附时列表往往已经回到顶部，canScrollBackward 变 false 会让模糊带整条消失。
    // 统一走一个 derivedStateOf，顶栏只读一次派生值，滚动时不会因多次读滚动状态整体重组。
    val density = LocalDensity.current
    // WindowInsets.ime 是 @Composable 取值，只能在组合里读出来；它的 getBottom 仍会跟踪 insets 快照。
    val imeInsets = WindowInsets.ime
    val contentScrolled by remember(imeInsets) {
        derivedStateOf {
            listState.canScrollBackward || imeInsets.getBottom(density) > 0
        }
    }

    var showTopPopup by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showMethodologyDialog by remember { mutableStateOf(false) }

    val effectiveState = uiState.effectiveLoadState

    if (showResetDialog) {
        MiuixConfirmDialog(
            show = true,
            title = "重置墓碑统计",
            message = "确定清空「今天」「近 7 天」与「开机以来」的全部统计计数与最近动态吗？此操作无法恢复。",
            confirmText = "确认重置",
            dismissText = "取消",
            onConfirm = {
                showResetDialog = false
                onResetStats()
            },
            onDismiss = { showResetDialog = false },
        )
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive) {
                MilletTopAppBar(
                    color = barColor,
                    title = "墓碑统计",
                    actions = {
                        BarIconButton(
                            icon = MiuixIcons.Glass.Refresh,
                            contentDescription = "刷新统计",
                            onClick = onManualRefresh,
                        )
                        BarIconButton(
                            icon = MiuixIcons.Glass.MoreCircle,
                            contentDescription = "更多操作",
                            onClick = { showTopPopup = true },
                            holdDownState = showTopPopup,
                        )
                        GlassOverlayListPopup(
                            show = showTopPopup,
                            popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                            alignment = PopupPositionProvider.Align.TopEnd,
                            onDismissRequest = { showTopPopup = false },
                            content = {
                                val canReset = uiState.serviceAlive && uiState.statsEnabled && !uiState.useSample &&
                                    effectiveState !is StatsLoadState.BackendUnsupported &&
                                    effectiveState !is StatsLoadState.ServiceUnavailable
                                val menuItems = buildList {
                                    if (canReset) add("重置统计数据")
                                    add("统计口径说明")
                                    if (BuildConfig.DEBUG) {
                                        add(if (uiState.useSample) "退出示例数据" else "预览示例数据")
                                    }
                                }
                                ListPopupColumn {
                                    menuItems.forEachIndexed { idx, label ->
                                        DropdownItem(
                                            text = label,
                                            optionSize = menuItems.size,
                                            index = idx,
                                            onSelectedIndexChange = {
                                                showTopPopup = false
                                                when (label) {
                                                    "重置统计数据" -> showResetDialog = true
                                                    "统计口径说明" -> showMethodologyDialog = true
                                                    "预览示例数据" -> onSetUseSample(true)
                                                    "退出示例数据" -> onSetUseSample(false)
                                                }
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    },
                    scrollBehavior = scrollBehavior,
                    contentScrolledSignal = { contentScrolled },
                    bottomContent = {
                        GlassSegmentedTabRow(
                            tabs = StatsWindow.entries.map { it.label },
                            selectedIndex = uiState.windowIndex,
                            onSelect = onSelectWindow,
                            backdrop = backdrop ?: fallbackTabBackdrop,
                            surfaceAlpha = if (blurActive && contentScrolled) 1f else 0f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(top = 6.dp, bottom = 12.dp),
                        )
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            PullToRefresh(
                isRefreshing = uiState.isRefreshing,
                pullToRefreshState = pullToRefreshState,
                onRefresh = onManualRefresh,
                refreshTexts = MilletRefreshTexts,
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 6.dp),
                topAppBarScrollBehavior = scrollBehavior,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 4.dp,
                        bottom = bottomInnerPadding + 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    overscrollEffect = null,
                ) {
                    when (effectiveState) {
                        StatsLoadState.Loading -> item(key = "state_loading") {
                            LoadingStateCard(active = uiState.isActive)
                        }

                        StatsLoadState.ServiceUnavailable -> item(key = "state_service_unavailable") {
                            StatsStateCard(
                                title = "统计服务未连接",
                                summary = "暂时读不到统计。请检查模块是否正常运行，再点重新检测。",
                                primaryLabel = "重新检测",
                                onPrimaryClick = onManualRefresh,
                                secondaryLabel = if (BuildConfig.DEBUG) "预览示例数据" else null,
                                onSecondaryClick = { onSetUseSample(true) },
                            )
                        }

                        StatsLoadState.BackendUnsupported -> item(key = "state_backend_unsupported") {
                            StatsStateCard(
                                title = "暂时读不到统计",
                                summary = "系统没有返回统计数据，可以稍后重试。",
                                primaryLabel = "重新检测",
                                onPrimaryClick = onManualRefresh,
                                secondaryLabel = if (BuildConfig.DEBUG) "预览示例数据" else null,
                                onSecondaryClick = { onSetUseSample(true) },
                            )
                        }

                        StatsLoadState.Disabled -> item(key = "state_disabled") {
                            StatsStateCard(
                                title = "墓碑统计采集已关闭",
                                summary = "开启后记录应用冻结、唤醒和拦截情况。统计会定期保存，方便下次继续查看。",
                                primaryLabel = if (onEnableStats != null) "立即启用统计" else null,
                                onPrimaryClick = { onEnableStats?.invoke() },
                                secondaryLabel = if (BuildConfig.DEBUG) "预览示例数据" else null,
                                onSecondaryClick = { onSetUseSample(true) },
                            )
                        }

                        is StatsLoadState.Ready -> {
                            val snap = effectiveState.snapshot
                            if (uiState.refreshFailed) {
                                item(key = "refresh_failed") {
                                    StatsStateCard(
                                        title = "更新失败，保留上次数据",
                                        summary = "上次更新于 ${formatClock(snap.generatedAtMillis)}，当前内容还没有更新。",
                                        primaryLabel = "重试",
                                        onPrimaryClick = onManualRefresh,
                                    )
                                }
                            }
                            if (snap.isSample) {
                                item(key = "sample_banner") {
                                    SampleBanner(onExit = { onSetUseSample(false) })
                                }
                            }
                            if (snap.isEmpty) {
                                item(key = "state_empty") {
                                    StatsStateCard(
                                        title = "暂无统计数据",
                                        summary = "统计从 ${formatDateTime(snap.sinceMillis)} 开始，收到有效记录后会显示在这里。",
                                        primaryLabel = "刷新数据",
                                        onPrimaryClick = onManualRefresh,
                                    )
                                }
                            } else {
                                statsContent(
                                    snap = snap,
                                    visibleSections = visibleSections,
                                    rankModeIndex = uiState.rankModeIndex,
                                    onRankModeChange = onSelectRankMode,
                                    onOpenDetail = { onSelectDetail(it.uid) },
                                )
                            }
                        }
                    }
                }
            }

            // 注意：OverlayBottomSheet 与 OverlayDialog 必须留在 Scaffold 内容作用域内才能挂载至 MiuixPopupHost
            AppStatsDetailSheet(
                app = (effectiveState as? StatsLoadState.Ready)?.snapshot?.let { snapshot ->
                    resolveStatsDetail(
                        uiState.detailSelection,
                        uiState.queryScope,
                        StatsQueryScope(snapshot.window, snapshot.isSample),
                        snapshot.apps,
                        AppFreezeStats::uid,
                    )
                },
                updatedAtMillis = (effectiveState as? StatsLoadState.Ready)?.snapshot?.generatedAtMillis,
                refreshFailed = uiState.refreshFailed,
                onDismiss = { onSelectDetail(null) },
                onOpenAppProfile = { app ->
                    onSelectDetail(null)
                    onOpenAppProfile(app.packageName, app.uid)
                },
            )

            MethodologyDialog(
                show = showMethodologyDialog,
                onDismiss = { showMethodologyDialog = false },
            )
        }
    }
}
