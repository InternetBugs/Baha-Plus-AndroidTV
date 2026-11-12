package app.bahaplus.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterSamplingTest {
    @Test fun decodedDimensionsStayWithinBothResourceBudgets() {
        for ((width, height) in listOf(640 to 360, 1920 to 1080, 600 to 850, 641 to 359, 16384 to 1)) {
            for (budget in listOf(320, 512)) {
                val sample = posterSampleSize(width, height, budget)
                assertTrue((width + sample - 1) / sample <= budget)
                assertTrue((height + sample - 1) / sample <= budget)
            }
        }
        // A common 640x360 cover used to decode all 230,400 pixels in low mode.
        assertEquals(2, posterSampleSize(640, 360, 320))
        assertEquals(1, posterSampleSize(240, 135, 320))
    }

    @Test fun invalidImageDimensionsAreRejectedBeforeDecode() {
        for ((width, height) in listOf(-1 to 360, 0 to 360, 640 to 0, 16385 to 360)) {
            assertThrows(IllegalArgumentException::class.java) { posterSampleSize(width, height, 320) }
        }
    }
}
