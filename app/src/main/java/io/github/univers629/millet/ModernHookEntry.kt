package io.github.univers629.millet

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.univers629.millet.core.HookInstallRegistry
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.ModernBridge
import io.github.univers629.millet.core.ModernHookInstaller
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.ipc.MilletIpcContract
import io.github.univers629.millet.ipc.SystemBinderService
import io.github.univers629.millet.powerkeeper.PowerKeeperHooks
import io.github.univers629.millet.powerkeeper.PowerKeeperState
import io.github.univers629.millet.system.BroadcastAndDozeHooks
import io.github.univers629.millet.system.FreezeStatsRecorder
import io.github.univers629.millet.system.SystemHooks
import io.github.univers629.millet.system.SystemState

/**
 * 现代化 Modern LSPosed / LibXposed (API 102) 模块主入口。
 *
 * 规范：
 * 1. 继承 [io.github.libxposed.api.XposedModule]；
 * 2. 由 META-INF/xposed/java_init.list 显式声明；
 * 3. 在 [onSystemServerStarting] 中拦截 system_server（"android"）并注入系统侧 Hook；
 * 4. 在 [onPackageReady] 中拦截 com.miui.powerkeeper 并注入电量与性能侧 Hook；
 * 5. 支持 API 102 热重载：旧一代句柄全部卸载后按原角色重装，免去改一行代码就重启手机。
 */
