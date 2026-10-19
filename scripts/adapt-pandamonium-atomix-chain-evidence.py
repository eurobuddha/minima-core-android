"""Reuse NFT chain-inclusion shape checks in AtomiX's existing depth lookup."""
def adapt(root):
    p = root / 'atomix/src/main/java/com/eurobuddha/atomix/swap/MinimaHtlc.java'
    text = p.read_text()
    old = r'''    /** Reused from PandaPools ActivityLog.confirmationDepth; malformed evidence is never confirmation. */
    static int confirmedDepth(JSONObject reply) {
        JSONObject r = reply == null ? null : reply.optJSONObject("response");
        if (!checkFlag(reply, "status") || !checkFlag(r, "found")) return -1;
        try {
            int depth = new java.math.BigDecimal(r.get("confirmations").toString()).intValueExact();
            return depth < 0 ? -1 : depth;
        } catch (Exception invalid) { return -1; }
    }

'''
    new = r'''    /** Reuses NFT TransactionEvidence.inclusion's shape and height-consistency checks.
     * Returns observed depth; the swap controller retains its existing settlement threshold. */
    static int confirmedDepth(JSONObject reply) {
        JSONObject r = reply == null ? null : reply.optJSONObject("response");
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))
                || r == null || !Boolean.TRUE.equals(r.opt("found"))) return -1;
        try {
            Object raw = r.opt("blockid");
            if (!(raw instanceof String) || !((String) raw).matches("(?i)0x[0-9a-f]{64}")) return -1;
            if (new java.math.BigInteger(((String) raw).substring(2), 16).signum() == 0) return -1;
            java.math.BigInteger block = whole(r, "block"), tip = whole(r, "tip"), depth = whole(r, "confirmations");
            if (block.signum() <= 0 || !tip.subtract(block).equals(depth)) return -1;
            return new java.math.BigDecimal(depth).intValueExact();
        } catch (Exception invalid) { return -1; }
    }
    private static java.math.BigInteger whole(JSONObject object, String key) {
        Object raw = object.opt(key);
        if (!(raw instanceof String) || !((String) raw).matches("[0-9]{1,30}"))
            throw new IllegalArgumentException("Incomplete chain evidence");
        return new java.math.BigInteger((String) raw);
    }

'''
    assert text.count(old) == 1, 'AtomiX chain evidence source drift'
    p.write_text(text.replace(old, new))
