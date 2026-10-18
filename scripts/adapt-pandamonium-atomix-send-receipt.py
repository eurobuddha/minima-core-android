"""Validate AtomiX send acknowledgements before releasing interrupted-write evidence."""
def adapt(root):
    base = root / 'atomix/src/main/java/com/eurobuddha/comms'
    def patch(name, old, new, count):
        p = base / name
        text = p.read_text()
        assert text.count(old) == count, ('AtomiX send receipt drift', name, old)
        p.write_text(text.replace(old, new))
    source = r'''    /** A send ACK identifies a submitted TxPoW; it does not prove mining or chain inclusion.
     * Reuses NFT TransactionEvidence's typed/nonzero ID checks and MinimaHtlc's 32-byte ID grammar. */
    static String sendReceipt(JSONObject reply) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) return null;
        if (reply.has("pending") && !Boolean.FALSE.equals(reply.opt("pending"))) return null;
        if (reply.has("dryrun") && !Boolean.FALSE.equals(reply.opt("dryrun"))) return null;
        JSONObject response = reply.optJSONObject("response");
        Object raw = response == null ? null : response.opt("txpowid");
        if (!(raw instanceof String) || !((String) raw).matches("(?i)0x[0-9a-f]{64}")) return null;
        String id = (String) raw;
        return new java.math.BigInteger(id.substring(2), 16).signum() == 0 ? null : id;
    }
    static boolean completeReply(String command, JSONObject reply) {
        if (!completeReply(reply)) return false;
        String verb = command == null ? "" : command.trim().split("\\s+", 2)[0];
        // Other commands retain their existing result contracts. A definite send rejection
        // also needs no transaction ID; a positive reply without one remains uncertain.
        return !verb.equals("send") || command.matches(".*(?:^|\\s)dryrun:true(?:\\s|$).*")
                || !Boolean.TRUE.equals(reply.opt("status"))
                || sendReceipt(reply) != null;
    }
    static boolean completeReply(JSONObject reply) {'''
    patch('WriteSafety.java', '    static boolean completeReply(JSONObject reply) {', source, 1)
    patch('NodeApi.java', 'WriteSafety.completeReply(zResponse)', 'WriteSafety.completeReply(command, zResponse)', 2)
    patch('CommsTransport.java', 'WriteSafety.completeReply(j)', 'WriteSafety.completeReply(cmd, j)', 1)
    patch('CommsTransport.java', '                    JSONObject r = j.optJSONObject("response");\n                    cb.onSent(r != null ? r.optString("txpowid", "") : "");', '                    cb.onSent(WriteSafety.sendReceipt(j));', 1)
