package io.github.univers629.millet.ui

import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults.flingBehavior
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.univers629.millet.BuildConfig
import io.github.univers629.millet.core.OobeManager
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.component.AppUpdateDialogMiuix
import io.github.univers629.millet.ui.component.BottomBarDestination
import io.github.univers629.millet.ui.component.BottomBarMiuix
import io.github.univers629.millet.ui.component.MiuixConfirmDialog
import io.github.univers629.millet.ui.component.rememberMainPagerState
import io.github.univers629.millet.ui.di.appUiContainer
import io.github.univers629.millet.ui.nav.NavEntryStoreRegistry
import io.github.univers629.millet.ui.nav.ProvideNavEntryViewModelStore
import io.github.univers629.millet.ui.nav.SyncNavBackStackStores
import io.github.univers629.millet.ui.screen.AboutKnowledgeScreenMiuix
import io.github.univers629.millet.ui.screen.AboutLicensesScreenMiuix
import io.github.univers629.millet.ui.screen.AboutRepoScreenMiuix
import io.github.univers629.millet.ui.screen.AboutScreenMiuix
import io.github.univers629.millet.ui.screen.AppProfileRoute
import io.github.univers629.millet.ui.screen.AppsRoute
import io.github.univers629.millet.ui.screen.CloudWhitelistRoute
import io.github.univers629.millet.ui.screen.HomeRoute
import io.github.univers629.millet.ui.screen.OobeScreenMiuix
import io.github.univers629.millet.ui.screen.PolicyTemplateDetailRoute
import io.github.univers629.millet.ui.screen.PolicyTemplatesRoute
import io.github.univers629.millet.ui.screen.ProcessListRoute
import io.github.univers629.millet.ui.screen.SettingsRoute
import io.github.univers629.millet.ui.screen.ThemeSettingsScreenMiuix
import io.github.univers629.millet.ui.screen.TombStatsScreenMiuix
import io.github.univers629.millet.ui.theme.LocalFloatingBottomBarStyle
import io.github.univers629.millet.ui.theme.LocalEnableBlur
import io.github.univers629.millet.ui.theme.UiThemeState
import io.github.univers629.millet.ui.theme.XMilletMiuixTheme
import io.github.univers629.millet.ui.util.AppUpdateController
import io.github.univers629.millet.ui.util.AppUpdateDownloadState
import io.github.univers629.millet.ui.util.CardClickLock
import io.github.univers629.millet.ui.util.LocalCardClickLock
import io.github.univers629.millet.ui.util.PopupBackdropHolder
import io.github.univers629.millet.ui.util.rememberBlurBackdrop
import io.github.univers629.millet.ui.vm.MainShellViewModel
import io.github.univers629.millet.ui.vm.ProcessListViewModel
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec

/**
 * XMillet 主界面宿主 Activity：仅负责导航壳层、前景生命周期编排与主题提供，
 * 业务状态与计算下沉至各页面 `ViewModel` 与响应式 `Repository`。
 */
class MainActivity : ComponentActivity() {

    // 返回栈以 JSON 持久化，所有路由都必须是可序列化的多态子类。
    @Serializable
    private sealed interface Route : NavKey {
        @Serializable
        data object Main : Route

        // 只携带身份，不携带完整模型：模型里的策略布尔会随配置变化，放进路由会让相等性不稳定，
        // 也无法跨进程重建。展示所需字段在组合时按包名与 UID 从仓库解析。
        @Serializable
        data class AppProfile(val packageName: String, val uid: Int) : Route

        @Serializable
        data object PolicyTemplates : Route

        @Serializable
        data class PolicyTemplateDetail(val templateId: String) : Route

        @Serializable
        data object CloudWhitelist : Route

        @Serializable
        data object Apps : Route

        @Serializable
        data object Appearance : Route

        @Serializable
        data object Oobe : Route

        @Serializable
        data object About : Route

