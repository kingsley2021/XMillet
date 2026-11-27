package io.github.univers629.millet.core

/**
 * 冻结闸门的三层结构化裁决原因。
 *
 * 优先级由高到低：
 * 1. 硬安全层：用户免冻白名单、内置核心保护（指纹支付/音效）、当前默认输入法、无障碍服务及服务角色未就绪保守保护；
 * 2. 软豁免层：未配置强制冻结时的「活跃前台服务不冻结」（消息应用仍正常冻结，仅在收到推送/广播时短暂解冻）；
 * 3. 默认放行层：允许官方链路执行墓碑冻结。
 */
enum class GateReason(
    val key: String,
    val label: String,
    val blocksFreeze: Boolean,
    val recordsProtectStat: Boolean,
) {
    WHITE_LIST("white_list", "免冻白名单", blocksFreeze = true, recordsProtectStat = true),
    BUILTIN_PROTECT("builtin_protect", "内置核心保护", blocksFreeze = true, recordsProtectStat = true),
    DEFAULT_IME("default_ime", "当前默认输入法", blocksFreeze = true, recordsProtectStat = false),
    ACCESSIBILITY_SERVICE("accessibility", "已启用无障碍服务", blocksFreeze = true, recordsProtectStat = false),
    UNKNOWN_SERVICE_ROLES("unknown_service_roles", "服务角色未就绪保守保护", blocksFreeze = true, recordsProtectStat = false),
    FOREGROUND_SERVICE("foreground_service", "活跃前台服务保护", blocksFreeze = true, recordsProtectStat = true),
    ALLOW_FREEZE("allow_freeze", "允许冻结", blocksFreeze = false, recordsProtectStat = false),
}

/**
 * 解冻闸门放行的细分原因。
 */
enum class ThawAllowReason {
    UNMANAGED_OR_UNREADY,
    NOT_FROZEN,
    USER_OPEN,
    CURRENTLY_FOREGROUND,
    WHITE_LIST,
    PROTECTED_SYSTEM_SERVICE,
    SCREEN_ON_EXEMPT,
    ALLOWED_BY_POLICY,
}

/**
 * 解冻闸门裁决结果。
 */
sealed interface ThawGateVerdict {
    data class Allow(val reason: ThawAllowReason) : ThawGateVerdict
    data class Intercept(val rule: InterceptRule) : ThawGateVerdict
}

/**
 * 广播投递闸门（`GreezeManagerService#isAllowBroadcast`）裁决结果。
 */
sealed interface BroadcastGateVerdict {
    data class Intercept(val rule: InterceptRule) : BroadcastGateVerdict
    data object AllowExempt : BroadcastGateVerdict
    data object DelegateToRom : BroadcastGateVerdict
}

/**
 * 单次冻结/解冻/广播判定的纯上下文快照与惰性探针。
 *
 * 所有可能触发反射或系统缓存读取的信号均以惰性函数传入并做单次求值缓存：
 * 例如 `hasForegroundService` 仅在开启 `checkFgs`、命中 `notFreezeWhenHasFgsSet` 且不在 `forceFreezeSet` 时才调用，
 * 避免在 `S8` / `S33` / `P23` 等无 FGS 诉求或已被白名单短路的入口上做多余反射。
 */
internal class AppGateContext(
    val uid: Int,
    val packageName: String?,
    val isGreezeReady: Boolean = true,
    private val isUidFrozen: () -> Boolean = { false },
    private val isCurrentlyForeground: () -> Boolean = { false },
    private val serviceRoleProvider: () -> ServiceRoleProtection = { ServiceRoleProtection.NONE },
    private val hasForegroundService: () -> Boolean = { false },
    private val isActiveLiveWallpaper: () -> Boolean = { false },
    private val isActiveAutofillService: () -> Boolean = { false },
    private val isForegroundBinderBound: () -> Boolean = { false },
    private val isWidgetApp: () -> Boolean = { false },
) {
    private var cachedUidFrozen: Boolean? = null
    private var cachedForeground: Boolean? = null
    private var cachedServiceRole: ServiceRoleProtection? = null
    private var cachedFgs: Boolean? = null
    private var cachedLiveWallpaper: Boolean? = null
    private var cachedAutofillService: Boolean? = null
    private var cachedForegroundBinder: Boolean? = null
    private var cachedWidgetApp: Boolean? = null

    fun isUidFrozen(): Boolean =
        cachedUidFrozen ?: isUidFrozen.invoke().also { cachedUidFrozen = it }

    fun isCurrentlyForeground(): Boolean =
        cachedForeground ?: isCurrentlyForeground.invoke().also { cachedForeground = it }

    fun serviceRoleProtection(): ServiceRoleProtection =
        cachedServiceRole ?: serviceRoleProvider.invoke().also { cachedServiceRole = it }

    fun hasForegroundService(): Boolean =
        cachedFgs ?: hasForegroundService.invoke().also { cachedFgs = it }

    fun isActiveLiveWallpaper(): Boolean =
        cachedLiveWallpaper ?: isActiveLiveWallpaper.invoke().also { cachedLiveWallpaper = it }

    fun isActiveAutofillService(): Boolean =
        cachedAutofillService ?: isActiveAutofillService.invoke().also { cachedAutofillService = it }

    fun isForegroundBinderBound(): Boolean =
        cachedForegroundBinder ?: isForegroundBinderBound.invoke().also { cachedForegroundBinder = it }

    fun isWidgetApp(): Boolean =
        cachedWidgetApp ?: isWidgetApp.invoke().also { cachedWidgetApp = it }
}

