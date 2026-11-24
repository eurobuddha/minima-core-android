package com.eurobuddha.salon;

import java.util.regex.Pattern;

/**
 * Validators for values that get concatenated into space-delimited Minima node commands
 * ({@code send amount:.. address:.. tokenid:.. state:..}). Minima parses commands as
 * {@code param:value} split on whitespace, so ANY externally-sourced value — a tip/DM
 * address read from a stranger's hosted profile.json, a tokenid, an amount — that contains
 * whitespace or command metacharacters can inject extra parameters (e.g. a second
 * {@code address:}, a {@code burn:}) and drain funds. Everything remote is checked here
 * before it can reach a command string; on failure the caller refuses to send.
 */
final class Args {

    // 0x-hex (covers 0x00 = MINIMA and 32-byte ids/addresses) OR an Mx-address.
    private static final Pattern ADDR = Pattern.compile("^(0x[0-9A-Fa-f]{2,80}|Mx[0-9A-Za-z]{20,120})$");
    private static final Pattern HEX  = Pattern.compile("^0x[0-9A-Fa-f]{2,80}$");
    private static final Pattern DEC  = Pattern.compile("^[0-9]{1,20}(\\.[0-9]{1,20})?$");

    /** A Minima receive address (0x… or Mx…), no whitespace/metacharacters. */
    static boolean isAddr(String s) { return s != null && ADDR.matcher(s).matches(); }

    /** A 0x-hex token id (incl. 0x00). */
    static boolean isTokenId(String s) { return s != null && HEX.matcher(s).matches(); }

    /** A plain decimal amount — no exponent, sign, NaN, or Infinity. */
    static boolean isDecimal(String s) { return s != null && DEC.matcher(s).matches(); }

    /** Typed text is REFUSED, never escaped. The node's command tokenizer counts { } [ ] with one depth counter
     *  even inside a JSON string (a "]" in a name closes "name:{...}" early and the rest becomes new parameters),
     *  ends a quoted value at the first '"', has no backslash escape, splits the whole command at every ';' and
     *  starts a new parameter at a control character.
     *  @return the first such character as shown to the user, or null when the text is safe */
    static String badChar(String s) {
        if (s == null) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return "line break / control character";
            if ("{}[]\"\\;".indexOf(c) >= 0) return String.valueOf(c);
        }
        return null;
    }

    /** True if the free text is safe inside the JSON metadata of a node command: none of { } [ ] " \ ; and no
     *  control character (see badChar). */
    static boolean isSafeMeta(String s) { return badChar(s) == null; }

    /** The hosted profile URL rides the identity tokencreate: http(s), no spaces, quotes, and nothing badChar refuses. */
    static boolean isSafeUrl(String u) {
        return u != null && u.matches("https?://[^\\s']+") && isSafeMeta(u);
    }

    /** Every command is checked once more where it leaves the app: the node splits command text at every ';'
     *  and starts a new parameter at a control character, and runs each piece as admin. */
    static boolean unsafeCommand(String command) {
        if (command == null) return true;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == ';' || c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    private Args() {}
}
