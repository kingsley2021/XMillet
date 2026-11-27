package io.github.univers629.millet.ipc

import io.github.univers629.millet.core.HookInstallRegistry

/**
 * 跨进程汇总的 Hook 覆盖情况。
 *
 * 背景：system_server 只装 S* 点位、com.miui.powerkeeper 只装 P* 点位，而 App 进程
 * 既看不到宿主私有类、也无法探测某个点位到底装没装上。因此由两侧各自记账
 * （[HookInstallRegistry]）并在注册时上报，App 只负责把两份账本拼起来展示。
 *
 * 线上格式刻意做成「键值对 + `|` 分段」，并且每个字段都可缺失：
 * 老版本模块不会上报，App 侧必须把「未上报」显示成未知，而不是 0。
 */
data class HookCoverage(
    /** 系统侧（system_server）账本 */
    val sys: HookInstallRegistry.Wire = HookInstallRegistry.Wire(),
    /** 电量与性能侧（com.miui.powerkeeper）账本 */
    val pk: HookInstallRegistry.Wire = HookInstallRegistry.Wire(),
    /** 宿主能力位，见 [CAP_*]；null 表示两侧都没有探测到该位 */
    val capabilities: Map<String, Boolean> = emptyMap()
) {

    /** 两侧合计已安装条数 */
    val installedCount: Int
        get() = sys.installed.coerceAtLeast(0) + pk.installed.coerceAtLeast(0)

    /** 两侧合计规格总数 */
    val totalCount: Int
        get() = sys.total.coerceAtLeast(0) + pk.total.coerceAtLeast(0)

    /** 是否至少有一侧上报了数据 */
    val hasData: Boolean
        get() = sys.hasData || pk.hasData

    /** 本版本不支持而跳过的点位（两侧合并） */
    val unsupportedIds: List<String>
        get() = sys.unsupported + pk.unsupported

    /** 门控不满足而跳过的点位（两侧合并） */
    val gatedIds: List<String>
        get() = sys.gated + pk.gated

    /** 安装失败的点位（两侧合并） */
    val failedIds: List<String>
        get() = sys.failed + pk.failed

    /**
     * 查询一个宿主能力位：true = 确认存在，false = 确认不存在，null = 两侧都没上报。
     *
     * 注意不能只看某一侧：`MiGardService` 只有 system_server 能看见，
     * `com.miui.powerkeeper.*` 只有 powerkeeper 进程能看见。
     */
    fun capability(key: String): Boolean? = capabilities[key]

    fun missingOf(ids: Collection<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val skipped = (unsupportedIds + gatedIds + failedIds).toSet()
        return ids.filter { it in skipped }
    }

    /**
     * 形如 `42/48` 的覆盖比；没有数据时返回 null，调用方据此显示「未知」。
     */
    fun ratioOrNull(): String? =
        if (hasData && totalCount > 0) "$installedCount/$totalCount" else null

    /** 编码成可写进 Parcel 的字符串 */
    fun encode(): String = buildString {
        append("sys=").append(sys.encode())
        append('|').append("pk=").append(pk.encode())
        append('|').append("caps=")
        append(
            capabilities.entries.joinToString(",") { (key, value) ->
                "$key=${if (value) 1 else 0}"
            }
        )
    }

    companion object {

        const val CAP_MILLET = "millet"
        const val CAP_CONTROLLER = "controller"
        const val CAP_FROZEN_CTRL = "frozen_ctrl"
        const val CAP_MIGARD = "migard"
        const val CAP_BWA = "bwa"

        /** 深度睡眠统计的观测点位是否齐备（S14/S38/S39/S40），缺失时统计会漏记。 */
        const val CAP_DOZE_OBS = "doze_obs"

        val EMPTY = HookCoverage()

        /**
         * 合并两组能力位：任一进程探测到「存在」即视为存在。
         *
         * 单进程只能看见自己的类，所以 powerkeeper 报 `migard=0` 并不代表宿主没有
         * MiGard —— 它只是看不见。用「或」合并才能得到整套系统的真实能力。
         */
        fun mergeCapabilities(
            first: Map<String, Boolean>,
            second: Map<String, Boolean>
        ): Map<String, Boolean> {
            if (first.isEmpty()) return second
            if (second.isEmpty()) return first
            val merged = LinkedHashMap<String, Boolean>(first)
            for ((key, value) in second) {
                merged[key] = (merged[key] == true) || value
            }
            return merged
        }

        /** 解码 `millet=1,controller=0,migard=1,bwa=0` 形式的能力位串 */
        fun parseCapabilities(raw: String?): Map<String, Boolean> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split(',')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null
                    else part.substring(0, idx).trim() to (part.substring(idx + 1).trim() == "1")
                }
                .toMap()
        }

        /** 解码 [encode] 的输出；任何畸形输入都退化成 [EMPTY] 而不是抛异常。 */
        fun decode(raw: String?): HookCoverage {
            if (raw.isNullOrBlank()) return EMPTY
            val sections = raw.split('|').mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
            }.toMap()
            return HookCoverage(
                sys = HookInstallRegistry.decodeWireFormat(sections["sys"]),
                pk = HookInstallRegistry.decodeWireFormat(sections["pk"]),
                capabilities = parseCapabilities(sections["caps"])
            )
        }
    }
}

/** [HookInstallRegistry.Wire] 的编码，供 [HookCoverage.encode] 复用。 */
private fun HookInstallRegistry.Wire.encode(): String = buildString {
    append("i=").append(installed)
    append(';').append("t=").append(total)
    append(';').append("u=").append(unsupported.joinToString(","))
    append(';').append("g=").append(gated.joinToString(","))
    append(';').append("f=").append(failed.joinToString(","))
}