/**
 * 跨 OS1–OS4 共用的纯函数闸门裁决引擎（零反射、零 Android 服务句柄，支持 100% JVM 单元测试）。
 *
 * 统一收口 `system_server`（S6/S7/S8/S20/S21/S23/S24/S26/S27/S33）与
 * `com.miui.powerkeeper`（P9/P10/P22/P23/P25/P26）的免冻、解冻拦截与广播放行优先级，
 * 消除双进程各自手写判定分支造成的语义漂移。
 */
internal object GateDecision {

    /**
     * 冻结闸门三层判定。
     *
     * 注意：`messageAppSet` 应用设计语义是「平时照常墓碑冻结省电，收到推送包/广播时短暂解冻」，
     * 因此此处对单纯消息应用始终返回 [GateReason.ALLOW_FREEZE]。
     */
    fun decideFreeze(
        ctx: AppGateContext,
        prefs: PrefsSnapshot,
        checkFgs: Boolean,
    ): GateReason {
        val pkg = ctx.packageName
        if (pkg.isNullOrEmpty()) return GateReason.ALLOW_FREEZE
        if (AppUidUtils.containsPolicyTarget(prefs.whiteAppSet, pkg, ctx.uid)) {
            return GateReason.WHITE_LIST
        }
        if (pkg in BuiltinProtect.FREEZE_EXEMPT_PACKAGES) {
            return GateReason.BUILTIN_PROTECT
        }
        when (ctx.serviceRoleProtection()) {
            ServiceRoleProtection.DEFAULT_IME -> return GateReason.DEFAULT_IME
            ServiceRoleProtection.ACCESSIBILITY -> return GateReason.ACCESSIBILITY_SERVICE
            ServiceRoleProtection.UNKNOWN -> return GateReason.UNKNOWN_SERVICE_ROLES
            ServiceRoleProtection.NONE -> Unit
        }
        if (checkFgs &&
            ctx.uid >= 10000 &&
            !AppUidUtils.containsPolicyTarget(prefs.forceFreezeSet, pkg, ctx.uid) &&
            AppUidUtils.containsPolicyTarget(prefs.notFreezeWhenHasFgsSet, pkg, ctx.uid) &&
            ctx.hasForegroundService()
        ) {
            return GateReason.FOREGROUND_SERVICE
        }
        return GateReason.ALLOW_FREEZE
    }

    /**
     * 免冻结/免查杀硬保护快速判定（供 PowerKeeper P9/P10/P22/P23/guardKill 共用）。
     */
    fun isFreezeOrKillExempt(ctx: AppGateContext, prefs: PrefsSnapshot): Boolean =
        decideFreeze(ctx, prefs, checkFgs = false).blocksFreeze

    /**
     * 亮屏解冻豁免判断：
     * - 核心系统 UID（`< 10000`）与用户免冻白名单始终随亮屏放行；
     * - 当前正处于屏幕前台/可见的应用放行；
     * - 系统默认输入法、无障碍服务（含未就绪保守态）放行；
     * - 当前动态壁纸、当前自动填充服务与前台 Binder 关联 UID 放行；
     * - 未配置后台解冻压制且未强制冻结的桌面小组件应用及拥有活跃前台服务的 `notFreezeWhenHasFgsSet` 应用放行。
     */
    fun decideScreenOnThawExempt(ctx: AppGateContext, prefs: PrefsSnapshot): Boolean {
        if (AppUidUtils.appIdOf(ctx.uid) in 0 until 10000) return true
        val pkg = ctx.packageName
        if (pkg.isNullOrEmpty()) return false
        if (AppUidUtils.containsPolicyTarget(prefs.whiteAppSet, pkg, ctx.uid)) return true
        if (ctx.isCurrentlyForeground()) return true
        if (ctx.serviceRoleProtection().blocksFreeze) return true
        if (ctx.isActiveLiveWallpaper() || ctx.isActiveAutofillService() || ctx.isForegroundBinderBound()) {
            return true
        }
        if (AppUidUtils.containsPolicyTarget(prefs.blockThawSet, pkg, ctx.uid) ||
            AppUidUtils.containsPolicyTarget(prefs.ignoreNetworkWakeSet, pkg, ctx.uid)
        ) {
            return false
        }
        if (ctx.uid >= 10000 &&
            !AppUidUtils.containsPolicyTarget(prefs.forceFreezeSet, pkg, ctx.uid) &&
            ctx.isWidgetApp()
        ) {
            return true
        }
        return ctx.uid >= 10000 &&
            !AppUidUtils.containsPolicyTarget(prefs.forceFreezeSet, pkg, ctx.uid) &&
            AppUidUtils.containsPolicyTarget(prefs.notFreezeWhenHasFgsSet, pkg, ctx.uid) &&
            ctx.hasForegroundService()
    }

