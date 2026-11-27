package io.github.univers629.millet.core

/**
 * 主动进入深睡的最近一次结果，供环境检查页直接显示。
 *
 * 模块每次尝试都会写入 /data/system/XMillet/log；这里在 system_server 内存保留
 * 最近一次的尝试、失败与成功时间戳及失败原因，经由运行状态事务同步到 App 展示。
 *
 * 只在 system_server 进程内有效：模块重启后清零，不影响统计台账。
 */
object DozeDiagnostics {

    @Volatile
    private var attemptMillis: Long = 0L

    @Volatile
    private var failureMillis: Long = 0L

    @Volatile
    private var successMillis: Long = 0L

    @Volatile
    private var failureDetail: String = ""

    fun onAttempt(nowMillis: Long) {
        attemptMillis = nowMillis
    }

    fun onFailure(nowMillis: Long, detail: String) {
        failureMillis = nowMillis
        failureDetail = detail.take(MAX_DETAIL_LENGTH)
    }

    fun onSuccess(nowMillis: Long) {
        successMillis = nowMillis
    }

    fun reset() {
        attemptMillis = 0L
        failureMillis = 0L
        successMillis = 0L
        failureDetail = ""
    }

    fun snapshot(): Snapshot = Snapshot(
        attemptMillis = attemptMillis,
        failureMillis = failureMillis,
        successMillis = successMillis,
        failureDetail = failureDetail
    )

    fun encode(): String = snapshot().encode()

    fun decode(raw: String?): Snapshot? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('|')
        if (parts.size < 4) return null
        return runCatching {
            Snapshot(
                attemptMillis = parts[0].toLong(),
                failureMillis = parts[1].toLong(),
                successMillis = parts[2].toLong(),
                failureDetail = parts.drop(3).joinToString("|")
            )
        }.getOrNull()
    }

    data class Snapshot(
        val attemptMillis: Long,
        val failureMillis: Long,
        val successMillis: Long,
        val failureDetail: String
    ) {
        fun encode(): String =
            "$attemptMillis|$failureMillis|$successMillis|${failureDetail.replace('\n', ' ')}"

        /** 最近一次尝试是否以失败告终（失败时间晚于成功时间或从未成功）。 */
        val lastAttemptFailed: Boolean
            get() = failureMillis > successMillis

        /** 失败是否比最近一次成功更晚，用于决定要不要在界面上提示。 */
        fun failureAgeMillis(nowMillis: Long): Long? =
            if (failureMillis <= 0L) null else (nowMillis - failureMillis).coerceAtLeast(0L)

        /** 「12 分钟前」一类的相对时间；超过一天按天显示。 */
        fun failureAgeText(nowMillis: Long): String? {
            val age = failureAgeMillis(nowMillis) ?: return null
            val minutes = age / 60_000L
            val text = when {
                minutes < 1L -> "刚刚"
                minutes < 60L -> "$minutes 分钟前"
                minutes < 24L * 60L -> "${minutes / 60L} 小时前"
                else -> "${minutes / (24L * 60L)} 天前"
            }
            return text
        }
    }

    private const val MAX_DETAIL_LENGTH = 160
}
