package com.phoneuse.files

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDownscalerTest {
    @Test fun longEdgeBecomes1280KeepingAspect() {
        assertEquals(1280 to 960, ImageDownscaler.targetSize(4032, 3024))
        assertEquals(720 to 1280, ImageDownscaler.targetSize(1440, 2560))
        assertEquals(1280 to 1280, ImageDownscaler.targetSize(3000, 3000))
    }

    @Test fun smallImagesAreNotUpscaledUnlessAsked() {
        assertEquals(800 to 600, ImageDownscaler.targetSize(800, 600))
        assertEquals(1280 to 1280, ImageDownscaler.targetSize(1280, 1280))
        // A PDF page (points) is rendered up to the same long edge.
        assertEquals(989 to 1280, ImageDownscaler.targetSize(612, 792, upscale = true))
    }

    @Test fun extremeAspectKeepsAtLeastOnePixel() {
        assertEquals(1280 to 1, ImageDownscaler.targetSize(100_000, 10))
        assertEquals(1 to 1, ImageDownscaler.targetSize(0, 0))
    }

    @Test fun sampleSizeIsLargestPowerOfTwoStillCoveringTheTarget() {
        // 4032 / 2 = 2016 ≥ 1280, / 4 = 1008 < 1280.
        assertEquals(2, ImageDownscaler.sampleSize(4032, 3024, 1280))
        assertEquals(1, ImageDownscaler.sampleSize(1000, 800, 1280))
        // Thumbnails: 4032 → 504 ≥ 352 at 8, 252 at 16.
        assertEquals(8, ImageDownscaler.sampleSize(4032, 3024, 352))
    }
}
