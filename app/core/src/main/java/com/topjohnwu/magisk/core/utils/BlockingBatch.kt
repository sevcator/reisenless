package com.topjohnwu.magisk.core.utils

import java.util.ArrayDeque

class BlockingBatch<T>(
    private val capacity: Int,
    private val schedule: (() -> Unit) -> Unit,
    private val consume: (List<T>) -> Unit,
) {
    private val monitor = Object()
    private val queue = ArrayDeque<T>()
    private var scheduled = false

    init { require(capacity > 0) }

    fun put(value: T) {
        val post = synchronized(monitor) {
            while (queue.size >= capacity) monitor.wait()
            queue.addLast(value)
            (!scheduled).also { scheduled = true }
        }
        if (post) schedule(::drain)
    }

    fun drain() {
        val values = synchronized(monitor) {
            val values = queue.toList()
            queue.clear()
            scheduled = false
            monitor.notifyAll()
            values
        }
        if (values.isNotEmpty()) consume(values)
    }
}