        @Serializable
        data object AboutKnowledge : Route

        @Serializable
        data object AboutRepo : Route

        @Serializable
        data object AboutLicenses : Route
    }

    private class Navigator(
        val backStack: NavBackStack,
        private val clickLock: CardClickLock,
    ) {
        /**
         * 压栈。转场动画未播完时（[CardClickLock.NavigationWindowMillis] 窗口内）直接忽略，
         * 避免卡片连点在动画过程中又跳一次页面。
         */
        fun push(key: NavKey) {
            val identity = routeIdentity(key)
            if (backStack.any { routeIdentity(it) == identity }) return
            if (!clickLock.tryAcquire(CardClickLock.NavigationWindowMillis)) return
            backStack.add(key)
        }

        fun routeIdentity(key: NavKey): Any = when (key) {
            is Route.AppProfile -> key.packageName to key.uid
            else -> key
        }

        fun pop() {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        }

        fun current() = backStack.lastOrNull()
    }

    @Composable
    private fun rememberNavigator(startRoute: Route, clickLock: CardClickLock): Navigator {
        val backStack = rememberNavBackStack<Route>(startRoute)
        return remember(backStack, clickLock) { Navigator(backStack, clickLock) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        val container = appUiContainer
        migrateRemovedThemedIconAlias(container.uiPrefs)

        // 尝试建立跨进程双向 Binder 连接
        PowerKeeperBinderClient.bindFromApp(this)

        setContent {
            val shellVm: MainShellViewModel = viewModel {
                MainShellViewModel(
                    themeState = UiThemeState(container.uiPrefs),
                    initialProvisioned = OobeManager.isProvisioned(this@MainActivity),
                    policyRepo = container.policyRepository,
                    runtimeRepo = container.runtimeAndAppsRepository,
                )
            }
            XMilletMiuixTheme(themeState = shellVm.themeState) {
                MainActivityContent(shellVm = shellVm)
            }
        }
    }

    /**
     * 旧版本提供过一个「桌面图标取色」开关，原理只是把启动器别名从 MainActivityAlias 切到
     * MainActivityAliasThemed。该别名已随取色开关一并删除，若用户此前开着它，升级后默认别名
     * 仍是被禁用的，桌面图标会凭空消失。这里做一次迁移：把图标补回默认别名，并清掉旧键。
     */
    private fun migrateRemovedThemedIconAlias(prefs: SharedPreferences) {
        val alias = ComponentName(this, "$packageName.ui.MainActivityAlias")
        val legacyAlias = ComponentName(this, "$packageName.ui.MainActivityAliasThemed")
        val aliasState = packageManager.getComponentEnabledSetting(alias)
        val legacyState = packageManager.getComponentEnabledSetting(legacyAlias)
        val legacyToggle = prefs.getBoolean(KEY_THEMED_ICON_LEGACY, false)
        val legacyAliasEnabled = legacyState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if ((legacyToggle || legacyAliasEnabled) &&
            aliasState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ) {
            packageManager.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        if (legacyAliasEnabled) {
            packageManager.setComponentEnabledSetting(
                legacyAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        if (prefs.getBoolean(KEY_THEMED_ICON_MIGRATED, false) && !legacyToggle) return
        prefs.edit()
            .remove(KEY_THEMED_ICON_LEGACY)
            .putBoolean(KEY_THEMED_ICON_MIGRATED, true)
            .apply()
    }

    @Composable
    private fun MainActivityContent(shellVm: MainShellViewModel) {
        val container = remember { appUiContainer }
        val themeState = shellVm.themeState
        // 激活状态决定底栏 Tab 数量与几处界面的可见性，必须在使用它的 destinations 之前收集。
        val isActivated by shellVm.activatedFlow.collectAsStateWithLifecycle()
        val navigator = rememberNavigator(Route.Main, LocalCardClickLock.current)
        val navStoreRegistry: NavEntryStoreRegistry = viewModel { NavEntryStoreRegistry() }
        val processListVm: ProcessListViewModel = viewModel {
            ProcessListViewModel(container.runtimeAndAppsRepository)
        }

        val activeRouteIdentities = navigator.backStack.mapTo(HashSet()) { navigator.routeIdentity(it) }
        SyncNavBackStackStores(navStoreRegistry, activeRouteIdentities)

        val currentRoute = navigator.current()
        LaunchedEffect(currentRoute) {
            shellVm.onRouteChanged(currentRoute == Route.Main)
        }

        val destinations = remember(themeState.showTombStatsTab, isActivated) {
            BottomBarDestination.visible(themeState.showTombStatsTab, isActivated)
        }
        val pagerState = rememberPagerState(initialPage = 0, pageCount = { destinations.size })
        val mainPagerState =
            rememberMainPagerState(pagerState = pagerState, animatePageChanges = true)
        val navigateTo: (BottomBarDestination) -> Unit = { dest ->
            val idx = destinations.indexOf(dest)
            if (idx >= 0) mainPagerState.animateToPage(idx)
        }

        val settledPage = mainPagerState.pagerState.settledPage
        val currentPage = mainPagerState.pagerState.currentPage
        val currentDestinations by rememberUpdatedState(destinations)
        LaunchedEffect(destinations) {
            val target = BottomBarDestination.resolvePageIndex(
                destinations = destinations,
                activeDestination = shellVm.activeDestination.value,
            )
            if (pagerState.currentPage != target) {
                pagerState.scrollToPage(target)
            }
            mainPagerState.cancelAndSyncPage()
            destinations.getOrNull(target)?.let(shellVm::onDestinationSettled)
        }
        LaunchedEffect(settledPage) {
            currentDestinations.getOrNull(settledPage)?.let(shellVm::onDestinationSettled)
        }
        LaunchedEffect(currentPage) {
            mainPagerState.syncPage()
        }

        // 主界面 Tab 返回首页拦截（经共享 NavigationEventDispatcher 仲裁，让位于已打开的 Miuix 浮层）。
        // 返回栈有子页时也保持启用：NavDisplay 的处理器在 SideEffect 里才切到启用，压栈那一帧
        // 会出现「两边都未启用」的空窗，此时系统按无处理器处理，直接结束 Activity。
        val mainBackEventState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = mainBackEventState,
            isBackEnabled = navigator.backStack.size > 1 || mainPagerState.selectedPage != 0,
            onBackCompleted = {
                if (navigator.backStack.size > 1) {
                    navigator.pop()
                } else {
                    mainPagerState.animateToPage(0)
                }
            },
        )

        val isProvisioned by shellVm.isProvisioned.collectAsStateWithLifecycle()
        val serviceAlive by shellVm.serviceAliveFlow.collectAsStateWithLifecycle()
        val updateNoticeDialog by PowerKeeperBinderClient.appUpdateNoticeFlow.collectAsStateWithLifecycle()
        val updateUiState by AppUpdateController.uiState.collectAsStateWithLifecycle()
        val lifecycleOwner = LocalLifecycleOwner.current
        val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
        val isForegroundStarted = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

        LaunchedEffect(updateUiState.pendingAutoInstall) {
            if (updateUiState.pendingAutoInstall) {
                AppUpdateController.consumeAutoInstall(this@MainActivity)
            }
        }

        // 仅在完成开箱向导且处于前台（STARTED）时刷新应用列表、检查更新与运行态采样；
        // 退到后台或熄屏（ON_STOP）自动取消采样循环，严格遵守零后台轮询约束。
        LaunchedEffect(lifecycleOwner, isProvisioned) {
            if (!isProvisioned) return@LaunchedEffect
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                shellVm.onForegroundStart()
                AppUpdateController.checkOnAppLaunch(
                    prefs = container.uiPrefs,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    currentVersionName = BuildConfig.VERSION_NAME,
                )
                shellVm.runForegroundPolling()
            }
        }

        val openProfileByTarget: (String, Int) -> Unit = { pkg, targetUid ->
            val resolvedUid = shellVm.resolveTargetUid(pkg, targetUid) { p ->
                runCatching { packageManager.getApplicationInfo(p, 0).uid }.getOrNull() ?: 0
            }
            navigator.push(Route.AppProfile(pkg, resolvedUid))
        }

        val enableBlur = LocalEnableBlur.current
        val floatingBottomBarStyle = LocalFloatingBottomBarStyle.current
        val surfaceColor = MiuixTheme.colorScheme.surface

        val bottomNavPadding =
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() +
                if (floatingBottomBarStyle != 0) 96.dp else 72.dp

        val blurBackdrop = rememberBlurBackdrop(enableBlur)
        val backdrop = rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
        val popupBackdrop = rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
        SideEffect { PopupBackdropHolder.current = if (enableBlur) popupBackdrop else null }

        // 根级 Miuix Scaffold 承载最外层 MiuixPopupHost，确保所有 Tab 的 Dialog / BottomSheet / ListPopup
        // 及其暗色遮罩统一渲染在 BottomBarMiuix 之上，且共享 insets 不重复叠加
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            if (updateNoticeDialog) {
                MiuixConfirmDialog(
                    show = true,
                    title = "模块已更新",
                    message = "检测到 XMillet 模块已更新，请重启系统框架以加载最新 Hook 逻辑。",
                    confirmText = "知道了",
                    dismissText = null,
                    onConfirm = { PowerKeeperBinderClient.consumeAppUpdateNotice() },
                    onDismiss = { PowerKeeperBinderClient.consumeAppUpdateNotice() }
                )
            }

            val availableRelease = updateUiState.availableRelease
            if (!updateNoticeDialog && updateUiState.dialogVisible && availableRelease != null) {
                AppUpdateDialogMiuix(
                    show = true,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    release = availableRelease,
                    downloadState = updateUiState.downloadState,
                    onDismiss = { AppUpdateController.dismissDialog() },
                    onIgnoreVersion = { AppUpdateController.ignoreCurrentRelease(container.uiPrefs) },
                    onOpenReleasePage = {
                        AppUpdateController.openReleasePage(this@MainActivity, availableRelease.htmlUrl)
                    },
                    onPrimaryAction = {
                        when (updateUiState.downloadState) {
                            is AppUpdateDownloadState.Ready ->
                                AppUpdateController.installReadyApk(this@MainActivity)
                            else ->
                                AppUpdateController.startDownload(this@MainActivity)
                        }
                    },
                    onCancelDownload = { AppUpdateController.cancelDownload() },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (enableBlur) Modifier.layerBackdrop(popupBackdrop) else Modifier)
            ) {
                AnimatedContent(
                    targetState = isProvisioned,
                    transitionSpec = {
                        val os3SineInOut = CubicBezierEasing(0.37f, 0.0f, 0.63f, 1.0f)
                        (scaleIn(
                            initialScale = 1.3f,
                            animationSpec = spring(dampingRatio = 0.65f, stiffness = 130.5f)
                        ) + fadeIn(
                            animationSpec = tween(
                                durationMillis = 230,
                                delayMillis = 60,
                                easing = os3SineInOut
                            )
                        )).togetherWith(
                            fadeOut(
                                animationSpec = tween(durationMillis = 360, easing = os3SineInOut)
                            )
                        )
                    },
                    label = "ProvisionStateTransition"
                ) { provisioned ->
                    if (!provisioned) {
                        OobeScreenMiuix(
                            isPreview = false,
                            onComplete = {
                                OobeManager.setProvisioned(this@MainActivity, true)
                                shellVm.markProvisioned()
                            }
                        )
                    } else {
                        val swipeDismiss =
                            if (!themeState.enableSwipeBack) {
                                NavSwipeDirection.None
                            } else if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                                NavSwipeDirection.RightToLeft
                            } else {
                                NavSwipeDirection.LeftToRight
                            }

                        NavDisplay(
                            backStack = navigator.backStack,
                            effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                            onBack = { navigator.pop() },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            entry<Route.Main>(swipeDismiss = NavSwipeDirection.None) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    HorizontalPager(
                                        state = mainPagerState.pagerState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(
                                                if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop)
                                                else Modifier
                                            )
                                            .then(
                                                // 统一模糊开关：只有开启模糊时才录制底栏采样源
                                                if (floatingBottomBarStyle != 0 && enableBlur) {
                                                    Modifier.layerBackdrop(backdrop)
                                                } else {
                                                    Modifier
                                                }
                                            ),
                                        beyondViewportPageCount = (destinations.size - 1).coerceAtLeast(0),
                                        overscrollEffect = null,
                                        flingBehavior = flingBehavior(
                                            state = mainPagerState.pagerState,
                                            snapAnimationSpec = PagerNavigationSpringSpec,
                                        ),
                                    ) { page ->
                                        val isCurrentPage = page == settledPage
                                        when (destinations.getOrNull(page)) {
                                            BottomBarDestination.Home -> HomeRoute(
                                                bottomInnerPadding = bottomNavPadding,
                                                onNavigateToTab = navigateTo,
                                                onNavigateToProcessList = { filterIndex ->
                                                    processListVm.applyPresetFilter(filterIndex)
                                                    navigateTo(BottomBarDestination.Processes)
                                                },
                                                onOpenAbout = { navigator.push(Route.About) },
                                                onOpenAppProfile = openProfileByTarget,
                                                onOpenApps = { navigator.push(Route.Apps) },
                                                isActivated = isActivated,
                                            )

                                            BottomBarDestination.Processes -> ProcessListRoute(
                                                isCurrentPage = isCurrentPage,
                                                bottomInnerPadding = bottomNavPadding,
                                                onOpenAppProfile = openProfileByTarget,
                                                onOpenApps = { navigator.push(Route.Apps) },
                                            )

                                            BottomBarDestination.TombStats -> TombStatsScreenMiuix(
                                                isActive = isCurrentPage && isForegroundStarted && currentRoute == Route.Main,
                                                serviceAlive = serviceAlive,
                                                statsEnabled = themeState.showTombStatsTab,
                                                visibleSections = themeState.tombSections,
                                                bottomInnerPadding = bottomNavPadding,
                                                onEnableStats = { themeState.updateShowTombStatsTab(true) },
                                                onOpenAppProfile = openProfileByTarget,
                                            )

                                            BottomBarDestination.Settings -> SettingsRoute(
                                                themeState = themeState,
                                                bottomInnerPadding = bottomNavPadding,
                                                onOpenAppearance = { navigator.push(Route.Appearance) },
                                                onOpenOobe = { navigator.push(Route.Oobe) },
                                                onOpenAbout = { navigator.push(Route.About) },
                                                isActivated = isActivated,
                                            )

                                            null -> Unit
                                        }
                                    }

                                    BottomBarMiuix(
                                        mainState = mainPagerState,
                                        blurBackdrop = blurBackdrop,
                                        backdrop = backdrop,
                                        destinations = destinations,
                                        modifier = Modifier.align(Alignment.BottomCenter)
                                    )
                                }
                            }

                            entry<Route.Apps>(swipeDismiss = swipeDismiss) {
                                AppsRoute(
                                    isCurrentPage = true,
                                    bottomInnerPadding = 0.dp,
                                    onOpenAppProfile = { app ->
                                        navigator.push(Route.AppProfile(app.packageName, app.uid))
                                    },
                                    onOpenTemplates = { navigator.push(Route.PolicyTemplates) },
                                    onOpenCloudWhitelist = { navigator.push(Route.CloudWhitelist) },
                                    onBack = { navigator.pop() },
                                )
                            }

                            entry<Route.Appearance>(swipeDismiss = swipeDismiss) {
                                ThemeSettingsScreenMiuix(
                                    themeState = themeState,
                                    onBack = { navigator.pop() },
                                    isActivated = isActivated,
                                )
                            }

                            entry<Route.AppProfile>(
                                contentKey = { route -> route.packageName to route.uid },
                                swipeDismiss = swipeDismiss,
                            ) { key ->
                                ProvideNavEntryViewModelStore(
                                    registry = navStoreRegistry,
                                    entryKey = navigator.routeIdentity(key),
                                ) {
                                    AppProfileRoute(
                                        packageName = key.packageName,
                                        uid = key.uid,
                                        onBack = { navigator.pop() },
                                    )
                                }
                            }

                            entry<Route.PolicyTemplates>(swipeDismiss = swipeDismiss) {
                                ProvideNavEntryViewModelStore(
                                    registry = navStoreRegistry,
                                    entryKey = Route.PolicyTemplates,
                                ) {
                                    PolicyTemplatesRoute(
                                        onBack = { navigator.pop() },
                                        onOpenTemplate = { templateId ->
                                            navigator.push(Route.PolicyTemplateDetail(templateId))
                                        },
                                    )
                                }
                            }

                            entry<Route.PolicyTemplateDetail>(swipeDismiss = swipeDismiss) { key ->
                                ProvideNavEntryViewModelStore(
                                    registry = navStoreRegistry,
                                    entryKey = key,
                                ) {
                                    PolicyTemplateDetailRoute(
                                        templateId = key.templateId,
                                        onBack = { navigator.pop() },
                                        onOpenAppProfile = { app ->
                                            navigator.push(Route.AppProfile(app.packageName, app.uid))
                                        },
                                    )
                                }
                            }

                            entry<Route.CloudWhitelist>(swipeDismiss = swipeDismiss) {
                                ProvideNavEntryViewModelStore(
                                    registry = navStoreRegistry,
                                    entryKey = Route.CloudWhitelist,
                                ) {
                                    CloudWhitelistRoute(
                                        onBack = { navigator.pop() },
                                        onOpenAppProfile = { app ->
                                            navigator.push(Route.AppProfile(app.packageName, app.uid))
                                        },
                                    )
                                }
                            }

                            entry<Route.Oobe>(swipeDismiss = swipeDismiss) {
                                OobeScreenMiuix(
                                    isPreview = true,
                                    onBack = { navigator.pop() },
                                    onComplete = {
                                        mainPagerState.animateToPage(0)
                                        navigator.pop()
                                    }
                                )
                            }

                            entry<Route.About>(swipeDismiss = swipeDismiss) {
                                AboutScreenMiuix(
                                    onBack = { navigator.pop() },
                                    onOpenKnowledge = { navigator.push(Route.AboutKnowledge) },
                                    onOpenRepo = { navigator.push(Route.AboutRepo) },
                                    onOpenLicenses = { navigator.push(Route.AboutLicenses) }
                                )
                            }

                            entry<Route.AboutKnowledge>(swipeDismiss = swipeDismiss) {
                                AboutKnowledgeScreenMiuix(onBack = { navigator.pop() })
                            }

                            entry<Route.AboutRepo>(swipeDismiss = swipeDismiss) {
                                AboutRepoScreenMiuix(onBack = { navigator.pop() })
                            }

                            entry<Route.AboutLicenses>(swipeDismiss = swipeDismiss) {
                                AboutLicensesScreenMiuix(onBack = { navigator.pop() })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 旧版「桌面图标取色」开关的持久化键，仅用于一次性迁移。 */
private const val KEY_THEMED_ICON_LEGACY = "ui_themed_icon_enabled"
private const val KEY_THEMED_ICON_MIGRATED = "ui_themed_icon_migration_done"
