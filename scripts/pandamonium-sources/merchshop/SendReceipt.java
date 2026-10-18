package com.eurobuddha.merchshop.integratedcomms;
import org.json.JSONObject;
/** AtomiX send acknowledgement predicate; not chain confirmation. */
final class SendReceipt {
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
}
