package com.pokebinder.scanner.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameColorConversionTest {
    @Test
    fun convertsCameraRgbaBytesToAndroidArgbPixel() {
        val pixel = rgbaToArgb(
            red = 0x12,
            green = 0x34,
            blue = 0x56,
            alpha = 0xFF,
        )

        assertEquals(0xFF123456.toInt(), pixel)
    }

    @Test
    fun convertsPackedLittleEndianRgbaToAndroidArgbPixel() {
        val packedRgba = 0xFF563412.toInt()

        assertEquals(0xFF123456.toInt(), packedRgbaToArgb(packedRgba))
    }
}
