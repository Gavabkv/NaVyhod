package com.example.dovykhoda.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeDigitsTest {

    @Test
    fun `typing over a full field replaces everything`() {
        assertEquals("9", applyTypedDigits("07", "079"))
        assertEquals("9", applyTypedDigits("07", "907"))
        assertEquals("45", applyTypedDigits("00", "0045"))
        assertEquals("45", applyTypedDigits("00", "4500"))
        assertEquals("3", applyTypedDigits("45", "453"))
    }

    @Test
    fun `typing into a half filled field appends`() {
        assertEquals("45", applyTypedDigits("4", "45"))
        assertEquals("4", applyTypedDigits("", "4"))
    }

    @Test
    fun `backspace shortens the value`() {
        assertEquals("4", applyTypedDigits("45", "4"))
        assertEquals("0", applyTypedDigits("07", "0"))
        assertEquals("", applyTypedDigits("4", ""))
    }

    @Test
    fun `non digits are dropped and length stays at two`() {
        assertEquals("34", applyTypedDigits("12", "12345"))
        assertEquals("", applyTypedDigits("12", "ab"))
        assertEquals("7", applyTypedDigits("", "a7"))
    }
}
