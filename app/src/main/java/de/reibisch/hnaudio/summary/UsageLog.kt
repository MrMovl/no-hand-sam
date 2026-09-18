package de.reibisch.hnaudio.summary

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/** Running token totals for this app process, logged after every call. */
class UsageLog {
    private val calls = AtomicInteger()
    private val input = AtomicInteger()
    private val output = AtomicInteger()

    fun add(inputTokens: Int, outputTokens: Int) {
        val n = calls.incrementAndGet()
        val i = input.addAndGet(inputTokens)
        val o = output.addAndGet(outputTokens)
        Log.i("TokenUsage", "call $n: +$inputTokens in / +$outputTokens out; session total $i in / $o out")
    }
}
