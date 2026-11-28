package com.chengyan.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ask_user 交互桥 —— AI 执行任务遇到关键信息缺失 / 需要用户决策时，
 * 通过 ask_user 工具向用户提问并阻塞等待回答（回答作为工具结果回传模型，任务继续），
 * 而不是瞎猜或直接结束任务。澄言原创协议。
 */
object AskUserBridge {

    data class Req(val id: String, val question: String, val choices: List<String>)

    /** 当前待回答的提问（UI 观察；null=无）。 */
    val pending = MutableStateFlow<Req?>(null)

    @Volatile private var latch: CountDownLatch? = null
    @Volatile private var answer: String? = null

    /**
     * AI 侧调用：展示提问并等待用户回答。
     * 超时（默认 180s）或用户取消返回提示语，模型可自行决定降级路径。
     */
    fun ask(question: String, choices: List<String>, timeoutSec: Long = 180): String {
        if (question.isBlank()) return "错误：question 不能为空"
        val id = UUID.randomUUID().toString()
        answer = null
        val l = CountDownLatch(1)
        latch = l
        pending.value = Req(id, question, choices)
        val got = l.await(timeoutSec, TimeUnit.SECONDS)
        val a = answer
        pending.value = null
        latch = null
        return when {
            got && !a.isNullOrBlank() -> a
            got && a.isNullOrBlank() -> "用户跳过了这个问题（未提供答案）。请基于现有信息继续，或用更简单的方式推进。"
            else -> "等待回答超时（${timeoutSec}s）。请基于现有信息继续推进任务，不要卡死等待。"
        }
    }

    /** UI 侧提交回答。 */
    fun submit(text: String) {
        answer = text
        latch?.countDown()
    }

    /** UI 侧跳过。 */
    fun skip() {
        answer = ""
        latch?.countDown()
    }
}
