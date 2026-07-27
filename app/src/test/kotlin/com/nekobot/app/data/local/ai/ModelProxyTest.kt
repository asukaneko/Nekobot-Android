package com.nekobot.app.data.local.ai

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class ModelProxyTest {

    @Test
    fun blankProxyKeepsDirectClient() {
        val client = OkHttpClient()

        assertNull(parseModelProxyUrl("  "))
        assertSame(client, client.withModelProxy(""))
    }

    @Test
    fun hostAndPortWithoutSchemeUseHttpProxy() {
        val config = requireNotNull(parseModelProxyUrl("127.0.0.1:7890"))
        val address = config.proxy.address() as InetSocketAddress
        val client = OkHttpClient().withModelProxy("127.0.0.1:7890")

        assertEquals(Proxy.Type.HTTP, config.proxy.type())
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(7890, address.port)
        assertEquals(config.proxy, client.proxy)
    }

    @Test
    fun httpProxyCredentialsAreDecoded() {
        val config = requireNotNull(
            parseModelProxyUrl("http://neko%40user:p%40ss@example.com:8080")
        )

        assertEquals("neko@user", config.username)
        assertEquals("p@ss", config.password)
    }

    @Test
    fun socks5ProxyUsesSocksTypeAndDefaultPort() {
        val config = requireNotNull(parseModelProxyUrl("socks5://proxy.example.com"))
        val address = config.proxy.address() as InetSocketAddress

        assertEquals(Proxy.Type.SOCKS, config.proxy.type())
        assertEquals(1080, address.port)
    }

    @Test
    fun unsupportedSchemeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            parseModelProxyUrl("https://proxy.example.com:443")
        }
    }
}
