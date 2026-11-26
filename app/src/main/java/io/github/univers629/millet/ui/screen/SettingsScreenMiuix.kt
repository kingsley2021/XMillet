package io.github.univers629.millet.ui.screen
import io.github.univers629.millet.ui.component.ProgressiveDialogLayout
import io.github.univers629.millet.ui.component.FoldablePreferenceCard
import io.github.univers629.millet.ui.component.FoldablePreferenceSection
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import io.github.univers629.millet.ui.util.MilletTopAppBar

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import io.github.univers629.millet.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.ui.BackupRestoreHelper
import io.github.univers629.millet.ui.component.DialogBackHandler
import io.github.univers629.millet.ui.component.MiuixConfirmDialog
import io.github.univers629.millet.ui.di.appUiContainer
import io.github.univers629.millet.ui.theme.LocalEnableBlur
import io.github.univers629.millet.ui.theme.UiThemeState
import io.github.univers629.millet.ui.util.AppUpdateController
import io.github.univers629.millet.ui.util.AppUpdateStatus
import io.github.univers629.millet.ui.util.BlurredBar
import io.github.univers629.millet.ui.util.BugreportHelper
import io.github.univers629.millet.ui.util.PopupBackdropHolder
import io.github.univers629.millet.ui.util.rememberBlurBackdrop
import io.github.univers629.millet.ui.vm.SettingsUiState
import io.github.univers629.millet.ui.vm.SettingsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Widget
import top.yukonga.miuix.kmp.icon.glass.Timer
import top.yukonga.miuix.kmp.icon.glass.Send
import top.yukonga.miuix.kmp.icon.glass.Report
import top.yukonga.miuix.kmp.icon.glass.RecordingTape
import top.yukonga.miuix.kmp.icon.glass.Prohibit
import top.yukonga.miuix.kmp.icon.glass.Power
import top.yukonga.miuix.kmp.icon.glass.MultiSplitScreen
import top.yukonga.miuix.kmp.icon.glass.LockOutline
import top.yukonga.miuix.kmp.icon.glass.JumpNext
import top.yukonga.miuix.kmp.icon.glass.HapticIntensity
import top.yukonga.miuix.kmp.icon.glass.NoSunProtectionNeeded
import top.yukonga.miuix.kmp.icon.glass.NotesOutline
import top.yukonga.miuix.kmp.icon.glass.Battery100
import top.yukonga.miuix.kmp.icon.glass.DisplayBrightness
import top.yukonga.miuix.kmp.icon.glass.QuietMode
import top.yukonga.miuix.kmp.icon.glass.Save
import top.yukonga.miuix.kmp.icon.glass.Share
import top.yukonga.miuix.kmp.icon.glass.Theme
import top.yukonga.miuix.kmp.icon.glass.Update
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.glass.Backup
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Import
import top.yukonga.miuix.kmp.icon.glass.Reset
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置页入口：连接 [SettingsViewModel] 与纯展示 [SettingsContent]。
 */
@Composable
fun SettingsRoute(
    themeState: UiThemeState,
    bottomInnerPadding: Dp,
    onOpenAppearance: () -> Unit,
    onOpenOobe: () -> Unit,
    onOpenAbout: () -> Unit,
    /** 系统服务是否已激活；未激活时隐藏「全局策略」整组（策略改不动，显示出来只会误导）。 */
    isActivated: Boolean = true,
) {
    val context = LocalContext.current
    val container = remember(context) { context.appUiContainer }
    val vm: SettingsViewModel = viewModel {
        SettingsViewModel(
            policyRepo = container.policyRepository,
            runtimeRepo = container.runtimeAndAppsRepository,
        )
    }
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    SettingsContent(
        uiState = uiState,
        themeState = themeState,
        bottomInnerPadding = bottomInnerPadding,
        onSetBooleanPref = vm::setBooleanPref,
        onSetDetailedLogging = vm::setDetailedLogging,
        onImportBackupJson = { json -> vm.importBackupJson(context, json) },
        onResetToDefaults = { vm.resetToDefaults(context) },
        onCheckForUpdates = {
            AppUpdateController.checkManually(
                context = context,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
            )
        },
        onOpenAppearance = onOpenAppearance,
        onOpenOobe = onOpenOobe,
        onOpenAbout = onOpenAbout,
        isActivated = isActivated,
    )
}

