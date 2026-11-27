package io.github.univers629.millet.core

import android.content.Context
import android.database.ContentObserver
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 当前激活默认输入法追踪器（供 system_server 与 PowerKeeper 共用）。
 *
 * 仅保护 Secure 设置中当前启用的默认输入法与无障碍服务；强制冻结不绕过此安全豁免。
 * UID 入口按目标用户读取设置；读取失败时保守阻断冻结，不继承主用户缓存。
 */
object ActiveImeTracker {

    private const val REFRESH_FALLBACK_INTERVAL_MS = 30_000L

    /** `Settings.Secure.AUTOFILL_SERVICE`（SDK 隐藏常量），格式为 `package/class`。 */
    const val SETTING_AUTOFILL_SERVICE = "autofill_service"

    @Volatile
    private var cachedImePackage: String? = null

    @Volatile
    private var cachedAccessibilityPackages: Set<String> = emptySet()

    @Volatile
    private var cachedAutofillPackage: String? = null

    @Volatile
    private var lastRefreshUptimeMs: Long = 0L

    private data class UserServices(
        val roles: UserServiceRoles,
        val refreshedAt: Long,
    )

    private val userServices = java.util.concurrent.ConcurrentHashMap<Int, UserServices>()

    private val observerRegistered = AtomicBoolean(false)
    private val observerLock = Any()

