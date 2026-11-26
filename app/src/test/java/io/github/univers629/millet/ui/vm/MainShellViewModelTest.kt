package io.github.univers629.millet.ui.vm

import android.content.SharedPreferences
import io.github.univers629.millet.core.CloudListCodec
import io.github.univers629.millet.core.PolicyFlag
import io.github.univers629.millet.core.PolicyTemplate
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.MainDispatcherRule
import io.github.univers629.millet.ui.component.BottomBarDestination
import io.github.univers629.millet.ui.data.InMemorySharedPreferences
import io.github.univers629.millet.ui.data.InstalledPackageSource
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.PolicyWriter
import io.github.univers629.millet.ui.data.RawPackageMeta
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.data.RuntimeEnvironmentSource
import io.github.univers629.millet.ui.screen.RunningProcessUiModel
import io.github.univers629.millet.ui.theme.UiThemeState
import io.github.univers629.millet.ui.util.shouldApplyUniformBarBlur
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainShellViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private class DirectWriter(private val prefs: SharedPreferences) : PolicyWriter {
        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
        override fun putDetailedLogging(enabled: Boolean) = Unit
        override fun putStringSets(updates: Map<String, Set<String>>, notifyKey: String) = Unit
        override fun saveCustomTemplate(id: String?, name: String, flags: Set<PolicyFlag>): PolicyTemplate? = null
        override fun deleteCustomTemplate(id: String) = Unit
    }

    private class FakePackageSource : InstalledPackageSource {
        override suspend fun loadPrimaryPackages(): List<RawPackageMeta> = emptyList()
        override suspend fun loadDualPackages(primaryByPackage: Map<String, RawPackageMeta>) =
            emptyList<RawPackageMeta>() to false
    }

    private class FakeRuntimeSource : RuntimeEnvironmentSource {
        override suspend fun queryRuntimeStatus() = PowerKeeperBinderClient.RuntimeStatusSnapshot()
        override suspend fun queryRunningProcesses(): List<RunningProcessUiModel> = emptyList()
        override suspend fun loadFallbackCloudLists(): CloudListCodec.CloudLists? = null
        override suspend fun readBatteryUnrestrictedPackages(): Set<String> = emptySet()
        override suspend fun thawAllFrozenUids(): Int = 0
    }

    @Test
    fun adaptivePollIntervalProvisioningAndThemeSyncWork() = runTest(mainRule.dispatcher) {
        val prefs = InMemorySharedPreferences()
        val policyRepo = PolicyRepository(prefs, DirectWriter(prefs))
        val repoScope = CoroutineScope(SupervisorJob() + mainRule.dispatcher)
        val runtimeRepo = RuntimeAndAppsRepository(
            policyRepository = policyRepo,
            packageSource = FakePackageSource(),
            environmentSource = FakeRuntimeSource(),
            scope = repoScope,
            computeDispatcher = mainRule.dispatcher,
        )
        val themeState = UiThemeState(prefs)
        val vm = MainShellViewModel(
            themeState = themeState,
            initialProvisioned = false,
            policyRepo = policyRepo,
            runtimeRepo = runtimeRepo,
        )
        testScheduler.advanceUntilIdle()

        assertFalse(vm.isProvisioned.value)
        vm.markProvisioned()
        assertTrue(vm.isProvisioned.value)

        // 主页默认 15 秒；切到进程列表页变为 5 秒；进入子页后回到 15 秒
        assertEquals(15_000L, vm.currentPollIntervalMs())
        vm.onDestinationSettled(BottomBarDestination.Processes)
        assertEquals(5_000L, vm.currentPollIntervalMs())
        vm.onRouteChanged(onMain = false)
        assertEquals(15_000L, vm.currentPollIntervalMs())

        // 仓库更新 freezeStats 后，底栏 TombStats 可见性自动同步
        assertTrue(vm.themeState.showTombStatsTab)
        policyRepo.setBoolean(PrefsKeys.FREEZE_STATS, false)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.themeState.showTombStatsTab)

        repoScope.cancel()
    }

    @Test
    fun bottomBarDestinationResolutionAndUniformBarBlurFollowMiuixSpec() {
        val fiveTabs = BottomBarDestination.visible(showTombStats = true)
        val fourTabs = BottomBarDestination.visible(showTombStats = false)

        // 底栏已改为 4 个目的地（应用策略改为主页入口）：关闭「墓碑统计」后设置页下标从 3 重映射为 2
        assertEquals(2, BottomBarDestination.resolvePageIndex(fourTabs, BottomBarDestination.Settings))
        assertEquals(3, BottomBarDestination.resolvePageIndex(fiveTabs, BottomBarDestination.Settings))
        assertEquals(1, BottomBarDestination.resolvePageIndex(fourTabs, BottomBarDestination.Processes))
        // 墓碑统计隐藏时回退到首页
        assertEquals(0, BottomBarDestination.resolvePageIndex(fourTabs, BottomBarDestination.TombStats))

        // 未激活（系统服务没连上 / 冻结闸门未就绪）时底栏只剩「主页」「设置」两个 Tab
        val inactiveTabs = BottomBarDestination.visible(showTombStats = true, isActivated = false)
        assertEquals(
            listOf(BottomBarDestination.Home, BottomBarDestination.Settings),
            inactiveTabs,
        )
        assertEquals(1, BottomBarDestination.resolvePageIndex(inactiveTabs, BottomBarDestination.Settings))
        assertEquals(0, BottomBarDestination.resolvePageIndex(inactiveTabs, BottomBarDestination.Processes))
        assertEquals(0, BottomBarDestination.resolvePageIndex(inactiveTabs, BottomBarDestination.TombStats))

        // 顶栏支持渐进式模糊：渐进模式下由 GlassTopAppBar 自行绘制，BlurredBar 不叠整块模糊
        assertFalse(shouldApplyUniformBarBlur(blurActive = false, progressiveSupported = true, enableProgressiveBlur = false, hasBackdrop = true))
        assertTrue(shouldApplyUniformBarBlur(blurActive = true, progressiveSupported = true, enableProgressiveBlur = false, hasBackdrop = true))
        assertFalse(shouldApplyUniformBarBlur(blurActive = true, progressiveSupported = true, enableProgressiveBlur = true, hasBackdrop = true))

        // 经典非悬浮底栏不支持渐进式模糊：渐进模式下仍保持整块均匀毛玻璃（关闭模糊才停用）
        assertFalse(shouldApplyUniformBarBlur(blurActive = false, progressiveSupported = false, enableProgressiveBlur = true, hasBackdrop = true))
        assertTrue(shouldApplyUniformBarBlur(blurActive = true, progressiveSupported = false, enableProgressiveBlur = false, hasBackdrop = true))
        assertTrue(shouldApplyUniformBarBlur(blurActive = true, progressiveSupported = false, enableProgressiveBlur = true, hasBackdrop = true))
        assertFalse(shouldApplyUniformBarBlur(blurActive = true, progressiveSupported = false, enableProgressiveBlur = true, hasBackdrop = false))
    }
}