@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    themeState: UiThemeState,
    bottomInnerPadding: Dp,
    onSetBooleanPref: (String, Boolean) -> Unit,
    onSetDetailedLogging: (Boolean) -> Unit,
    onImportBackupJson: (String) -> Unit,
    onResetToDefaults: () -> Unit,
    onCheckForUpdates: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenOobe: () -> Unit = {},
    onOpenAbout: () -> Unit,
    /** 系统服务是否已激活；未激活时隐藏「全局策略」整组。 */
    isActivated: Boolean = true,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val pageListState = rememberLazyListState()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    val prefs = uiState.prefs
    val disableKill = prefs.disableKill
    val disableCharge = prefs.disableCharge
    val disableScreenOnThaw = prefs.disableScreenOnThaw
    val blockPowerSaveWhitelist = prefs.blockAddPowerSaveWhitelistApps
    val freezeDualApp = prefs.freezeDualApp
    val isLog = prefs.isLog
    val simplifyLog = prefs.simplifyLog
    val enableDoze = prefs.enableDoze
    val skipStateCheck = prefs.skipStateCheck
    val disableMotionListener = prefs.disableMotionListener
    val updateStatus = uiState.updateStatus
    val availableUpdateVersion = uiState.availableUpdateVersion

    var showRebootNotice by remember { mutableStateOf(false) }

    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showSendLogDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showConfigMenu by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val configScope = rememberCoroutineScope()
    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            isLoading = true
            configScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val json = BackupRestoreHelper.exportToJson(context)
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("无法写入目标文件")
                    }
                    Toast.makeText(context, "配置备份已保存", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    Toast.makeText(context, "保存失败：${t.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                }.orEmpty()
                if (json.isNotBlank()) {
                    onImportBackupJson(json)
                    Toast.makeText(context, "配置导入并同步成功", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showRebootNotice) {
        MiuixConfirmDialog(
            show = true,
            title = "需重启系统生效",
            message = "「墓碑与进程跟踪注入」控制系统服务启动期的核心底层注入，修改后请重启手机或软重启系统框架生效。",
            confirmText = "知道了",
            dismissText = null,
            onConfirm = { showRebootNotice = false },
            onDismiss = { showRebootNotice = false }
        )
    }

    if (showResetConfirmDialog) {
        MiuixConfirmDialog(
            show = true,
            title = "恢复默认配置",
            message = "确定要清空所有自定义应用名单并恢复初始开关状态（禁止杀后台=开启，消息应用=微信）吗？",
            confirmText = "确认重置",
            dismissText = "取消",
            onConfirm = {
                showResetConfirmDialog = false
                onResetToDefaults()
                Toast.makeText(context, "已恢复出厂默认配置", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showResetConfirmDialog = false }
        )
    }

    LoadingDialogMiuix(show = isLoading)

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive) {
                MilletTopAppBar(
                    color = barColor,
                    title = "设置",
                    scrollBehavior = scrollBehavior,
                    contentScrolledSignal = { pageListState.canScrollBackward },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                state = pageListState,
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = innerPadding,
                overscrollEffect = null
            ) {
                item {
                    SmallTitle(
                        text = "外观与主题",
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        GlassDropdownPreference(backdrop = backdrop, 
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.Theme,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "颜色模式",
                            items = listOf("跟随系统", "始终浅色", "始终深色"),
                            selectedIndex = themeState.darkMode,
                            onSelectedIndexChange = { themeState.updateDarkMode(it) }
                        )

                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.Widget,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "外观个性化",
                            summary = "悬浮底栏、玻璃材质和动态取色",
                            onClick = onOpenAppearance
                        )
                    }

                    if (isActivated) {
                        SmallTitle(text = "全局策略")
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp)
                        ) {
                            FoldablePreferenceSection(
                                title = "Millet 墓碑冻结全局策略",
                                summary = "已开启 ${listOf(disableKill, disableCharge, disableScreenOnThaw, blockPowerSaveWhitelist, freezeDualApp).count { it }}/5 项",
                                icon = MiuixIcons.Glass.NoSunProtectionNeeded,
                            ) {
                            SwitchPreference(
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
                                title = "禁止息屏与夜间清理后台",
                                summary = "拦截 PowerCheckerController 与 PowerStateMachine自动杀后台",
                                checked = disableKill,
                                onCheckedChange = { onSetBooleanPref(PrefsKeys.DISABLE_KILL, it) }
                            )
                            SwitchPreference(
                                startAction = {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.Power,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(24.dp),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                title = "禁止插电时全量解冻",
                                summary = "拦截 MilletBrightHandler what=16 与 Greezer 的插电解冻",
                                checked = disableCharge,
                                onCheckedChange = { onSetBooleanPref(PrefsKeys.DISABLE_CHARGE, it) }
                            )
                            SwitchPreference(
                                startAction = {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.DisplayBrightness,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(24.dp),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                title = "亮屏时保持冻结",
                                summary = "屏幕亮起时，不让系统批量解冻后台应用。此项不控制手机进入深度睡眠",
                                checked = disableScreenOnThaw,
                                onCheckedChange = { onSetBooleanPref(PrefsKeys.DISABLE_SCREEN_ON_THAW, it) }
                            )
                            SwitchPreference(
                                startAction = {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.Prohibit,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(24.dp),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                title = "禁止篡改省电白名单",
                                summary = "拦截电量与性能后台自动增删省电白名单（保留你在系统设置里的手动修改）",
                                checked = blockPowerSaveWhitelist,
                                onCheckedChange = {
                                    onSetBooleanPref(PrefsKeys.BLOCK_ADD_POWER_SAVE_WHITELIST_APPS, it)
                                }
                            )
                            SwitchPreference(
                                startAction = {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.MultiSplitScreen,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(24.dp),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                title = "控制双开应用冻结",
                                summary = "接管双开空间（用户 999）的冻结，应用策略单独设置；原生包名级名单仍可能共享",
                                checked = freezeDualApp,
                                onCheckedChange = { onSetBooleanPref(PrefsKeys.FREEZE_DUAL_APP, it) }
                            )
                            }
                            FoldablePreferenceSection(
                                title = "Doze 深度睡眠策略",
                                summary = "已开启 ${listOf(enableDoze, skipStateCheck, disableMotionListener).count { it }}/3 项",
                                icon = MiuixIcons.Glass.QuietMode,
                            ) {
                            SwitchPreference(
                                startAction = {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.Timer,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(24.dp),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                title = "启用 Doze 省电控制增强",
                                summary = "息屏且未充电时，约 10 秒后尝试进入深度睡眠。亮屏或充电时，退出本模块开启的强制睡眠",
                                checked = enableDoze,
                                onCheckedChange = { onSetBooleanPref(PrefsKeys.ENABLE_DOZE, it) }
                            )
                            SwitchPreference(
                                startAction = {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.JumpNext,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(24.dp),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                title = "跳过 Doze 中间传感检查",
                                summary = "当状态进入IDLE_PENDING(2)时直接跃迁至 IDLE_MAINTENANCE(6)",
                                checked = skipStateCheck,
                                onCheckedChange = { onSetBooleanPref(PrefsKeys.SKIP_STATE_CHECK, it) }
                            )
                            SwitchPreference(
                                startAction = {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.HapticIntensity,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(24.dp),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                title = "屏蔽 Doze 移动传感器唤醒",
                                summary = "DeviceIdleController#motionLocked，移动手机不退出深度睡眠",
                                checked = disableMotionListener,
                                onCheckedChange = { onSetBooleanPref(PrefsKeys.DISABLE_MOTION_LISTENER, it) }
                            )
                            }
                        }
                    }

                    SmallTitle(text = "日志与电量诊断")
                    FoldablePreferenceCard(
                        title = "运行日志与诊断",
                        summary = "已开启 ${listOf(isLog, simplifyLog).count { it }}/2 项",
                        icon = MiuixIcons.Glass.NotesOutline,
                    ) {
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.Report,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "开启详细运行日志",
                            summary = "记录运行拦截、电量统计与 Doze 日志并写入本地目录",
                            checked = isLog,
                            onCheckedChange = onSetDetailedLogging
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.RecordingTape,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "启用墓碑日志与进程级跟踪",
                            summary = "跟踪单进程冻结并输出墓碑日志(F)/(T)，需重启生效",
                            checked = simplifyLog,
                            onCheckedChange = {
                                onSetBooleanPref(PrefsKeys.SIMPLIFY_LOG, it)
                                showRebootNotice = true
                            }
                        )
                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.Send,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "发送日志",
                            summary = "打包诊断日志与运行状态，支持保存或分享",
                            onClick = { showSendLogDialog = true }
                        )
                    }

                    SmallTitle(text = "关于")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.Backup,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "配置与备份",
                            summary = "备份、恢复、分享、导出或恢复默认配置",
                            onClick = { showConfigMenu = true }
                        )
                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.LockOutline,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "权限配置向导",
                            summary = "重新检查所需应用权限",
                            onClick = onOpenOobe
                        )
                        val updateSummary = when (updateStatus) {
                            AppUpdateStatus.Checking -> "正在检查更新…"
                            AppUpdateStatus.Available -> if (!availableUpdateVersion.isNullOrBlank()) {
                                "发现新版本 v$availableUpdateVersion，点击查看"
                            } else {
                                "发现新版本，点击查看"
                            }
                            AppUpdateStatus.UpToDate -> "当前已是最新版本 (${BuildConfig.VERSION_NAME})"
                            AppUpdateStatus.Failed -> "检查失败，点击重试"
                            AppUpdateStatus.Idle -> "检查 GitHub Releases 是否有新版本"
                        }
                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.Update,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "检查更新",
                            summary = updateSummary,
                            onClick = onCheckForUpdates
                        )
                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Info,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "关于 XMillet",
                            summary = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                            onClick = onOpenAbout
                        )
                    }

                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }

            ConfigActionsDialog(
                show = showConfigMenu,
                onDismissRequest = { showConfigMenu = false },
                onBackup = { createBackupLauncher.launch(BugreportHelper.buildBackupFileName()) },
                onRestore = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                onShare = {
                    configScope.launch {
                        isLoading = true
                        try {
                            val file = withContext(Dispatchers.IO) {
                                BugreportHelper.createBackupCacheFile(context)
                            }
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${BuildConfig.APPLICATION_ID}.fileprovider",
                                file
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_STREAM, uri)
                                setDataAndType(uri, "application/json")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "分享配置备份"))
                        } catch (t: Throwable) {
                            Toast.makeText(context, "分享失败：${t.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            isLoading = false
                        }
                    }
                },
                onExport = {
                    configScope.launch {
                        isLoading = true
                        try {
                            val name = withContext(Dispatchers.IO) {
                                exportBackupToDownloads(context)
                            }
                            Toast.makeText(context, "已导出到下载目录：$name", Toast.LENGTH_SHORT).show()
                        } catch (t: Throwable) {
                            Toast.makeText(context, "导出失败：${t.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            isLoading = false
                        }
                    }
                },
                onReset = { showResetConfirmDialog = true },
            )
            SendLogDialog(
                show = showSendLogDialog,
                onDismissRequest = { showSendLogDialog = false },
                onSetLoading = { isLoading = it }
            )
            BackupConfigDialog(
                show = showBackupDialog,
                onDismissRequest = { showBackupDialog = false },
                onSetLoading = { isLoading = it }
            )
        }
    }
}

