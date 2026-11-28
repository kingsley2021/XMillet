package io.github.univers629.millet.ipc

/**
 * 跨进程 Binder 通道与消息编解码协议定义。
 */
object MilletIpcContract {

    /** 辅助粘性广播 Action（用于在 registerReceiverWithFeature 中握手传递 Binder） */
    const val ACTION_XMILLET_BINDER = "XMillet-Binder"

    /** Intent Extras 中存放 system_server Binder 的键名 */
    const val BINDER_KEY_SYSTEM = "system"

    /** 客户端注册名称常量 */
    const val CLIENT_POWER_KEEPER = "PowerKeeper"
    const val CLIENT_XMILLET_APP = "XMillet"

    /** Settings.System 中 MIUI 无限制应用列表键名 */
    const val SETTINGS_KEY_NO_RESTRICT_APP = "MILLET_NO_RESTRICT_APP"

    /** system_server 侧主服务 AIDL 接口描述符 */
    const val DESCRIPTOR_SYSTEM_SERVICE = "io.github.univers629.millet.SystemInterface"

    /** 客户端（powerkeeper / App）回调 AIDL 接口描述符 */
    const val DESCRIPTOR_CLIENT_CALLBACK = "io.github.univers629.millet.ISystemCallback"

    // ==================== SystemBinderService 事务码 ====================
    const val TX_REGISTER_CLIENT = 1          // 注册客户端回调 Binder（+ 可选：本进程 Hook 覆盖串、能力位串）
    const val TX_UNREGISTER_CLIENT = 2        // 注销客户端回调 Binder
    const val TX_PING_ALIVE = 3               // 存活探测 -> boolean(1)
    const val TX_NOTIFY_PREF_CHANGED = 4      // 配置热更新广播(prefKey)
    const val TX_SET_NO_RESTRICT_APPS = 5     // 上报无限制应用列表(noRestrictAppsStr)
    const val TX_IS_NO_RESTRICT_APP = 6       // 查询是否为无限制应用(packageName) -> boolean(0/1)
    const val TX_CHECK_APP_UPDATE = 7         // 检测模块 APK 更新时间
    const val TX_TRIGGER_INIT_CHECK = 8       // 触发启动期全量配置同步
    const val TX_IS_GREEZE_READY = 9          // 查询 Greeze 冻结服务是否就绪 -> boolean(0/1)
    const val TX_GET_RUNTIME_STATUS = 10      // 查询实时状态：就绪位 + 冻结 UID + frameworkApiVersion + Hook 覆盖
    const val TX_THAW_ALL_FROZEN = 11         // 一键解冻当前所有已冻结 UID -> int(count)
    // 事务码 12、13 已停用，保留空编号以兼容存续事务。
    const val TX_GET_PROCESS_SNAPSHOT = 14    // 查询应用进程快照 -> String（ProcessSnapshotProvider 序列化结果，App 因此不需要 root）
    const val TX_APPLY_BATTERY_POLICY = 15    // 应用省电策略(packageName, noRestrict 0/1) -> int(1=成功)，由 system_server 直接写，免 root
    const val TX_GET_KERNEL_FREEZER = 16      // 内核冻结器类型 -> String("V2"/"V1"/"NONE"/"UNKNOWN")，system_server 探测，免 root
    const val TX_GET_FREEZE_STATS = 17        // 查询墓碑统计快照(windowName) -> String (FreezeStatsCodec v1)
    const val TX_RESET_FREEZE_STATS = 18      // 重置墓碑统计计数与持久文件 -> int(1=成功)
    const val TX_GET_DUAL_APPS = 19           // 查询双开空间 (user 999) 已安装应用清单 -> String (`pkg|uid|flags`)
    const val TX_QUERY_UID_PACKAGES = 20     // 批量查询完整 UID 的成员包名，只读
    const val TX_CLEANUP_RESIDUE = 21        // 卸载前清理残留（释放 Doze、回收旧键、按需还原省电策略、删除 /data/system/XMillet）

    const val MAX_UID_PACKAGES_QUERY = 512
    const val MAX_CLEANUP_BATTERY_REVERTS = 512

    // TX_QUERY_UID_PACKAGES：请求 int[]（完整 UID，去重后最多 512 个）；响应 int 成功项数，
    // 随后逐项写 int UID + StringList 包名。查询失败的 UID 不写入，成功无成员写空列表。

    // ==================== 尾部扩展字段（向下兼容约定） ====================
    /*
     * 旧版 TX_GET_RUNTIME_STATUS 回包以 int frameworkApiVersion 结尾。新版在其后追加：
     *   String coverage（HookCoverage.encode()，同时含覆盖数量、跳过点位与宿主能力位）
     *   String cloudLists（CloudListCodec.encode()，powerkeeper 用官方接口读到的三条云名单）
     * 旧版 TX_REGISTER_CLIENT 只带「回调 Binder + 客户端名」。新版追加：
     *   String clientHooks（HookInstallRegistry.toWireFormat()）
     *   String clientCaps （能力位串，格式见 HookCoverage.parseCapabilities）
     *   String clientCloudLists（CloudListCodec.encode()，只有 powerkeeper 侧会填）
     *
     * 一侧新、一侧旧都可能发生（模块与 App 各自升级），因此收发两端都必须
     * 用 Parcel.dataAvail() 判断剩余长度后再读，缺字段时退化为「未知」而不是 0。
     */

    // ==================== ClientCallback 事务码 ====================
    const val TX_ON_CLIENT_MESSAGE = 1        // mo498(message)

    const val MSG_IS_UPDATE = "isUpdate"
    const val MSG_REBIND_SYSTEM_BINDER = "__xmillet_rebind_system_binder__"
}
