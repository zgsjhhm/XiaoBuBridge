// XiaoBuBridge v2.0 配置服务 IPC 接口
// 由模块进程的 ConfigService 实现，UI 通过 bindService 连接；
// 后续可扩展为 libxposed-service 通道，供小布进程回调状态。
package com.wanxiang.xiaobubridge;

interface IConfigService {
    /** 读取一项配置（返回字符串形式） */
    String getConfig(String key);

    /** 写入一项配置（按 key 自动转换类型） */
    void setConfig(String key, String value);

    /** 当前监听端口 */
    int getPort();

    /** 服务开关是否启用 */
    boolean isServerEnabled();

    /** 日志级别 */
    String getLogLevel();

    /** 运行时状态快照（JSON 字符串） */
    String getServerStatus();

    /** 连通性探针 */
    void ping();
}
