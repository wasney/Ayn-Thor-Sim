package com.aynthor.simulator.service

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Pure-Kotlin Linux /dev/uhid virtual 4-axis HID Joystick (no root, no NDK; runs under shell UID 2000).
 * Provides real kernel MotionRanges for Left Stick (X, Y) and Right Stick (Z, Rz) so native ports/emulators
 * recognize analog sticks smoothly without InputManager deviceId=-1/0 limitations.
 */
class UhidGamepad(private val logger: (String) -> Unit) {

    companion object {
        const val DEVICE_NAME = "AYN Thor Virtual Stick"
        private const val UHID_DESTROY = 1
        private const val UHID_GET_REPORT = 9
        private const val UHID_GET_REPORT_REPLY = 10
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val UHID_SET_REPORT = 13
        private const val UHID_SET_REPORT_REPLY = 14
        private const val UHID_EVENT_SIZE = 4376

        // Minimal 4-axis + 1-button HID Joystick descriptor (Generic.kl compatible)
        private val REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(),       // Usage Page (Generic Desktop)
            0x09.toByte(), 0x05.toByte(),       // Usage (Game Pad)
            0xA1.toByte(), 0x01.toByte(),       // Collection (Application)

            // Left X, Left Y, Right X (Z), Right Y (Rz) -> 16-bit signed (-32767..32767)
            0x05.toByte(), 0x01.toByte(),       //   Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(),       //   Usage (X)
            0x09.toByte(), 0x31.toByte(),       //   Usage (Y)
            0x09.toByte(), 0x32.toByte(),       //   Usage (Z)
            0x09.toByte(), 0x35.toByte(),       //   Usage (Rz)
            0x16.toByte(), 0x01.toByte(), 0x80.toByte(), // Logical Min (-32767)
            0x26.toByte(), 0xFF.toByte(), 0x7F.toByte(), // Logical Max (32767)
            0x75.toByte(), 0x10.toByte(),       //   Report Size (16)
            0x95.toByte(), 0x04.toByte(),       //   Report Count (4)
            0x81.toByte(), 0x02.toByte(),       //   Input (Data, Var, Abs)

            // 1 dummy button + 7-bit padding so Android classifies it as a Joystick/Gamepad
            0x05.toByte(), 0x09.toByte(),       //   Usage Page (Button)
            0x19.toByte(), 0x01.toByte(),       //   Usage Min (1)
            0x29.toByte(), 0x01.toByte(),       //   Usage Max (1)
            0x15.toByte(), 0x00.toByte(),       //   Logical Min (0)
            0x25.toByte(), 0x01.toByte(),       //   Logical Max (1)
            0x75.toByte(), 0x01.toByte(),       //   Report Size (1)
            0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
            0x81.toByte(), 0x02.toByte(),       //   Input (Data, Var, Abs)
            0x75.toByte(), 0x07.toByte(),       //   Report Size (7)
            0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
            0x81.toByte(), 0x03.toByte(),       //   Input (Const, Var, Abs)

            0xC0.toByte()                       // End Collection
        )
    }

    private var file: RandomAccessFile? = null
    @Volatile var isOpen: Boolean = false
        private set

    private var axisX: Short = 0
    private var axisY: Short = 0
    private var axisZ: Short = 0
    private var axisRz: Short = 0

    fun open(): Boolean {
        if (isOpen) return true
        return try {
            val raf = RandomAccessFile("/dev/uhid", "rw")
            file = raf
            sendCreate2(raf)
            isOpen = true
            startEventReader(raf)
            logger("UHID virtual stick created at /dev/uhid")
            true
        } catch (e: Throwable) {
            logger("UHID open failed (${e.javaClass.simpleName}: ${e.message})")
            close()
            false
        }
    }

    private fun sendCreate2(raf: RandomAccessFile) {
        val buf = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, UHID_CREATE2)
        val nameBytes = DEVICE_NAME.toByteArray(Charsets.UTF_8)
        buf.position(4)
        buf.put(nameBytes, 0, minOf(nameBytes.size, 127))
        buf.putShort(260, REPORT_DESCRIPTOR.size.toShort())
        buf.putShort(262, 0x0003.toShort()) // BUS_USB
        buf.putInt(264, 0x1209)             // Generic pid.codes VID (uses Generic.kl)
        buf.putInt(268, 0x0001)             // Generic PID
        buf.putInt(272, 0x0100)
        buf.putInt(276, 0)
        buf.position(280)
        buf.put(REPORT_DESCRIPTOR)
        raf.write(buf.array())
    }

    private fun startEventReader(raf: RandomAccessFile) {
        thread(isDaemon = true, name = "uhid-reader") {
            val readBuf = ByteArray(UHID_EVENT_SIZE)
            while (isOpen) {
                try {
                    val n = raf.read(readBuf)
                    if (n < 4) break
                    val bb = ByteBuffer.wrap(readBuf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                    val type = bb.getInt(0)
                    if (type == UHID_GET_REPORT && n >= 8) {
                        val id = bb.getInt(4)
                        val reply = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                        reply.putInt(0, UHID_GET_REPORT_REPLY)
                        reply.putInt(4, id)
                        reply.putShort(8, 0)
                        reply.putShort(10, 9)
                        reply.position(12)
                        reply.put(buildReport())
                        synchronized(this) { file?.write(reply.array()) }
                    } else if (type == UHID_SET_REPORT && n >= 8) {
                        val id = bb.getInt(4)
                        val reply = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                        reply.putInt(0, UHID_SET_REPORT_REPLY)
                        reply.putInt(4, id)
                        reply.putShort(8, 0)
                        synchronized(this) { file?.write(reply.array()) }
                    }
                } catch (_: Throwable) {
                    break
                }
            }
        }
    }

    @Synchronized
    fun updateAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        if (!isOpen) return
        axisX = (lx.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        axisY = (ly.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        axisZ = (rx.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        axisRz = (ry.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        sendInputReport()
    }

    private fun buildReport(): ByteArray {
        val report = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        report.putShort(axisX)
        report.putShort(axisY)
        report.putShort(axisZ)
        report.putShort(axisRz)
        report.put(0.toByte())
        return report.array()
    }

    private fun sendInputReport() {
        val raf = file ?: return
        try {
            val report = buildReport()
            val buf = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0, UHID_INPUT2)
            buf.putShort(4, report.size.toShort())
            buf.position(6)
            buf.put(report)
            raf.write(buf.array())
        } catch (e: Throwable) {
            logger("UHID write failed: ${e.message}")
            close()
        }
    }

    @Synchronized
    fun close() {
        isOpen = false
        try {
            val raf = file
            if (raf != null) {
                val buf = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(0, UHID_DESTROY)
                raf.write(buf.array())
                raf.close()
            }
        } catch (_: Throwable) {
        } finally {
            file = null
        }
    }
}
