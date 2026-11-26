package io.github.univers629.millet.ui.screen
import io.github.univers629.millet.ui.util.MilletRefreshTexts
import io.github.univers629.millet.ui.util.GlassOverlayListPopup
import top.yukonga.miuix.kmp.icon.glass.SecurityStatus
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import io.github.univers629.millet.ui.util.MilletTopAppBar
import io.github.univers629.millet.ui.util.BarIconButton


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.univers629.millet.core.FreezeModeLabels
import io.github.univers629.millet.ui.component.AppIconImage
import io.github.univers629.millet.ui.component.DropdownItem
import io.github.univers629.millet.ui.component.ListPopupDefaults
import io.github.univers629.millet.ui.component.StatusTagMiuix
import io.github.univers629.millet.ui.di.appUiContainer
import io.github.univers629.millet.ui.theme.LocalEnableBlur
import io.github.univers629.millet.ui.util.BlurredBar
import io.github.univers629.millet.ui.util.rememberBlurBackdrop
import io.github.univers629.millet.ui.vm.ProcessFilterMode
import io.github.univers629.millet.ui.vm.ProcessListUiState
import io.github.univers629.millet.ui.vm.ProcessListViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import io.github.univers629.millet.ui.util.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.glass.MoreCircle
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 进程列表入口：连接 [ProcessListViewModel] 与纯展示 [ProcessListContent]。
 */
@Composable
fun ProcessListRoute(
    isCurrentPage: Boolean,
    bottomInnerPadding: Dp,
    onOpenAppProfile: (packageName: String, uid: Int) -> Unit,
    onOpenApps: () -> Unit = {},
) {
    val context = LocalContext.current
    val runtimeRepo = remember(context) { context.appUiContainer.runtimeAndAppsRepository }
    val vm: ProcessListViewModel = viewModel { ProcessListViewModel(runtimeRepo) }
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    ProcessListContent(
        uiState = uiState.copy(isRefreshing = uiState.isRefreshing && isCurrentPage),
        bottomInnerPadding = bottomInnerPadding,
        onFilterIndexChange = vm::setFilterIndex,
        onSearchQueryChange = vm::setSearchQuery,
        onToggleShowSystemApps = vm::toggleShowSystemApps,
        onRefresh = vm::refresh,
        onThawAll = vm::thawAll,
        onOpenAppProfile = onOpenAppProfile,
        onOpenApps = onOpenApps,
    )
}

