package com.chengyan.app.data

/**
 * 澄言 Linux —— JNI 声明（libchlinux.so，Rust 内核 + ARM64 二进制翻译引擎）。
 *
 * 会话式 API：AI 通过单工具 `linux` 自主编排长程任务——run 启动、poll 轮询、
 * wait 等退出；客户端不内置任何固定编排流程。
 */
internal object LinuxNative {
    init { System.loadLibrary("chlinux") }

    /** 创建会话（busybox 已装载、rootfs = dataDir/rootfs）；返回 machine 句柄，负数=失败。 */
    external fun start(dataDir: String): Long

    /** 在会话里执行一条命令（busybox applet 形式）；返回任务号，-3=忙。 */
    external fun exec(machine: Long, cmd: String): Long

    /** 轮询：返回 JSON {busy, exit?, output}（环形缓冲最新 384KB）。 */
    external fun poll(machine: Long): String?

    /** 释放会话。 */
    external fun stop(machine: Long)
}
