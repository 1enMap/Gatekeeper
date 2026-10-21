package app.gatekeeper.monitor

/** Growable FIFO byte buffer: data sent to an app but not yet acknowledged by it. */
class ByteQueue(initial: Int = 16 * 1024) {
    private var data = ByteArray(initial)
    private var start = 0
    var size = 0
        private set

    fun append(src: ByteArray, offset: Int, length: Int) {
        ensure(size + length)
        System.arraycopy(src, offset, data, start + size, length)
        size += length
    }

    /** Copies [length] bytes beginning [from] bytes after the head. */
    fun copy(from: Int, length: Int): ByteArray = data.copyOfRange(start + from, start + from + length)

    fun drop(n: Int) {
        val k = n.coerceIn(0, size)
        start += k
        size -= k
        if (size == 0) start = 0
    }

    private fun ensure(capacity: Int) {
        if (start + capacity <= data.size) return
        val next = if (capacity <= data.size) data else ByteArray(maxOf(capacity, data.size * 2))
        System.arraycopy(data, start, next, 0, size)
        data = next
        start = 0
    }
}