@Composable
private fun LoadingDialogMiuix(show: Boolean) {
    OverlayDialog(
        show = show,
        onDismissRequest = { },
        content = {
            DialogBackHandler { }
            val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
            NavigationBackHandler(
                state = navEventState,
                isBackEnabled = true,
                onBackCompleted = { },
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    InfiniteProgressIndicator(
                        color = colorScheme.onBackground
                    )
                    Text(
                        modifier = Modifier.padding(start = 12.dp),
                        text = "正在处理…",
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    )
}

@Composable
private fun SendLogDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSetLoading: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    SaveShareActionDialog(
        show = show,
        title = "发送日志",
        saveTitle = "保存日志",
        shareTitle = "发送日志",
        shareChooserTitle = "发送日志",
        mimeType = "application/gzip",
        defaultFileName = { BugreportHelper.buildBugreportFileName() },
        saveSuccessToast = "日志已保存",
        onDismissRequest = onDismissRequest,
        onSetLoading = onSetLoading,
        onSaveToUri = { uri ->
            val bugreport = BugreportHelper.getBugreportFile(context)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                bugreport.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("无法写入目标文件")
        },
        onCreateShareFile = { BugreportHelper.getBugreportFile(context) }
    )
}

@Composable
private fun BackupConfigDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSetLoading: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    SaveShareActionDialog(
        show = show,
        title = "备份配置",
        saveTitle = "保存备份",
        shareTitle = "分享备份",
        shareChooserTitle = "分享配置备份",
        mimeType = "application/json",
        defaultFileName = { BugreportHelper.buildBackupFileName() },
        saveSuccessToast = "配置备份已保存",
        onDismissRequest = onDismissRequest,
        onSetLoading = onSetLoading,
        onSaveToUri = { uri ->
            val json = BackupRestoreHelper.exportToJson(context)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入目标文件")
        },
        onCreateShareFile = { BugreportHelper.createBackupCacheFile(context) }
    )
}

@Composable
private fun SaveShareActionDialog(
    show: Boolean,
    title: String,
    saveTitle: String,
    shareTitle: String,
    shareChooserTitle: String,
    mimeType: String,
    defaultFileName: () -> String,
    saveSuccessToast: String,
    onDismissRequest: () -> Unit,
    onSetLoading: (Boolean) -> Unit,
    onSaveToUri: suspend (Uri) -> Unit,
    onCreateShareFile: suspend () -> java.io.File,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType)
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            onSetLoading(true)
            try {
                withContext(Dispatchers.IO) {
                    onSaveToUri(uri)
                }
                Toast.makeText(context, saveSuccessToast, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(context, "保存失败：${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                onSetLoading(false)
            }
        }
    }

    // 与「运行环境与服务」底部面板同一套模糊实现：GlassDialog 采样全局弹窗背景源，
    // 关闭背景模糊时 PopupBackdropHolder 为 null，自动回退为不透明底色。
    val glassBackdrop = PopupBackdropHolder.current
    val maskColor = if (glassBackdrop != null) {
        colorScheme.background.copy(alpha = 0.72f)
    } else {
        colorScheme.background
    }

    GlassDialog(
        onDismissRequest = onDismissRequest,
        show = show,
        backdrop = glassBackdrop,
        insideMargin = DpSize(0.dp, 0.dp),
        content = {
            ProgressiveDialogLayout(
                title = title,
                headerPadding = PaddingValues(top = 24.dp, bottom = 12.dp),
                footerPadding = PaddingValues(top = 12.dp, bottom = 24.dp, start = 24.dp, end = 24.dp),
                surfaceColor = maskColor,
                footer = {
                    TextButton(
                        text = "取消",
                        onClick = onDismissRequest,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
            ) {
            ArrowPreference(
                title = saveTitle,
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.Save,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                        tint = colorScheme.onSurface
                    )
                },
                onClick = {
                    createDocumentLauncher.launch(defaultFileName())
                    onDismissRequest()
                },
                insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            )
            ArrowPreference(
                title = shareTitle,
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.Share,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                        tint = colorScheme.onSurface
                    )
                },
                onClick = {
                    scope.launch {
                        onDismissRequest()
                        onSetLoading(true)
                        try {
                            val file = withContext(Dispatchers.IO) {
                                onCreateShareFile()
                            }
                            val uri: Uri = FileProvider.getUriForFile(
                                context,
                                "${BuildConfig.APPLICATION_ID}.fileprovider",
                                file
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_STREAM, uri)
                                setDataAndType(uri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, shareChooserTitle)
                            )
                        } catch (t: Throwable) {
                            Toast.makeText(context, "分享失败：${t.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            onSetLoading(false)
                        }
                    }
                },
                insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            )
            }
        }
    )
}

