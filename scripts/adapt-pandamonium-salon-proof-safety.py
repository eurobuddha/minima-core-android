"""Validate Salon's existing plain-wallet proof protocol before any node command."""
from pathlib import Path

def adapt(root):
    base=root/'salon/src/main/java/com/eurobuddha/salon'
    p=base/'NftProof.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert old in s,('Salon proof safety drift',old);s=s.replace(old,new)
    change('if (tokenid == null || tokenid.isEmpty())', 'if (!safeId(tokenid))')
    change('node.cmd("coins relevant:true tokenid:" + tokenid,', 'node.cmd("coins relevant:true sendable:true tokenid:" + tokenid,')
    change('''                JSONArray a = j.optJSONArray("response");
                JSONObject coin = (a != null && a.length() > 0) ? a.optJSONObject(0) : null;
                cb.result(coin != null, coin);''','''                JSONArray a = ok(j) ? j.optJSONArray("response") : null;
                JSONObject coin = a == null || a.length() == 0 ? null : a.optJSONObject(0);
                boolean held = matchingCoin(coin, tokenid);
                cb.result(held, held ? coin : null);''')
    change('''    static void generate(final NodeApi node, final String tokenid, final String bindHex, final Gen cb) {
        node.cmd''','''    static void generate(final NodeApi node, final String tokenid, final String bindHex, final Gen cb) {
        if (!safeId(tokenid) || !validBinding(bindHex)) { cb.fail("incomplete proof"); return; }
        node.cmd''')
    change('JSONArray arr = r.optJSONArray("response");','JSONArray arr = ok(r) ? r.optJSONArray("response") : null;')
    change('''                JSONObject coin = arr.optJSONObject(0);
                final String coinid''','''                JSONObject coin = arr.optJSONObject(0);
                if (!matchingCoin(coin, tokenid)) { cb.fail("coin has no id/address"); return; }
                final String coinid''')
    change('JSONObject er = e.optJSONObject("response");','JSONObject er = ok(e) ? e.optJSONObject("response") : null;')
    change('final String proof = er == null ? "" : er.optString("data", "");','final String proof = text(er, "data");')
    change('if (proof.isEmpty())', 'if (!hexBytes(proof, 1_048_576))')
    change('JSONObject sr = firstObj(s.opt("response"));','JSONObject sr = ok(s) ? firstObj(s.opt("response")) : null;')
    change('final String script = sr == null ? "" : sr.optString("script", "");','final String script = text(sr, "script");')
    change('final String pk = sr == null ? "" : sr.optString("publickey", "");','final String pk = text(sr, "publickey");')
    change('if (script.isEmpty() || pk.isEmpty())', 'if (!simpleScript(script, pk) || !Boolean.TRUE.equals(sr.opt("simple")) || !address.equalsIgnoreCase(text(sr, "address")))')
    change('if (!sg.optBoolean("status", false))', 'if (!ok(sg))')
    change('String sig = sg.opt("response") instanceof String ? sg.optString("response") : "";','String sig = text(sg, "response");')
    change('if (sig.isEmpty())', 'if (!hexBytes(sig, 1_048_576))')
    change('''        final String tokenid = item.optString("tokenid"), proof = item.optString("coinproof"),
                script = item.optString("script"), pk = item.optString("publickey"), sig = item.optString("sig");
        if (proof.isEmpty() || script.isEmpty() || pk.isEmpty() || sig.isEmpty()) { cb.result(false, "incomplete proof", ""); return; }''','''        final String tokenid = text(item, "tokenid"), coinid = text(item, "coinid"), proof = text(item, "coinproof"),
                script = text(item, "script"), pk = text(item, "publickey"), sig = text(item, "sig");
        if (!safeId(tokenid) || !safeId(coinid) || !safeId(pk) || !validBinding(bindHex)
                || !hexBytes(proof, 1_048_576) || !hexBytes(sig, 1_048_576)) {
            cb.result(false, "incomplete proof", ""); return;
        }
        if (!simpleScript(script, pk)) { cb.result(false, "key doesn't control the coin", ""); return; }''')
    change('JSONObject cr = c.optJSONObject("response");','JSONObject cr = ok(c) ? c.optJSONObject("response") : null;')
    change('''                if (coin.optBoolean("spent", false)) { cb.result(false, "coin has been spent", ""); return; }''','''                if (Boolean.TRUE.equals(coin.opt("spent"))) { cb.result(false, "coin has been spent", ""); return; }
                if (!matchingCoin(coin, tokenid) || !coinid.equalsIgnoreCase(text(coin, "coinid"))) {
                    cb.result(false, "coin not found on-chain", ""); return;
                }''')
    change('if (!cr.optBoolean("valid", false))', 'if (!Boolean.TRUE.equals(cr.opt("valid")))')
    change('''                // the named pubkey must appear in the script that controls the coin
                if (!script.toLowerCase().contains(pk.toLowerCase().replaceFirst("^0x", ""))) { cb.result(false, "key doesn't control the coin", ""); return; }
                node.cmd("runscript script:\\"" + script + "\\"",''','''                // Build only the node's plain-wallet script from a validated key.
                node.cmd("runscript script:\\"RETURN SIGNEDBY(" + pk + ")\\"",''')
    change('JSONObject rr = rs.optJSONObject("response");','JSONObject rr = ok(rs) ? rs.optJSONObject("response") : null;')
    change('if (computed.isEmpty() || !computed.equalsIgnoreCase(coinAddr))', 'if (computed.isEmpty() || !computed.equalsIgnoreCase(coinAddr) || !Boolean.TRUE.equals(rr.opt("parseok")) || !simpleScript(text(clean, "script"), pk))')
    change('boolean ok = v.optBoolean("status", false);', 'boolean ok = ok(v) && "Signature valid".equals(text(v, "response"));')
    change('((JSONArray) resp).length() > 0', '((JSONArray) resp).length() == 1')
    helpers=(Path(__file__).parent/'pandamonium-sources/salon/ProofChecks.java.inc').read_text()
    change('    private NftProof() {}',helpers+'\n    private NftProof() {}');p.write_text(s)
    # This separate read path also interpolates IDs from a remote profile.
    p=base/'MainActivity.java';s=p.read_text();old='if (!nodeUp || tokenid == null || tokenid.isEmpty()) { cb.ready(java.util.Collections.emptyList()); return; }';assert old in s
    s=s.replace(old,'if (!nodeUp || !Args.isTokenId(tokenid)) { cb.ready(java.util.Collections.emptyList()); return; }');p.write_text(s)
