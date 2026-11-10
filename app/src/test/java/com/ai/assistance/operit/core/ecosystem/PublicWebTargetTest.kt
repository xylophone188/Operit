package com.ai.assistance.operit.core.ecosystem

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class PublicWebTargetTest {
    @Test fun blocksPrivateAndSpecialAddresses() {
        listOf("0.0.0.0","10.0.0.1","100.64.0.1","127.0.0.1","169.254.169.254","172.16.0.1","192.168.0.1","198.18.0.1","224.0.0.1","240.0.0.1","::","::1","fc00::1","fe80::1","2001:db8::1","64:ff9b::7f00:1","::ffff:127.0.0.1").forEach {
            assertFalse(it,PublicWebTarget.allowed(InetAddress.getByName(it)))
        }
    }
    @Test fun literalUrlsCannotBypassDnsGuard() {
        listOf("https://169.254.169.254/","https://10.0.0.5/","https://[::1]/","https://[fc00::1]/","http://example.com/","https://user:secret@example.com/","https://example.com:8443/").forEach {url ->
            try {PublicWebTarget.validateUrl(url.toHttpUrl());fail("unsafe URL accepted: $url")} catch(_:IllegalArgumentException) {}
        }
        PublicWebTarget.validateUrl("https://1.1.1.1/".toHttpUrl())
    }
    @Test fun allowsPublicAddresses() {
        listOf("1.1.1.1","8.8.8.8","2606:4700:4700::1111").forEach {
            assertTrue(it,PublicWebTarget.allowed(InetAddress.getByName(it)))
        }
    }
    @Test fun rejectsMixedDnsAnswersAndRechecksEveryLookup() {
        var calls=0
        val resolver=object:Dns { override fun lookup(hostname:String):List<InetAddress> { calls++;return listOf(InetAddress.getByName(if(calls==1) "1.1.1.1" else "127.0.0.1")) } }
        assertEquals("1.1.1.1",PublicWebTarget.resolve("fixture.invalid",resolver).single().hostAddress)
        try {PublicWebTarget.resolve("fixture.invalid",resolver);fail("rebinding accepted")} catch(_:UnknownHostException) {}
        try {PublicWebTarget.resolve("fixture.invalid",object:Dns {override fun lookup(hostname:String)=listOf(InetAddress.getByName("1.1.1.1"),InetAddress.getByName("10.0.0.1"))});fail("mixed answers accepted")} catch(_:UnknownHostException) {}
        assertEquals(2,calls)
    }
}
