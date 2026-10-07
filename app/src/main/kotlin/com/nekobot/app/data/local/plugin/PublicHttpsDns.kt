package com.nekobot.app.data.local.plugin

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

/** 在 OkHttp 实际连接解析主机时重复执行公网地址策略，避免校验后 DNS 被替换。 */
internal object PublicHttpsAddressPolicy {
    fun resolve(host: String): List<InetAddress> {
        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (error: Exception) {
            throw UnknownHostException("无法解析公网 HTTPS 主机：$host").also { it.initCause(error) }
        }
        if (addresses.isEmpty() || addresses.any(::isBlocked)) {
            throw UnknownHostException("插件网络仅允许公网 HTTPS 地址：$host")
        }
        return addresses
    }

    private fun isBlocked(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true

        val bytes = address.address
        if (address is Inet4Address || bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            val third = bytes[2].toInt() and 0xff
            return first == 0 || first == 10 || first == 127 || first >= 224 ||
                (first == 100 && second in 64..127) ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 0) ||
                (first == 192 && second == 2) ||
                (first == 192 && second == 88 && third == 99) ||
                (first == 192 && second == 168) ||
                (first == 198 && second in 18..19) ||
                (first == 198 && second == 51 && third == 100) ||
                (first == 203 && second == 0 && third == 113)
        }

        if (bytes.size != 16) return true
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if ((first and 0xfe) == 0xfc || (first == 0xfe && (second and 0xc0) == 0x80) || first == 0xff) {
            return true
        }
        if (first == 0x20 && (bytes[1].toInt() and 0xff) == 0x01 &&
            (bytes[2].toInt() and 0xff) == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8
        ) return true

        // IPv4-mapped IPv6 必须沿用 IPv4 地址范围判断。
        val mappedPrefix = bytes.take(10).all { it.toInt() == 0 } &&
            (bytes[10].toInt() and 0xff) == 0xff && (bytes[11].toInt() and 0xff) == 0xff
        if (mappedPrefix) {
            return isBlocked(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        return false
    }
}

internal class PublicHttpsDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = PublicHttpsAddressPolicy.resolve(hostname)
}
