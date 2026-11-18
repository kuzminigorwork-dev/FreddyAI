package com.freddyai.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Нейросети с запасной цепочкой и Fish Audio — на локальном тестовом сервере. */
@OptIn(ExperimentalCoroutinesApi::class)
class NetTest {
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        S.defaults()
        a = MockWebServer().apply { start() }; b = MockWebServer().apply { start() }
        S.services.clear()
        S.services += Svc("one", "Первый", a.url("/v1").toString().trimEnd('/'), "m1", key = "k1")
        S.services += Svc("two", "Второй", b.url("/v1").toString().trimEnd('/'), "m2", key = "k2")
    }

    @After fun tearDown() { a.shutdown(); b.shutdown(); Dispatchers.resetMain() }

    private fun sse(vararg parts: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        parts.joinToString("") { "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", it)))) + "\n\n" } + "data: [DONE]\n\n"
    )

    @Test fun streamsAnswerFromFirstService() = runBlocking {
        a.enqueue(sse("[весёлый] При", "вет!"))
        val got = StringBuilder()
        val text = Llm.chat("system", listOf(Msg(1, true, "привет"))) { got.append(it) }
        assertEquals("[весёлый] Привет!", text)
        assertEquals(text, got.toString())
        val req = a.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer k1", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("m1", body.getString("model")); assertTrue(body.getBoolean("stream"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals(1, S.services.first { it.id == "one" }.status)
    }

    @Test fun fallsBackWhenLimitReached() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limit"}}"""))
        b.enqueue(sse("Отвечает второй."))
        var via = ""
        val text = Llm.chat("system", listOf(Msg(1, true, "вопрос")), onVia = { via = it.name }) {}
        assertEquals("Отвечает второй.", text)
        assertEquals("Второй", via)
        val first = S.services.first { it.id == "one" }
        assertEquals(2, first.status)
        assertTrue(first.statusText, first.statusText.contains("429") && first.statusText.contains("rate limit"))
    }

    @Test fun allServicesFailGivesReadableError() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(401))
        b.enqueue(MockResponse().setResponseCode(500))
        try { Llm.chat("s", listOf(Msg(1, true, "q"))) {}; fail() } catch (e: ApiError) {
            assertTrue(e.message!!, e.message!!.contains("Первый") && e.message!!.contains("ключ") && e.message!!.contains("Второй"))
        }
    }

    @Test fun cameraNotesGoBeforeLastQuestion() = runBlocking {
        a.enqueue(sse("ок"))
        Llm.chat("s", listOf(Msg(1, true, "раньше"), Msg(2, false, "ответ"), Msg(3, true, "что видишь?")), extra = "камера: кошка") {}
        val m = JSONObject(a.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals("камера: кошка", m.getJSONObject(m.length() - 2).getString("content"))
        assertEquals("что видишь?", m.getJSONObject(m.length() - 1).getString("content"))
    }

    @Test fun modelsListDoublesAsKeyCheck() = runBlocking {
        a.enqueue(MockResponse().setBody("""{"data":[{"id":"b-model"},{"id":"whisper-large-v3"},{"id":"a-model"}]}"""))
        assertEquals(listOf("a-model", "b-model"), Llm.models(S.services[0]))
        a.enqueue(MockResponse().setResponseCode(401))
        assertTrue(Llm.check(S.services[0]).contains("ключ"))
        assertEquals(2, S.services[0].status)
    }

    @Test fun fishRetriesOnFreeModelWhenPaidRefused() = runBlocking {
        Fish.API = a.url("").toString().trimEnd('/')
        S.fish = FishCfg(key = "fk", model = "s2.1-pro")
        a.enqueue(MockResponse().setResponseCode(402).setBody("""{"message":"no balance"}"""))
        a.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
        val bytes = Fish.tts("[happy] Привет", "voice123")
        assertEquals(3, bytes.size)
        val first = a.takeRequest(); val second = a.takeRequest()
        assertEquals("/v1/tts", first.path)
        assertEquals("s2.1-pro", first.getHeader("model")); assertEquals("s2.1-pro-free", second.getHeader("model"))
        val body = JSONObject(second.body.readUtf8())
        assertEquals("[happy] Привет", body.getString("text")); assertEquals("voice123", body.getString("reference_id")); assertEquals("mp3", body.getString("format"))
    }

    @Test fun fishWalletCheck() = runBlocking {
        Fish.API = a.url("").toString().trimEnd('/')
        S.fish = FishCfg(key = "fk")
        a.enqueue(MockResponse().setBody("""{"credit":"1.5"}"""))
        a.enqueue(MockResponse().setBody("""{"balance":8000,"total":8000}"""))
        assertEquals("", Fish.check())
        assertEquals(1, S.fish.status)
        assertTrue(S.fish.credit, S.fish.credit.contains("1.50 $") && S.fish.credit.contains("8000 / 8000"))
        assertEquals("/wallet/self/api-credit", a.takeRequest().path)
    }
}
