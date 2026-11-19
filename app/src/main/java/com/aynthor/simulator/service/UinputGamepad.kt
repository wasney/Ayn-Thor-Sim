package com.aynthor.simulator.service

import android.view.InputDevice
import android.view.KeyEvent
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.concurrent.thread

/**
 * Registers a unified Xbox 360 (0x045e:0x028e) virtual controller using AOSP's built-in
 * `/system/bin/uinput` command under Shizuku (shell UID 2000, no root).
 * Android automatically loads Vendor_045e_Product_028e.kl, giving all games and native ports
 * real analog sticks (ABS_X/Y/RX/RY), triggers (ABS_Z/RZ), D-pad hat, and all buttons on one device.
 */
class UinputGamepad(private val logger: (String) -> Unit) {

    companion object {
        const val DEVICE_NAME = "AYN Thor X360 Pad"

        private const val EV_SYN = 0x00
        private const val SYN_REPORT = 0x00
        private const val EV_KEY = 0x01
        private const val EV_ABS = 0x03

        private const val ABS_X = 0x00
        private const val ABS_Y = 0x01
        private const val ABS_Z = 0x02
        private const val ABS_RX = 0x03
        private const val ABS_RY = 0x04
        private const val ABS_RZ = 0x05
        private const val ABS_HAT0X = 0x10
        private const val ABS_HAT0Y = 0x11

        private const val BTN_A = 0x130
        private const val BTN_B = 0x131
        private const val BTN_X = 0x133
        private const val BTN_Y = 0x134
        private const val BTN_TL = 0x136
        private const val BTN_TR = 0x137
        private const val BTN_SELECT = 0x13A
        private const val BTN_START = 0x13B
        private const val BTN_THUMBL = 0x13D
        private const val BTN_THUMBR = 0x13E
    }

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    @Volatile var isActive: Boolean = false
        private set
    @Volatile private var hasError: Boolean = false
    @Volatile private var cachedDeviceId: Int = -1

    private var dpadU = false
    private var dpadD = false
    private var dpadL = false
    private var dpadR = false
    private var lastHatX = 0
    private var lastHatY = 0

    private fun absEntry(code: Int, min: Int, max: Int): String {
        return """{"code":$code,"info":{"value":0,"minimum":$min,"maximum":$max,"fuzz":0,"flat":0,"resolution":0}}"""
    }

