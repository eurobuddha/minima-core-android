package com.eurobuddha.nftwallet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The sealed payload is whatever bytes we put in state port 1, forever. Two things must hold:
 * we can tell later what format those bytes are, and an SVG we seal is inert.
 */
public class ImageFormatTest {

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- magic-byte sniffing ----------

    @Test public void detectsWebp() {
        byte[] riff = new byte[]{'R','I','F','F', 0,0,0,0, 'W','E','B','P', 'V','P','8',' '};
        assertEquals("image/webp", ImageTools.mimeOf(b64(riff)));
    }

    @Test public void detectsJpeg() {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        assertEquals("image/jpeg", ImageTools.mimeOf(b64(jpeg)));
    }

    @Test public void detectsPng() {
        byte[] png = new byte[]{(byte) 0x89, 'P','N','G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertEquals("image/png", ImageTools.mimeOf(b64(png)));
    }

    @Test public void detectsSvgInBothPreambles() {
        assertEquals("image/svg+xml", ImageTools.mimeOf(b64("<svg xmlns='http://www.w3.org/2000/svg'></svg>")));
        assertEquals("image/svg+xml", ImageTools.mimeOf(b64("<?xml version='1.0'?><svg></svg>")));
    }

    /** Unknown bytes fall back to jpeg — the historical format — rather than throwing. */
    @Test public void unknownBytesFallBack() {
        assertEquals("image/jpeg", ImageTools.mimeOf(b64(new byte[]{1, 2, 3, 4, 5, 6, 7, 8})));
        assertEquals("image/jpeg", ImageTools.mimeOf("!!!not base64!!!"));
    }

    @Test public void dataUriCarriesTheSniffedType() {
        String svg = b64("<svg></svg>");
        assertEquals("data:image/svg+xml;base64," + svg, ImageTools.dataUri(svg));
        assertEquals("", ImageTools.dataUri(""));
        assertEquals("", ImageTools.dataUri(null));
    }

    // ---------- SVG sanitising ----------

    @Test public void stripsActiveContentButKeepsTheArtwork() {
        String dirty = "<svg xmlns='http://www.w3.org/2000/svg' onload='steal()'>"
                + "<script>alert(1)</script>"
                + "<foreignObject><body>hi</body></foreignObject>"
                + "<defs><linearGradient id='grad'/></defs>"
                + "<a href='javascript:evil()'><rect fill='url(#grad)'/></a>"
                + "<use href='#grad'/>"
                + "<image href='https://tracker.example/pixel.png'/>"
                + "</svg>";
        String clean = SvgSanitizer.sanitize(dirty);

        assertFalse(clean, clean.contains("<script"));
        assertFalse(clean, clean.contains("foreignObject"));
        assertFalse(clean, clean.toLowerCase().contains("onload"));
        assertFalse(clean, clean.toLowerCase().contains("javascript:"));
        assertFalse(clean, clean.contains("tracker.example"));   // no phone-home from a sealed record

        // the artwork itself survives
        assertTrue(clean, clean.contains("linearGradient"));
        assertTrue(clean, clean.contains("url(#grad)"));
        assertTrue(clean, clean.contains("href='#grad'"));
    }

    @Test public void stripsDoctypeAndEntityBombs() {
        String bomb = "<?xml version='1.0'?><!DOCTYPE svg [<!ENTITY a 'aaaa'>]><svg><rect/></svg>";
        String clean = SvgSanitizer.sanitize(bomb);
        assertFalse(clean, clean.contains("DOCTYPE"));
        assertFalse(clean, clean.contains("ENTITY"));
        assertTrue(clean, clean.contains("<rect/>"));
    }

    @Test public void rejectsNonSvg() {
        assertNull(SvgSanitizer.sanitize("<html><body>no</body></html>"));
        assertNull(SvgSanitizer.sanitize("just text"));
        assertNull(SvgSanitizer.sanitize(null));
        assertFalse(SvgSanitizer.isSvg("<html>"));
        assertTrue(SvgSanitizer.isSvg("  <SVG viewBox='0 0 1 1'>"));
    }
}
