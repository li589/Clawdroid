package com.clawdroid.app.ui.rich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RichPreviewSecurityTest {
    @Test
    fun svgAndDrawioDoNotUseWebViewPreview() {
        assertNull(previewModeForRichBlock(RichBlock.Svg("<svg onload=\"alert(1)\"/>")))
        assertNull(previewModeForRichBlock(RichBlock.Drawio("<svg/>", previewAsSvg = true)))
    }

    @Test
    fun safeFormatsKeepTheirPreviewModes() {
        assertEquals("katex", previewModeForRichBlock(RichBlock.Math("x", display = true)))
        assertEquals("mermaid", previewModeForRichBlock(RichBlock.Mermaid("graph TD")))
    }
}