    fun start(): Boolean {
        if (isActive && process?.isAlive == true) return true
        hasError = false
        return try {
            val proc = ProcessBuilder("/system/bin/uinput", "-")
                .redirectErrorStream(true)
                .start()
            process = proc
            val w = BufferedWriter(OutputStreamWriter(proc.outputStream))
            writer = w

            thread(isDaemon = true, name = "uinput-stdout") {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                        while (true) {
                            val line = br.readLine() ?: break
                            logger("uinput: $line")
                            if (line.contains("Error", ignoreCase = true)) {
                                hasError = true
                                isActive = false
        cachedDeviceId = -1
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    isActive = false
                }
            }

            val absArray = listOf(
                absEntry(ABS_X, -32767, 32767),
                absEntry(ABS_Y, -32767, 32767),
                absEntry(ABS_RX, -32767, 32767),
                absEntry(ABS_RY, -32767, 32767),
                absEntry(ABS_Z, 0, 255),
                absEntry(ABS_RZ, 0, 255),
                absEntry(ABS_HAT0X, -1, 1),
                absEntry(ABS_HAT0Y, -1, 1)
            ).joinToString(",")

            val registerJson = """{"id":1,"command":"register","name":"$DEVICE_NAME","vid":1118,"pid":654,"bus":"usb","configuration":[{"type":"UI_SET_EVBIT","data":["EV_KEY","EV_ABS"]},{"type":"UI_SET_KEYBIT","data":[$BTN_A,$BTN_B,$BTN_X,$BTN_Y,$BTN_TL,$BTN_TR,$BTN_SELECT,$BTN_START,$BTN_THUMBL,$BTN_THUMBR]},{"type":"UI_SET_ABSBIT","data":[$ABS_X,$ABS_Y,$ABS_Z,$ABS_RX,$ABS_RY,$ABS_RZ,$ABS_HAT0X,$ABS_HAT0Y]}],"abs_info":[$absArray]}"""

            w.write(registerJson)
            w.newLine()
            w.flush()

            Thread.sleep(300)
            if (proc.isAlive && !hasError) {
                isActive = true
                cachedDeviceId = resolveDeviceId()
                logger("uinput Xbox 360 controller registered ($DEVICE_NAME, deviceId=$cachedDeviceId)")
                true
            } else {
                logger("uinput registration failed (alive=${proc.isAlive}, hasError=$hasError); using InputManager fallback")
                stop()
                false
            }
        } catch (e: Throwable) {
            logger("uinput start failed (${e.javaClass.simpleName}: ${e.message})")
            stop()
            false
        }
    }

    @Synchronized
    fun sendKey(keyCode: Int, isDown: Boolean): Boolean {
        if (!isActive || hasError) return false
        val events = mutableListOf<Int>()

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> dpadU = isDown
            KeyEvent.KEYCODE_DPAD_DOWN -> dpadD = isDown
            KeyEvent.KEYCODE_DPAD_LEFT -> dpadL = isDown
            KeyEvent.KEYCODE_DPAD_RIGHT -> dpadR = isDown
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                events.addAll(listOf(EV_ABS, ABS_Z, if (isDown) 255 else 0))
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                events.addAll(listOf(EV_ABS, ABS_RZ, if (isDown) 255 else 0))
            }
            else -> {
                val btn = when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> BTN_A
                    KeyEvent.KEYCODE_BUTTON_B -> BTN_B
                    KeyEvent.KEYCODE_BUTTON_X -> BTN_X
                    KeyEvent.KEYCODE_BUTTON_Y -> BTN_Y
                    KeyEvent.KEYCODE_BUTTON_L1 -> BTN_TL
                    KeyEvent.KEYCODE_BUTTON_R1 -> BTN_TR
                    KeyEvent.KEYCODE_BUTTON_SELECT -> BTN_SELECT
                    KeyEvent.KEYCODE_BUTTON_START -> BTN_START
                    KeyEvent.KEYCODE_BUTTON_THUMBL -> BTN_THUMBL
                    KeyEvent.KEYCODE_BUTTON_THUMBR -> BTN_THUMBR
                    else -> -1
                }
                if (btn < 0) return false
                events.addAll(listOf(EV_KEY, btn, if (isDown) 1 else 0))
            }
        }

        if (keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
            val hx = (if (dpadR) 1 else 0) - (if (dpadL) 1 else 0)
            val hy = (if (dpadD) 1 else 0) - (if (dpadU) 1 else 0)
            if (hx != lastHatX) {
                lastHatX = hx
                events.addAll(listOf(EV_ABS, ABS_HAT0X, hx))
            }
            if (hy != lastHatY) {
                lastHatY = hy
                events.addAll(listOf(EV_ABS, ABS_HAT0Y, hy))
            }
        }

        if (events.isEmpty()) return true
        events.addAll(listOf(EV_SYN, SYN_REPORT, 0))
        return writeInject(events)
    }

    @Synchronized
    fun sendAxes(lx: Float, ly: Float, rx: Float, ry: Float): Boolean {
        if (!isActive || hasError) return false
        val ix = (lx.coerceIn(-1f, 1f) * 32767f).toInt()
        val iy = (ly.coerceIn(-1f, 1f) * 32767f).toInt()
        val irx = (rx.coerceIn(-1f, 1f) * 32767f).toInt()
        val iry = (ry.coerceIn(-1f, 1f) * 32767f).toInt()

        val events = listOf(
            EV_ABS, ABS_X, ix,
            EV_ABS, ABS_Y, iy,
            EV_ABS, ABS_RX, irx,
            EV_ABS, ABS_RY, iry,
            EV_SYN, SYN_REPORT, 0
        )
        return writeInject(events)
    }

    private fun writeInject(events: List<Int>): Boolean {
        val w = writer ?: return false
        return try {
            val cmd = """{"id":1,"command":"inject","events":[${events.joinToString(",")}]}"""
            w.write(cmd)
            w.newLine()
            w.flush()
            true
        } catch (e: Throwable) {
            logger("uinput write failed: ${e.message}")
            stop()
            false
        }
    }

    fun getDeviceId(): Int {
        if (cachedDeviceId > 0) return cachedDeviceId
        cachedDeviceId = resolveDeviceId()
        return cachedDeviceId
    }

    private fun resolveDeviceId(): Int {
        return try {
            InputDevice.getDeviceIds().firstOrNull { id ->
                InputDevice.getDevice(id)?.name == DEVICE_NAME
            } ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    @Synchronized
    fun stop() {
        isActive = false
        try {
            writer?.close()
        } catch (_: Throwable) {
        } finally {
            writer = null
        }
        try {
            process?.destroy()
        } catch (_: Throwable) {
        } finally {
            process = null
        }
    }
}
