package io.github.univers629.millet.core

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 宿主能力探测层（PowerKeeper / 系统框架的跨版本差异适配）。
 *
 * 不同 ROM 版本会增删宿主的字段与方法：例如 `com.miui.powerkeeper.millet.MilletPolicy`
 * 的静态字段 `BRIGHT_WHITE_APPS` 只存在于 Android 14 / 澎湃 2.0（PowerKeeper 5.x）及以上，
 * Android 13 / 澎湃 1.0（PowerKeeper 4.2.00）完全没有这个字段。
 *
 * 因此模块在「安装 Hook」与「读写宿主静态表」之前统一走这里探测能力：
 * 1. 安装期过滤：目标类/方法在该版本不存在时直接跳过该点位，不再让安装器抛异常；
 * 2. 运行期判断：字段不存在时跳过对应写入，避免每次进页面都刷 `NoSuchFieldException`；
 * 3. 结果缓存：反射探测结果按「类名 + 成员名」缓存，热路径零额外开销。
 */
object RomCapabilities {

    /** 宿主进程包名 */
    const val POWERKEEPER_PACKAGE = "com.miui.powerkeeper"

    private val classCache = ConcurrentHashMap<String, Boolean>()
    private val fieldCache = ConcurrentHashMap<String, Boolean>()
    private val methodCache = ConcurrentHashMap<String, Boolean>()

    /** 目标类是否存在。 */
    fun hasClass(className: String, loader: ClassLoader?): Boolean =
        classCache.getOrPut(className) {
            XReflect.findClassIfExists(className, loader) != null
        }

    /** 目标类（含父类链）是否存在指定字段。 */
    fun hasField(className: String, fieldName: String, loader: ClassLoader?): Boolean =
        fieldCache.getOrPut("$className#$fieldName") {
            val clazz = XReflect.findClassIfExists(className, loader) ?: return@getOrPut false
            var current: Class<*>? = clazz
            var found = false
            while (current != null && current != Any::class.java) {
                if (current.declaredFields.any { it.name == fieldName }) {
                    found = true
                    break
                }
                current = current.superclass
            }
            found
        }

    /** 以已解析的 Class 判断字段是否存在（运行期热点路径用）。 */
    fun hasField(clazz: Class<*>?, fieldName: String): Boolean {
        if (clazz == null) return false
        return hasField(clazz.name, fieldName, clazz.classLoader)
    }

    /** 目标类（含父类链）是否存在指定方法（不校验重载签名）。 */
    fun hasMethod(className: String, methodName: String, loader: ClassLoader?): Boolean =
        methodCache.getOrPut("$className#$methodName") {
            val clazz = XReflect.findClassIfExists(className, loader) ?: return@getOrPut false
            var current: Class<*>? = clazz
            var found = false
            while (current != null && current != Any::class.java) {
                if (current.declaredMethods.any { it.name == methodName }) {
                    found = true
                    break
                }
                current = current.superclass
            }
            found
        }

    /** 以已解析的 Class 判断方法是否存在（运行期热点路径用）。 */
    fun hasMethod(clazz: Class<*>?, methodName: String): Boolean {
        if (clazz == null) return false
        return hasMethod(clazz.name, methodName, clazz.classLoader)
    }

    /**
     * 判断一条 [HookSpec] 在当前宿主版本上是否可安装。
     *
     * 构造器点位（[HookSpec.methodName] 为 null）只要求目标类存在；
     * 普通方法点位额外要求同名方法存在，否则视为该版本不支持。
     */
    fun isInstallable(spec: HookSpec, loader: ClassLoader?): Boolean {
        if (!hasClass(spec.targetClass, loader)) return false
        val method = spec.methodName ?: return true
        return hasMethod(spec.targetClass, method, loader)
    }

    /**
     * 按能力过滤一组点位，并把被跳过的点位汇总成一条日志。
     *
     * 用于「整批安装」的入口：宿主版本删掉整块子系统时（例如澎湃 3.0 移除
     * `com.miui.powerkeeper.millet` 与 `com.miui.process`），相关点位会被静默跳过，
     * 而不是让安装器逐条抛 `ClassNotFoundException`。
     */
    fun filterInstallable(
        specs: List<HookSpec>,
        loader: ClassLoader?,
        sceneTag: String
    ): List<HookSpec> {
        // 先按版本门控筛一遍：像 S9/S10 这种「按 SDK 二选一」的点位，另一支本来就不该装，
        // 若直接丢给 isInstallable 探测，会被记成「本版本不存在」，诊断面板上看着像缺功能。
        val (gated, ungated) = specs.partition { !it.gate() }
        for (spec in gated) {
            HookInstallRegistry.record(
                spec.id,
                spec.name,
                HookInstallRegistry.State.SKIPPED_GATED
            )
        }
        val (ok, skipped) = ungated.partition { isInstallable(it, loader) }
        if (skipped.isNotEmpty()) {
            // 跳过也要记账：这些点位在本版本上「本来就不存在」，既不算失败，也不能计入已安装数。
            for (spec in skipped) {
                HookInstallRegistry.record(
                    spec.id,
                    spec.name,
                    HookInstallRegistry.State.SKIPPED_UNSUPPORTED
                )
            }
            Logger.alwaysWarn(
                "$sceneTag 本版本不支持的 Hook 点位:" + skipped.joinToString(",") { it.id }
            )
        }
        return ok
    }

