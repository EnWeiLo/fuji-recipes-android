package com.enweilo.fujirecipes.camera

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val FUJI_VID = 0x04CB
const val PTP_OK = 0x2001

private object PtpOp {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_PROP = 0x1015
    const val SET_PROP = 0x1016
}

class PtpException(message: String) : IOException(message)

class PtpResponse(val code: Int, val data: ByteArray)

class DeviceInfo(val manufacturer: String, val model: String, val version: String, val serial: String)

private class Container(val length: Int, val type: Int, val code: Int, val tid: Int, val payload: ByteArray)

/**
 * Talks PTP (Picture Transfer Protocol) to a camera over the USB bulk endpoints.
 * This is a direct port of the PtpCam class of the web version, which used WebUSB.
 * All calls block, so run them off the main thread.
 */
class PtpCam(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val fallbackModel: String,
) {
    private var tid = 0
    private val packetSize = maxOf(64, epIn.maxPacketSize)

    // ---------------- low-level transfers ----------------

    private fun transferOut(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(512, bytes.size - offset)
            val written = connection.bulkTransfer(epOut, bytes, offset, n, TIMEOUT_MS)
            if (written < 0) throw PtpException("USB 寫入失敗")
            offset += n
        }
        // A transfer that ends exactly on a packet boundary must be closed by an empty packet.
        if (bytes.isNotEmpty() && bytes.size % epOut.maxPacketSize == 0) {
            connection.bulkTransfer(epOut, ByteArray(0), 0, 0, TIMEOUT_MS)
        }
    }

    private var failedReads = 0

    private fun readSome(): ByteArray {
        val buffer = ByteArray(packetSize)
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

    private fun readContainer(): Container {
        var acc = ByteArray(0)
        val start = SystemClock.elapsedRealtime()
        while (acc.size < 12) {
            if (SystemClock.elapsedRealtime() - start > 8000) throw PtpException("讀取 PTP 標頭逾時")
            acc += readSome()
        }
        val header = ByteBuffer.wrap(acc).order(ByteOrder.LITTLE_ENDIAN)
        val total = header.getInt(0).toLong() and 0xFFFFFFFFL
        if (total < 12 || total > 1024 * 1024) throw PtpException("PTP 長度異常 $total")
        while (acc.size < total) {
            if (SystemClock.elapsedRealtime() - start > 12000) throw PtpException("讀取 PTP 資料逾時")
            acc += readSome()
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
    fun cmd(opcode: Int, params: IntArray = IntArray(0), dataOut: ByteArray? = null): PtpResponse {
        val t = ++tid
        transferOut(pack(1, opcode, t, params))
        if (dataOut != null) transferOut(pack(2, opcode, t, IntArray(0), dataOut))
        var data = ByteArray(0)
        var code = 0
        for (i in 0 until 8) {
            val packet = readContainer()
            if (packet.type == 2) data = packet.payload
            if (packet.type == 3) {
                code = packet.code
                break
            }
        }
        return PtpResponse(code, data)
    }

    fun openSession(): PtpResponse = cmd(PtpOp.OPEN_SESSION, intArrayOf(1))

    fun close() {
        try { cmd(PtpOp.CLOSE_SESSION) } catch (_: Exception) {}
        try { connection.releaseInterface(usbInterface) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }

    fun getDeviceInfo(): DeviceInfo {
        val fallback = DeviceInfo("FUJIFILM", fallbackModel, "", "")
        return try {
            val response = cmd(PtpOp.GET_DEVICE_INFO)
            if (response.code != PTP_OK || response.data.isEmpty()) return fallback
            val r = PtpReader(response.data)
            r.u16(); r.u32(); r.u16(); r.string(); r.u16()
            repeat(5) { r.u16Array() }
            DeviceInfo(
                manufacturer = r.string().ifEmpty { fallback.manufacturer },
                model = r.string().ifEmpty { fallback.model },
                version = r.string(),
                serial = r.string(),
            )
        } catch (_: Exception) {
            fallback
        }
    }

    fun getProp(id: Int): ByteArray? {
        val r = cmd(PtpOp.GET_PROP, intArrayOf(id))
        return if (r.code == PTP_OK) r.data else null
    }

    fun getU16(id: Int): Int? {
        val d = getProp(id) ?: return null
        if (d.size < 2) return null
        return (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)
    }

    fun getI16(id: Int): Int? = getU16(id)?.toShort()?.toInt()

    fun setU16(id: Int, value: Int): Boolean =
        cmd(PtpOp.SET_PROP, intArrayOf(id), u16(value)).code == PTP_OK

    fun setI16(id: Int, value: Int): Boolean = setU16(id, value)

    fun setString(id: Int, value: String): Boolean =
        cmd(PtpOp.SET_PROP, intArrayOf(id), ptpString(value)).code == PTP_OK

    private companion object {
        const val TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 3000
    }
}

private fun u16(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

/** PTP strings: one length byte (characters + terminator), then UTF-16LE characters, then a zero. */
private fun ptpString(s: String): ByteArray {
    val n = s.length + 1
    val bb = ByteBuffer.allocate(1 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
    bb.put(n.toByte())
    s.forEach { bb.putShort(it.code.toShort()) }
    return bb.array()
}

fun parsePtpString(data: ByteArray): String {
    if (data.isEmpty()) return ""
    val n = data[0].toInt() and 0xFF
    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val sb = StringBuilder()
    for (i in 0 until maxOf(0, n - 1)) {
        val offset = 1 + i * 2
        if (offset + 1 >= data.size) break
        val c = bb.getShort(offset).toInt() and 0xFFFF
        if (c == 0) break
        sb.append(c.toChar())
    }
    return sb.toString()
}

private class PtpReader(bytes: ByteArray) {
    private val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val size = bytes.size
    private var pos = 0

    private fun left() = size - pos

    fun u16(): Int {
        if (left() < 2) return 0
        val v = bb.getShort(pos).toInt() and 0xFFFF
        pos += 2
        return v
    }

    fun u32(): Long {
        if (left() < 4) return 0
        val v = bb.getInt(pos).toLong() and 0xFFFFFFFFL
        pos += 4
        return v
    }

    fun string(): String {
        if (left() < 1) return ""
        val n = bb.get(pos).toInt() and 0xFF
        pos += 1
        val sb = StringBuilder()
        for (i in 0 until n) {
            if (left() < 2) break
            val c = bb.getShort(pos).toInt() and 0xFFFF
            pos += 2
            if (c != 0) sb.append(c.toChar())
        }
        return sb.toString()
    }

    fun u16Array(): List<Int> {
        val n = u16()
        val out = mutableListOf<Int>()
        var i = 0
        while (i < n && left() >= 2) {
            out += u16()
            i++
        }
        return out
    }
}
