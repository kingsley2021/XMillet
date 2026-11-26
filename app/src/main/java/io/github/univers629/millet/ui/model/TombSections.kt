package io.github.univers629.millet.ui.model

/** 墓碑统计页里可以单独开关的板块。 */
enum class TombSection(val id: String, val label: String) {
    RANK("rank", "应用排行"),
    WAKE_REASONS("wake_reasons", "解冻原因"),
    TIME_DISTRIBUTION("time_distribution", "解冻时间分布"),
    DOZE("doze", "深睡归因"),
    INTERVENTION("intervention", "XMillet 干预"),
    RECENT("recent", "最近系统记录"),
    ;

    companion object {
        val allIds: Set<String> = entries.map { it.id }.toSet()

        /** 未保存过时默认全部显示；只保留已知 id，避免旧数据里的废弃项。 */
        fun parse(raw: Set<String>?): Set<String> {
            if (raw == null) return allIds
            val known = entries.map { it.id }.toSet()
            return raw.filterTo(mutableSetOf()) { it in known }
        }
    }
}