    /**
     * 把本进程可见的宿主能力位编码成 `key=1,key=0` 形式（与 [io.github.univers629.millet.ipc.HookCoverage]
     * 的 caps 段同格式），用于跨进程汇总诊断。
     *
     * 单个进程的类加载器只能看到自己的类 —— system_server 看得到 `MiGardService`，
     * 看不到 `com.miui.powerkeeper.*`；powerkeeper 进程相反。两侧各报一份，
     * 由 App 侧按「任一进程为真即可用」合并。
     */
    fun buildCapabilityWireFormat(loader: ClassLoader?): String {
        val installed = HookInstallRegistry.snapshot()
            .filter { it.state == HookInstallRegistry.State.INSTALLED }
            .map { it.id }.toSet()
        val hasFrozenCtrl = hasClass(CLASS_FROZEN_APP_CONTROLLER, loader)
        val hasKillCtrl = hasClass(CLASS_KILL_PROCESS_CONTROLLER, loader)
        val hasActiveCtrl = hasClass(CLASS_ACTIVE_STATE_CONTROLLER, loader)
        val capabilities = capabilityMap(loader) + mapOf(
            "millet_registered" to installed.containsAll(setOf("P2", "P9", "P10", "P17")),
            "doze_obs" to installed.containsAll(DOZE_OBSERVATION_HOOKS),
            "controller_registered" to resolveControllerRegistered(
                installed = installed,
                hasFrozenController = hasFrozenCtrl,
                hasKillController = hasKillCtrl,
                hasActiveStateController = hasActiveCtrl,
            )
        )
        return capabilities.entries.joinToString(",") { (key, value) ->
            "$key=${if (value) 1 else 0}"
        }
    }

    /**
     * 按宿主实际存在的控制器类判定 PowerKeeper 新架构（OS3/OS4）是否完成注册：
     * - OS3 仍保留 `FrozenAppController`：要求 `P23_FROZEN_DECISION` 已安装；
     * - OS4 移除了 `FrozenAppController` 与 `PowerCheckerController`，冻结决策全部收归系统侧
     *   `GreezeManagerService`/`ListAppsManager`，PowerKeeper 侧由 `KillProcessController` 与
     *   `ActiveStateController` 承担：要求 `P22_KILL_POLICY`（及存在时的 `P28_NO_RESTRICT`）已安装。
     */
    internal fun resolveControllerRegistered(
        installed: Set<String>,
        hasFrozenController: Boolean,
        hasKillController: Boolean,
        hasActiveStateController: Boolean,
    ): Boolean = when {
        hasFrozenController -> "P23_FROZEN_DECISION" in installed
        hasKillController -> "P22_KILL_POLICY" in installed &&
            (!hasActiveStateController || "P28_NO_RESTRICT" in installed)
        else -> false
    }

    /** 本进程可见的能力位；键名见 [io.github.univers629.millet.ipc.HookCoverage.CAP_MILLET] 等。 */
    fun capabilityMap(loader: ClassLoader?): Map<String, Boolean> = linkedMapOf(
        "millet" to hasClass(CLASS_MILLET_POLICY, loader),
        "controller" to hasClass(CLASS_KILL_PROCESS_CONTROLLER, loader),
        "frozen_ctrl" to hasClass(CLASS_FROZEN_APP_CONTROLLER, loader),
        "migard" to hasClass(CLASS_MIGARD_SERVICE, loader),
        "bwa" to hasField(CLASS_MILLET_POLICY, "BRIGHT_WHITE_APPS", loader)
    )

    /** 探测宿主的版本与关键能力，供启动日志输出。 */
    fun describeHost(context: Context?, loader: ClassLoader?): String {
        val sb = StringBuilder("宿主能力: ")
        sb.append("SDK=").append(VersionGate.sdkInt)
        val rom = VersionGate.miOsVersionName.takeIf { it.isNotBlank() }
            ?: VersionGate.miuiVersionName.takeIf { it.isNotBlank() }
            ?: "unknown"
        sb.append(", ROM=").append(rom)
        if (context != null) {
            val version = runCatching {
                val info = context.packageManager.getPackageInfo(POWERKEEPER_PACKAGE, 0)
                "${info.versionName}(${info.longVersionCode})"
            }.getOrNull()
            sb.append(", PowerKeeper=").append(version ?: "unknown")
        }
        sb.append(", BRIGHT_WHITE_APPS=").append(
            hasField(CLASS_MILLET_POLICY, "BRIGHT_WHITE_APPS", loader)
        )
        // 澎湃 3.0 起删掉了 millet.* 查杀链路，OS4 进一步移除 FrozenAppController / PowerCheckerController，
        // 把关键控制器能力写进启动日志，便于判断当前 ROM 走的是哪一套决策入口。
        sb.append(", KillProcessController=").append(hasClass(CLASS_KILL_PROCESS_CONTROLLER, loader))
        sb.append(", FrozenAppController=").append(hasClass(CLASS_FROZEN_APP_CONTROLLER, loader))
        sb.append(", MiGard=").append(hasClass(CLASS_MIGARD_SERVICE, loader))
        return sb.toString()
    }

    private const val CLASS_MILLET_POLICY = "com.miui.powerkeeper.millet.MilletPolicy"
    private const val CLASS_KILL_PROCESS_CONTROLLER =
        "com.miui.powerkeeper.controller.KillProcessController"
    private const val CLASS_FROZEN_APP_CONTROLLER =
        "com.miui.powerkeeper.controller.FrozenAppController"
    private const val CLASS_ACTIVE_STATE_CONTROLLER =
        "com.miui.powerkeeper.controller.ActiveStateController"
    private const val CLASS_MIGARD_SERVICE = "com.miui.server.migard.MiGardService"

    /** 深度睡眠统计依赖的观测点位：任一缺失都会让会话或维护次数漏记。 */
    internal val DOZE_OBSERVATION_HOOKS = setOf(
        "S14",
        "S38_DOZE_BECOME_ACTIVE",
        "S39_DOZE_STEP_IDLE",
        "S40_DOZE_EVENT"
    )
}
