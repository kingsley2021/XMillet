package io.github.univers629.millet.ui.screen
import io.github.univers629.millet.ui.util.GlassOverlayListPopup
import io.github.univers629.millet.ui.util.MilletTopAppBar
import io.github.univers629.millet.ui.util.BarIconButton

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.millet.core.FreezeModeLabels
import io.github.univers629.millet.core.RootHelper
import io.github.univers629.millet.ui.component.AppIconImage
import io.github.univers629.millet.ui.component.DropdownItem
import io.github.univers629.millet.ui.component.ListPopupDefaults
import io.github.univers629.millet.ui.component.StatusTagMiuix
import io.github.univers629.millet.ui.component.WarningCard
import io.github.univers629.millet.ui.component.WarningLevel
import io.github.univers629.millet.ui.di.appUiContainer
import io.github.univers629.millet.ui.theme.LocalEnableBlur
import io.github.univers629.millet.ui.util.BlurredBar
import io.github.univers629.millet.ui.util.rememberBlurBackdrop
import io.github.univers629.millet.ui.vm.AndroidAppProfileSystemBridge
import io.github.univers629.millet.ui.vm.AppProfileUiState
import io.github.univers629.millet.ui.vm.AppProfileViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import io.github.univers629.millet.ui.util.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.SecurityStatus
import top.yukonga.miuix.kmp.icon.glass.Battery100
import top.yukonga.miuix.kmp.icon.glass.Blocklist
import top.yukonga.miuix.kmp.icon.glass.CoolingFan
import top.yukonga.miuix.kmp.icon.glass.NoNetwork
import top.yukonga.miuix.kmp.icon.glass.NotificationsStatusBar
import top.yukonga.miuix.kmp.icon.glass.PlayCircleFill
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 单应用策略详情页入口：连接按 `(packageName, uid)` 作用域化的 [AppProfileViewModel] 与纯展示 [AppProfileContent]。
 */
@Composable
fun AppProfileRoute(
    packageName: String,
    uid: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { context.appUiContainer }
    val vm: AppProfileViewModel = viewModel(key = "$packageName:$uid") {
        AppProfileViewModel(
            packageName = packageName,
            uid = uid,
            policyRepo = container.policyRepository,
            runtimeRepo = container.runtimeAndAppsRepository,
            bridge = AndroidAppProfileSystemBridge(container.appContext),
        )
    }
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.toastEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    AppProfileContent(
        uiState = uiState,
        onBack = onBack,
        onRefreshProcesses = vm::refreshProcesses,
        onSetWhiteList = vm::setWhiteList,
        onSetMessageApp = vm::setMessageApp,
        onSetNotFreezeWhenHasFgs = vm::setNotFreezeWhenHasFgs,
        onSetForceFreeze = vm::setForceFreeze,
        onSetBlockThaw = vm::setBlockThaw,
        onSetForbidSyncBinder = vm::setForbidSyncBinder,
        onSetIgnoreNetworkWake = vm::setIgnoreNetworkWake,
    )
}

