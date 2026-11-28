package io.github.univers629.millet.core

import android.content.Context

/**
 * 记录经由 XMillet 改动过的系统省电策略原始状态（`packageName -> 修改前是否为无限制`）。
 *
 * 账本保存在 App 私有 `SharedPreferences`（`/data/data/<pkg>/shared_prefs/`），不写入任何系统目录，
 * 卸载应用时由系统自动清除；卸载前执行「清理并还原」时可按原值回滚，不误动用户自行设置的条目。
 */
object BatteryPolicyJournal {

    private const val PREFS_NAME = "xmillet_battery_policy_journal"
    private const val KEY_ENTRIES = "origin_entries"
    private const val MAX_ENTRIES = 512

    fun encode(entries: Map<String, Boolean>): String =
        entries.entries
            .asSequence()
            .filter { (pkg, _) -> AppUidUtils.validPolicyTarget(pkg) && '#' !in pkg && '=' !in pkg && ';' !in pkg }
            .take(MAX_ENTRIES)
            .joinToString(";") { (pkg, orig) -> "$pkg=${if (orig) 1 else 0}" }

    fun decode(raw: String?): LinkedHashMap<String, Boolean> {
        val result = LinkedHashMap<String, Boolean>()
        if (raw.isNullOrBlank()) return result
        for (token in raw.split(';')) {
            val idx = token.indexOf('=')
            if (idx <= 0 || idx >= token.length - 1) continue
            val pkg = token.substring(0, idx).trim()
            val flag = token.substring(idx + 1).trim()
            if (!AppUidUtils.validPolicyTarget(pkg) || '#' in pkg) continue
            val original = when (flag) {
                "1" -> true
                "0" -> false
                else -> continue
            }
            result[pkg] = original
            if (result.size >= MAX_ENTRIES) break
        }
        return result
    }

    /**
     * 纯函数计算单次省电策略变更后的原值账本：
     * - 首次改动某包时记下其变更前原值（仅在状态确有变化时记账）；
     * - 后续把该包切回其原始状态时从账本移除（不再有净改动）。
     */
    fun recordChange(
        current: Map<String, Boolean>,
        packageName: String,
        originalNoRestrict: Boolean,
        newNoRestrict: Boolean,
    ): LinkedHashMap<String, Boolean> {
        val next = LinkedHashMap(current)
        if (!AppUidUtils.validPolicyTarget(packageName) || '#' in packageName) return next
        val existingOrigin = next[packageName]
        if (existingOrigin == null) {
            if (originalNoRestrict != newNoRestrict && next.size < MAX_ENTRIES) {
                next[packageName] = originalNoRestrict
            }
        } else if (newNoRestrict == existingOrigin) {
            next.remove(packageName)
        }
        return next
    }

    fun readAll(context: Context): LinkedHashMap<String, Boolean> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return decode(prefs.getString(KEY_ENTRIES, null))
    }

    fun recordApplied(
        context: Context,
        packageName: String,
        originalNoRestrict: Boolean,
        newNoRestrict: Boolean,
    ) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = decode(prefs.getString(KEY_ENTRIES, null))
            val updated = recordChange(current, packageName, originalNoRestrict, newNoRestrict)
            if (updated != current) {
                prefs.edit().putString(KEY_ENTRIES, encode(updated)).apply()
            }
        }
    }

    fun remove(context: Context, packages: Collection<String>) {
        if (packages.isEmpty()) return
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = decode(prefs.getString(KEY_ENTRIES, null))
            var changed = false
            for (pkg in packages) {
                if (current.remove(pkg) != null) changed = true
            }
            if (changed) {
                prefs.edit().putString(KEY_ENTRIES, encode(current)).apply()
            }
        }
    }

    fun clear(context: Context) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_ENTRIES).apply()
        }
    }
}
