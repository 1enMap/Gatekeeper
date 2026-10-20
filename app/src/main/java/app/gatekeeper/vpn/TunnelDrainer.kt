package app.gatekeeper.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileDescriptor
import java.io.InterruptedIOException

/**
 * Reads packets from the tunnel and throws them away. That's the whole firewall: blocked apps'
 * packets go into the tunnel and never come out.
 *
 * The thread sleeps in poll() on the tunnel plus a wake-up pipe, so it costs nothing while
 * idle and can be stopped instantly without relying on close() interrupting a blocking read.
 */
class TunnelDrainer(tunnel: ParcelFileDescriptor) : Thread("tunnel-drainer") {
    private val tunFd: FileDescriptor = tunnel.fileDescriptor
    private val wakeRead: FileDescriptor
    private val wakeWrite: FileDescriptor

    @Volatile private var stopped = false

    init {
        isDaemon = true
        val pipe = Os.pipe()
        wakeRead = pipe[0]
        wakeWrite = pipe[1]
    }

    override fun run() {
        val buffer = ByteArray(MAX_PACKET)
        val tun = StructPollfd().apply { fd = tunFd; events = OsConstants.POLLIN.toShort() }
        val wake = StructPollfd().apply { fd = wakeRead; events = OsConstants.POLLIN.toShort() }
        val fds = arrayOf(tun, wake)
        val errorMask = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
        try {
            while (!stopped) {
                tun.revents = 0
                wake.revents = 0
                try {
                    Os.poll(fds, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (stopped || wake.revents.toInt() != 0) break
                if (tun.revents.toInt() and errorMask != 0) break
                if (tun.revents.toInt() and OsConstants.POLLIN != 0) drainAvailable(buffer)
            }
        } catch (e: Exception) {
            if (!stopped) Log.w(TAG, "Drainer stopped unexpectedly", e)
        } finally {
            closeQuietly(wakeRead)
        }
    }

    /** Reads until the non-blocking tunnel reports EAGAIN. Packets are discarded. */
    private fun drainAvailable(buffer: ByteArray) {
        while (!stopped) {
            try {
                if (Os.read(tunFd, buffer, 0, buffer.size) <= 0) return
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) return
                if (e.errno == OsConstants.EINTR) continue
                throw e
            } catch (_: InterruptedIOException) {
                return
            }
        }
    }

    /** Stops the thread. Call before closing the tunnel descriptor. */
    fun shutdown() {
        if (stopped) return
        stopped = true
        try {
            Os.write(wakeWrite, byteArrayOf(1), 0, 1)
        } catch (_: Exception) {
        }
        try {
            join(JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
        }
        closeQuietly(wakeWrite)
    }

    private fun closeQuietly(fd: FileDescriptor) {
        try {
            Os.close(fd)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "TunnelDrainer"
        const val MAX_PACKET = 32_767
        const val JOIN_TIMEOUT_MS = 1_000L
    }
}
