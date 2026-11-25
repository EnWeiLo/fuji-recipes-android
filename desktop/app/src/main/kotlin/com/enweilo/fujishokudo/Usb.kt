package com.enweilo.fujishokudo

import com.enweilo.fujirecipes.camera.FUJI_VID
import com.enweilo.fujirecipes.camera.PtpCam
import com.enweilo.fujirecipes.camera.PtpException
import com.enweilo.fujirecipes.camera.PtpResponse
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The few libusb-1.0 calls we need (libusb-1.0.dll ships with the app). */
@Suppress("FunctionName")
interface LibUsb : Library {
    fun libusb_init(ctx: PointerByReference): Int
    fun libusb_exit(ctx: Pointer?)
    fun libusb_get_device_list(ctx: Pointer?, list: PointerByReference): Long
    fun libusb_free_device_list(list: Pointer, unrefDevices: Int)
    fun libusb_get_device_descriptor(dev: Pointer, desc: ByteArray): Int
    fun libusb_open(dev: Pointer, handle: PointerByReference): Int
    fun libusb_close(handle: Pointer)
    fun libusb_claim_interface(handle: Pointer, iface: Int): Int
    fun libusb_release_interface(handle: Pointer, iface: Int): Int
    fun libusb_control_transfer(
        handle: Pointer, requestType: Byte, request: Byte, value: Short, index: Short,
        data: ByteArray, length: Short, timeout: Int,
    ): Int
    fun libusb_bulk_transfer(handle: Pointer, endpoint: Byte, data: Pointer?, length: Int, transferred: IntByReference, timeout: Int): Int
    fun libusb_error_name(code: Int): String

    companion object {
        val lib: LibUsb? by lazy {
            runCatching {
                System.getProperty("compose.application.resources.dir")?.let { dir ->
                    val prev = System.getProperty("jna.library.path")
                    System.setProperty("jna.library.path", if (prev.isNullOrEmpty()) dir else "$dir${File.pathSeparator}$prev")
                }
                Native.load("libusb-1.0", LibUsb::class.java)
            }.getOrNull()
        }
    }
}

/** What happened when we looked for the camera. */
sealed class UsbFind {
    data object NoLibrary : UsbFind()
    data object NoCamera : UsbFind()
    /** A Fujifilm device is there but Windows' own driver holds it (Zadig not done, or another USB mode). */
    data class NeedsDriver(val pid: Int, val error: String) : UsbFind()
    data class Busy(val error: String) : UsbFind()
    data class Found(val cam: LibusbPtpCam) : UsbFind()
}

object Usb {
    private var ctx: Pointer? = null

    @Synchronized
    private fun context(l: LibUsb): Pointer? {
        if (ctx == null) {
            val ref = PointerByReference()
            if (l.libusb_init(ref) == 0) ctx = ref.value
        }
        return ctx
    }

    /** Looks for a Fujifilm camera and opens it. */
    @Synchronized
    fun find(): UsbFind {
        val l = LibUsb.lib ?: return UsbFind.NoLibrary
        val c = context(l) ?: return UsbFind.NoLibrary
        val listRef = PointerByReference()
        val n = l.libusb_get_device_list(c, listRef)
        if (n <= 0) return UsbFind.NoCamera
        val list = listRef.value
        try {
            for (i in 0 until n.toInt()) {
                val dev = list.getPointer(i.toLong() * Native.POINTER_SIZE) ?: continue
                val desc = ByteArray(18)
                if (l.libusb_get_device_descriptor(dev, desc) != 0) continue
                val vid = (desc[8].toInt() and 0xFF) or ((desc[9].toInt() and 0xFF) shl 8)
                val pid = (desc[10].toInt() and 0xFF) or ((desc[11].toInt() and 0xFF) shl 8)
                if (vid != FUJI_VID) continue
                val hRef = PointerByReference()
                val rc = l.libusb_open(dev, hRef)
                if (rc != 0) {
                    val name = l.libusb_error_name(rc)
                    return if (name.contains("NOT_SUPPORTED") || name.contains("ACCESS") || name.contains("NOT_FOUND")) {
                        UsbFind.NeedsDriver(pid, name)
                    } else {
                        UsbFind.Busy(name)
                    }
                }
                val h = hRef.value
                val eps = endpoints(l, h)
                if (eps == null) { l.libusb_close(h); return UsbFind.Busy("找不到資料通道") }
                val claim = l.libusb_claim_interface(h, eps.iface)
                if (claim != 0) { l.libusb_close(h); return UsbFind.Busy(l.libusb_error_name(claim)) }
                return UsbFind.Found(LibusbPtpCam(l, h, eps.iface, eps.epIn, eps.epOut, eps.maxPacket, pid))
            }
            return UsbFind.NoCamera
        } finally {
            l.libusb_free_device_list(list, 1)
        }
    }

    private class Eps(val iface: Int, val epIn: Int, val epOut: Int, val maxPacket: Int)

