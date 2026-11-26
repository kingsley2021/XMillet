package io.github.univers629.millet.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.univers629.millet.ui.component.BottomBarDestination
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.theme.UiThemeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主窗口壳层 ViewModel（宿主 Activity 级作用域）：
 * - 跨配置变更 / 澎湃小窗切换保留 [themeState] 与引导完成状态；
 * - 监听 [PolicyRepository.prefsFlow] 自动同步底栏「墓碑统计」Tab 可见性；
 * - 统一调度前景按需刷新与自适应采样间隔（进程页 5 秒，其余页面 15 秒）。
 */
class MainShellViewModel(
    val themeState: UiThemeState,
    initialProvisioned: Boolean,
    policyRepo: PolicyRepository,
    private val runtimeRepo: RuntimeAndAppsRepository,
) : ViewModel() {

    private val _isProvisioned = MutableStateFlow(initialProvisioned)
    val isProvisioned: StateFlow<Boolean> = _isProvisioned.asStateFlow()

    private val _activeDestination = MutableStateFlow(BottomBarDestination.Home)
    val activeDestination: StateFlow<BottomBarDestination> = _activeDestination.asStateFlow()

    private val _isOnMainRoute = MutableStateFlow(true)
    val isOnMainRoute: StateFlow<Boolean> = _isOnMainRoute.asStateFlow()

    val serviceAliveFlow: StateFlow<Boolean> = runtimeRepo.runtimeStatusFlow
        .map { it.binderAlive }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = runtimeRepo.runtimeStatusFlow.value.binderAlive,
        )

    /**
     * 系统服务是否已激活：Binder 已连上，且 system_server 侧冻结闸门已就绪（或 PowerKeeper 客户端已注册）。
     * 判据与主页 [io.github.univers629.millet.ui.screen.HomeScreenMiuix] 状态卡的 `isWorking` 完全一致，
     * 未激活时底栏、主页统计卡、设置页策略组、外观页墓碑统计开关一并收起来。
     */
    val activatedFlow: StateFlow<Boolean> = runtimeRepo.runtimeStatusFlow
        .map { it.binderAlive && (it.greezeReady || it.powerKeeperConnected) }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            // 首帧直接按当前快照算，避免先画一帧「未激活」再跳回「已激活」的闪烁。
            initialValue = runtimeRepo.runtimeStatusFlow.value.let {
                it.binderAlive && (it.greezeReady || it.powerKeeperConnected)
            },
        )

    init {
        viewModelScope.launch {
            policyRepo.prefsFlow.collect { snapshot ->
                themeState.syncFromSnapshot(snapshot)
            }
        }
    }

    fun markProvisioned() {
        _isProvisioned.value = true
    }

    fun onDestinationSettled(destination: BottomBarDestination) {
        _activeDestination.value = destination
    }

    fun onRouteChanged(onMain: Boolean) {
        _isOnMainRoute.value = onMain
    }

    fun currentPollIntervalMs(): Long =
        if (isOnMainRoute.value && activeDestination.value == BottomBarDestination.Processes) {
            5_000L
        } else {
            15_000L
        }

    fun onForegroundStart() {
        viewModelScope.launch {
            runtimeRepo.refreshRuntimeAndProcesses(visible = false)
            runtimeRepo.refreshInstalledPackages(force = false, visible = false)
        }
    }

    suspend fun runForegroundPolling() {
        runtimeRepo.runForegroundPollingLoop(::currentPollIntervalMs)
    }

    fun resolveTargetUid(
        packageName: String,
        hintUid: Int,
        fallbackUidLookup: (String) -> Int,
    ): Int {
        val target = runtimeRepo.installedAppsFlow.value.find {
            it.packageName == packageName && (hintUid <= 0 || it.uid == hintUid)
        }
        return target?.uid
            ?: if (hintUid > 0) hintUid else fallbackUidLookup(packageName)
    }
}
