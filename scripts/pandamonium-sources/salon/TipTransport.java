package com.eurobuddha.salon;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Native-value tips. A tip is a real Minima {@code send} of MINIMA or mxUSD to the
 * recipient's published tip address, with the sender's handle + an optional note
 * stamped into coin state (state[1]=handle, state[2]=note) so the recipient's app
 * can show "X tipped you". Mirrors apks/atomix/.../comms/CommsTransport.sendPayment;
 * accepts on status||pending and never gates on istransaction (async mining).
 */
final class TipTransport {

    static final String MINIMA = "0x00";
    static final String MXUSD  = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    interface Cb { void onSent(String txpowid); void onFailed(String message); }

    static void tip(NodeApi node, String toAddress, String amount, String tokenid,
                    String fromHandle, String note, Cb cb) {
        // Everything entering the command is validated first — toAddress comes from a
        // stranger's hosted profile.json and could otherwise inject extra `send` parameters.
        if (!Args.isAddr(toAddress)) { cb.onFailed("that profile's tip address is malformed — not sending"); return; }
        if (!Args.isTokenId(tokenid)) { cb.onFailed("bad token id"); return; }
        if (!Args.isDecimal(amount)) { cb.onFailed("bad amount"); return; }
        final java.util.concurrent.atomic.AtomicBoolean received=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            JSONObject state = new JSONObject();
            state.put("1", "0x" + Hex.to((fromHandle == null ? "" : fromHandle).getBytes(StandardCharsets.UTF_8)));
            if (note != null && !note.isEmpty()) state.put("2", "0x" + Hex.to(note.getBytes(StandardCharsets.UTF_8)));
            String cmd = "send amount:" + amount + " address:" + toAddress + " tokenid:" + tokenid + " state:" + state;
            node.cmd(cmd, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    if(!received.compareAndSet(false,true))return;
                    if (j!=null && (Boolean.TRUE.equals(j.opt("status")) || Boolean.TRUE.equals(j.opt("pending")))) {
                        JSONObject r = j.optJSONObject("response");
                        cb.onSent(r != null ? r.optString("txpowid", "") : "");
                    } else {
                        cb.onFailed(j!=null && Boolean.FALSE.equals(j.opt("status")) ? j.optString("error","the node rejected the tip") : "Invalid node result. Outcome remains unknown.");
                    }
                }
                @Override public void onError(String m) { if(received.compareAndSet(false,true))cb.onFailed(m); }
            });
        } catch (Exception e) { if(received.compareAndSet(false,true))cb.onFailed(e.getMessage()); }
    }

    /** Same strict result/decimal checks as the balance preview, also matching the requested token. */
    static String sendable(JSONObject reply,String tokenid) {
        if(!Args.isTokenId(tokenid) || reply==null || !Boolean.TRUE.equals(reply.opt("status")))throw new IllegalArgumentException();
        org.json.JSONArray rows=reply.optJSONArray("response");
        if(rows==null || rows.length()>1)throw new IllegalArgumentException();
        if(rows.length()==0)return "0";
        JSONObject row=rows.optJSONObject(0);
        if(row==null || !(row.opt("tokenid") instanceof String) || !tokenid.equalsIgnoreCase(row.optString("tokenid"))
                || !(row.opt("sendable") instanceof String) || !Args.isDecimal(row.optString("sendable")))throw new IllegalArgumentException();
        return row.optString("sendable");
    }

    /** "MINIMA" | "mxUSD" for a tokenid. */
    static String label(String tokenid) { return MXUSD.equalsIgnoreCase(tokenid) ? "mxUSD" : "MINIMA"; }

    private TipTransport() {}
}
