package com.enweilo.fujirecipes.camera

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Shared by the Android app and the desktop app: everything about PTP that does not depend on how the bytes
// travel. The transport (Android USB host, libusb on the desktop) subclasses PtpCam.

const val FUJI_VID = 0x04CB
const val PTP_OK = 0x2001

internal object PtpOp {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_PROP = 0x1015
    const val SET_PROP = 0x1016
}

class PtpException(message: String) : IOException(message)

class PtpResponse(val code: Int, val data: ByteArray)

class DeviceInfo(val manufacturer: String, val model: String, val version: String, val serial: String)

/** A PTP camera. All calls block, so run them off the main thread. */
abstract class PtpCam(protected val fallbackModel: String) {
    /** One PTP transaction: command, optional data out, data in, response. */
    abstract fun cmd(
        opcode: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        waitMs: Long = 8000,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): PtpResponse

    /** Lets go of the USB connection. */
    protected abstract fun closeTransport()

    fun openSession(): PtpResponse = cmd(PtpOp.OPEN_SESSION, intArrayOf(1))

    fun close() {
        try { cmd(PtpOp.CLOSE_SESSION) } catch (_: Exception) {}
        try { closeTransport() } catch (_: Exception) {}
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

}

internal fun u16(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

/** PTP strings: one length byte (characters + terminator), then UTF-16LE characters, then a zero. */
internal fun ptpString(s: String): ByteArray {
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

internal class PtpReader(bytes: ByteArray) {
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
