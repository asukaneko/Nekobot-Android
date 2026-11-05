package com.nekobot.app.data.local.ai

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LocalBrowserFetchClientTest {
    @Test fun crossOriginRedirectGetsTargetCookiesAndDropsSourceCredentials() {
        MockWebServer().use { origin ->
            MockWebServer().use { target ->
                origin.start()
                target.start()
                val targetUrl = target.url("/download").newBuilder().host("127.0.0.1").build()
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", targetUrl))
                target.enqueue(MockResponse().setBody("demo"))
                val requestedCookies = mutableListOf<String>()
                val client = LocalBrowserFetchClient(OkHttpClient(), { url ->
                    requestedCookies += url.host
                    if (url.host == "localhost") "demo_session=origin" else "demo_session=target"
                }, { true })
                val request = Request.Builder().url(origin.url("/redirect").newBuilder().host("localhost").build())
                    .header("Authorization", "Bearer demo-value")
                    .header("X-Demo-Secret", "demo-value")
                    .header("Range", "bytes=0-3")
                    .header("Cookie", "untrusted=value").build()
                client.execute(request).use { assertEquals("demo", it.body!!.string()) }
                assertEquals("demo_session=origin", origin.takeRequest().getHeader("Cookie"))
                val received = target.takeRequest()
                assertEquals("demo_session=target", received.getHeader("Cookie"))
                assertNull(received.getHeader("Authorization"))
                assertNull(received.getHeader("X-Demo-Secret"))
                assertEquals("bytes=0-3", received.getHeader("Range"))
                assertEquals(listOf("localhost", "127.0.0.1"), requestedCookies)
            }
        }
    }

    @Test fun sameOriginRedirectRefreshesCookiesAndRetainsAuthorization() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
            server.enqueue(MockResponse().setBody("demo"))
            var cookieCalls = 0
            val client = LocalBrowserFetchClient(OkHttpClient(), { "demo_session=${++cookieCalls}" }, { true })
            client.execute(Request.Builder().url(server.url("/first"))
                .header("Authorization", "Bearer demo-value").build()).close()
            assertEquals("demo_session=1", server.takeRequest().getHeader("Cookie"))
            val next = server.takeRequest()
            assertEquals("demo_session=2", next.getHeader("Cookie"))
            assertEquals("Bearer demo-value", next.getHeader("Authorization"))
        }
    }

    @Test fun redirectCannotBypassTheAddressPolicy() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/blocked"))
            val initialUrl = server.url("/first")
            val client = LocalBrowserFetchClient(OkHttpClient(), { "" }, { it.host == initialUrl.host })
            assertThrows(IOException::class.java) {
                client.execute(Request.Builder().url(initialUrl).build())
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun secureDownloadCannotRedirectToCleartext() {
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(302).message("redirect").header("Location", "http://example.com/download")
                .body("".toResponseBody()).build()
        }.build()
        val client = LocalBrowserFetchClient(transport, { "demo_session=value" }, { true })
        assertThrows(IOException::class.java) {
            client.execute(Request.Builder().url("https://example.com/download").build())
        }
    }

    @Test fun redirectLoopsAreBounded() {
        var calls = 0
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(302).message("redirect").header("Location", "/loop")
                .body("".toResponseBody()).build()
        }.build()
        assertThrows(IOException::class.java) {
            LocalBrowserFetchClient(transport, { "" }, { true })
                .execute(Request.Builder().url("https://example.com/loop").build())
        }
        assertEquals(21, calls)
    }
}
