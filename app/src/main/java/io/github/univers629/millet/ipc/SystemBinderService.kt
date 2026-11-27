package io.github.univers629.millet.ipc

import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import io.github.univers629.millet.core.DozeDiagnostics
import io.github.univers629.millet.core.FreezeStatsCodec
import io.github.univers629.millet.core.HookDecisions
import io.github.univers629.millet.core.HookInstallRegistry
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.ModernBridge
import io.github.univers629.millet.core.NoRestrictSync
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.core.RomCapabilities
import io.github.univers629.millet.core.StatsWindow
import io.github.univers629.millet.system.BatteryPolicyApplier
import io.github.univers629.millet.system.BroadcastAndDozeHooks
import io.github.univers629.millet.system.FreezeStatsRecorder
import io.github.univers629.millet.system.KernelFreezerDetector
import io.github.univers629.millet.system.ProcessSnapshotProvider
import io.github.univers629.millet.system.SystemState
import java.util.concurrent.ConcurrentHashMap

/**
 * system_server 侧跨进程 Binder 服务端实现。
 *
 * 职责：
 * 1. 通过 S1（ActivityManagerService#registerReceiverWithFeature）向 powerkeeper 与模块 App 派发本 Binder 句柄。
 * 2. 维护已注册客户端（`"PowerKeeper"` 与 `"XMillet"`）的回调 Binder 及 DeathRecipient。
 * 3. 处理配置热更新广播、MILLET_NO_RESTRICT_APP 镜像查询、实时运行状态与进程快照等事务。
 */
class SystemBinderService : Binder() {

    init {
        attachInterface(null, MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
        activeService = this
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
            return true
        }

        return try {
            when (code) {
                MilletIpcContract.TX_REGISTER_CLIENT -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    val callbackBinder = data.readStrongBinder()
                    val clientName = data.readString()
                    // 尾部可选字段：本进程 Hook 账本、能力位与云名单。旧版客户端不会写这几段。
                    val hooksReport = if (data.dataAvail() >= 4) data.readString() else null
                    val capsReport = if (data.dataAvail() >= 4) data.readString() else null
                    val cloudLists = if (data.dataAvail() >= 4) data.readString() else null
                    registerClient(callbackBinder, clientName, hooksReport, capsReport, cloudLists)
                    reply?.writeNoException()
                    true
                }

                MilletIpcContract.TX_UNREGISTER_CLIENT -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    val clientName = data.readString()
                    unregisterClient(clientName)
                    reply?.writeNoException()
                    true
                }

                MilletIpcContract.TX_PING_ALIVE -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    reply?.writeNoException()
                    reply?.writeInt(1)
                    true
                }

