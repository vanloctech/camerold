package vn.camerold.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class RtcTest {
    /** A plain TURN address is tried over UDP and TCP (UDP 3478 is often blocked); explicit ones stay as typed. */
    @Test fun turnUrlsTryUdpAndTcp() {
        assertEquals(listOf("turn:turn.example.com:3478?transport=udp", "turn:turn.example.com:3478?transport=tcp"),
            Rtc.parseTurnUrls(" turn.example.com:3478 "))
        assertEquals(listOf("turn:a:3478?transport=tcp", "turns:b:5349", "turn:c:80?transport=udp", "turn:c:80?transport=tcp"),
            Rtc.parseTurnUrls("turn:a:3478?transport=tcp, turns:b:5349\nc:80"))
    }
}