@Composable
fun ProcessListContent(
    uiState: ProcessListUiState,
    bottomInnerPadding: Dp,
    onFilterIndexChange: (Int) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleShowSystemApps: () -> Unit,
    onRefresh: () -> Unit,
    onThawAll: () -> Unit,
    onOpenAppProfile: (packageName: String, uid: Int) -> Unit,
    onOpenApps: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val pageListState = rememberLazyListState()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    val filteredProcesses = uiState.filteredProcesses
    val totalProcessCount = uiState.totalProcessCount
    val frozenCount = uiState.frozenCount
    val runningCount = uiState.runningCount
    val filterIndex = uiState.filterIndex
    val searchQuery = uiState.searchQuery
    val showSystemApps = uiState.showSystemApps
    val isRefreshing = uiState.isRefreshing
    val snapshotUnavailable = uiState.snapshotUnavailable

    val pullToRefreshState = rememberPullToRefreshState()
    var showTopPopup by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive) {
                MilletTopAppBar(
                    color = barColor,
                    title = "进程列表 (${filteredProcesses.size})",
                    actions = {
                        BarIconButton(
                            icon = MiuixIcons.Glass.Refresh,
                            contentDescription = "刷新进程列表",
                            onClick = onRefresh,
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
                                val menuItems = listOf("一键解冻全部应用", "重新扫描进程", if (showSystemApps) "隐藏系统应用" else "显示系统应用")
                                ListPopupColumn {
                                    menuItems.forEachIndexed { idx, text ->
                                        DropdownItem(
                                            text = text,
                                            optionSize = menuItems.size,
                                            index = idx,
                                            onSelectedIndexChange = {
                                                showTopPopup = false
                                                when (idx) {
                                                    0 -> onThawAll()
                                                    1 -> onRefresh()
                                                    2 -> onToggleShowSystemApps()
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    },
                    scrollBehavior = scrollBehavior,
                    contentScrolledSignal = { pageListState.canScrollBackward },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            PullToRefresh(
                isRefreshing = isRefreshing,
                pullToRefreshState = pullToRefreshState,
                onRefresh = onRefresh,
                refreshTexts = MilletRefreshTexts,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 6.dp
                ),
            ) {
                LazyColumn(
                    state = pageListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = bottomInnerPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    overscrollEffect = null
                ) {
                    item {
                        Spacer(Modifier.height(0.dp))
                    }

                    // 1. Miuix 胶囊搜索框
                    item {
                        MiuixCapsuleSearchBar(
                            query = searchQuery,
                            onQueryChange = onSearchQueryChange,
                            placeholder = "搜索进程名称、包名或 PID/UID...",
                            horizontalPadding = 0.dp,
                            topPadding = 4.dp,
                            // 搜索栏的语义边界比视觉胶囊高约 1.3dp，补 1dp 让「搜索→筛选」
                            // 与 10dp 行距对齐（440dpi 实测 28px）
                            bottomPadding = 1.dp
                        )
                    }

                    // 2. 应用策略入口卡片（从底栏移到筛选上方）
                    item(key = "apps_entry") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            ArrowPreference(
                                title = "应用策略",
                                summary = "为单个应用配置冻结、唤醒与保护策略",
                                startAction = {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.SecurityStatus,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(24.dp),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = onOpenApps,
                            )
                        }
                    }

                    // 3. 状态筛选（下拉，避免与切页手势冲突）
                    if (snapshotUnavailable) {
                        item {
                            BasicComponent(title = "进程快照不可用", summary = "显示上次结果；请检查模块连接后刷新，旧结果不代表当前状态。")
                        }
                    }
                    item {
                        val filterCounts = ProcessFilterMode.entries.map { mode ->
                            when (mode) {
                                ProcessFilterMode.ALL -> totalProcessCount
                                ProcessFilterMode.FROZEN -> frozenCount
                                ProcessFilterMode.RUNNING -> runningCount
                                ProcessFilterMode.UNKNOWN -> totalProcessCount - frozenCount - runningCount
                            }
                        }
                        // 下拉选择代替横向胶囊条：不再与左右切页手势争抢横向拖动
                        Card(modifier = Modifier.fillMaxWidth()) {
                            GlassDropdownPreference(
                                title = "筛选",
                                items = ProcessFilterMode.entries.mapIndexed { i, mode -> "${mode.label} (${filterCounts[i]})" },
                                selectedIndex = filterIndex.coerceIn(0, ProcessFilterMode.entries.lastIndex),
                                onSelectedIndexChange = onFilterIndexChange,
                                backdrop = backdrop,
                            )
                        }
                    }

                    // 3. 进程卡片列表
                    if (filteredProcesses.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                onClick = {
                                    onSearchQueryChange("")
                                    onFilterIndexChange(0)
                                    onRefresh()
                                },
                                showIndication = true
                            ) {
                                BasicComponent(
                                    title = if (snapshotUnavailable) "暂时无法获取进程" else if (totalProcessCount == 0) "暂无活跃应用进程" else "未找到匹配的进程",
                                    summary = if (totalProcessCount == 0) "点击此卡片或右上角刷新按钮重新扫描" else "点击重置搜索与分类"
                                )
                            }
                        }
                    } else {
                        items(filteredProcesses, key = { "${it.packageName}:${it.uid}" }) { proc ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onOpenAppProfile(proc.packageName, proc.uid) },
                                showIndication = true,
                                pressFeedbackType = PressFeedbackType.Sink,
                                insideMargin = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AppIconImage(
                                        iconSource = proc.iconSource,
                                        label = proc.appName,
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(46.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = proc.appName,
                                            fontWeight = FontWeight(550),
                                            color = colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val descText = buildString {
                                            append("${proc.processCount}个进程")
                                            if (proc.frozenCount > 0) {
                                                append(" · ${proc.frozenCount}个已冻结")
                                            }
                                            if (proc.formattedMemSize.isNotEmpty()) {
                                                append(" · ${proc.formattedMemSize}")
                                            }
                                        }
                                        Text(
                                            text = descText,
                                            fontSize = 12.sp,
                                            color = colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${proc.packageName} · UID: ${proc.uid}",
                                            fontSize = 11.sp,
                                            color = colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    // 冻结状态与冻结方式固定在右侧，避免长包名把标签挤出可视区域
                                    Row(
                                        modifier = Modifier.padding(start = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (proc.isDualApp) {
                                            StatusTagMiuix(
                                                label = "双开",
                                                backgroundColor = colorScheme.tertiaryContainer,
                                                contentColor = colorScheme.onTertiaryContainer
                                            )
                                        }
                                        if (proc.isFrozen) {
                                            StatusTagMiuix(
                                                label = "已冻结",
                                                backgroundColor = colorScheme.primary,
                                                contentColor = colorScheme.onPrimary
                                            )
                                        } else {
                                            StatusTagMiuix(
                                                 label = when {
                                                    proc.frozenCount > 0 && proc.unknownCount > 0 -> "部分冻结 · 其余未知"
                                                    proc.frozenCount > 0 -> "部分冻结"
                                                    proc.unknownCount > 0 -> "状态未知"
                                                    else -> "运行中"
                                                },
                                                backgroundColor = colorScheme.secondaryContainer,
                                                contentColor = colorScheme.onSecondaryContainer
                                            )
                                        }
                                        if (proc.isFrozen) {
                                            if (proc.freezerTypes.isEmpty()) {
                                                StatusTagMiuix(
                                                    label = "冻结方式未知",
                                                    backgroundColor = colorScheme.secondaryContainer,
                                                    contentColor = colorScheme.onSecondaryContainer
                                                )
                                            } else {
                                                for (fType in proc.freezerTypes) {
                                                    val modeColor = FreezeModeLabels.colorHex(fType)
                                                    StatusTagMiuix(
                                                        label = FreezeModeLabels.shortLabel(fType),
                                                        backgroundColor = if (modeColor != null) {
                                                            Color(modeColor)
                                                        } else {
                                                            colorScheme.secondaryContainer
                                                        },
                                                        contentColor = if (modeColor != null) {
                                                            Color.White
                                                        } else {
                                                            colorScheme.onSecondaryContainer
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Icon(
                                        imageVector = MiuixIcons.Basic.ArrowRight,
                                        contentDescription = "查看详情与进程列表",
                                        tint = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(bottomInnerPadding))
                    }
                }
            }
        }
    }
}
