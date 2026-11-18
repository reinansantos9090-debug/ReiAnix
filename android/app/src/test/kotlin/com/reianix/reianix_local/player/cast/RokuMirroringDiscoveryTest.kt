package com.reianix.reianix_local.player.cast

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Roku discovery must not accidentally treat unrelated SSDP devices as Roku. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RokuMirroringDiscoveryTest {
    private val rokuResponse =
        "HTTP/1.1 200 OK\r\n" +
            "ST: roku:ecp\r\n" +
            "USN: uuid:roku:ecp:123456789\r\n\r\n"

    @Test fun discoversRokuOnlyFromPrivateLanResponse() {
        val result = RokuMirroringDiscovery.parseResponse(
            rokuResponse, InetAddress.getByName("192.168.1.108"),
        )
        assertEquals("192.168.1.108", result?.address)
        assertEquals("Roku • 192.168.1.108", result?.label)
    }

    @Test fun rejectsOtherSsdpDevicesAndOutsideNetworkAddresses() {
        assertNull(
            RokuMirroringDiscovery.parseResponse(
                "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n",
                InetAddress.getByName("192.168.1.2"),
            )
        )
        assertNull(
            RokuMirroringDiscovery.parseResponse(
                rokuResponse, InetAddress.getByName("8.8.8.8"),
            )
        )
        assertNull(
            RokuMirroringDiscovery.parseResponse(
                "HTTP/1.1 404 Not Found\r\nST: roku:ecp\r\n",
                InetAddress.getByName("192.168.1.108"),
            )
        )
        assertNull(
            RokuMirroringDiscovery.parseResponse(
                rokuResponse, InetAddress.getByName("::1"),
            )
        )
    }

    @Test fun acceptsRokuReplyCaseInsensitive() {
        val reply = "http/1.1 200 OK\r\nusn: uuid:ROKU:ECP:device\r\n"
        assertTrue(
            RokuMirroringDiscovery.parseResponse(
                reply, InetAddress.getByName("10.0.0.15"),
            ) != null
        )
    }
}
