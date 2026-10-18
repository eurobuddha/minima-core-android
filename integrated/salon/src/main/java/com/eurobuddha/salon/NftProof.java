package com.eurobuddha.salon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Trustless proof that a profile owner HOLDS an on-chain asset — the Axe S3 mechanism,
 * adapted for a public profile. No faking: a hosted list is never trusted; the VIEWER's
 * own node independently verifies each showcased item.
 *
 * Owner produces per-asset: {tokenid, coinid, coinproof (coinexport MMR blob), script,
 * publickey, sig} where sig = sign(coin's key, the owner's Salon tokenid) — binding the
 * coin holder to THIS profile so a stranger can't reuse someone else's valid proof.
 *
 * Viewer verifies (all on their own node): coincheck(coinproof) → the coin exists,
 * unspent, holds tokenid X; runscript(script) → that script hashes to the coin's address
 * (so `publickey` really controls it); verify(salonTokenid, publickey, sig) → the holder
 * signed THIS profile's id. All pass ⇒ "verified holding". Live: if the owner sells the
 * asset the coin is spent and coincheck fails, so the badge vanishes.
 *
 * v1 works for assets held at a plain wallet address (script RETURN SIGNEDBY(pk)) —
 * standard NFTs/tokens incl. signed identity tokens. (StateNFT collection items sit at a
 * covenant script, not SIGNEDBY(holder), so they're out of scope here.)
 */
final class NftProof {

    interface Gen { void ok(JSONObject item); void fail(String message); }
    interface Verify { void result(boolean verified, String reason, String stateImage); }
    interface Held { void result(boolean held, JSONObject coin); }

    /** LIVE holding check on THIS node — do we currently hold an unspent coin of {@code tokenid}?
     *  Definitive and never stale (Axe-S3 checks live too), so it verifies the owner viewing their
     *  own page without depending on the frozen coinexport proof, which ages out as the MMR grows. */
    static void holds(final NodeApi node, final String tokenid, final Held cb) {
        if (!safeId(tokenid)) { cb.result(false, null); return; }
        node.cmd("coins relevant:true sendable:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray a = ok(j) ? j.optJSONArray("response") : null;
                JSONObject coin = a == null || a.length() == 0 ? null : a.optJSONObject(0);
                boolean held = matchingCoin(coin, tokenid);
                cb.result(held, held ? coin : null);
            }
            @Override public void onError(String m) { cb.result(false, null); }
        });
    }

    /** bindHex = hex of the owner's Salon tokenid (identity to bind the holding to). */
    static void generate(final NodeApi node, final String tokenid, final String bindHex, final Gen cb) {
        generate(node, tokenid, bindHex, () -> true, cb);
    }

    static void generate(final NodeApi node, final String tokenid, final String bindHex,
            final java.util.function.BooleanSupplier current, final Gen cb) {
        if (!current.getAsBoolean()) { cb.fail("Proof request is no longer current."); return; }
        if (!safeId(tokenid) || !validBinding(bindHex)) { cb.fail("incomplete proof"); return; }
        node.cmd("coins relevant:true sendable:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject r) {
                if (!current.getAsBoolean()) { cb.fail("Proof request is no longer current."); return; }
                JSONArray arr = ok(r) ? r.optJSONArray("response") : null;
                if (arr == null || arr.length() == 0) { cb.fail("You don't hold a spendable coin of this asset."); return; }
                JSONObject coin = arr.optJSONObject(0);
                if (!matchingCoin(coin, tokenid)) { cb.fail("coin has no id/address"); return; }
                final String coinid = coin.optString("coinid", ""), address = coin.optString("address", "");
                if (coinid.isEmpty() || address.isEmpty()) { cb.fail("coin has no id/address"); return; }
                node.cmd("coinexport coinid:" + coinid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject e) {
                        if (!current.getAsBoolean()) { cb.fail("Proof request is no longer current."); return; }
                        JSONObject er = ok(e) ? e.optJSONObject("response") : null;
                        final String proof = text(er, "data");
                        if (!hexBytes(proof, 1_048_576)) { cb.fail("could not export coin proof"); return; }
                        node.cmd("scripts address:" + address, new NodeApi.Cb() {
                            @Override public void onResult(JSONObject s) {
                                if (!current.getAsBoolean()) { cb.fail("Proof request is no longer current."); return; }
                                JSONObject sr = ok(s) ? firstObj(s.opt("response")) : null;
                                final String script = text(sr, "script");
                                final String pk = text(sr, "publickey");
                                if (!simpleScript(script, pk) || !Boolean.TRUE.equals(sr.opt("simple")) || !address.equalsIgnoreCase(text(sr, "address"))) { cb.fail("address is not a simple wallet address (can't prove holding)"); return; }
                                node.cmd("sign publickey:" + pk + " data:" + bindHex, new NodeApi.Cb() {
                                    @Override public void onResult(JSONObject sg) {
                                        if (!current.getAsBoolean()) { cb.fail("Proof request is no longer current."); return; }
                                        if (!ok(sg)) { cb.fail("signing failed (is your node unlocked?)"); return; }
                                        String sig = text(sg, "response");
                                        if (!hexBytes(sig, 1_048_576)) { cb.fail("no signature returned"); return; }
                                        try {
                                            JSONObject item = new JSONObject();
                                            item.put("tokenid", tokenid); item.put("coinid", coinid);
                                            item.put("coinproof", proof); item.put("script", script);
                                            item.put("publickey", pk); item.put("sig", sig);
                                            cb.ok(item);
                                        } catch (Exception ex) { cb.fail(ex.getMessage()); }
                                    }
                                    @Override public void onError(String m) { cb.fail(m); }
                                });
                            }
                            @Override public void onError(String m) { cb.fail(m); }
                        });
                    }
                    @Override public void onError(String m) { cb.fail(m); }
                });
            }
            @Override public void onError(String m) { cb.fail(m); }
        });
    }

    /** Verify a showcase item against {@code bindHex} (hex of the viewed profile's tokenid). */
    static void verify(final NodeApi node, final JSONObject item, final String bindHex, final Verify cb) {
        final String tokenid = text(item, "tokenid"), coinid = text(item, "coinid"), proof = text(item, "coinproof"),
                script = text(item, "script"), pk = text(item, "publickey"), sig = text(item, "sig");
        if (!safeId(tokenid) || !safeId(coinid) || !safeId(pk) || !validBinding(bindHex)
                || !hexBytes(proof, 1_048_576) || !hexBytes(sig, 1_048_576)) {
            cb.result(false, "incomplete proof", ""); return;
        }
        if (!simpleScript(script, pk)) { cb.result(false, "key doesn't control the coin", ""); return; }
        node.cmd("coincheck data:" + proof, new NodeApi.Cb() {
            @Override public void onResult(JSONObject c) {
                JSONObject cr = ok(c) ? c.optJSONObject("response") : null;
                JSONObject coin = cr == null ? null : cr.optJSONObject("coin");
                if (cr == null || coin == null) { cb.result(false, "coin not found on-chain", ""); return; }
                if (!tokenid.equalsIgnoreCase(coin.optString("tokenid"))) { cb.result(false, "wrong token", ""); return; }
                if (Boolean.TRUE.equals(coin.opt("spent"))) { cb.result(false, "coin has been spent", ""); return; }
                if (!matchingCoin(coin, tokenid) || !coinid.equalsIgnoreCase(text(coin, "coinid"))) {
                    cb.result(false, "coin not found on-chain", ""); return;
                }
                // valid=false with an UNSPENT coin means the exported MMR proof aged out, NOT that
                // the coin is gone — say so honestly (the owner's live check below covers their own view).
                if (!Boolean.TRUE.equals(cr.opt("valid"))) { cb.result(false, "proof out of date — re-publish to refresh it", ""); return; }
                final String coinAddr = coin.optString("address", "");
                // the sealed StateNFT edition art rides in the proof's coin state (port 1)
                final String stateImg = stateImageUri(coin);
                // Build only the node's plain-wallet script from a validated key.
                node.cmd("runscript script:\"RETURN SIGNEDBY(" + pk + ")\"", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject rs) {
                        JSONObject rr = ok(rs) ? rs.optJSONObject("response") : null;
                        JSONObject clean = rr == null ? null : rr.optJSONObject("clean");
                        String computed = clean == null ? "" : clean.optString("address", "");
                        if (computed.isEmpty() || !computed.equalsIgnoreCase(coinAddr) || !Boolean.TRUE.equals(rr.opt("parseok")) || !simpleScript(text(clean, "script"), pk)) { cb.result(false, "script/address mismatch", ""); return; }
                        node.cmd("verify data:" + bindHex + " publickey:" + pk + " signature:" + sig, new NodeApi.Cb() {
                            @Override public void onResult(JSONObject v) { boolean ok = ok(v) && "Signature valid".equals(text(v, "response")); cb.result(ok, ok ? "verified holding" : "signature not valid for this profile", ok ? stateImg : ""); }
                            @Override public void onError(String m) { cb.result(false, "verify call failed", ""); }
                        });
                    }
                    @Override public void onError(String m) { cb.result(false, "script check failed", ""); }
                });
            }
            @Override public void onError(String m) { cb.result(false, "coin check failed (node busy?)", ""); }
        });
    }

    /** The sealed StateNFT edition image (coin state port 1, an {@code [base64]} blob) as a
     *  data: URI, or "" for a plain token/NFT with no embedded state art. Ported from Atelier
     *  StateNft; renders via {@link ImageTools#dataUri} + ImageLoader's data: path. */
    static String stateImageUri(JSONObject coin) {
        String s = state(coin, 1);
        if (s != null && s.length() > 2 && s.startsWith("[") && s.endsWith("]"))
            return ImageTools.dataUri(s.substring(1, s.length() - 1));
        return "";
    }

    /** Per-edition image for a StateNFT coin: the embedded state art if present, else the
     *  hosted URL-mode image {@code base + index + ext} (index = state port 0), else "".
     *  Mirrors Atelier StateNft.imageUrl so both embed- and url-mode collections render. */
    static String editionImageUrl(JSONObject coin, String base, String ext) {
        String emb = stateImageUri(coin);
        if (!emb.isEmpty()) return emb;
        if (base != null && !base.isEmpty()) {
            String idx = state(coin, 0);
            if (idx != null && idx.matches("[0-9]+")) return base + idx + (ext == null || ext.isEmpty() ? ".png" : ext);
        }
        return "";
    }

    /** Read a coin's state variable at {@code port} (array or object form). */
    static String state(JSONObject coin, int port) {
        Object raw = coin == null ? null : coin.opt("state");
        if (raw instanceof JSONObject) { Object d = ((JSONObject) raw).opt(String.valueOf(port)); return d == null ? null : clean(d); }
        if (!(raw instanceof JSONArray)) return null;
        JSONArray st = (JSONArray) raw;
        for (int i = 0; i < st.length(); i++) {
            JSONObject s = st.optJSONObject(i);
            if (s != null && String.valueOf(s.opt("port")).equals(String.valueOf(port))) return clean(s.opt("data"));
        }
        return null;
    }

    private static String clean(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }

    static String hexOf(String s) {
        StringBuilder b = new StringBuilder("0x");
        for (byte x : (s == null ? "" : s).getBytes(StandardCharsets.UTF_8)) b.append(String.format("%02X", x));
        return b.toString();
    }

    private static JSONObject firstObj(Object resp) {
        if (resp instanceof JSONObject) return (JSONObject) resp;
        if (resp instanceof JSONArray && ((JSONArray) resp).length() == 1) return ((JSONArray) resp).optJSONObject(0);
        return null;
    }

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

    private NftProof() {}
}
