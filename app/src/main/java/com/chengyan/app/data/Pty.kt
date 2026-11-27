package com.chengyan.app.data

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 真 PTY（forkpty）——澄言独立实现。
 *
 * 关键：这不是 ProcessBuilder 的管道，而是 JNI forkpty 出来的真终端：
 * 有 controlling tty，^C 发 SIGINT，isatty=1，行规程由内核完成。
 * 静态 busybox sh 直接作为 PTY 子进程运行，不需要 proot。
 */
class Pty(
    val process: Process,
    val masterFd: FileDescriptor,
    val pid: Int,
) {
    val stdout: InputStream = FileInputStream(masterFd)
    val stdin: OutputStream = FileOutputStream(masterFd)

    fun isAlive(): Boolean = process.isAlive

    /** 温和销毁：SIGHUP 整组（线路规程会话断开），关两端 fd，并回收子进程防 zombie。 */
    fun destroy() {
        runCatching { killGroup(pid, 1) }          // SIGHUP → 进程组（含 Alpine 内 sh）
        process.destroy()                           // 兼容原路径：SIGHUP 直达 proot
        runCatching { Companion.waitPidTimeout(pid, 1500) }
        runCatching { stdout.close() }
        runCatching { stdin.close() }
    }

    /**
     * 强制销毁：SIGKILL 整个进程组 + SIGKILL 本人 + 阻塞等待真死。
     * restart 前必须走这个 —— 旧 proot/ash 没死透就开新会话 = 进程泄漏
     * （真机复现：5 组 proot+sh 常驻，load 9.0）。
     */
    fun destroyForcibly(): Int {
        runCatching { killGroup(pid, 9) }          // SIGKILL → 整组
        runCatching { android.os.Process.killProcess(pid) }
        val rc = Companion.waitPidTimeout(pid, 3000)
        runCatching { stdout.close() }
        runCatching { stdin.close() }
        return rc
    }

    fun waitForExit(timeoutMs: Long): Int = Companion.waitPidTimeout(pid, timeoutMs.toInt())

    companion object {
        init {
            try { System.loadLibrary("pty") } catch (_: UnsatisfiedLinkError) {}
        }

        fun start(command: Array<String>, env: Map<String, String>, workingDir: File): Pty {
            val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()
            val info = createSubprocess(command, envArray, workingDir.absolutePath)
            val childPid = info[0]
            val masterFd = info[1]
            if (childPid <= 0 || masterFd <= 0) {
                throw java.io.IOException("forkpty 失败 pid=$childPid fd=$masterFd")
            }
            val fd = FileDescriptor().apply {
                val f = FileDescriptor::class.java.getDeclaredField("descriptor")
                f.isAccessible = true
                f.set(this, masterFd)
            }
            val p = object : Process() {
                override fun destroy() { android.os.Process.sendSignal(childPid, 1) } // SIGHUP
                override fun exitValue(): Int {
                    try {
                        android.os.Process.sendSignal(childPid, 0) // 探活
                        throw IllegalThreadStateException("still alive")
                    } catch (e: IllegalThreadStateException) { throw e
                    } catch (e: IllegalArgumentException) { return -1
                    } catch (e: Exception) { throw IllegalThreadStateException("unknown") }
                }
                override fun getErrorStream(): InputStream? = null
                override fun getInputStream(): InputStream? = null
                override fun getOutputStream(): OutputStream? = null
                override fun waitFor(): Int = Companion.waitFor(childPid)
            }
            return Pty(p, fd, childPid)
        }

        private external fun createSubprocess(
            cmdArray: Array<String>, envArray: Array<String>, workingDir: String
        ): IntArray
        private external fun waitFor(pid: Int): Int
        private external fun killGroup(pid: Int, sig: Int): Int
        private external fun waitPidTimeout(pid: Int, timeoutMs: Int): Int
    }

    fun setWindowSize(rows: Int, cols: Int): Boolean = setPtyWindowSize(masterFdInt, rows, cols) == 0

    private val masterFdInt: Int get() {
        val f = FileDescriptor::class.java.getDeclaredField("descriptor")
        f.isAccessible = true
        return f.get(masterFd) as Int
    }

    private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int
}
