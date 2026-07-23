package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeServerUrlTest {

    @Test
    fun wraps_bare_ipv6_loopback_with_port() {
        assertEquals("http://[::1]:5000", PrefsManager.normalizeServerUrl("http://::1:5000"))
    }

    @Test
    fun wraps_bare_ipv6_full_address_with_port() {
        assertEquals(
            "http://[2001:db8::1]:5000",
            PrefsManager.normalizeServerUrl("http://2001:db8::1:5000")
        )
    }

    @Test
    fun keeps_already_bracketed_ipv6() {
        assertEquals("http://[::1]:5000", PrefsManager.normalizeServerUrl("http://[::1]:5000"))
        assertEquals(
            "http://[2001:db8::1]:5000",
            PrefsManager.normalizeServerUrl("http://[2001:db8::1]:5000")
        )
    }

    @Test
    fun wraps_bare_ipv6_without_port() {
        assertEquals("http://[::1]", PrefsManager.normalizeServerUrl("http://::1"))
        assertEquals(
            "http://[2001:db8::1]",
            PrefsManager.normalizeServerUrl("http://2001:db8::1")
        )
    }

    @Test
    fun leaves_ipv4_untouched() {
        assertEquals("http://192.168.1.1:5000", PrefsManager.normalizeServerUrl("http://192.168.1.1:5000"))
        assertEquals("http://10.0.0.1", PrefsManager.normalizeServerUrl("http://10.0.0.1"))
    }

    @Test
    fun leaves_hostname_untouched() {
        assertEquals("http://example.com:5000", PrefsManager.normalizeServerUrl("http://example.com:5000"))
        assertEquals("https://nekobot.app", PrefsManager.normalizeServerUrl("https://nekobot.app"))
    }

    @Test
    fun strips_trailing_slash() {
        assertEquals("http://[::1]:5000", PrefsManager.normalizeServerUrl("http://::1:5000/"))
        assertEquals("http://example.com", PrefsManager.normalizeServerUrl("http://example.com/"))
    }

    @Test
    fun trims_whitespace() {
        assertEquals("http://[::1]:5000", PrefsManager.normalizeServerUrl("  http://::1:5000  "))
    }

    @Test
    fun preserves_https_scheme_case() {
        assertEquals("https://[::1]:5000", PrefsManager.normalizeServerUrl("HTTPS://::1:5000"))
    }

    @Test
    fun preserves_path_after_authority() {
        assertEquals(
            "http://[::1]:5000/api",
            PrefsManager.normalizeServerUrl("http://::1:5000/api")
        )
    }

    @Test
    fun auto_prepends_scheme_for_bare_ipv6_with_port() {
        assertEquals("http://[::1]:5000", PrefsManager.normalizeServerUrl("::1:5000"))
        assertEquals(
            "http://[2001:db8::1]:5000",
            PrefsManager.normalizeServerUrl("2001:db8::1:5000")
        )
    }

    @Test
    fun auto_prepends_scheme_for_bare_ipv6_without_port() {
        assertEquals("http://[::1]", PrefsManager.normalizeServerUrl("::1"))
        assertEquals("http://[2001:db8::1]", PrefsManager.normalizeServerUrl("2001:db8::1"))
    }

    @Test
    fun auto_prepends_scheme_for_ipv4() {
        assertEquals("http://192.168.1.1:5000", PrefsManager.normalizeServerUrl("192.168.1.1:5000"))
        assertEquals("http://10.0.0.1", PrefsManager.normalizeServerUrl("10.0.0.1"))
    }

    @Test
    fun auto_prepends_scheme_for_hostname() {
        assertEquals("http://example.com:5000", PrefsManager.normalizeServerUrl("example.com:5000"))
        assertEquals("http://nekobot.app", PrefsManager.normalizeServerUrl("nekobot.app"))
    }

    @Test
    fun preserves_existing_https_scheme_when_no_scheme_input() {
        // 用户输入 https:// 前缀时应保留 https
        assertEquals("https://[::1]:5000", PrefsManager.normalizeServerUrl("https://::1:5000"))
    }

    @Test
    fun empty_string_returns_empty() {
        assertEquals("", PrefsManager.normalizeServerUrl(""))
        assertEquals("", PrefsManager.normalizeServerUrl("   "))
    }

    @Test
    fun bracketed_input_is_idempotent() {
        val once = PrefsManager.normalizeServerUrl("http://::1:5000")
        val twice = PrefsManager.normalizeServerUrl(once)
        assertEquals(once, twice)
    }
}