    /** Reads the raw configuration descriptor and picks the interface with a bulk in and a bulk out endpoint. */
    private fun endpoints(l: LibUsb, h: Pointer): Eps? {
        val buf = ByteArray(1024)
        val n = l.libusb_control_transfer(h, 0x80.toByte(), 6, (2 shl 8).toShort(), 0, buf, buf.size.toShort(), 2000)
        if (n < 9) return null
        var i = 0
        var iface = -1
        var epIn = 0
        var epOut = 0
        var mp = 512
        var best: Eps? = null
        while (i + 2 <= n) {
            val len = buf[i].toInt() and 0xFF
            if (len < 2) break
            when (buf[i + 1].toInt() and 0xFF) {
                4 -> {
                    if (iface >= 0 && epIn != 0 && epOut != 0 && best == null) best = Eps(iface, epIn, epOut, mp)
                    iface = buf[i + 2].toInt() and 0xFF
                    epIn = 0; epOut = 0
                }
                5 -> if (i + 6 < n) {
                    val addr = buf[i + 2].toInt() and 0xFF
                    val attr = buf[i + 3].toInt() and 3
                    val size = (buf[i + 4].toInt() and 0xFF) or ((buf[i + 5].toInt() and 0xFF) shl 8)
                    if (attr == 2) {
                        if (addr and 0x80 != 0) epIn = addr else { epOut = addr; mp = if (size > 0) size else 512 }
                    }
                }
            }
            i += len
        }
        if (best == null && iface >= 0 && epIn != 0 && epOut != 0) best = Eps(iface, epIn, epOut, mp)
        return best
    }
}

/** PTP over libusb: the same camera conversation as the Android app, on a WinUSB-driven camera. */
class LibusbPtpCam(
    private val l: LibUsb,
    private var h: Pointer?,
    private val iface: Int,
    private val epIn: Int,
    private val epOut: Int,
    private val maxPacket: Int,
    val pid: Int,
) : PtpCam("FUJIFILM") {
    private var tid = 0
    private val chunk = 1024 * 1024
    private val outBuf = Memory(chunk.toLong())
    private val inBuf = Memory(chunk.toLong())
    private var leftover = ByteArray(0)

    private fun handle() = h ?: throw PtpException("相機已中斷")

    private fun write(bytes: ByteArray, onProgress: ((Long, Long) -> Unit)?) {
        var off = 0
        val tr = IntByReference()
        while (off < bytes.size) {
            val n = minOf(chunk, bytes.size - off)
            outBuf.write(0, bytes, off, n)
            val rc = l.libusb_bulk_transfer(handle(), epOut.toByte(), outBuf, n, tr, 30_000)
            if (rc != 0) throw PtpException("USB 寫入失敗（${l.libusb_error_name(rc)}）")
            off += tr.value
            onProgress?.invoke(off.toLong(), bytes.size.toLong())
        }
        if (bytes.isNotEmpty() && bytes.size % maxPacket == 0) l.libusb_bulk_transfer(handle(), epOut.toByte(), null, 0, tr, 5_000)
    }

    private fun readSome(want: Int, timeout: Int): ByteArray {
        if (leftover.isNotEmpty()) { val x = leftover; leftover = ByteArray(0); return x }
        val size = (((maxOf(want, maxPacket) + maxPacket - 1) / maxPacket) * maxPacket).coerceAtMost(chunk)
        val tr = IntByReference()
        val rc = l.libusb_bulk_transfer(handle(), epIn.toByte(), inBuf, size, tr, timeout)
        if (rc != 0 && tr.value == 0) throw PtpException("USB 讀取失敗（${l.libusb_error_name(rc)}）")
        return inBuf.getByteArray(0, tr.value)
    }

    private class Container(val type: Int, val code: Int, val payload: ByteArray)

    private fun readContainer(timeout: Int, onProgress: ((Long, Long) -> Unit)?): Container {
        var head = ByteArray(0)
        while (head.size < 12) head += readSome(512, timeout)
        val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val total = bb.getInt(0).toLong() and 0xFFFFFFFFL
        if (total < 12 || total > 400L * 1024 * 1024) throw PtpException("PTP 長度異常 $total")
        val acc = ByteArray(total.toInt())
        var got = minOf(head.size, total.toInt())
        System.arraycopy(head, 0, acc, 0, got)
        if (head.size > total) leftover = head.copyOfRange(total.toInt(), head.size)
        while (got < total) {
            val piece = readSome((total - got).toInt(), timeout)
            val n = minOf(piece.size, (total - got).toInt())
            System.arraycopy(piece, 0, acc, got, n)
            got += n
            if (piece.size > n) leftover = piece.copyOfRange(n, piece.size)
            onProgress?.invoke(got.toLong(), total)
        }
        val b2 = ByteBuffer.wrap(acc).order(ByteOrder.LITTLE_ENDIAN)
        return Container(b2.getShort(4).toInt() and 0xFFFF, b2.getShort(6).toInt() and 0xFFFF, acc.copyOfRange(12, acc.size))
    }

    private fun header(length: Long, type: Int, code: Int, t: Int, params: IntArray): ByteArray {
        val bb = ByteBuffer.allocate(12 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(length.toInt()); bb.putShort(type.toShort()); bb.putShort(code.toShort()); bb.putInt(t)
        params.forEach { bb.putInt(it) }
        return bb.array()
    }

    @Synchronized
    override fun cmd(
        opcode: Int,
        params: IntArray,
        dataOut: ByteArray?,
        waitMs: Long,
        onProgress: ((Long, Long) -> Unit)?,
    ): PtpResponse {
        val t = ++tid
        write(header(12L + params.size * 4, 1, opcode, t, params), null)
        if (dataOut != null) {
            val all = header(12L + dataOut.size, 2, opcode, t, IntArray(0)) + dataOut
            write(all, onProgress?.let { p -> { d, tot -> p(maxOf(0, d - 12), tot - 12) } })
        }
        var data = ByteArray(0)
        val timeout = maxOf(waitMs, 3000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        for (i in 0 until 8) {
            val c = readContainer(timeout, if (dataOut == null) onProgress else null)
            if (c.type == 2) data = c.payload
            if (c.type == 3) return PtpResponse(c.code, data)
        }
        throw PtpException("相機沒有回應")
    }

    override fun closeTransport() {
        val x = h ?: return
        h = null
        runCatching { l.libusb_release_interface(x, iface) }
        runCatching { l.libusb_close(x) }
    }
}
