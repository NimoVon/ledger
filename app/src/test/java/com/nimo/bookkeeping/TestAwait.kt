package com.nimo.bookkeeping

import android.os.Looper
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.Shadows.shadowOf

/**
 * Compose 测试里有界等待的唯一实现。
 *
 * 为什么不用 composeRule.waitUntil(10_000)：那个 10 秒是墙钟预算，本机一跑并行
 * （Gradle + 真机脚本）就可能被别的进程吃掉，用例于是假阴性——报"超时"，
 * 其实条件早晚会成立。另外 Room 的写回调、待跑的 recompose 都排在主 Looper 上，
 * 只推 Compose 时钟不会把它们跑掉，所以每轮轮询先 idle 一次主线程。
 *
 * 条件仍然由调用方给（必须是真条件，不是 sleep）：这里只放宽预算、并在每轮推进主线程。
 */
fun ComposeTestRule.awaitUntil(condition: () -> Boolean) {
    try {
        waitUntil(NODE_TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("等待超时（${NODE_TIMEOUT_MS}ms）：条件始终没成立", e)
    }
}

/**
 * 节点/数据等待的墙钟预算。放宽的原因只有一个：这条等待不是被测逻辑的一部分，
 * 用例成败不该取决于跑用例时机器有多忙。
 */
const val NODE_TIMEOUT_MS = 60_000L
