package com.freddyai.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Разбор ответов модели, протокол платы, резервная копия, нарезка речи и локальные команды. */
@OptIn(ExperimentalCoroutinesApi::class)
class LogicTest {
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        S.defaults(); S.messages.clear()
        S.fish = FishCfg(tts = false)
        S.board = BoardCfg()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun emotionIsTakenAndVoiceTagsKept() {
        val r = Reply.parse("[весёлый] Привет! [laughing] Ха-ха. [непонятный тег] Конец.")
        assertEquals("веселый", r.emotion)
        assertEquals("Привет! [laughing] Ха-ха. Конец.", r.display)
        assertEquals(r.display, r.speech)
    }

    @Test fun thinkBlocksLinksAndMarkdownAreCleaned() {
        val r = Reply.parse("<think>скрытые рассуждения</think>[злой] **Смотри** [тут](https://example.com/a) и https://x.org/b")
        assertEquals("злой", r.emotion)
        assertEquals("Смотри тут — https://example.com/a и https://x.org/b", r.display)
        assertFalse("ссылки не озвучиваются", r.speech.contains("http"))
    }

    @Test fun unknownEmotionKeepsNormal() {
        assertEquals("нормальный", Reply.parse("Просто текст").emotion)
        assertEquals("поднятая бровь", Reply.parse("[Поднятая бровь] Хм.").emotion)
    }

    @Test fun speakableStopsBeforeOpenTagOrThink() {
        assertEquals("Привет.", Reply.speakable("Привет. [laugh"))
        assertEquals("Начало", Reply.speakable("Начало <think>ещё думаю"))
    }

    @Test fun stripTagsRemovesAllBrackets() {
        assertEquals("Привет мир", Reply.stripTags("[happy] Привет [break] мир"))
    }

    @Test fun boardAcceptsOnlyKnownCommands() {
        for (c in listOf("RED", "OFF", "DISCO", "MUSIC", "B255", "L0", "E255,0,10,200", "RGB1,2,3", "CBLUE")) assertTrue(c, Board.allowed(c))
        for (c in listOf("B256", "rm -rf", "RED;OFF", "E1,2,3", "RGB300,0,0", "", "PURPLE")) assertFalse(c, Board.allowed(c))
    }

    @Test fun eyeModeAiFollowsEmotionOnly() {
        assertTrue(Board.EYE_MODES.any { it.first == "ai" })
        assertTrue(Board.EYE_MODES.filter { it.first != "ai" }.all { Board.allowed(it.first) })
    }

    @Test fun backupRoundTripAndWrongPassword() {
        val o = JSONObject().put("hello", "мир").put("n", 42)
        val packed = Backup.pack(o, "секрет12")
        assertTrue(packed.startsWith("FAI1:"))
        assertEquals("мир", Backup.unpack(packed, "секрет12").getString("hello"))
        try { Backup.unpack(packed, "другой"); fail("неверный пароль должен отклоняться") } catch (e: IllegalArgumentException) { }
        try { Backup.unpack("мусор", "секрет12"); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun snapshotRestoreKeepsSettingsAndHidesKeys() {
        S.setSvc("groq") { it.copy(key = "gsk_secret", model = "llama-3.3-70b-versatile") }
        S.board = S.board.copy(eyeMode = "BLUE", bright = 120, options = mapOf("PSRAM" to "opi"))
        S.vision = S.vision.copy(source = "net", url = "http://cam/shot.jpg")
        S.fish = S.fish.copy(volume = 1.5f)
        val noKeys = S.snapshot(full = false, keys = false)
        assertFalse(noKeys.toString().contains("gsk_secret"))
        val full = S.snapshot(full = true, keys = true)
        S.defaults(); S.board = BoardCfg(); S.vision = VisionCfg(); S.fish = FishCfg()
        S.restore(full, own = true)
        assertEquals("gsk_secret", S.services.first { it.id == "groq" }.key)
        assertEquals("llama-3.3-70b-versatile", S.services.first { it.id == "groq" }.model)
        assertEquals("BLUE", S.board.eyeMode); assertEquals(120, S.board.bright); assertEquals(mapOf("PSRAM" to "opi"), S.board.options)
        assertEquals("http://cam/shot.jpg", S.vision.url)
        assertEquals(1.5f, S.fish.volume, 0.001f)
    }

    @Test fun importWithoutKeysKeepsCurrentKeys() {
        S.setSvc("groq") { it.copy(key = "my_key") }
        val shared = S.snapshot(full = false, keys = false)
        S.restore(shared, own = false)
        assertEquals("my_key", S.services.first { it.id == "groq" }.key)
    }

    @Test fun speechIsCutIntoSentencesWithoutBreakingTags() {
        S.fish = S.fish.copy(tts = true, tags = true)
        val text = "[chuckling] Надеюсь, ты рад моему визиту, дружок? [break] У меня специальная доставка только для тебя. Это будет очень весело."
        val parts = Speaker.chunks(text)
        assertTrue(parts.size >= 2)
        assertEquals(Reply.stripTags(text).replace(" ", ""), parts.joinToString("") { Reply.stripTags(it) }.replace(" ", ""))
        parts.forEach { assertEquals("тег не должен разрываться: $it", it.count { c -> c == '[' }, it.count { c -> c == ']' }) }
    }

    @Test fun localCommands() {
        Brain.send("Фредди, запомни я люблю пиццу")
        assertTrue(S.profile.memory.contains("я люблю пиццу"))
        assertEquals("Запомнил.", S.messages.last().text)
        Brain.send("меня зовут Пётр")
        assertEquals("Пётр", S.profile.userName)
        Brain.send("что ты помнишь")
        assertTrue(S.messages.last().text.contains("пиццу"))
        Brain.send("который час")
        assertTrue(S.messages.last().text.matches(Regex("Сейчас \\d\\d:\\d\\d\\.")))
        Brain.send("забудь")
        assertTrue(S.profile.memory.isEmpty())
        assertTrue(Brain.systemPrompt().contains("Фредди"))
    }

    @Test fun wakeWordIsStripped() {
        assertEquals("как дела", Brain.stripWake("Фредди, как дела"))
        assertEquals("как дела", Brain.stripWake("freddy как дела"))
        assertEquals("Фредерик пришёл", Brain.stripWake("Фредерик пришёл"))
    }

    @Test fun researchAndVisionTriggers() {
        assertTrue(Llm.needsResearch("Найди в интернете курс доллара"))
        assertTrue(Llm.needsResearch("кто такой Фредди Фазбер"))
        assertFalse(Llm.needsResearch("привет, как дела"))
        assertTrue(Vision.detailRequested("Что ты видишь?"))
        assertTrue(Vision.detailRequested("посмотри на мой костюм"))
        assertFalse(Vision.detailRequested("расскажи анекдот"))
    }

    @Test fun firmwareChipDetection() {
        assertEquals(2, Firmware.chip("esp32-s2-mini")); assertEquals(9, Firmware.chip("esp32-s3")); assertEquals(5, Firmware.chip("esp32-c3"))
        assertEquals(2, Firmware.chip("esp32:esp32:lolin_s2_mini")); assertEquals(9, Firmware.chip("esp32:esp32:esp32s3"))
        assertEquals(-1, Firmware.chip("rp2040")); assertEquals(-1, Firmware.chip("arduino:avr:uno"))
        assertEquals("esp32:esp32:lolin_s2_mini", Firmware.fqbn("esp32-s2-mini"))
    }
}
