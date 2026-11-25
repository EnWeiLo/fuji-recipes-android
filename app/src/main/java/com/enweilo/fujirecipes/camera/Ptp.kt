package com.enweilo.fujirecipes.camera

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

private class Container(val length: Int, val type: Int, val code: Int, val tid: Int, val payload: ByteArray)

/**
 * Talks PTP (Picture Transfer Protocol) to a camera over the USB bulk endpoints.
 * This is a direct port of the PtpCam class of the web version, which used WebUSB.
 * All calls block, so run them off the main thread.
 */
class UsbPtpCam(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    fallbackModel: String,
) : PtpCam(fallbackModel) {
    private var tid = 0
    private val packetSize = maxOf(64, epIn.maxPacketSize)

    // ---------------- low-level transfers ----------------

    /** Big writes go in chunks that are whole USB packets, so the camera only sees the end at the real end. */
    private val outChunk: Int = run {
        val mp = maxOf(64, epOut.maxPacketSize)
        val cap = if (android.os.Build.VERSION.SDK_INT >= 28) 256 * 1024 else 16 * 1024
        (cap / mp) * mp
    }

    private fun transferOut(bytes: ByteArray, onProgress: ((Long, Long) -> Unit)? = null) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(outChunk, bytes.size - offset)
            var written = connection.bulkTransfer(epOut, bytes, offset, n, TIMEOUT_MS)
            if (written < 0) {
                // one retry: the camera may still be busy with the previous chunk
                SystemClock.sleep(50)
                written = connection.bulkTransfer(epOut, bytes, offset, n, TIMEOUT_MS)
            }
            if (written <= 0) throw PtpException("USB 寫入失敗")
            offset += written
            onProgress?.invoke(offset.toLong(), bytes.size.toLong())
        }
        // A transfer that ends exactly on a packet boundary must be closed by an empty packet.
        if (bytes.isNotEmpty() && bytes.size % epOut.maxPacketSize == 0) {
            connection.bulkTransfer(epOut, ByteArray(0), 0, 0, TIMEOUT_MS)
        }
    }

    /** A data container for a big payload, without copying the payload: the header rides in the first chunk. */
    private fun sendBigData(opcode: Int, t: Int, payload: ByteArray, onProgress: ((Long, Long) -> Unit)?) {
        val total = 12L + payload.size
        val first = minOf(outChunk, total.toInt())
        val buf = ByteArray(first)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(total.toInt()); bb.putShort(2); bb.putShort(opcode.toShort()); bb.putInt(t)
        System.arraycopy(payload, 0, buf, 12, first - 12)
        fun write(b: ByteArray, off: Int, n: Int) {
            var w = connection.bulkTransfer(epOut, b, off, n, TIMEOUT_MS)
            if (w < 0) { SystemClock.sleep(50); w = connection.bulkTransfer(epOut, b, off, n, TIMEOUT_MS) }
            if (w != n) throw PtpException("USB 寫入失敗")
        }
        write(buf, 0, first)
        var off = first - 12
        onProgress?.invoke(off.toLong(), payload.size.toLong())
        while (off < payload.size) {
            val n = minOf(outChunk, payload.size - off)
            write(payload, off, n)
            off += n
            onProgress?.invoke(off.toLong(), payload.size.toLong())
        }
        if (total % epOut.maxPacketSize == 0L) connection.bulkTransfer(epOut, ByteArray(0), 0, 0, TIMEOUT_MS)
    }

    private var failedReads = 0

    /** bytes read past the end of a container (kept for the next one) */
    private var leftover = ByteArray(0)

    private fun readSome(want: Int = packetSize): ByteArray {
        if (leftover.isNotEmpty()) {
            val l = leftover
            leftover = ByteArray(0)
            return l
        }
        val size = ((maxOf(want, packetSize) + packetSize - 1) / packetSize * packetSize).coerceAtMost(outChunk.coerceAtLeast(packetSize))
        val buffer = ByteArray(size)
        val n = connection.bulkTransfer(epIn, buffer, buffer.size, READ_TIMEOUT_MS)
        if (n < 0) {
            // Android reports timeouts and stalls the same way; clear a possible stall after repeated failures.
            failedReads++
            if (failedReads == 2) clearHalt(epIn)
            return ByteArray(0)
        }
        failedReads = 0
        return buffer.copyOf(n)
    }

    private fun clearHalt(endpoint: UsbEndpoint) {
        // standard CLEAR_FEATURE(ENDPOINT_HALT) request addressed to the endpoint
        connection.controlTransfer(0x02, 0x01, 0, endpoint.address, null, 0, 1000)
    }

    private fun readContainer(headerWaitMs: Long = 8000, onProgress: ((Long, Long) -> Unit)? = null): Container {
        var head = ByteArray(0)
        var start = SystemClock.elapsedRealtime()
        while (head.size < 12) {
            if (SystemClock.elapsedRealtime() - start > headerWaitMs) throw PtpException("讀取 PTP 標頭逾時")
            head += readSome()
        }
        val header = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val total = header.getInt(0).toLong() and 0xFFFFFFFFL
        if (total < 12 || total > 300L * 1024 * 1024) throw PtpException("PTP 長度異常 $total")
        val acc = ByteArray(total.toInt())
        var got = minOf(head.size, total.toInt())
        System.arraycopy(head, 0, acc, 0, got)
        if (head.size > total) leftover = head.copyOfRange(total.toInt(), head.size)
        start = SystemClock.elapsedRealtime()
        while (got < total) {
            // a big object can take a while; the clock restarts with every piece that arrives
            if (SystemClock.elapsedRealtime() - start > 12000) throw PtpException("讀取 PTP 資料逾時")
            val piece = readSome((total - got).toInt())
            if (piece.isEmpty()) continue
            start = SystemClock.elapsedRealtime()
            val n = minOf(piece.size, (total - got).toInt())
            System.arraycopy(piece, 0, acc, got, n)
            got += n
            if (piece.size > n) leftover = piece.copyOfRange(n, piece.size)
            onProgress?.invoke(got.toLong(), total)
        }
        val bb = ByteBuffer.wrap(acc).order(ByteOrder.LITTLE_ENDIAN)
        return Container(
            length = total.toInt(),
            type = bb.getShort(4).toInt() and 0xFFFF,
            code = bb.getShort(6).toInt() and 0xFFFF,
            tid = bb.getInt(8),
            payload = acc.copyOfRange(12, total.toInt()),
        )
    }

    private fun pack(type: Int, code: Int, tid: Int, params: IntArray = IntArray(0), payload: ByteArray = ByteArray(0)): ByteArray {
        val length = 12 + params.size * 4 + payload.size
        val bb = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(length)
        bb.putShort(type.toShort())
        bb.putShort(code.toShort())
        bb.putInt(tid)
        params.forEach { bb.putInt(it) }
        bb.put(payload)
        return bb.array()
    }

    // ---------------- PTP operations ----------------

    @Synchronized
    override fun cmd(
        opcode: Int,
        params: IntArray,
        dataOut: ByteArray?,
        waitMs: Long,
        onProgress: ((Long, Long) -> Unit)?,
    ): PtpResponse {
        val t = ++tid
        transferOut(pack(1, opcode, t, params))
        if (dataOut != null) {
            if (dataOut.size < 64 * 1024) transferOut(pack(2, opcode, t, IntArray(0), dataOut), onProgress)
            else sendBigData(opcode, t, dataOut, onProgress)
        }
        var data = ByteArray(0)
        var code = 0
        for (i in 0 until 8) {
            val packet = readContainer(waitMs, if (dataOut == null) onProgress else null)
            if (packet.type == 2) data = packet.payload
            if (packet.type == 3) {
                code = packet.code
                break
            }
        }
        return PtpResponse(code, data)
    }

    override fun closeTransport() {
        try { connection.releaseInterface(usbInterface) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }

    private companion object {
        const val TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 3000
    }
}


