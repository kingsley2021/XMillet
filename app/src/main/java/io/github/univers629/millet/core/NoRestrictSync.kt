package io.github.univers629.millet.core

import android.content.Context
import android.os.Binder
import android.provider.Settings

/**
 * 旧版 `Settings.System.MILLET_NO_RESTRICT_APP` 免冻同步的兼容回收工具。
 *
 * 当前版本模块白名单已由 `system_server`（S6/S7/S8/S23/S33）与 `PowerKeeper`（P2/P23）在内存内直接强制保护，
 * 不再向 `Settings.System` 持久化写入 `MILLET_NO_RESTRICT_APP` 或 `xmillet_no_restrict_written`。
 * 本对象仅保留纯函数拆分/回收逻辑与一次性的旧版残留迁移清理，平滑清除老版本遗留的系统设置键。
 */
object NoRestrictSync {

    /** 系统侧的免冻结名单键（Settings.System）。 */
    const val SYSTEM_KEY = "MILLET_NO_RESTRICT_APP"

    /** 旧版本模块维护的记账键，用于识别历史版本曾写入 `MILLET_NO_RESTRICT_APP` 的条目。 */
    const val TRACKING_KEY = "xmillet_no_restrict_written"

    private const val SEPARATOR = ", "

    data class ReclaimResult(
        val hadLegacyKey: Boolean,
        val removedCount: Int,
        val preservedCount: Int,
        val keyDeleted: Boolean,
        val deferred: Boolean = false,
    )

    /**
     * 把系统里的原始字符串拆成包名集合，去掉空白与空串。
     */
    fun split(raw: String?): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        if (raw.isNullOrBlank()) {
            return result
        }
        for (token in raw.split(',')) {
            val name = token.trim()
            if (name.isNotEmpty()) {
                result.add(name)
            }
        }
        return result
    }

    /**
     * 读取系统名单当前内容（包名集合）。
     */
    fun readSystemList(context: Context): LinkedHashSet<String> =
        split(readSetting(context, SYSTEM_KEY))

    /**
     * 计算旧版残留回收后的系统名单，纯函数，便于单测验证。
     *
     * @param current 系统 `MILLET_NO_RESTRICT_APP` 当前内容
     * @param writtenByUs 旧版记账键 `xmillet_no_restrict_written` 记录的包名
     * @param keepExplicitNoRestrict 在 PowerKeeper 配置库中明确设为「无限制」的包名，回收时保留
     */
    fun resolveReclaim(
        current: Set<String>,
        writtenByUs: Set<String>,
        keepExplicitNoRestrict: Set<String> = emptySet(),
    ): LinkedHashSet<String> {
        val next = LinkedHashSet(current)
        val candidates = AppUidUtils.policyPackagesForUser(writtenByUs, AppUidUtils.PRIMARY_USER_ID)
        for (pkg in candidates) {
            if (pkg !in keepExplicitNoRestrict) {
                next.remove(pkg)
            }
        }
        return next
    }

    /**
     * 安全版旧版残留回收规划：当 `keepExplicitNoRestrict == null`（例如开机早期 PowerKeeper 配置库
     * 尚未就绪）时返回 `null`，表示应延后迁移，避免误删用户在系统设置里显式设为「无限制」的应用。
     */
    fun resolveReclaimOrDefer(
        current: Set<String>,
        writtenByUs: Set<String>,
        keepExplicitNoRestrict: Set<String>?,
    ): LinkedHashSet<String>? {
        if (keepExplicitNoRestrict == null) return null
        return resolveReclaim(current, writtenByUs, keepExplicitNoRestrict)
    }

    /**
     * 回收旧版写入的 `xmillet_no_restrict_written` 及对应的 `MILLET_NO_RESTRICT_APP` 增量条目，
     * 并删除 `xmillet_no_restrict_written` 记账键。若记账键不存在则立即返回，不产生任何写入；
     * 若 `explicitNoRestrictProvider` 返回 `null`（PowerKeeper 尚未就绪），则延后迁移且不改动任何系统键。
     */
    fun reclaimLegacy(
        context: Context?,
        explicitNoRestrictProvider: (Set<String>) -> Set<String>? = { emptySet() },
    ): ReclaimResult {
        if (context == null) {
            return ReclaimResult(
                hadLegacyKey = false,
                removedCount = 0,
                preservedCount = 0,
                keyDeleted = false,
                deferred = true,
            )
        }
        val token = Binder.clearCallingIdentity()
        return try {
            val cr = context.contentResolver
            val rawTracked = Settings.System.getString(cr, TRACKING_KEY)
                ?: return ReclaimResult(hadLegacyKey = false, removedCount = 0, preservedCount = 0, keyDeleted = false)

            val written = split(rawTracked)
            var removedCount = 0
            var preservedCount = 0
            if (written.isNotEmpty()) {
                val current = split(Settings.System.getString(cr, SYSTEM_KEY))
                val keepExplicit = runCatching { explicitNoRestrictProvider(written) }.getOrNull()
                val next = resolveReclaimOrDefer(current, written, keepExplicit)
                if (next == null) {
                    Logger.alwaysInfo("PowerKeeper 配置库尚未就绪，延后迁移旧版免冻结记账键")
                    return ReclaimResult(
                        hadLegacyKey = true,
                        removedCount = 0,
                        preservedCount = 0,
                        keyDeleted = false,
                        deferred = true,
                    )
                }
                preservedCount = written.count { it in current && it in keepExplicit!! }
                if (next != current) {
                    removedCount = current.size - next.size
                    putSetting(cr, SYSTEM_KEY, next.joinToString(SEPARATOR))
                }
            }

            val keyDeleted = deleteSetting(cr, TRACKING_KEY)
            Logger.alwaysInfo(
                "旧版免冻结记账键已迁移清理: 移除=$removedCount, 保留系统无限制=$preservedCount, 记账键已删=$keyDeleted"
            )
            ReclaimResult(
                hadLegacyKey = true,
                removedCount = removedCount,
                preservedCount = preservedCount,
                keyDeleted = keyDeleted,
            )
        } catch (t: Throwable) {
            Logger.alwaysWarn("迁移清理旧版免冻结记账键失败: ${t.javaClass.simpleName}: ${t.message}")
            ReclaimResult(hadLegacyKey = true, removedCount = 0, preservedCount = 0, keyDeleted = false, deferred = true)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun readSetting(context: Context, key: String): String? = try {
        Settings.System.getString(context.contentResolver, key)
    } catch (t: Throwable) {
        Logger.warn("读取系统设置 $key 失败: ${t.message}")
        null
    }

    private fun putSetting(cr: android.content.ContentResolver, key: String, value: String) {
        check(Settings.System.putString(cr, key, value)) { "Cannot write system setting $key" }
    }

    private fun deleteSetting(cr: android.content.ContentResolver, key: String): Boolean {
        val deleted = runCatching {
            cr.delete(Settings.System.CONTENT_URI, "name=?", arrayOf(key))
        }.getOrDefault(0)
        if (deleted > 0) return true
        return runCatching {
            Settings.System.putString(cr, key, null)
            Settings.System.getString(cr, key) == null
        }.getOrDefault(false)
    }
}
