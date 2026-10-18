    // Reuse Args' command-token policy and IdentityBalance's strict success gate.
    private static boolean ok(JSONObject reply) {
        return reply != null && Boolean.TRUE.equals(reply.opt("status"));
    }
    private static String text(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        return value instanceof String ? (String) value : "";
    }
    private static boolean safeId(String value) {
        return Args.isTokenId(value) && (value.length() & 1) == 0;
    }
    private static boolean hexBytes(String value, int maxChars) {
        if (value == null || value.length() < 4 || value.length() > maxChars
                || (value.length() & 1) != 0 || !value.startsWith("0x")) return false;
        for (int i = 2; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) return false;
        }
        return true;
    }
    private static boolean validBinding(String value) {
        if (!hexBytes(value, 166)) return false;
        StringBuilder identity = new StringBuilder();
        for (int i = 2; i < value.length(); i += 2)
            identity.append((char)Integer.parseInt(value.substring(i, i + 2), 16));
        return safeId(identity.toString());
    }
    private static boolean simpleScript(String script, String key) {
        if (!safeId(key) || script == null || script.length() > 256) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\ARETURN +SIGNEDBY *\\( *(0x[0-9A-Fa-f]+) *\\)\\z", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(script.trim());
        return m.matches() && key.equalsIgnoreCase(m.group(1));
    }
    private static boolean matchingCoin(JSONObject coin, String tokenid) {
        if (coin == null || !tokenid.equalsIgnoreCase(text(coin, "tokenid"))
                || !safeId(text(coin, "coinid")) || !safeId(text(coin, "address"))
                || !Boolean.FALSE.equals(coin.opt("spent"))) return false;
        String amount = text(coin, "amount");
        if (amount.length() > 100 || !amount.matches("[0-9]+(?:\\.[0-9]+)?")) return false;
        return new java.math.BigDecimal(amount).signum() > 0;
    }
