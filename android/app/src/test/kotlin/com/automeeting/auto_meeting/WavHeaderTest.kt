package com.automeeting.auto_meeting

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WavHeaderTest {
    @Test fun headerDescribesExactlyTheCapturedPcm() {
        val header = SpeechRecorder.wavHeader(1_920_000)
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(1_920_036, data.getInt(4))
        assertEquals(1.toShort(), data.getShort(22))
        assertEquals(16_000, data.getInt(24))
        assertEquals(32_000, data.getInt(28))
        assertEquals(16.toShort(), data.getShort(34))
        assertEquals(1_920_000, data.getInt(40))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidSizeRejected() {
        SpeechRecorder.wavHeader(-1)
    }
}
