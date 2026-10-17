package com.eurobuddha.utxo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads the wallet's coin set within the node's reply cap. A node older than 1.3.0 has no file
 * hand-off, so any reply over 256,000 chars comes back as the "Result too long" stub (NodeApi turns
 * it into {@link NodeApi#ERR_TOO_LONG}). Token coins embed their token metadata, so even a
 * ~200-coin wallet overflows the single {@code coins relevant:true}. Tiers:
 *   1. whole list                                   coins relevant:true
 *   2. per token (from balance)                      coins relevant:true tokenid:T
 *   3. per token per tracked script address          coins relevant:true tokenid:T address:A
 * Each slice is fetched twice (plain, then sendable:true) and committed only when both fit.
 * {@code expected} is the sum of balance.coins so the UI can say how many coins it could NOT show.
 */
final class CoinLoader {

    interface Done {
        /** mode: "whole" | "per-token" | "per-address". expected &lt; 0 when the balance count is unknown. */
        void onCoins(List<Coin> coins, Set<String> sendableIds, int expected, String mode);
        void onError(String message);
    }

    static boolean isTooLong(String m) { return NodeApi.isTooLong(m); }

    private interface Ok { void run(); }
    private interface Fail { void run(String message); }

    private final MainActivity act;
    private final Done done;
    private final List<Coin> coins = new ArrayList<>();
    private final Set<String> sendable = new HashSet<>();
    private int expected = -1;
    private String mode = "whole";
    private boolean finished = false;

    private CoinLoader(MainActivity act, Done done) { this.act = act; this.done = done; }

    static void load(MainActivity act, Done done) { new CoinLoader(act, done).whole(); }

    // ---- tier 1 ----
    private void whole() {
        slice("coins relevant:true", this::finish, err -> { if (isTooLong(err)) perToken(); else fail(err); });
    }

    // ---- tier 2 ----
    private void perToken() {
        mode = "per-token";
        act.node().cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                List<String> tokens = new ArrayList<>();
                int sum = 0;
                JSONArray arr = json.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    tokens.add(b.optString("tokenid", Util.MINIMA_TOKENID));
                    sum += b.optInt("coins", 0);
                }
                expected = sum;
                nextToken(tokens, 0);
            }
            @Override public void onError(String message) { fail(message); }
        });
    }

    private void nextToken(List<String> tokens, int i) {
        if (i >= tokens.size()) { finish(); return; }
        String tid = tokens.get(i);
        slice("coins relevant:true tokenid:" + tid, () -> nextToken(tokens, i + 1),
                err -> { if (isTooLong(err)) perAddress(tid, () -> nextToken(tokens, i + 1)); else fail(err); });
    }

    // ---- tier 3 ----
    private List<String> scriptAddrs;   // every tracked script address (defaults + custom/contract), hex

    private void perAddress(String tid, Ok then) {
        mode = "per-address";
        if (scriptAddrs != null) { nextAddr(tid, 0, then); return; }
        act.node().cmd("scripts", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                JSONArray arr = json.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s == null) continue;
                    String hex = s.optString("address", "");
                    if (!hex.isEmpty()) seen.add(hex);
                }
                scriptAddrs = new ArrayList<>(seen);
                nextAddr(tid, 0, then);
            }
            @Override public void onError(String message) { fail(message); }
        });
    }

    private void nextAddr(String tid, int j, Ok then) {
        if (j >= scriptAddrs.size()) { then.run(); return; }
        slice("coins relevant:true tokenid:" + tid + " address:" + scriptAddrs.get(j),
                () -> nextAddr(tid, j + 1, then),
                // One address × one token still over the cap: nothing finer exists — skip that slice;
                // the expected-vs-loaded gap tells the user how many coins are hidden.
                err -> { if (isTooLong(err)) nextAddr(tid, j + 1, then); else fail(err); });
    }

    // ---- one slice = plain query + its sendable:true twin, committed together ----
    private void slice(String base, Ok ok, Fail failCb) {
        act.node().cmd(base, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONArray arr = json.optJSONArray("response");
                if (arr == null) { failCb.run("Coin list unavailable — the node returned an invalid reply."); return; }
                List<Coin> got = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c != null) got.add(Coin.from(c));
                }
                act.node().cmd(base + " sendable:true", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json2) {
                        JSONArray a2 = json2.optJSONArray("response");
                        if (a2 != null) for (int i = 0; i < a2.length(); i++) {
                            JSONObject c = a2.optJSONObject(i);
                            if (c != null) sendable.add(c.optString("coinid", ""));
                        }
                        coins.addAll(got);
                        ok.run();
                    }
                    @Override public void onError(String message) { failCb.run(message); }
                });
            }
            @Override public void onError(String message) { failCb.run(message); }
        });
    }

    private void finish() {
        if (finished) return;
        finished = true;
        // De-duplicate (a coin can match more than one slice only if the node repeats it; be safe).
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        List<Coin> out = new ArrayList<>();
        for (Coin c : coins) if (ids.add(c.coinid)) out.add(c);
        for (Coin c : out) c.sendable = sendable.contains(c.coinid);
        done.onCoins(out, sendable, expected, mode);
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        done.onError(message);
    }
}