                MilletIpcContract.TX_NOTIFY_PREF_CHANGED -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    val changedKey = data.readString()
                    if (!changedKey.isNullOrEmpty()) {
                        handlePrefChanged(changedKey)
                    }
                    reply?.writeNoException()
                    true
                }

                MilletIpcContract.TX_SET_NO_RESTRICT_APPS -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    val incoming = data.readString() ?: ""
                    noRestrictAppsSnapshot = incoming
                    resyncNoRestrictIfDropped(incoming)
                    reply?.writeNoException()
                    true
                }

                MilletIpcContract.TX_IS_NO_RESTRICT_APP -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    val pkg = data.readString()
                    val matched = !pkg.isNullOrEmpty() && isPackageInNoRestrictSnapshot(pkg)
                    reply?.writeNoException()
                    reply?.writeInt(if (matched) 1 else 0)
                    true
                }

                MilletIpcContract.TX_CHECK_APP_UPDATE -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    checkModuleUpdateTime()
                    reply?.writeNoException()
                    true
                }

                MilletIpcContract.TX_TRIGGER_INIT_CHECK -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    postReloadTask {
                        BroadcastAndDozeHooks.applyStartupSync()
                    }
                    reply?.writeNoException()
                    true
                }

                MilletIpcContract.TX_IS_GREEZE_READY -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    reply?.writeNoException()
                    reply?.writeInt(if (SystemState.isGreezeReady) 1 else 0)
                    true
                }

                MilletIpcContract.TX_GET_RUNTIME_STATUS -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    val greezeOk = SystemState.isGreezeReady && SystemState.greezeService != null
                    val aurogonOk = SystemState.aurogonService != null
                    val dozeOk = SystemState.deviceIdleController != null
                    val pkOk = clientCallbacks[MilletIpcContract.CLIENT_POWER_KEEPER]?.binder?.isBinderAlive == true
                    val frozenUids = SystemState.getFrozenUidsArray()
                    val frameworkApiVersion = ModernBridge.frameworkApiVersion
                    reply?.writeNoException()
                    reply?.writeInt(if (greezeOk) 1 else 0)
                    reply?.writeInt(if (aurogonOk) 1 else 0)
                    reply?.writeInt(if (dozeOk) 1 else 0)
                    reply?.writeInt(if (pkOk) 1 else 0)
                    reply?.writeIntArray(frozenUids)
                    reply?.writeInt(frameworkApiVersion)
                    // 尾部追加：跨进程汇总的 Hook 覆盖（旧版 App 读到 frameworkApiVersion 即止）
                    reply?.writeString(buildHookCoverage().encode())
                    // 再追加：powerkeeper 用官方接口读到的云名单；未上报时写空串
                    reply?.writeString(powerKeeperCloudLists)
                    // 再追加：最近一次主动进入深睡的结果；旧版 App 读到云名单即止
                    reply?.writeString(DozeDiagnostics.encode())
                    // 再追加：Doze 状态机的实时快照（状态 + 生效中的阻塞约束）
                    reply?.writeString(BroadcastAndDozeHooks.describeLiveDoze())
                    true
                }

                MilletIpcContract.TX_THAW_ALL_FROZEN -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    val thawedCount = SystemState.thawAllFrozenUids()
                    reply?.writeNoException()
                    reply?.writeInt(thawedCount)
                    true
                }

                MilletIpcContract.TX_APPLY_BATTERY_POLICY -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    val pkg = data.readString()
                    val noRestrict = data.readInt() == 1
                    val applied = if (pkg.isNullOrEmpty()) {
                        false
                    } else {
                        BatteryPolicyApplier.apply(pkg, noRestrict)
                    }
                    reply?.writeNoException()
                    reply?.writeInt(if (applied) 1 else 0)
                    true
                }
                MilletIpcContract.TX_GET_PROCESS_SNAPSHOT -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    // 由 system_server 直接读 /proc 与运行中服务：App 侧不再需要 root shell。
                    reply?.writeNoException()
                    reply?.writeString(ProcessSnapshotProvider.snapshot())
                    true
                }
                MilletIpcContract.TX_GET_KERNEL_FREEZER -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    // /sys/fs/cgroup 与 /proc/<pid>/wchan 在 App 侧被 SELinux 挡住，改由这里判定。
                    val report = KernelFreezerDetector.detectEvidence()
                    reply?.writeNoException()
                    reply?.writeString(report.mode)
                    reply?.writeString(report.evidence)
                    reply?.writeString(report.backend)
                    true
                }

                MilletIpcContract.TX_GET_FREEZE_STATS -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    val windowName = data.readString()
                    val window = StatsWindow.entries.firstOrNull { it.name == windowName } ?: StatsWindow.TODAY
                    val snapshot = FreezeStatsRecorder.buildSnapshot(window)
                    reply?.writeNoException()
                    reply?.writeString(FreezeStatsCodec.encode(snapshot))
                    true
                }

                MilletIpcContract.TX_RESET_FREEZE_STATS -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    val resetOk = FreezeStatsRecorder.reset()
                    reply?.writeNoException()
                    reply?.writeInt(if (resetOk) 1 else 0)
                    true
                }

                MilletIpcContract.TX_GET_DUAL_APPS -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    reply?.writeNoException()
                    reply?.writeString(SystemState.queryDualAppsSnapshot())
                    true
                }

                MilletIpcContract.TX_QUERY_UID_PACKAGES -> {
                    data.enforceInterface(MilletIpcContract.DESCRIPTOR_SYSTEM_SERVICE)
                    if (reply == null || data.dataAvail() < 4) return false
                    val count = data.readInt()
                    if (count !in 0..MilletIpcContract.MAX_UID_PACKAGES_QUERY || data.dataAvail() < count * 4) {
                        return false
                    }
                    val uids = LinkedHashSet<Int>(count)
                    repeat(count) { uids.add(data.readInt()) }
                    val packages = queryUidPackages(uids) ?: return false
                    reply.writeNoException()
                    reply.writeInt(packages.size)
                    for ((uid, members) in packages) {
                        reply.writeInt(uid)
                        reply.writeStringList(members)
                    }
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }
        } catch (t: Throwable) {
            Logger.error("SystemBinderService onTransact(code=$code) failed", t)
            false
        }
    }

    private fun queryUidPackages(uids: Collection<Int>): Map<Int, List<String>>? {
        val token = Binder.clearCallingIdentity()
        return try {
            val manager = SystemState.systemContext?.packageManager ?: return null
            val packages = LinkedHashMap<Int, List<String>>(uids.size)
            for (uid in uids) {
                try {
                    packages[uid] = manager.getPackagesForUid(uid)?.distinct().orEmpty()
                } catch (_: Throwable) {
                    // 缺少该 UID 的映射表示查询失败，不等同于成功的空成员列表。
                }
            }
            packages
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun registerClient(
        callbackBinder: IBinder?,
        clientName: String?,
        hooksReport: String? = null,
        capsReport: String? = null,
        cloudLists: String? = null
    ) {
        if (callbackBinder == null || clientName.isNullOrEmpty()) return

        lateinit var entry: ClientRegistration
        val deathRecipient = IBinder.DeathRecipient {
            // 仅当当前槽位仍指向这一条连接时才摘除并清空快照，防止旧客户端延迟触发 binderDied 误杀新连接
            invalidateClientEntry(clientName, entry)
        }
        entry = ClientRegistration(callbackBinder, deathRecipient)

        val previous = clientCallbacks.put(clientName, entry)
        if (previous != null) {
            runCatching { previous.binder.unlinkToDeath(previous.deathRecipient, 0) }
        }

        if (clientName == MilletIpcContract.CLIENT_POWER_KEEPER) {
            // powerkeeper 进程看得见 com.miui.powerkeeper.*（system_server 看不见），
            // 它的账本、能力位与官方云名单接口要留到查询时一起汇总给 App。
            //
            // 热重载时 HookInstallRegistry 先 clear 再逐条写回，此刻读出来的账本只是
            // 「已记到第几条」；而 restoreRegistrationSnapshot 又会把上一代账本塞回来
            // 并触发一次重绑上报，两条上报的先后顺序不定。先到完整、后到残缺时，
            // 界面会一直显示少几个点位（残缺账本还会随快照代代相传），故按 total 单调采纳。
            val incomingWire = HookInstallRegistry.decodeWireFormat(hooksReport)
            val currentWire = HookInstallRegistry.decodeWireFormat(powerKeeperHooksReport)
            if (HookDecisions.acceptLedgerReport(incomingWire, currentWire)) {
                if (!hooksReport.isNullOrEmpty()) powerKeeperHooksReport = hooksReport
                if (!capsReport.isNullOrEmpty()) powerKeeperCapsReport = capsReport
                if (!cloudLists.isNullOrEmpty()) powerKeeperCloudLists = cloudLists
                Logger.alwaysInfo(
                    "PowerKeeper 上报 Hook 账本: $hooksReport / 能力位: $capsReport / 云名单: ${cloudLists?.length ?: 0} 字符"
                )
            } else {
                Logger.alwaysWarn(
                    "PowerKeeper 上报的 Hook 账本不完整（${incomingWire.installed}/${incomingWire.total} < " +
                        "${currentWire.installed}/${currentWire.total}），保留已缓存的完整账本"
                )
            }
        }
        try {
            callbackBinder.linkToDeath(deathRecipient, 0)
        } catch (t: Throwable) {
            invalidateClientEntry(clientName, entry)
            Logger.error("注册 $clientName 的 DeathRecipient 失败", t)
            return
        }
        if (clientName == MilletIpcContract.CLIENT_POWER_KEEPER && SystemState.isGreezeReady) {
            postReloadTask {
                BroadcastAndDozeHooks.applyStartupSync()
            }
        }
    }

    /**
     * 汇总两侧进程的 Hook 覆盖情况。
     *
     * system_server 自己只装了 S*（[HookInstallRegistry] 在本进程），P* 的数量来自
     * powerkeeper 注册时上报的账本；能力位同理两侧各报一半，按「任一为真」合并。
     */
    private fun buildHookCoverage(): HookCoverage {
        val sysWire = HookInstallRegistry.decodeWireFormat(HookInstallRegistry.toWireFormat())
        val pkWire = HookInstallRegistry.decodeWireFormat(powerKeeperHooksReport)
        val sysInstalled = HookInstallRegistry.snapshot()
            .filter { it.state == HookInstallRegistry.State.INSTALLED }
            .map { it.id }.toSet()
        val caps = HookCoverage.mergeCapabilities(
            RomCapabilities.capabilityMap(SystemState.systemContext?.classLoader) + mapOf(
                "greeze_registered" to sysInstalled.containsAll(setOf("S2_Gate", "S6")),
                HookCoverage.CAP_DOZE_OBS to sysInstalled.containsAll(RomCapabilities.DOZE_OBSERVATION_HOOKS),
            ),
            HookCoverage.parseCapabilities(powerKeeperCapsReport)
        )
        return HookCoverage(sys = sysWire, pk = pkWire, capabilities = caps)
    }

    private fun unregisterClient(clientName: String?) {
        if (clientName.isNullOrEmpty()) return
        val removed = clientCallbacks.remove(clientName) ?: return
        runCatching { removed.binder.unlinkToDeath(removed.deathRecipient, 0) }
        if (clientName == MilletIpcContract.CLIENT_POWER_KEEPER) {
            clearPowerKeeperReports()
        }
    }

    private fun handlePrefChanged(changedKey: String) {
        // 在既有 Handler 上依次通知两端；通知接收不代表两端应用确认。
        postReloadTask(200L) {
            PrefReader.reloadSystemByKey(changedKey)
            sendToClient(MilletIpcContract.CLIENT_POWER_KEEPER, changedKey)
        }
    }

    private fun checkModuleUpdateTime() {
        val ctx = SystemState.systemContext ?: return
        try {
            @Suppress("DEPRECATION")
            val pkgInfo = ctx.packageManager.getPackageInfo(PrefsKeys.MODULE_PACKAGE, 0)
            val currentUpdateTime = pkgInfo.lastUpdateTime
            if (SystemState.moduleLastUpdateTime == -1L || SystemState.moduleLastUpdateTime != currentUpdateTime) {
                sendToClient(MilletIpcContract.CLIENT_XMILLET_APP, MilletIpcContract.MSG_IS_UPDATE)
            }
        } catch (t: Throwable) {
            Logger.error("获取XMillet更新时间异常", t)
        }
    }

    companion object {
        /** 当前进程里创建出来的服务实例，供热重载跨代搬运注册信息用。 */
        @Volatile
        private var activeService: SystemBinderService? = null

        /**
         * 导出已连接的客户端注册信息，供热重载时跨代传递。
         *
         * 热重载会换掉模块的类加载器，连带 `clientCallbacks` 一起清空；而 powerkeeper 往往在
         * 新一代装好之前已完成握手（相差约 50ms），于是它报的账本还在，但连接已断开。
         * 这里只填框架类型（IBinder / String），不携带任何模块类加载器创建的实例。
         */
        fun exportRegistrationSnapshot(): Array<Any?>? {
            val pkCallback = clientCallbacks[MilletIpcContract.CLIENT_POWER_KEEPER]?.binder
            val appCallback = clientCallbacks[MilletIpcContract.CLIENT_XMILLET_APP]?.binder
            if (pkCallback == null && appCallback == null) return null
            return arrayOf(pkCallback, powerKeeperHooksReport, powerKeeperCapsReport, powerKeeperCloudLists, appCallback)
        }

        /**
         * 把上一代导出的注册信息装回新一代，并通知仍持有旧代 `SystemBinderService` 代理的客户端
         * 重新通过 S1 握手绑定新一代 Binder，确保旧代 Binder 与 ClassLoader 可以被 GC 回收。
         */
        fun restoreRegistrationSnapshot(snapshot: Array<*>?) {
            if (snapshot == null) return
            val service = activeService ?: return

            val pkCallback = snapshot.getOrNull(0) as? IBinder
            if (pkCallback != null) {
                val alive = runCatching { pkCallback.isBinderAlive }.getOrDefault(false)
                if (!alive) {
                    Logger.alwaysWarn("XMillet 热重载：powerkeeper 回调已失效，等它重新注册")
                } else {
                    service.registerClient(
                        pkCallback,
                        MilletIpcContract.CLIENT_POWER_KEEPER,
                        snapshot.getOrNull(1) as? String,
                        snapshot.getOrNull(2) as? String,
                        snapshot.getOrNull(3) as? String
                    )
                    sendToClient(MilletIpcContract.CLIENT_POWER_KEEPER, MilletIpcContract.MSG_REBIND_SYSTEM_BINDER)
                    Logger.alwaysWarn("XMillet 热重载：已恢复 powerkeeper 注册并下发重绑通知")
                }
            }

            val appCallback = snapshot.getOrNull(4) as? IBinder
            if (appCallback != null && runCatching { appCallback.isBinderAlive }.getOrDefault(false)) {
                service.registerClient(appCallback, MilletIpcContract.CLIENT_XMILLET_APP)
                sendToClient(MilletIpcContract.CLIENT_XMILLET_APP, MilletIpcContract.MSG_REBIND_SYSTEM_BINDER)
            }
        }

        /**
         * 热重载卸载前解除全部客户端 DeathRecipient 绑定、清空频道映射并退出后台线程。
         */
        fun onHotReloading() {
            isShuttingDown = true
            activeService = null
            binderMap.clear()
            for (key in clientCallbacks.keys.toList()) {
                val removed = clientCallbacks.remove(key) ?: continue
                runCatching { removed.binder.unlinkToDeath(removed.deathRecipient, 0) }
            }
            clearPowerKeeperReports()
            val thread: HandlerThread?
            synchronized(reloadThreadLock) {
                reloadHandlerInstance?.removeCallbacksAndMessages(null)
                reloadHandlerInstance = null
                thread = reloadThread
                reloadThread = null
            }
            thread?.quitSafely()
        }

        /** 存放 system_server 暴露给外部进程的 Binder 表（由 S1 塞入返回 Intent 的 extras） */
        val binderMap: ConcurrentHashMap<String, IBinder> = ConcurrentHashMap()

        private class ClientRegistration(
            val binder: IBinder,
            val deathRecipient: IBinder.DeathRecipient
        )

        /** 已连接的客户端回调 Binder 表（key: "PowerKeeper" / "XMillet"） */
        private val clientCallbacks: ConcurrentHashMap<String, ClientRegistration> = ConcurrentHashMap()

        private fun invalidateClientEntry(clientName: String, entry: ClientRegistration) {
            if (clientCallbacks.remove(clientName, entry)) {
                runCatching { entry.binder.unlinkToDeath(entry.deathRecipient, 0) }
                if (clientName == MilletIpcContract.CLIENT_POWER_KEEPER) {
                    clearPowerKeeperReports()
                }
            }
        }

        private fun clearPowerKeeperReports() {
            powerKeeperHooksReport = ""
            powerKeeperCapsReport = ""
            powerKeeperCloudLists = ""
        }

        /** 由 powerkeeper 上报的 MILLET_NO_RESTRICT_APP 字符串快照 */
        @Volatile
        var noRestrictAppsSnapshot: String = ""

        /** powerkeeper 进程注册时上报的 Hook 账本（见 [HookInstallRegistry.toWireFormat]） */
        @Volatile
        private var powerKeeperHooksReport: String = ""

        /** powerkeeper 进程上报的宿主能力位串 */
        @Volatile
        private var powerKeeperCapsReport: String = ""

        /**
         * powerkeeper 进程用官方 `GlobalFeatureConfigureHelper` 读到的三条云名单。
         *
         * system_server 自己也能反射到该类（同一个 APK），但取不到 powerkeeper 的
         * Context，读出来是空；只在 powerkeeper 进程里读才准，故由它上报、这里缓存。
         */
        @Volatile
        private var powerKeeperCloudLists: String = ""

        private val reloadThreadLock = Any()

        @Volatile
        private var reloadThread: HandlerThread? = null

        @Volatile
        private var reloadHandlerInstance: Handler? = null

        @Volatile
        private var isShuttingDown: Boolean = false

        private fun ensureReloadHandler(): Handler? {
            if (isShuttingDown) return null
            reloadHandlerInstance?.let { return it }
            synchronized(reloadThreadLock) {
                if (isShuttingDown) return null
                reloadHandlerInstance?.let { return it }
                val thread = HandlerThread("XMillet-PrefReload", Process.THREAD_PRIORITY_BACKGROUND)
                thread.start()
                val handler = Handler(thread.looper)
                reloadThread = thread
                reloadHandlerInstance = handler
                return handler
            }
        }

        private fun postReloadTask(delayMs: Long = 0L, block: () -> Unit) {
            val handler = ensureReloadHandler() ?: return
            if (delayMs > 0L) {
                handler.postDelayed(block, delayMs)
            } else {
                handler.post(block)
            }
        }

        /**
         * 在 system_server 入口注册主服务实例。
         */
        fun registerToChannel(key: String = MilletIpcContract.BINDER_KEY_SYSTEM): SystemBinderService {
            val service = SystemBinderService()
            binderMap[key] = service
            return service
        }

        /**
         * 当 PowerKeeper 重建 `MILLET_NO_RESTRICT_APP`（澎湃 3.0 ActiveStateController#dealNoRestrictApp）
         * 并把快照上报回来时，若模块白名单中的包被宿主清掉了，则在后台补回并刷新快照。
         */
        private fun resyncNoRestrictIfDropped(incomingSnapshot: String) {
            if (!SystemState.isGreezeReady) return
            val whiteApps = PrefReader.activePrefs().whiteAppSet
            if (whiteApps.isEmpty()) return
            val present = NoRestrictSync.split(incomingSnapshot)
            if (whiteApps.all { it in present }) return
            postReloadTask {
                val ctx = SystemState.systemContext ?: return@postReloadTask
                val latestWhiteApps = PrefReader.activePrefs().whiteAppSet
                if (NoRestrictSync.sync(ctx, latestWhiteApps)) {
                    noRestrictAppsSnapshot = NoRestrictSync.readSystemList(ctx).joinToString(", ")
                }
            }
        }

        /**
         * 判断指定包名是否处于省电策略「无限制」名单中。
         */
        fun isPackageInNoRestrictSnapshot(pkg: String): Boolean {
            val snapshot = noRestrictAppsSnapshot
            if (snapshot.isEmpty()) return false
            return snapshot.split(',', ';', ' ', '[', ']')
                .any { it.trim() == pkg }
        }

        /**
         * 向指定客户端发送单向回调消息（调用 INativeService#mo498）。
         */
        fun sendToClient(clientName: String, message: String) {
            val entry = clientCallbacks[clientName]
            if (entry == null) {
                Logger.debug("${clientName}未连接(忽略消息: $message)")
                return
            }
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(MilletIpcContract.DESCRIPTOR_CLIENT_CALLBACK)
                data.writeString(message)
                entry.binder.transact(MilletIpcContract.TX_ON_CLIENT_MESSAGE, data, null, IBinder.FLAG_ONEWAY)
            } catch (e: RemoteException) {
                invalidateClientEntry(clientName, entry)
                Logger.error("发送消息给客户端[$clientName]出现 RemoteException", e)
            } catch (t: Throwable) {
                Logger.error("发送消息给客户端[$clientName]出现异常", t)
            } finally {
                data.recycle()
            }
        }
    }
}