@Composable
private fun ConfigActionsDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
) {
    // 与「运行环境与服务检测」同款底部面板：GlassBottomSheet 采样全局弹窗背景源，
    // 关闭背景模糊时 PopupBackdropHolder 为 null，面板自动回退为不透明底色。
    val dismissState = LocalDismissState.current
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val bodyMaxHeight = (screenHeight * 0.66f).coerceIn(280.dp, 580.dp)
    val scrollState = rememberScrollState()

    GlassBottomSheet(backdrop = PopupBackdropHolder.current,
        show = show,
        title = "配置与备份",
        // 20dp：标题、右上关闭按钮与整段行内容都跟着向内收，不再贴屏幕边缘。
        insideMargin = DpSize(20.dp, 8.dp),
        // 与运行环境检测面板一致：progressiveBlur 关闭后顶栏为栈式排布，不再以浅色带覆盖正文
        progressiveBlur = false,
        endAction = {
            IconButton(
                onClick = {
                    onDismissRequest()
                    dismissState?.invoke()
                },
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "关闭",
                    tint = colorScheme.onBackground,
                )
            }
        },
        onDismissRequest = {
            onDismissRequest()
            dismissState?.invoke()
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(scrollState)
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ConfigActionRow("备份配置", MiuixIcons.Glass.Backup) {
                    onDismissRequest()
                    onBackup()
                }
                ConfigActionRow("恢复配置", MiuixIcons.Glass.Import) {
                    onDismissRequest()
                    onRestore()
                }
                ConfigActionRow("分享备份", MiuixIcons.Glass.Share) {
                    onDismissRequest()
                    onShare()
                }
                ConfigActionRow("导出到下载目录", MiuixIcons.Glass.Download) {
                    onDismissRequest()
                    onExport()
                }
                ConfigActionRow("恢复默认配置", MiuixIcons.Glass.Reset) {
                    onDismissRequest()
                    onReset()
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = "取消",
                    onClick = {
                        onDismissRequest()
                        dismissState?.invoke()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ConfigActionRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = title,
        startAction = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 16.dp).size(24.dp),
                tint = colorScheme.onSurface,
            )
        },
        onClick = onClick,
        // 面板自身已留 20dp 内边距，行再固定 8dp：图标 / 标题 / 右侧箭头合计距屏幕边约 28dp。
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
    )
}

/** 把配置 JSON 写入系统下载目录，返回文件名；失败抛异常。 */
private fun exportBackupToDownloads(context: Context): String {
    val fileName = BugreportHelper.buildBackupFileName()
    val json = BackupRestoreHelper.exportToJson(context)
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "application/json")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("无法创建下载文件")
    resolver.openOutputStream(uri)?.use { output ->
        output.write(json.toByteArray(Charsets.UTF_8))
    } ?: error("无法写入下载文件")
    return fileName
}