    /** 设置读取与观察者注册一律转到后台，避免钩子线程在持有平台锁时做跨进程调用。 */
    private val refreshExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "XMillet-SettingsRefresh").apply { isDaemon = true }
        }
    }

    private val mainRefreshInFlight = AtomicBoolean(false)
    private val userRefreshInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var hotReloading = false

    @Volatile
    private var registeredContext: Context? = null

    @Volatile
    private var registeredObserver: ContentObserver? = null

    fun onHotReloading() {
        synchronized(observerLock) {
            hotReloading = true
            observerRegistered.set(false)
            cachedImePackage = null
            cachedAccessibilityPackages = emptySet()
            cachedAutofillPackage = null
            userServices.clear()
            userRefreshInFlight.clear()
            mainRefreshInFlight.set(false)
            lastRefreshUptimeMs = 0L
            unregisterObserverLocked()
        }
        runCatching { refreshExecutor.shutdownNow() }
    }

    private fun unregisterObserverLocked(): Boolean {
        val ctx = registeredContext ?: return true
        val observer = registeredObserver ?: return true
        val token = Binder.clearCallingIdentity()
        return try {
            ctx.contentResolver.unregisterContentObserver(observer)
            registeredContext = null
            registeredObserver = null
            true
        } catch (_: Throwable) {
            false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun parseImePackage(componentId: String?): String? {
        if (componentId.isNullOrBlank()) return null
        val pkg = componentId.substringBefore('/').trim()
        return pkg.takeIf { it.isNotEmpty() && '.' in it }
    }

    fun parseAccessibilityPackages(enabledFlag: Int, servicesRaw: String?): Set<String> {
        if (enabledFlag != 1 || servicesRaw.isNullOrBlank()) return emptySet()
        val result = LinkedHashSet<String>()
        for (entry in servicesRaw.split(':')) {
            parseImePackage(entry)?.let(result::add)
        }
        return result
    }

    fun resolveDefaultImePackage(context: Context?): String? {
        val ctx = context ?: return cachedImePackage
        if (!isCacheFresh()) requestBackgroundRefresh(ctx)
        return cachedImePackage
    }

    fun resolveAccessibilityPackages(context: Context?): Set<String> {
        val ctx = context ?: return cachedAccessibilityPackages
        if (!isCacheFresh()) requestBackgroundRefresh(ctx)
        return cachedAccessibilityPackages
    }

    fun resolveAutofillPackage(context: Context?): String? {
        val ctx = context ?: return cachedAutofillPackage
        if (!isCacheFresh()) requestBackgroundRefresh(ctx)
        return cachedAutofillPackage
    }

    /**
     * 判断指定应用是否为目标用户当前启用的自动填充服务。
     *
     * 仅用于亮屏解冻精准豁免，不参与冻结硬阻断；缓存未就绪时触发后台刷新并返回 false。
     */
    fun isActiveAutofillService(context: Context?, packageName: String?, uid: Int): Boolean {
        if (!AppUidUtils.isManagedAppUid(uid) || packageName.isNullOrEmpty()) return false
        val roles = resolveUserServices(context, AppUidUtils.userIdOf(uid))?.roles ?: return false
        return packageName == roles.autofillPackage
    }

    private fun isCacheFresh(): Boolean {
        if (observerRegistered.get() && lastRefreshUptimeMs > 0L) return true
        val now = SystemClock.uptimeMillis()
        return lastRefreshUptimeMs > 0L && (now - lastRefreshUptimeMs in 0..REFRESH_FALLBACK_INTERVAL_MS)
    }

    @Suppress("UNUSED_PARAMETER")
    fun isProtectedDefaultIme(context: Context?, packageName: String?, prefs: PrefsSnapshot): Boolean {
        if (packageName.isNullOrEmpty()) return false
        val activeIme = resolveDefaultImePackage(context) ?: return false
        return packageName == activeIme
    }

    @Suppress("UNUSED_PARAMETER")
    fun isProtectedAccessibilityApp(context: Context?, packageName: String?, prefs: PrefsSnapshot): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return packageName in resolveAccessibilityPackages(context)
    }

    fun isProtectedSystemServiceApp(context: Context?, packageName: String?, prefs: PrefsSnapshot): Boolean =
        isProtectedDefaultIme(context, packageName, prefs) ||
            isProtectedAccessibilityApp(context, packageName, prefs)

    @Suppress("UNUSED_PARAMETER")
    fun isProtectedDefaultIme(context: Context?, packageName: String?, prefs: PrefsSnapshot, uid: Int): Boolean {
        if (!AppUidUtils.isManagedAppUid(uid) || packageName.isNullOrEmpty()) return false
        val roles = resolveUserServices(context, AppUidUtils.userIdOf(uid))?.roles ?: return true
        return packageName == roles.imePackage
    }

    @Suppress("UNUSED_PARAMETER")
    fun isProtectedAccessibilityApp(context: Context?, packageName: String?, prefs: PrefsSnapshot, uid: Int): Boolean {
        if (!AppUidUtils.isManagedAppUid(uid) || packageName.isNullOrEmpty()) return false
        val roles = resolveUserServices(context, AppUidUtils.userIdOf(uid))?.roles ?: return true
        return packageName in roles.accessibilityPackages
    }

    @Suppress("UNUSED_PARAMETER")
    fun isProtectedSystemServiceApp(context: Context?, packageName: String?, prefs: PrefsSnapshot, uid: Int): Boolean =
        resolveServiceRoleProtection(context, packageName, uid).blocksFreeze

    internal fun resolveServiceRoleProtection(context: Context?, packageName: String?, uid: Int): ServiceRoleProtection =
        evaluateUserServiceProtection(uid, packageName) { userId -> resolveUserServices(context, userId)?.roles }

    private fun resolveUserServices(context: Context?, userId: Int): UserServices? {
        val ctx = context ?: return null
        val now = SystemClock.uptimeMillis()
        userServices[userId]?.takeIf { now - it.refreshedAt in 0..REFRESH_FALLBACK_INTERVAL_MS }?.let { return it }
        requestBackgroundUserRefresh(ctx, userId)
        return null
    }

    /** 未命中缓存时在后台读取设置并注册观察者；失败返回 null，调用方按「未知」保守处理。 */
    private fun requestBackgroundRefresh(context: Context) {
        if (!mainRefreshInFlight.compareAndSet(false, true)) return
        refreshExecutor.execute {
            try {
                ensureObserver(context)
                if (!isCacheFresh()) refresh(context)
            } catch (_: Throwable) {
            } finally {
                mainRefreshInFlight.set(false)
            }
        }
    }

    private fun requestBackgroundUserRefresh(context: Context, userId: Int) {
        if (!userRefreshInFlight.add(userId)) return
        refreshExecutor.execute {
            try {
                ensureObserver(context)
                refreshUserServices(context, userId)
            } catch (_: Throwable) {
            } finally {
                userRefreshInFlight.remove(userId)
            }
        }
    }

    private fun refreshUserServices(context: Context, userId: Int): UserServices? {
        val now = SystemClock.uptimeMillis()
        val ctx = context
        val token = Binder.clearCallingIdentity()
        return try {
            // ROMs without per-user Secure access cannot provide an independent safety role.
            val getString = Settings.Secure::class.java.getDeclaredMethod(
                "getStringForUser", android.content.ContentResolver::class.java,
                String::class.java, Int::class.javaPrimitiveType,
            )
            val resolver = ctx.contentResolver
            val roles = readUserServiceRoles { key -> getString.invoke(null, resolver, key, userId) as String? }
                ?: return null
            UserServices(roles, now)
                .also { userServices[userId] = it }
        } catch (_: Throwable) {
            null
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    internal fun readUserServiceRoles(readSetting: (String) -> String?): UserServiceRoles? = runCatching {
        val ime = readSetting(Settings.Secure.DEFAULT_INPUT_METHOD)
        val enabled = readSetting(Settings.Secure.ACCESSIBILITY_ENABLED)
        val services = readSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        // 自动填充读取失败不影响输入法与无障碍的硬保护状态，按无自动填充处理。
        val autofill = runCatching { readSetting(SETTING_AUTOFILL_SERVICE) }.getOrNull()
        UserServiceRoles(
            imePackage = parseImePackage(ime),
            accessibilityPackages = parseAccessibilityPackages(enabled?.toIntOrNull() ?: 0, services),
            autofillPackage = parseImePackage(autofill),
        )
    }.getOrNull()

    private fun refresh(context: Context): String? {
        val token = Binder.clearCallingIdentity()
        return try {
            val resolver = context.contentResolver
            val rawIme = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val imePkg = parseImePackage(rawIme)
            val a11yEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            val rawA11y = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val rawAutofill = runCatching {
                Settings.Secure.getString(resolver, SETTING_AUTOFILL_SERVICE)
            }.getOrNull()
            cachedImePackage = imePkg
            cachedAccessibilityPackages = parseAccessibilityPackages(a11yEnabled, rawA11y)
            cachedAutofillPackage = parseImePackage(rawAutofill)
            lastRefreshUptimeMs = SystemClock.uptimeMillis()
            imePkg
        } catch (_: Throwable) {
            cachedImePackage
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun ensureObserver(context: Context) {
        if (hotReloading || observerRegistered.get()) return
        val looper = Looper.getMainLooper() ?: return
        synchronized(observerLock) {
            if (hotReloading || observerRegistered.get()) return
            // Release any previous registration before creating another observer.
            if (!unregisterObserverLocked()) return
            val appCtx = context.applicationContext ?: context
            val token = Binder.clearCallingIdentity()
            try {
                val resolver = appCtx.contentResolver
                val observer = object : ContentObserver(Handler(looper)) {
                    override fun onChange(selfChange: Boolean) {
                        if (hotReloading) return
                        userServices.clear()
                        refresh(appCtx)
                    }
                }
                // Keep the cleanup handle before the first URI registration.
                registeredContext = appCtx
                registeredObserver = observer
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                    false,
                    observer
                )
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false,
                    observer
                )
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                    false,
                    observer
                )
                runCatching {
                    resolver.registerContentObserver(
                        Settings.Secure.getUriFor(SETTING_AUTOFILL_SERVICE),
                        false,
                        observer
                    )
                }
                observerRegistered.set(true)
                refresh(appCtx)
            } catch (_: Throwable) {
                observerRegistered.set(false)
                unregisterObserverLocked()
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
    }
}