class ModernHookEntry : XposedModule() {

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val classLoader = param.classLoader
        role = ROLE_SYSTEM_SERVER
        roleClassLoader = classLoader
        bootstrapSystemServer(classLoader, "API 102")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkgName = param.packageName
        if (pkgName == SCOPE_POWERKEEPER) {
            role = ROLE_POWERKEEPER
            roleClassLoader = param.classLoader
            bootstrapPowerKeeper(param.classLoader)
        } else if (pkgName == SCOPE_ANDROID || pkgName == SCOPE_SYSTEM) {
            if (SystemState.greezeService == null) {
                role = ROLE_PACKAGE_SYSTEM
                roleClassLoader = param.classLoader
                bootstrapSystemServer(param.classLoader, "API 102 - PackageReady Fallback")
            }
        } else {
            // 静态作用域下框架只该把模块注入 system / powerkeeper，但 system 这个 uid 里还有
            // 一批同 uid 的进程（com.android.providers.settings 等），它们同样会收到 PackageReady。
            // 只有在「当前进程没有装机角色」时才 detach：否则会把 system_server 这一代的
            // 入口一起摘掉，LSPosed 之后就无法再对 system_server 做热重载
            // （框架报错：Auto hot reload failed ... The old module has already detached its entry）。
            if (role == null) {
                Logger.alwaysWarn("模块被注入非目标进程 $pkgName，已 detach")
                runCatching { detach() }
            }
        }
    }

    /**
     * 接受热重载。
     *
     * 只在能确认上一代的装机角色与类加载器时才放行：新一代代码拿不到目标类所在的
     * 类加载器，就无法重装 Hook，此时放弃热重载比留下一个「看似装好、实际为空」
     * 的进程更安全。
     *
     * 传给新一代的只有角色名与类加载器两个框架对象，不携带任何模块类加载器创建的
     * 实例，符合 API 102 对热重载状态的要求。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        val savedRole = role
        val savedLoader = roleClassLoader
        if (savedRole == null || savedLoader == null) {
            Logger.alwaysWarn("XMillet 热重载：缺少上一代装机上下文，放弃本次热重载，请重启设备")
            return false
        }
        // powerkeeper / App 的注册信息与 PowerKeeper 宿主实例句柄一起带过去（均为宿主或框架对象，不携带模块类实例）：
        // system_server 侧重装后通知客户端重绑新 Binder；powerkeeper 侧保留 MilletPolicy 等启动期构造器句柄，避免热重载后失联。
        val carriedSnapshot = when (savedRole) {
            ROLE_SYSTEM_SERVER, ROLE_PACKAGE_SYSTEM ->
                runCatching { SystemBinderService.exportRegistrationSnapshot() }.getOrNull()
            ROLE_POWERKEEPER -> arrayOf(
                PowerKeeperState.milletPolicyInstance,
                PowerKeeperState.powerKeeperManagerInstance,
                PowerKeeperState.brightScenarioInstance
            )
            else -> null
        }
        param.setSavedInstanceState(arrayOf<Any?>(savedRole, savedLoader, carriedSnapshot))
        Logger.alwaysWarn("XMillet 热重载：接受重装请求（$savedRole）")

        when (savedRole) {
            ROLE_SYSTEM_SERVER, ROLE_PACKAGE_SYSTEM -> SystemHooks.onHotReloading()
            ROLE_POWERKEEPER -> runCatching { PowerKeeperHooks.onHotReloading() }
        }
        Logger.onHotReloading()
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        val saved = param.savedInstanceState as? Array<*>
        val savedRole = saved?.getOrNull(0) as? String
        val savedLoader = saved?.getOrNull(1) as? ClassLoader
        val carriedSnapshot = saved?.getOrNull(2) as? Array<*>

        // 新一代代码有全新的静态字段，这里必须先挂上模块实例，
        // 否则它之前的日志会落在「模块实例为 null」的空档里丢掉。
        ModernBridge.xposedModule = this
        HookInstallRegistry.clear()

        if (savedRole == null || savedLoader == null) {
            var removed = 0
            for (handle in param.oldHookHandles) {
                if (runCatching { handle.unhook() }.isSuccess) removed++
            }
            Logger.alwaysWarn("XMillet 热重载：已卸载 $removed 个旧 Hook，但没有可复用的类加载器，需重启设备恢复")
            return
        }

        role = savedRole
        roleClassLoader = savedLoader
        ModernHookInstaller.beginHotReloadSession()
        try {
            when (savedRole) {
                ROLE_SYSTEM_SERVER, ROLE_PACKAGE_SYSTEM -> {
                    // 先把框架服务句柄拿回来，否则新一代的闸门会因为 isGreezeReady=false 全部放行
                    val servicesReady = SystemState.reacquireFrameworkServices()
                    Logger.alwaysWarn(
                        "XMillet 热重载：greeze=${SystemState.greezeService != null}, " +
                            "aurogon=${SystemState.aurogonService != null}, " +
                            "doze=${SystemState.deviceIdleController != null}"
                    )
                    if (!servicesReady) {
                        Logger.alwaysWarn("XMillet 热重载：未能重新获取 Greezer 服务，Hook 会装上但闸门保持放行，请重启设备")
                    }
                    bootstrapSystemServer(savedLoader, "热重载")
                    runCatching { SystemBinderService.restoreRegistrationSnapshot(carriedSnapshot) }
                }
                ROLE_POWERKEEPER -> {
                    PowerKeeperState.reacquireApplicationContext()
                    PowerKeeperState.restoreHostHandles(carriedSnapshot)
                    bootstrapPowerKeeper(savedLoader)
                }
                else -> Logger.alwaysWarn("XMillet 热重载：未知装机角色 $savedRole，跳过重装")
            }
        } catch (t: Throwable) {
            Logger.error("XMillet 热重载重装失败（$savedRole）: ${t.message}", t)
        } finally {
            // 新一代按 id 原子替换同点位后，再摘掉不再安装的过时旧句柄，消除中间空窗期
            val staleRemoved = ModernHookInstaller.finishHotReloadSession(param.oldHookHandles)
            Logger.alwaysWarn(
                "XMillet 热重载：按 $savedRole 无空窗重装完成（旧句柄 ${param.oldHookHandles.size} 个，清理过时句柄 $staleRemoved 个）"
            )
        }
    }

    /** system_server 侧装配：Binder 通道 → 配置快照 → 日志轮转 → 全部 Hook。 */
    private fun bootstrapSystemServer(classLoader: ClassLoader, scene: String) {
        ModernBridge.xposedModule = this
        try {
            // 1. 注册 system_server 侧跨进程 Binder 服务端实例
            SystemBinderService.registerToChannel(MilletIpcContract.BINDER_KEY_SYSTEM)

            // 2. 初始化并加载 system_server 侧配置快照，轮转本地日志文件并恢复墓碑统计
            PrefReader.initSystemPrefs(this)
            Logger.initSystemLogRotation()
            FreezeStatsRecorder.init()

            // 3. 输出装配启动标志日志
            Logger.alwaysWarn("开始XMillet Modern Hook [$scene]")
            logPrefsSelfCheck("system_server")

            // 4. 安装 system_server 侧全部安装点
            SystemHooks.installAllModern(this, classLoader)

            // 5. 热重载会摘掉广播接收器，重装后立刻补回来，避免亮灭屏与充电事件断链
            SystemState.systemContext?.let { ctx ->
                runCatching { BroadcastAndDozeHooks.registerScreenStatusReceiver(ctx) }
                runCatching { FreezeStatsRecorder.registerScreenReceiver(ctx) }
            }
        } catch (t: Throwable) {
            Logger.error("Modern Hook bootstrapSystemServer Failure [$scene]: ${t.message}", t)
        }
    }

    /** com.miui.powerkeeper 侧装配：类加载器与配置快照 → 全部 Hook。 */
    private fun bootstrapPowerKeeper(classLoader: ClassLoader) {
        ModernBridge.xposedModule = this
        try {
            // 1. 记录 powerkeeper 类加载器并加载配置快照
            PowerKeeperState.appClassLoader = classLoader
            PrefReader.initPowerKeeperPrefs(this)
            logPrefsSelfCheck("powerkeeper")

            // 2. 安装 com.miui.powerkeeper 侧全部安装点
            PowerKeeperHooks.installAllModern(this, classLoader)

            // 3. 补回 system_server 链路（P1 只在应用启动时触发一次，热重载后必须重连）
            PowerKeeperHooks.ensureBinderLink()

            // 3.1 插电状态纠正：热重载时若标志已置为「暂停新冻结」，则纠正回可冻结状态
            runCatching { PowerKeeperHooks.healChargingFreezeGate() }
        } catch (t: Throwable) {
            Logger.error("Modern Hook bootstrapPowerKeeper Failure: ${t.message}", t)
        }
    }

    /**
     * 配置读取自检：Hook 进程到底有没有读到 App 写入的 `Settings.xml`。
     *
     * Hook 进程读不到 App 写入的配置时，开关会退回默认值而不生效。
     * 每次装配打一行真实读数，让这类问题在日志里一眼可辨。
     */
    private fun logPrefsSelfCheck(scene: String) {
        try {
            val prefs = PrefReader.activePrefs()
            Logger.alwaysWarn(
                "配置自检[$scene]: isLog=${prefs.isLog}, simplifyLog=${prefs.simplifyLog}, " +
                    "disableKill=${prefs.disableKill}, disableCharge=${prefs.disableCharge}, " +
                    "blockCloudDoze=${prefs.blockCloudDozeWhitelist}, " +
                    "blockCloudFrozen=${prefs.blockCloudFrozenWhitelist}, " +
                    "freezeStats=${prefs.freezeStats}, " +
                    "enableDoze=${prefs.enableDoze}, " +
                    "messageAppSet=${prefs.messageAppSet.size}, " +
                    "whiteAppSet=${prefs.whiteAppSet.size}, ignoreNetworkWake=${prefs.ignoreNetworkWakeSet.size}"
            )
        } catch (t: Throwable) {
            Logger.error("配置自检失败[$scene]: ${t.message}", t)
        }
    }

    companion object {
        const val SCOPE_ANDROID = "android"
        const val SCOPE_SYSTEM = "system"
        const val SCOPE_POWERKEEPER = "com.miui.powerkeeper"

        private const val ROLE_SYSTEM_SERVER = "system_server"
        private const val ROLE_PACKAGE_SYSTEM = "package_system"
        private const val ROLE_POWERKEEPER = "powerkeeper"

        /**
         * 已装机角色与目标类加载器。
         *
         * 由旧一代代码在 [onHotReloading] 中读取并转交给新一代；新一代在
         * [onHotReloaded] 重新赋值，保证连续多次热重载也能拿到上下文。
         */
        @Volatile
        private var role: String? = null

        @Volatile
        private var roleClassLoader: ClassLoader? = null
    }
}