    /**
     * 解冻闸门统一裁决（供 S20/S21/S24/S26/S27 共用）。
     */
    fun decideThaw(
        ctx: AppGateContext,
        reason: String,
        prefs: PrefsSnapshot,
    ): ThawGateVerdict {
        val pkg = ctx.packageName
        if (!ctx.isGreezeReady ||
            !AppUidUtils.isManagedAppUid(ctx.uid) ||
            pkg.isNullOrEmpty() ||
            reason.isBlank()
        ) {
            return ThawGateVerdict.Allow(ThawAllowReason.UNMANAGED_OR_UNREADY)
        }
        if (FreezeReasonClassifier.isUserOpenReason(reason)) {
            return ThawGateVerdict.Allow(ThawAllowReason.USER_OPEN)
        }
        if (ctx.isCurrentlyForeground()) {
            return ThawGateVerdict.Allow(ThawAllowReason.CURRENTLY_FOREGROUND)
        }
        if (AppUidUtils.containsPolicyTarget(prefs.whiteAppSet, pkg, ctx.uid)) {
            return ThawGateVerdict.Allow(ThawAllowReason.WHITE_LIST)
        }
        if (ctx.serviceRoleProtection().blocksFreeze) {
            return ThawGateVerdict.Allow(ThawAllowReason.PROTECTED_SYSTEM_SERVICE)
        }
        if (FreezeReasonClassifier.isScreenOnThawReason(reason) && decideScreenOnThawExempt(ctx, prefs)) {
            return ThawGateVerdict.Allow(ThawAllowReason.SCREEN_ON_EXEMPT)
        }
        if (!ctx.isUidFrozen()) {
            return ThawGateVerdict.Allow(ThawAllowReason.NOT_FROZEN)
        }
        val rule = when {
            pkg == "com.tencent.mobileqq" && reason.trim().equals("broadcast", ignoreCase = true) ->
                InterceptRule.HARD_RULE
            FreezeReasonClassifier.isSyncBinderThawReason(reason) &&
                AppUidUtils.containsPolicyTarget(prefs.forbidSyncBinderSet, pkg, ctx.uid) ->
                InterceptRule.FORBID_SYNC_BINDER
            FreezeReasonClassifier.isNetworkWakeReason(reason) &&
                AppUidUtils.containsPolicyTarget(prefs.ignoreNetworkWakeSet, pkg, ctx.uid) ->
                InterceptRule.IGNORE_NETWORK
            prefs.disableCharge && FreezeReasonClassifier.isChargeThawReason(reason) ->
                InterceptRule.NO_CHARGE_THAW
            prefs.disableScreenOnThaw && FreezeReasonClassifier.isScreenOnThawReason(reason) ->
                InterceptRule.NO_SCREEN_ON_THAW
            AppUidUtils.containsPolicyTarget(prefs.blockThawSet, pkg, ctx.uid) &&
                !FreezeReasonClassifier.isAllowedThawReason(reason) ->
                InterceptRule.BLOCK_THAW
            else -> null
        }
        return if (rule != null) {
            ThawGateVerdict.Intercept(rule)
        } else {
            ThawGateVerdict.Allow(ThawAllowReason.ALLOWED_BY_POLICY)
        }
    }

    /**
     * 广播投递闸门裁决（供 S24 `GreezeManagerService#isAllowBroadcast` 调用）。
     */
    fun decideBroadcastAllow(
        ctx: AppGateContext,
        prefs: PrefsSnapshot,
    ): BroadcastGateVerdict {
        val pkg = ctx.packageName
        if (!ctx.isGreezeReady || !AppUidUtils.isManagedAppUid(ctx.uid) || pkg.isNullOrEmpty()) {
            return BroadcastGateVerdict.DelegateToRom
        }
        val thawVerdict = decideThaw(ctx, "broadcast", prefs)
        if (thawVerdict is ThawGateVerdict.Intercept) {
            return BroadcastGateVerdict.Intercept(thawVerdict.rule)
        }
        val suppressSoftExemption = AppUidUtils.containsPolicyTarget(prefs.forceFreezeSet, pkg, ctx.uid) ||
            AppUidUtils.containsPolicyTarget(prefs.blockThawSet, pkg, ctx.uid) ||
            AppUidUtils.containsPolicyTarget(prefs.ignoreNetworkWakeSet, pkg, ctx.uid)
        val exempt = AppUidUtils.containsPolicyTarget(prefs.whiteAppSet, pkg, ctx.uid) ||
            ctx.serviceRoleProtection().blocksFreeze ||
            (!suppressSoftExemption &&
                (AppUidUtils.containsPolicyTarget(prefs.messageAppSet, pkg, ctx.uid) ||
                    (AppUidUtils.containsPolicyTarget(prefs.notFreezeWhenHasFgsSet, pkg, ctx.uid) &&
                        ctx.hasForegroundService())))
        return if (exempt) {
            BroadcastGateVerdict.AllowExempt
        } else {
            BroadcastGateVerdict.DelegateToRom
        }
    }
}
