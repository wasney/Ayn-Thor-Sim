package com.aynthor.simulator.service

import android.view.KeyEvent
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Pure-Kotlin Linux /dev/uhid virtual HID gamepad (no root, no NDK required; runs under shell UID 2000).
 * Sends the entire gamepad state (sticks, triggers, D-pad hat, 16 buttons) in one atomic 13-byte HID report.
 */
class UhidGamepad(private val logger: (String) -> Unit) {

    companion object {
        private const val UHID_DESTROY = 1
        private const val UHID_GET_REPORT = 9
        private const val UHID_GET_REPORT_REPLY = 10
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val UHID_SET_REPORT = 13
        private const val UHID_SET_REPORT_REPLY = 14
        private const val UHID_EVENT_SIZE = 4376

        // Standard HID Gamepad Report Descriptor (4x 16-bit axes, 2x 8-bit triggers, 4-bit hat + 4-bit pad, 16 buttons)
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

            // R2 (Accelerator 0xC4) and L2 (Brake 0xC5) -> 8-bit unsigned (0..255)
            0x05.toByte(), 0x02.toByte(),       //   Usage Page (Simulation Controls)
            0x09.toByte(), 0xC4.toByte(),       //   Usage (Accelerator -> AXIS_RTRIGGER)
            0x09.toByte(), 0xC5.toByte(),       //   Usage (Brake -> AXIS_LTRIGGER)
            0x15.toByte(), 0x00.toByte(),       //   Logical Min (0)
            0x26.toByte(), 0xFF.toByte(), 0x00.toByte(), // Logical Max (255)
            0x75.toByte(), 0x08.toByte(),       //   Report Size (8)
            0x95.toByte(), 0x02.toByte(),       //   Report Count (2)
            0x81.toByte(), 0x02.toByte(),       //   Input (Data, Var, Abs)

            // Hat Switch (D-Pad: 1..8, 0 = null/centered) + 4-bit padding
            0x05.toByte(), 0x01.toByte(),       //   Usage Page (Generic Desktop)
            0x09.toByte(), 0x39.toByte(),       //   Usage (Hat switch)
            0x15.toByte(), 0x01.toByte(),       //   Logical Min (1)
            0x25.toByte(), 0x08.toByte(),       //   Logical Max (8)
            0x35.toByte(), 0x00.toByte(),       //   Physical Min (0)
            0x46.toByte(), 0x3B.toByte(), 0x01.toByte(), // Physical Max (315)
            0x65.toByte(), 0x14.toByte(),       //   Unit (Degrees)
            0x75.toByte(), 0x04.toByte(),       //   Report Size (4)
            0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
            0x81.toByte(), 0x42.toByte(),       //   Input (Data, Var, Abs, Null)
            0x75.toByte(), 0x04.toByte(),       //   Report Size (4)
            0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
            0x81.toByte(), 0x03.toByte(),       //   Input (Const, Var, Abs)

            // 16 Buttons (A=1, B=2, C=3, X=4, Y=5, Z=6, L1=7, R1=8, L2=9, R2=10, Select=11, Start=12)
            0x05.toByte(), 0x09.toByte(),       //   Usage Page (Button)
            0x19.toByte(), 0x01.toByte(),       //   Usage Min (1)
            0x29.toByte(), 0x10.toByte(),       //   Usage Max (16)
            0x15.toByte(), 0x00.toByte(),       //   Logical Min (0)
            0x25.toByte(), 0x01.toByte(),       //   Logical Max (1)
            0x75.toByte(), 0x01.toByte(),       //   Report Size (1)
            0x95.toByte(), 0x10.toByte(),       //   Report Count (16)
            0x81.toByte(), 0x02.toByte(),       //   Input (Data, Var, Abs)

            0xC0.toByte()                       // End Collection
        )
    }

    private var file: RandomAccessFile? = null
    @Volatile var isOpen: Boolean = false
        private set

    private var buttonMask: Int = 0
    private var dpadUp = false
    private var dpadDown = false
    private var dpadLeft = false
    private var dpadRight = false
    private var axisX: Short = 0
    private var axisY: Short = 0
    private var axisZ: Short = 0
    private var axisRz: Short = 0
    private var lTrigger: Int = 0
    private var rTrigger: Int = 0

    fun open(): Boolean {
        if (isOpen) return true
        return try {
            val raf = RandomAccessFile("/dev/uhid", "rw")
            file = raf
            sendCreate2(raf)
            isOpen = true
            startEventReader(raf)
            logger("UHID virtual gamepad created at /dev/uhid")
            true
        } catch (e: Throwable) {
            logger("UHID open failed (${e.javaClass.simpleName}: ${e.message}); using InputManager fallback")
            close()
            false
        }
    }

    private fun sendCreate2(raf: RandomAccessFile) {
        val buf = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, UHID_CREATE2)
        val nameBytes = "AYN Thor Virtual Gamepad".toByteArray(Charsets.UTF_8)
        buf.position(4)
        buf.put(nameBytes, 0, minOf(nameBytes.size, 127))
        // phys (132..195), uniq (196..259) left zeroed
        buf.putShort(260, REPORT_DESCRIPTOR.size.toShort())
        buf.putShort(262, 0x0003.toShort()) // BUS_USB
        buf.putInt(264, 0x045E)             // Microsoft VID
        buf.putInt(268, 0x02FD)             // Xbox One S PID
        buf.putInt(272, 0x0100)             // version
        buf.putInt(276, 0)                  // country
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
                        reply.putShort(8, 0) // err = 0
                        reply.putShort(10, 13)
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
    fun updateKey(keyCode: Int, isDown: Boolean) {
        if (!isOpen) return
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> dpadUp = isDown
            KeyEvent.KEYCODE_DPAD_DOWN -> dpadDown = isDown
            KeyEvent.KEYCODE_DPAD_LEFT -> dpadLeft = isDown
            KeyEvent.KEYCODE_DPAD_RIGHT -> dpadRight = isDown
            KeyEvent.KEYCODE_BUTTON_A -> setBit(0, isDown)
            KeyEvent.KEYCODE_BUTTON_B -> setBit(1, isDown)
            KeyEvent.KEYCODE_BUTTON_X -> setBit(3, isDown)
            KeyEvent.KEYCODE_BUTTON_Y -> setBit(4, isDown)
            KeyEvent.KEYCODE_BUTTON_L1 -> setBit(6, isDown)
            KeyEvent.KEYCODE_BUTTON_R1 -> setBit(7, isDown)
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                setBit(8, isDown)
                lTrigger = if (isDown) 255 else 0
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                setBit(9, isDown)
                rTrigger = if (isDown) 255 else 0
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> setBit(10, isDown)
            KeyEvent.KEYCODE_BUTTON_START -> setBit(11, isDown)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> setBit(13, isDown)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> setBit(14, isDown)
        }
        sendInputReport()
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

    private fun setBit(bit: Int, down: Boolean) {
        buttonMask = if (down) buttonMask or (1 shl bit) else buttonMask and (1 shl bit).inv()
    }

    private fun computeHat(): Int {
        return when {
            dpadUp && dpadRight -> 2
            dpadRight && dpadDown -> 4
            dpadDown && dpadLeft -> 6
            dpadLeft && dpadUp -> 8
            dpadUp -> 1
            dpadRight -> 3
            dpadDown -> 5
            dpadLeft -> 7
            else -> 0
        }
    }

    private fun buildReport(): ByteArray {
        val report = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        report.putShort(axisX)
        report.putShort(axisY)
        report.putShort(axisZ)
        report.putShort(axisRz)
        report.put(rTrigger.toByte())
        report.put(lTrigger.toByte())
        report.put(computeHat().toByte())
        report.putShort(buttonMask.toShort())
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
