package com.eurobuddha.terminalide.terminal;

/** A bounded display preview; the complete, escaped JSON remains available for export. */
public final class TerminalOutput {
    public static final int MAX_CHARS = 12000;
    public static final int MAX_LINE = 512;
    public static final int MAX_LINES = 160;
    public final String full;
    public final String preview;
    public final boolean shortened;

    public TerminalOutput(String text) {
        full = text;
        StringBuilder out = new StringBuilder();
        int pos = 0, lines = 0;
        boolean omitted = false;
        while (pos < text.length() && out.length() < MAX_CHARS && lines++ < MAX_LINES) {
            int newline = text.indexOf('\n', pos);
            int end = newline < 0 ? text.length() : newline;
            int stop = Math.min(end, pos + Math.min(MAX_LINE, MAX_CHARS - out.length()));
            // Never split a Unicode surrogate pair at a preview boundary.
            if (stop > pos && stop < end && Character.isHighSurrogate(text.charAt(stop - 1))) stop--;
            out.append(text, pos, stop);
            if (stop < end) {
                omitted = true;
                if (out.length() < MAX_CHARS) out.append('…');
            }
            pos = newline < 0 ? text.length() : newline + 1;
            if (newline >= 0 && out.length() < MAX_CHARS) out.append('\n');
        }
        shortened = omitted || pos < text.length() || out.length() != text.length();
        preview = out.toString();
    }

    /** Same bounded worker policy as MinimaCore BackgroundWork; never run overload on the UI. */
    public static java.util.concurrent.ThreadPoolExecutor worker() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(8),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
