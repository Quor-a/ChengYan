package com.chengyan.app.data

/**
 * 澄言 Linux —— JNI 声明（libchlinux.so，Rust 内核 + ARM64 二进制翻译引擎）。
 *
 * 会话式 API：AI 通过单工具 `linux` 自主编排长程任务——run 启动、poll 轮询、
 * wait 等退出；客户端不内置任何固定编排流程。
 *
 * ## 这里刻意**没有** `init { System.loadLibrary(...) }`
 *
 * 类初始化抛异常后 ART 会把本类永久标记 erroneous，之后每次访问都是
 * `NoClassDefFoundError: com.chengyan.app.data.LinuxNative`，同一进程内无法恢复
 * （2026-11-26 真机事故：一次 dlopen 失败就把整个终端永久打死）。
 * 加载改由 [NativeLib] 负责（可重试、三通路兜底、失败原因全文保留），
 * 所有调用点必须先 `NativeLib.ensure(ctx)`。
 */
internal object LinuxNative {

    /** 创建会话（busybox 已装载、rootfs = dataDir/rootfs）；返回 machine 句柄，负数=失败。 */
    external fun start(dataDir: String): Long

    /** 在会话里执行一条命令（busybox applet 形式）；返回任务号，-3=忙。 */
    external fun exec(machine: Long, cmd: String): Long

    /** 轮询：返回 JSON {busy, exit?, output}（环形缓冲最新 384KB）。 */
    external fun poll(machine: Long): String?

    /** 释放会话。 */
    external fun stop(machine: Long)

    /**
     * 原生侧自检：`abi=... window=ok|failed tier1=... machines=...`
     *
     * `window` 是 Tier1 恒等映射窗口（0x400000..64MB）在**真实 App 进程**里能否预留
     * 成功——这是「原生执行」能否成立的前提，也是 VMM 里测不出来的一项。
     */
    external fun info(): String?
}
