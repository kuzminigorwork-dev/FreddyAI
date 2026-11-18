package com.freddyai.app

import com.freddyai.EspRomFlasher
import com.freddyai.FirmwarePackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * Запись готовых прошивок из оригинала через имитацию загрузчика ROM ESP32:
 * SLIP-кадры, синхронизация, определение чипа, стирание, запись блоков и сверка MD5.
 */
class FlasherTest {
    private class FakeRom(val chip: Int, val corrupt: Boolean = false) : EspRomFlasher.Link {
        val flash = HashMap<Int, Byte>()
        private val out = ArrayDeque<Int>()
        private val frame = ByteArrayOutputStream(); private var inFrame = false; private var esc = false
        private var base = 0
        var blocks = 0

        override fun write(bytes: ByteArray) {
            for (raw in bytes) {
                val v = raw.toInt() and 255
                if (v == 0xC0) { if (inFrame && frame.size() > 0) handle(frame.toByteArray()); frame.reset(); inFrame = true; continue }
                if (esc) { frame.write(if (v == 0xDC) 0xC0 else 0xDB); esc = false } else if (v == 0xDB) esc = true else frame.write(v)
            }
        }

        override fun readByte(deadlineNanos: Long): Int = if (out.isEmpty()) -1 else out.poll()!!

        private fun word(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

        private fun reply(op: Int, value: Int = 0, data: ByteArray = ByteArray(0), status: Int = 0) {
            val body = data + byteArrayOf(status.toByte(), 0, 0, 0)
            val r = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            r.put(1).put(op.toByte()).putShort(body.size.toShort()).putInt(value).put(body)
            EspRomFlasher.slip(r.array()).forEach { out.add(it.toInt() and 255) }
        }

        private fun handle(p: ByteArray) {
            val op = p[1].toInt() and 255; val len = (p[2].toInt() and 255) or ((p[3].toInt() and 255) shl 8)
            val payload = p.copyOfRange(8, 8 + len)
            when (op) {
                0x08 -> reply(op)
                0x14 -> if (chip == 2) reply(op, data = ByteArray(12)) else reply(op, data = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(0).putInt(0).putInt(chip).putInt(0).array())
                0x0A -> reply(op, value = 0x7c6)
                0x0D, 0x0B -> reply(op)
                0x02 -> { base = word(payload, 12); reply(op) }
                0x03 -> {
                    val seq = word(payload, 4); val block = payload.copyOfRange(16, payload.size)
                    var sum = 0xEF; block.forEach { sum = sum xor (it.toInt() and 255) }
                    if (sum != word(p, 4)) { reply(op, status = 1); return }
                    block.forEachIndexed { i, b -> flash[base + seq * 1024 + i] = b }
                    blocks++; reply(op)
                }
                0x13 -> {
                    val at = word(payload, 0); val n = word(payload, 4)
                    val data = ByteArray(n) { flash[at + it] ?: -1 }
                    if (corrupt) data[0] = (data[0] + 1).toByte()
                    val md5 = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
                    reply(op, data = md5.toByteArray())
                }
                else -> reply(op, status = 1)
            }
        }
    }

    private class Log : EspRomFlasher.Progress {
        var last = -1; val messages = ArrayList<String>()
        override fun update(percent: Int, message: String) { assertTrue("прогресс не убывает", percent >= last || percent == 0); last = percent; messages += message }
        override fun cancelled() = false
    }

    private fun pack(board: String) = FirmwarePackage.load(File("src/main/assets/firmware/stock-$board.zip"))

    @Test fun stockPackagesAreValid() {
        for ((board, chip) in listOf("esp32-s2-mini" to 2, "esp32-s3" to 9, "esp32-c3" to 5)) {
            val p = pack(board)
            assertEquals(board, p.board); assertEquals(chip, p.chip); assertEquals(Firmware.chip(board), p.chip)
            assertTrue(p.segments.size >= 3)
        }
    }

    @Test fun stockFirmwareMatchesBundledSource() {
        val src = File("src/main/assets/firmware/FreddyAdaptive.ino").readText()
        val hash = Firmware.hash(src)
        assertEquals(hash, pack("esp32-s2-mini").sourceSha256)
    }

    @Test fun flashesEveryChipAndVerifiesMd5() {
        for ((board, chip) in listOf("esp32-s2-mini" to 2, "esp32-s3" to 9, "esp32-c3" to 5)) {
            val p = pack(board); val rom = FakeRom(chip); val log = Log()
            EspRomFlasher(rom, log).flash(p.segments, p.chip, p.flashSize)
            assertEquals(100, log.last)
            for (s in p.segments) for (i in s.bytes.indices step 997) assertEquals(s.bytes[i], rom.flash[s.offset + i])
            assertEquals(p.segments.sumOf { (it.bytes.size + 1023) / 1024 }, rom.blocks)
        }
    }

    @Test fun refusesWrongChip() {
        val p = pack("esp32-s3")
        try { EspRomFlasher(FakeRom(5), Log()).flash(p.segments, p.chip, p.flashSize); fail() } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("другой чип"))
        }
    }

    @Test fun reportsMd5Mismatch() {
        val p = pack("esp32-c3")
        try { EspRomFlasher(FakeRom(5, corrupt = true), Log()).flash(p.segments, p.chip, p.flashSize); fail() } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("не совпала"))
        }
    }

    @Test fun rejectsBrokenPackage() {
        val bad = File.createTempFile("bad", ".zip").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        try { FirmwarePackage.load(bad); fail() } catch (e: Exception) { }
    }
}
