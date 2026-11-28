package io.github.univers629.millet.system

import android.app.ActivityManager
import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.univers629.millet.core.Logger

/**
 * 前台/可见进程的只读快照，事件驱动，不注册任何周期任务。
 *
 * 钩子会在持有平台锁的线程上执行（例如焦点切换时持有 `WindowManagerGlobalLock`，
 * 广播投递时持有 `ActivityManagerService` 锁）。这些线程里再调用
 * `ActivityManager.getRunningAppProcesses()` 或 `getPackageImportance()` 会去抢
 * `ActivityManagerProcLock`，与 AMS 线程「持 ProcLock 等 WindowManagerGlobalLock」形成环路，
 * 最终触发 system_server watchdog 重启。
 *
 * 因此前台状态由独立线程按需采样，钩子只读取不可变快照：
 * - 屏幕熄灭时直接视为「无可见应用」，不读 AMS，也不保留采样线程；
 * - 屏幕亮起、或钩子发现快照过期时，才请求一次采样，且 5 秒内重复请求会被合并；
 * - 快照缺失或过期时返回 `null`，调用方按「可能在前台」放行，宁可少冻也不参与锁等待。
 */
object ForegroundState {

    /** 两次采样请求的最小间隔，避免钩子密集触发时变成轮询。 */
    private const val MIN_REFRESH_INTERVAL_MS = 5_000L

    /** 快照有效期，超过后视为不可用。 */
    private const val SNAPSHOT_TTL_MS = 8_000L

    private const val THREAD_NAME = "XMillet-Foreground"

    class Snapshot(
        val updatedAtElapsed: Long,
        val visiblePackages: Set<String>,
        val visibleUids: Set<Int>,
        val processNames: Map<Int, String>,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    /** 最近一次屏幕状态；null 表示尚未得知。 */
    @Volatile
    private var screenOn: Boolean? = null

    /** 当前是否明确处于息屏状态。 */
    val isScreenOff: Boolean
        get() = screenOn == false

    @Volatile
    private var refreshHandler: Handler? = null

    @Volatile
    private var refreshThread: HandlerThread? = null

    private var lastRequestAtElapsed = 0L

    private val lock = Any()

    /** 测试注入的时钟，默认使用 `elapsedRealtime`。 */
    internal var nowProvider: () -> Long = { SystemClock.elapsedRealtime() }

    /** 注入时调用：记录初始屏幕状态，不启动任何周期任务。 */
    fun start(context: Context?) {
        if (context == null) return
        val interactive = runCatching {
            context.getSystemService(Context.POWER_SERVICE)
                ?.let { it as? android.os.PowerManager }
                ?.isInteractive
        }.getOrNull()
        setScreenState(interactive)
    }

    /** 热重载前回收采样线程。 */
    fun stop() {
        synchronized(lock) {
            refreshHandler?.removeCallbacksAndMessages(null)
            refreshThread?.quitSafely()
            refreshHandler = null
            refreshThread = null
            snapshot = null
            lastRequestAtElapsed = 0L
        }
    }

    /**
     * 屏幕亮灭广播回调：只更新状态。
     *
     * 熄屏时立即生成「无可见应用」快照并停止采样；亮屏时才请一次快照。
     */
    fun setScreenState(on: Boolean?) {
        screenOn = on
        when (on) {
            false -> {
                snapshot = Snapshot(nowProvider(), emptySet(), emptySet(), emptyMap())
                releaseHandler()// 熄屏后不再保留采样线程
            }
            true -> requestRefresh()
            null -> Unit
        }
    }

    /**
     * 事件驱动地请求一次后台采样；[MIN_REFRESH_INTERVAL_MS] 内重复调用会被合并。
     */
    fun requestRefresh() {
        val now = nowProvider()
        synchronized(lock) {
            if (now - lastRequestAtElapsed < MIN_REFRESH_INTERVAL_MS) return
            lastRequestAtElapsed = now
        }
        if (screenOn == false) {
            snapshot = Snapshot(now, emptySet(), emptySet(), emptyMap())
            return
        }
        val handler = ensureHandler() ?: return
        handler.post {
            runCatching { refresh() }.onFailure { Logger.error("刷新前台快照失败", it) }
        }
    }

    /**
     * 包名当前是否有可见进程。
     *
     * @return true 可见；false 明确不可见；null 快照不可用（调用方按「可能在前台」处理）
     */
    fun isForeground(packageName: String?): Boolean? {
        if (packageName.isNullOrEmpty()) return null
        if (screenOn == false) return false
        val fresh = freshSnapshot() ?: run {
            requestRefresh()
            return null
        }
        return packageName in fresh.visiblePackages
    }

    /**
     * UID 当前是否有可见进程。
     *
     * @return true 可见；false 明确不可见；null 快照不可用（调用方按「可能在前台」保守处理）
     */
    fun isUidVisible(uid: Int): Boolean? {
        if (screenOn == false) return false
        val fresh = freshSnapshot() ?: run {
            requestRefresh()
            return null
        }
        return uid in fresh.visibleUids
    }

    /** 进程名查询；快照不可用时返回 null。 */
    fun processNameOf(pid: Int): String? = freshSnapshot()?.processNames?.get(pid)

    internal fun freshSnapshot(): Snapshot? {
        val current = snapshot ?: return null
        val age = nowProvider() - current.updatedAtElapsed
        return if (age in 0..SNAPSHOT_TTL_MS) current else null
    }

    /** 仅测试使用。 */
    internal fun installForTest(value: Snapshot?) {
        snapshot = value
    }

    /** 仅测试使用：复位屏幕状态，避免用例之间互相影响。 */
    internal fun installScreenStateForTest(on: Boolean?) {
        screenOn = on
    }

    private fun releaseHandler() {
        synchronized(lock) {
            refreshHandler?.removeCallbacksAndMessages(null)
            refreshThread?.quitSafely()
            refreshHandler = null
            refreshThread = null
        }
    }

    private fun ensureHandler(): Handler? {
        synchronized(lock) {
            refreshHandler?.let { return it }
            // 单元测试没有 system_server Context，直接不建线程。
            if (SystemState.systemContext == null) return null
            val thread = try {
                HandlerThread(THREAD_NAME).apply { start() }
            } catch (t: Throwable) {
                Logger.error("启动前台采样线程失败", t)
                return null
            }
            val handler = Handler(thread.looper)
            refreshThread = thread
            refreshHandler = handler
            return handler
        }
    }

    private fun refresh() {
        if (screenOn == false) return
        val context = SystemState.systemContext ?: return
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val token = Binder.clearCallingIdentity()
        val processes = try {
            manager.runningAppProcesses.orEmpty()
        } catch (t: Throwable) {
            Logger.error("读取运行进程失败", t)
            emptyList()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        val visiblePackages = HashSet<String>()
        val visibleUids = HashSet<Int>()
        val processNames = HashMap<Int, String>()
        for (process in processes) {
            processNames[process.pid] = process.processName
            if (process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                visibleUids += process.uid
                process.pkgList?.forEach { visiblePackages += it }
            }
        }
        snapshot = Snapshot(nowProvider(), visiblePackages, visibleUids, processNames)
    }
}
