package com.eurobuddha.terminalide.terminal;

import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalOutputTest {
    @Test public void embeddedImageIsShortenedWithoutLosingTheFollowingFieldsOrExport() {
        String text = "{\n  \"image\": \"" + "a".repeat(5*1024*1024) + "\",\n  \"amount\": \"123.0001\"\n}";
        TerminalOutput out = new TerminalOutput(text);
        assertTrue(out.shortened); assertSame(text,out.full);
        assertTrue(out.preview.contains("123.0001"));
        assertTrue(out.preview.length() <= TerminalOutput.MAX_CHARS);
        for (String line : out.preview.split("\n")) assertTrue(line.length() <= TerminalOutput.MAX_LINE+1);
    }
    @Test public void hugeMultilineRepliesAreBoundedAndUnchangedForExport() {
        String text = "coinid amount\n".repeat(100000);
        TerminalOutput out = new TerminalOutput(text);
        assertTrue(out.shortened); assertSame(text,out.full);
        assertTrue(out.preview.length() <= TerminalOutput.MAX_CHARS);
        assertTrue(out.preview.split("\n",-1).length <= TerminalOutput.MAX_LINES+1);
    }
    @Test public void smallResultsAndJsonEscapesRemainLiteral() {
        String text = "{\"token\":\"Chinese 中文 Russian Русский Ukrainian Українська \\n \\t\"}";
        TerminalOutput out = new TerminalOutput(text);
        assertFalse(out.shortened); assertEquals(text,out.preview); assertSame(text,out.full);
    }
    @Test public void previewBoundaryDoesNotSplitEmoji() {
        TerminalOutput out = new TerminalOutput("a".repeat(511)+"😀"+"b".repeat(100));
        assertTrue(out.shortened); assertFalse(Character.isHighSurrogate(out.preview.charAt(511)));
    }
}
