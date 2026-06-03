package com.epcbc.scan

import java.util.concurrent.LinkedBlockingQueue

/**
 * Buffers a high-frequency EPC stream from the SDK callback thread.
 *
 * The Chainway SDK delivers ~100-200 tag callbacks per second when many tags are in the field.
 * We do not want the UI thread or the matching engine to touch each individual callback.
 *
 * Pattern:
 *   - SDK callback calls push(epcHex). This just enqueues — extremely cheap, thread-safe.
 *   - UI/ViewModel layer periodically calls drain() (e.g. once every ~50ms) to get a batch.
 *
 * The queue is bounded at 1024 entries; if it fills, the oldest entries are dropped so new
 * tags always win (we care about WHICH unique EPCs we see, not perfect ordering).
 */
class EpcStream {

    private val queue = LinkedBlockingQueue<String>(1024)

    /** Called from any thread (typically the SDK callback). Never blocks. */
    fun push(epcHex: String) {
        if (!queue.offer(epcHex)) {
            // Buffer is full — drop the oldest, then retry.
            queue.poll()
            queue.offer(epcHex)
        }
    }

    /** Drains all currently-queued EPCs and returns them as a snapshot. */
    fun drain(): List<String> {
        val batch = ArrayList<String>()
        queue.drainTo(batch)
        return batch
    }

    fun pendingCount(): Int = queue.size
}