@Composable
fun AppProfileContent(
    uiState: AppProfileUiState,
    onBack: () -> Unit,
    onRefreshProcesses: () -> Unit,
    onSetWhiteList: (Boolean) -> Unit,
    onSetMessageApp: (Boolean) -> Unit,
    onSetNotFreezeWhenHasFgs: (Boolean) -> Unit,
    onSetForceFreeze: (Boolean) -> Unit,
    onSetBlockThaw: (Boolean) -> Unit,
    onSetForbidSyncBinder: (Boolean) -> Unit,
    onSetIgnoreNetworkWake: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val pageListState = rememberLazyListState()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    val app = uiState.app
    val isDualApp = uiState.isDualApp
    val appProcesses = uiState.appProcesses
    val isScanningProcesses = uiState.isScanningProcesses
    val processSnapshotUnavailable = uiState.processSnapshotUnavailable
    val hasScannedProcesses = uiState.hasScannedProcesses
    val inWhite = uiState.inWhite
    val inMessage = uiState.inMessage
    val inForceFreeze = uiState.inForceFreeze
    val inBlockThaw = uiState.inBlockThaw
    val inForbidSync = uiState.inForbidSync
    val inNotFreezeFgs = uiState.inNotFreezeFgs
    val inIgnoreNetworkWake = uiState.inIgnoreNetworkWake
    val systemUnrestricted = uiState.systemUnrestricted
    val systemPolicyLabel = uiState.systemPolicyLabel
    val refreshProcesses = onRefreshProcesses

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive) {
                MilletTopAppBar(
                    color = barColor,
                    title = "应用策略配置",
                    navigationIcon = {
                        BarIconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier
                                    .size(28.dp)
                                    .graphicsLayer {
                                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                    },
                                imageVector = MiuixIcons.Glass.Back,
                                contentDescription = "返回",
                                tint = colorScheme.onBackground
                            )
                        }
                    },
                    actions = {
                        val showTopPopup = remember { mutableStateOf(false) }
                        BarIconButton(
                            icon = MiuixIcons.Glass.MoreCircle,
                            contentDescription = "更多操作",
                            onClick = { showTopPopup.value = true },
                            holdDownState = showTopPopup.value,
                        )
                        GlassOverlayListPopup(
                            show = showTopPopup.value,
                            popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                            alignment = PopupPositionProvider.Align.TopEnd,
                            onDismissRequest = { showTopPopup.value = false },
                            content = {
                                ListPopupColumn {
                                    val items = listOf(
                                        "启动该应用",
                                        "系统应用详情页"
                                    )
                                    items.forEachIndexed { idx, text ->
                                        DropdownItem(
                                            text = text,
                                            optionSize = items.size,
                                            index = idx,
                                            onSelectedIndexChange = {
                                                showTopPopup.value = false
                                                when (idx) {
                                                    0 -> {
                                                        val launchIntent = context.packageManager
                                                            .getLaunchIntentForPackage(app.packageName)
                                                        if (launchIntent != null) {
                                                            runCatching { context.startActivity(launchIntent) }
                                                        } else {
                                                            Toast.makeText(context, "该应用无桌面启动入口", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                    1 -> {
                                                        val intent = Intent(
                                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            Uri.fromParts("package", app.packageName, null)
                                                        )
                                                        runCatching { context.startActivity(intent) }
                                                    }
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
            LazyColumn(
                state = pageListState,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = 12.dp)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = innerPadding,
                overscrollEffect = null
            ) {
                item {
                    // 1. 顶部应用信息卡片（复刻 KernelSU AppProfileMiuix）
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                        insideMargin = PaddingValues(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIconImage(
                                iconSource = app.iconSource,
                                label = app.appName,
                                modifier = Modifier.size(64.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .padding(start = 12.dp, end = 8.dp)
                                    .weight(1f)
                            ) {
                                Text(
                                    text = app.appName,
                                    color = colorScheme.onSurface,
                                    fontWeight = FontWeight(600),
                                    fontSize = 17.sp,
                                    modifier = Modifier.basicMarquee(),
                                    maxLines = 1,
                                    softWrap = false
                                )
                                if (app.versionName.isNotEmpty()) {
                                    Text(
                                        text = "${app.versionName} (${app.versionCode})",
                                        fontSize = 12.sp,
                                        color = colorScheme.onSurfaceVariantSummary,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.basicMarquee(),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Text(
                                    text = app.packageName,
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.basicMarquee(),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (app.isDualApp) {
                                    StatusTagMiuix(
                                        label = "双开",
                                        backgroundColor = colorScheme.tertiaryContainer,
                                        contentColor = colorScheme.onTertiaryContainer
                                    )
                                }
                                StatusTagMiuix(
                                    label = "UID ${app.uid}",
                                    backgroundColor = colorScheme.primary.copy(alpha = 0.85f),
                                    contentColor = colorScheme.onPrimary
                                )
                                if (app.isSystem) {
                                    StatusTagMiuix(
                                        label = "SYSTEM",
                                        backgroundColor = colorScheme.secondaryContainer,
                                        contentColor = colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }

                    // 2. 系统省电策略「无限制」提示卡片
                    if (systemUnrestricted) {
                        WarningCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            message = "系统包名级省电策略记录为「无限制 (MILLET_NO_RESTRICT_APP)」，该记录不代表当前进程的内核冻结状态。",
                            level = WarningLevel.Notice
                        )
                    }

                    // 3. 应用进程快照
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SmallTitle(
                            text = "进程快照 (${appProcesses.size})",
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = refreshProcesses,
                            enabled = !isScanningProcesses,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "刷新进程状态",
                                tint = colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                        insideMargin = PaddingValues(14.dp)
                    ) {
                        if (processSnapshotUnavailable && appProcesses.isNotEmpty()) {
                            Text(
                                text = "进程快照不可用，本次采集失败；保留上次成功快照。",
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        if (appProcesses.isEmpty()) {
                            // 空列表占位保持固定高度，减少扫描期间的布局变化。
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(96.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = when {
                                        isScanningProcesses -> "正在扫描进程..."
                                        processSnapshotUnavailable -> "进程快照不可用"
                                        !hasScannedProcesses -> "正在准备进程快照..."
                                        else -> "快照中没有该 UID 的进程"
                                    },
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = colorScheme.onSurface
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = when {
                                        isScanningProcesses -> "请稍候..."
                                        processSnapshotUnavailable -> "本次采集失败，点击刷新可重试"
                                        !hasScannedProcesses -> "转场结束后立即读取进程表"
                                        else -> "仅表示本次采集未返回目标 UID 的进程，不推断原因"
                                    },
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary
                                )
                            }
                        } else {
                            val totalMemKb = appProcesses.sumOf { it.rssKb }
                            val totalMemFormatted = RootHelper.formatMemSize(totalMemKb)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "共 ${appProcesses.size} 个进程",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onSurfaceVariantSummary
                                )
                                if (totalMemFormatted.isNotEmpty()) {
                                    Text(
                                        text = "总物理内存：$totalMemFormatted",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colorScheme.primary
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                appProcesses.forEachIndexed { index, proc ->
                                    if (index > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(0.6.dp)
                                                .background(colorScheme.outline.copy(alpha = 0.2f))
                                        )
                                    }
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // 头部：主/子进程标识 + 进程名 + 冻结/等待状态标签
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            val isMain = proc.processName == app.packageName
                                            Row(
                                                modifier = Modifier.weight(1f, fill = false),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (isMain) "● 主进程 " else "○ 子进程 ",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = if (isMain) colorScheme.primary else colorScheme.onSurfaceVariantSummary
                                                )
                                                Text(
                                                    text = proc.processName,
                                                    fontWeight = FontWeight(600),
                                                    fontSize = 13.5.sp,
                                                    color = colorScheme.onSurface,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier.basicMarquee()
                                                )
                                            }

                                            Spacer(Modifier.size(8.dp))

                                            // 冻结状态仅取进程快照，Greeze UID 记账不作为内核事实。
                                            val modeType = proc.freezerType
                                            val modeColor = FreezeModeLabels.colorHex(modeType)
                                            when (proc.freezeState) {
                                                RootHelper.FreezeState.FROZEN -> {
                                                    StatusTagMiuix(
                                                        label = if (modeColor != null) FreezeModeLabels.processLabel(modeType) else "已冻结（方式未知）",
                                                        backgroundColor = if (modeColor != null) Color(modeColor) else colorScheme.primary,
                                                        contentColor = Color.White
                                                    )
                                                }
                                                RootHelper.FreezeState.RUNNING -> {
                                                    StatusTagMiuix(
                                                        label = "未冻结",
                                                        backgroundColor = colorScheme.secondaryContainer,
                                                        contentColor = colorScheme.onSecondaryContainer
                                                    )
                                                }
                                                RootHelper.FreezeState.UNKNOWN -> {
                                                    StatusTagMiuix(
                                                        label = "冻结状态未知",
                                                        backgroundColor = colorScheme.secondaryContainer,
                                                        contentColor = colorScheme.onSecondaryContainer
                                                    )
                                                }
                                            }
                                        }

                                        // 核心指标行 1：实际物理内存与 PID
                                        val rssText = RootHelper.formatMemSize(proc.rssKb).ifEmpty { "0 KB" }
                                        Text(
                                            text = "物理内存: $rssText · PID: ${proc.pid}",
                                            fontSize = 12.sp,
                                            color = colorScheme.onSurfaceVariantSummary
                                        )

                                        // 核心指标行 2：运行时长与内核等待通道
                                        val uptimeText = proc.uptime.ifEmpty { "存活中" }
                                        Text(
                                            text = "运行时长: $uptimeText · 内核通道: ${proc.wchan}",
                                            fontSize = 12.sp,
                                            color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.85f)
                                        )

                                        // 挂载服务展示
                                        if (proc.services.isNotEmpty()) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "挂载服务 (${proc.services.size} 个):",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colorScheme.primary
                                                )
                                                proc.services.forEach { svc ->
                                                    Text(
                                                        text = "└─ $svc",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = colorScheme.onSurfaceVariantSummary,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        modifier = Modifier.basicMarquee()
                                                    )
                                                }
                                            }
                                        } else {
                                            Text(
                                                text = "挂载服务: 无活跃服务",
                                                fontSize = 11.sp,
                                                color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.65f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. 冻结豁免与消息策略组
                    SmallTitle(text = "冻结豁免与保活策略")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        SwitchPreference(
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
                            title = "加入 XMillet 免冻结白名单",
                            summary = if (isDualApp) {
                                "此双开实例加入模块免冻结名单，并关闭冲突策略；不修改系统包名级省电策略"
                            } else {
                                "永不冻结此应用（开启后自动关闭消息应用与强制冻结，并同步设为系统省电策略无限制）"
                            },
                            checked = inWhite,
                            onCheckedChange = onSetWhiteList
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.NotificationsStatusBar,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "设为消息应用",
                            summary = "允许收到网络推送包后短暂解冻处理消息（与免冻结白名单、忽略网络唤醒互斥）",
                            checked = inMessage,
                            onCheckedChange = onSetMessageApp
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.PlayCircleFill,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "存在前台服务时不冻结",
                            summary = "当 Aurogon 检测到活跃 ForegroundService 时阻止冻结（与免冻结白名单、强制冻结互斥）",
                            checked = inNotFreezeFgs,
                            onCheckedChange = onSetNotFreezeWhenHasFgs
                        )
                    }

                    // 5. 强力冻结与解冻压制组（带平滑折叠展开动画）
                    SmallTitle(text = "强力冻结与唤醒压制")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.CoolingFan,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "强制冻结",
                            summary = "向官方链路请求冻结并限制已适配的后台豁免（与免冻结白名单、前台服务不冻结互斥）",
                            checked = inForceFreeze,
                            onCheckedChange = onSetForceFreeze
                        )
                        AnimatedVisibility(
                            visible = inForceFreeze,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column {
                                SwitchPreference(
                                    startAction = {
                                        Icon(
                                            imageVector = MiuixIcons.Glass.Blocklist,
                                            contentDescription = null,
                                            modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    title = "禁止后台解冻",
                                    summary = "仅放行 Activity 启动与切换前台解冻，强力压制其他后台唤醒",
                                    checked = inBlockThaw,
                                    onCheckedChange = onSetBlockThaw
                                )
                                SwitchPreference(
                                    startAction = {
                                        Icon(
                                            imageVector = MiuixIcons.Glass.NoNetwork,
                                            contentDescription = null,
                                            modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    title = "阻止同步 Binder 唤醒",
                                    summary = "拦截 Sync Binder / Check Binder 与 MilletHandler what=13 唤醒",
                                    checked = inForbidSync,
                                    onCheckedChange = onSetForbidSyncBinder
                                )
                                SwitchPreference(
                                    startAction = {
                                        Icon(
                                            imageVector = MiuixIcons.Glass.NoNetwork,
                                            contentDescription = null,
                                            modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    title = "忽略网络唤醒",
                                    summary = "不再因推送、网络变化唤醒该应用，冻结会一直保持到手动打开（与设为消息应用互斥；代价是消息延迟送达）",
                                    checked = inIgnoreNetworkWake,
                                    onCheckedChange = onSetIgnoreNetworkWake
                                )
                            }
                        }
                    }

                    // 6. 系统省电策略（只读）
                    //    真值来自 Settings.System.MILLET_NO_RESTRICT_APP，进页面自动读取，
                    //    没有「绑定 / 初始化」按钮；要改就去系统设置里改。
                    SmallTitle(text = "系统省电策略")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.Battery100,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "当前策略",
                            summary = if (isDualApp) {
                                "$systemPolicyLabel；系统策略按包名共享，不能为双开独立设置；模块名单可按实例独立设置"
                            } else {
                                systemPolicyLabel
                            },
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        )
                    }

                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() +
                                24.dp
                        )
                    )
                }
            }
        }
    }
}
