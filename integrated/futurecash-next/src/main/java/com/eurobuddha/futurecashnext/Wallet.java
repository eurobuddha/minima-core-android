package com.eurobuddha.futurecashnext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything about addresses: which are ours, the contract registration, the rescue destination,
 * and the Harden/Recover machinery for reused change addresses.
 *
 * <p>Worker-thread only (it blocks on {@link Node}).
 */
public final class Wallet {

    /** Never retire so many defaults that the node's change pool could hit zero and break every
     *  change-making transaction. A restart refills the pool to 64. */
    public static final int MIN_DEFAULTS_KEEP = 8;

    private final Node node;
    private final Cfg cfg;

    public Wallet(Node node, Cfg cfg) { this.node = node; this.cfg = cfg; }

    /* ================= address identity ================= */

    /** Canonical form so hex and Mx spellings of the same address compare equal within their kind. */
    public static String canonAddr(String a) {
        String s = a == null ? "" : a.trim();
        if (s.toLowerCase().startsWith("0x")) return "h" + s.substring(2).toLowerCase();
        if (s.startsWith("Mx") || s.startsWith("MX")) return "m" + s;
        if (s.matches("^[0-9a-fA-F]{8,}$")) return "h" + s.toLowerCase();
        return "x" + s.toLowerCase();
    }

    /**
     * Do WE hold the key for this script row — is it an address of ours rather than a contract?
     *
     * <p>This must NOT be written as {@code default || publickey || …}. Every custom script row
     * carries the literal STRING "0x00" as its publickey, and "0x00" is truthy. That made every
     * contract the node tracks — this covenant included — read as an address we own, so any
     * chain-wide stake paying out to one of them passed the ownership gate and could be collected
     * with the user's own burn.
     */
    public static boolean hasOwnKey(JSONObject scriptRow) {
        if (scriptRow == null) return false;
        if (scriptRow.optBoolean("default", false)) return true;
        final String pk = scriptRow.optString("publickey", "");
        return !pk.isEmpty() && !"0x00".equals(pk);
    }

    public static final class AddressSets {
        public final Set<String> ownDefault = new HashSet<>();   // canonAddr of addresses we can sign for
        public final Set<String> allTracked = new HashSet<>();   // raw forms of everything the node tracks
    }

    public AddressSets addressSets() {
        final AddressSets sets = new AddressSets();
        final JSONObject r = node.cmd("scripts");
        final JSONArray arr = Node.arr(r);
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            final String addr = s.optString("address", ""), mx = s.optString("miniaddress", "");
            if (!addr.isEmpty()) sets.allTracked.add(addr.toLowerCase());
            if (!mx.isEmpty()) sets.allTracked.add(mx);
            if (hasOwnKey(s)) {
                if (!addr.isEmpty()) sets.ownDefault.add(canonAddr(addr));
                if (!mx.isEmpty()) sets.ownDefault.add(canonAddr(mx));
            }
        }
        return sets;
    }

    public static boolean owns(Set<String> own, String addr) {
        return addr != null && !addr.isEmpty() && own.contains(canonAddr(addr));
    }

    /** One scripts row for an address (the node answers with a single object when queried this way). */
    public JSONObject scriptRowFor(String addr) {
        if (!Node.validAddr(addr)) return null;   // command-injection choke point — see Node.validAddr
        final JSONObject r = node.cmd("scripts address:" + addr);
        final JSONArray a = Node.arr(r);
        final JSONObject s = a.length() > 0 ? a.optJSONObject(0) : null;
        return (s != null && !s.optString("script", "").isEmpty()) ? s : null;
    }

    public boolean addressIsLocal(String addr) {
        if (!Node.validAddr(addr)) return false;
        final JSONObject r = node.cmd("scripts address:" + addr);
        final JSONArray a = Node.arr(r);
        for (int i = 0; i < a.length(); i++) if (hasOwnKey(a.optJSONObject(i))) return true;
        return false;
    }

    /* ================= the contract ================= */

    private static String cleanScript(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /**
     * Assert the covenant exists in THIS node's script table, and return its address.
     *
     * <p>Call at every start — never gate it on the cached address. The cache lives in app storage
     * and the script lives in the node, so a node restored, re-synced or rebuilt keeps the cache and
     * loses the script. Detection still works and locking still works (a send needs no script), so
     * the gap only shows at SPEND time: the collect attaches no script, the post reports success, the
     * chain rejects it, and the collect retries forever in silence.
     *
     * <p>We ASK THE NODE first and only run {@code newscript} when the script is really missing.
     * {@code newscript} is NOT an idempotent no-op — core removes then re-adds the script as two
     * separate calls, so re-registering briefly leaves the table with NO covenant. This app posts
     * collects from a background service, and a build landing in that window would attach no script,
     * reproducing the exact bug this check exists to prevent.
     *
     * <p>TRACKALL MUST STAY FALSE. The covenant address is shared by every FutureCash and Maximize
     * user alive, so tracking it makes EVERY stake on the chain relevant to this node. With
     * trackall:false a stake is kept only because state port 2 (its payout) is one of OUR addresses,
     * which core tracks for us — exactly "my stakes only".
     */
    public String registerContract() {
        final String cached = cfg.get(Cfg.SCRIPT_ADDRESS, null);
        if (cached != null) {
            final JSONObject row = scriptRowFor(cached);
            if (row != null) {
                if (row.optBoolean("track", false)) {
                    // track=true means this node was importing every FutureCash/Maximize stake on the
                    // chain. Replace the row to flip the flag.
                    cfg.log(Cfg.LVL_WARN, "Repairing contract registration — this node was tracking every "
                            + "FutureCash/Maximize stake on the chain, not just yours");
                    return registerNow();
                }
                return cached;   // present and correctly scoped — touch nothing, log nothing
            }
            return registerNow();   // cache survived a restore that dropped the script
        }
        // No cached address: it may still be registered from an earlier install, so look by text first.
        final JSONObject r = node.cmd("scripts");
        final JSONArray arr = Node.arr(r);
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            if (cleanScript(s.optString("script", "")).equals(cleanScript(FutureCashContract.SCRIPT))) {
                if (s.optBoolean("track", false)) return registerNow();
                return cacheAddress(s.optString("address", ""));
            }
        }
        return registerNow();
    }

    private String registerNow() {
        final JSONObject r = node.cmd("newscript trackall:false clean:true script:\""
                + FutureCashContract.SCRIPT + "\"");
        final JSONObject resp = Node.obj(r);
        return cacheAddress(resp == null ? "" : resp.optString("address", ""));
    }

    private String cacheAddress(String addr) {
        if (addr == null || addr.isEmpty()) return null;
        final String prev = cfg.get(Cfg.SCRIPT_ADDRESS, null);
        cfg.set(Cfg.SCRIPT_ADDRESS, addr);
        if (!addr.equals(prev)) cfg.log(Cfg.LVL_INFO, "FutureCash contract address | address:" + addr);
        return addr;
    }

    public String contractAddress() {
        final String cached = cfg.get(Cfg.SCRIPT_ADDRESS, null);
        return cached != null ? cached : registerContract();
    }

    /* ================= the rescue destination ================= */

    public static final class Res {
        public final boolean ok;
        public final String value;    // address on success
        public final String error;
        private Res(boolean ok, String value, String error) { this.ok = ok; this.value = value; this.error = error; }
        public static Res ok(String v) { return new Res(true, v, null); }
        public static Res err(String e) { return new Res(false, null, e); }
    }

    /**
     * A single fresh CLEAN address on THIS node where rescued funds land. Minted once and reused.
     * Stored as one atomic blob so a concurrent mint can't pair one address's hex with another's Mx.
     */
    public Res cleanAddress() {
        final String[] blob = cfg.cleanAddr();
        if (blob != null && Node.validMx(blob[0]) && Node.validHexAddr(blob[1])) return Res.ok(blob[0]);

        // Minting CREATES a key pair, which a locked vault cannot do — `newaddress` just fails.
        // Check first so the user gets the real reason and a fix instead of a dead-end.
        if (vaultLocked()) {
            return Res.err("Your node's keys are locked, so it can't make a new address. "
                    + "Unlock your node (Vault), then try again.");
        }
        final JSONObject r = node.cmd("newaddress");
        if (Node.pending(r)) {
            return Res.err("The embedded node did not authorize the request. Check its status before retrying.");
        }
        final JSONObject resp = Node.obj(r);
        final String mx = resp == null ? "" : resp.optString("miniaddress", "");
        final String hex = resp == null ? "" : resp.optString("address", "");
        if (!Node.validMx(mx) || !Node.validHexAddr(hex)) {
            // Surface the node's own error — swallowing it made a locked vault, a permissions hold
            // and a malformed reply all look identical.
            return Res.err("Couldn't make a fresh address on this node."
                    + (r != null && !r.optString("error", "").isEmpty() ? " (" + r.optString("error") + ")" : ""));
        }
        cfg.setCleanAddr(mx, hex);
        cfg.log(Cfg.LVL_INFO, "Clean same-node destination set | address:" + mx);
        return Res.ok(mx);
    }

    /** The hex form of the minted clean address, for exact-match lookups. */
    public String cleanAddressHex() {
        final String[] blob = cfg.cleanAddr();
        return blob == null ? null : blob[1];
    }

    /**
     * Point the rescue at an EXTERNAL wallet.
     *
     * @param reuseCount -1 = the reuse database couldn't be reached (accept, unverified),
     *                   0 = verified clean, >0 = refuse.
     */
    public Res setSafeExternal(String addr, long reuseCount) {
        final String a = addr == null ? "" : addr.trim();
        if (!Node.validMx(a)) return Res.err("Not a valid Mx address (must start with Mx).");
        if (reuseCount > 0) {
            return Res.err("That address has already signed " + reuseCount + " time(s) — its key is "
                    + "reused. Sweeping your rescued money there would move it onto another exposed "
                    + "key. Use a fresh address from a clean wallet.");
        }
        final String localErr = "That address belongs to THIS node. Use an EXTERNAL wallet "
                + "(a different node/seed) with fresh keys.";
        final AddressSets sets = addressSets();
        if (sets.allTracked.contains(a)) return Res.err(localErr);
        if (addressIsLocal(a)) return Res.err(localErr);

        cfg.set(Cfg.SAFE_MODE, "external");
        cfg.set(Cfg.SAFE_ADDRESS, a);
        if (!a.equals(cfg.get(Cfg.SAFE_ADDRESS, null))) {
            return Res.err("Write did NOT persist (app storage is failing on this device).");
        }
        // Clear any flag left over from a PREVIOUS safe address — it described that address, not this.
        new Audit(cfg, node).setSafeReused(null, 0);
        cfg.log(Cfg.LVL_INFO, "Safe address set (external)"
                + (reuseCount == 0 ? ", verified clean" : " — reuse check UNVERIFIED, will re-check")
                + " | address:" + a);
        return Res.ok(a);
    }

    /** Point the rescue at a fresh clean address on THIS node (for single-device users). */
    public Res setSafeSameNode() {
        final Res r = cleanAddress();
        if (!r.ok) return r;
        cfg.set(Cfg.SAFE_MODE, "samenode");
        cfg.set(Cfg.SAFE_ADDRESS, r.value);
        cfg.log(Cfg.LVL_INFO, "Safe destination = a fresh same-node address | address:" + r.value);
        return Res.ok(r.value);
    }

    public boolean sameNodeMode() { return "samenode".equals(cfg.get(Cfg.SAFE_MODE, "external")); }

    /**
     * Is the configured safe usable as a sweep destination?
     *
     * <p>Whether the safe is a KNOWN-REUSED address is deliberately NOT tested here. That answer
     * comes from the reuse database, and it must not feed this check — a false here would also stop
     * sweeping coins that are ALREADY stranded on an exposed address, which is strictly worse than
     * moving them. The reuse gate applies to NEW at-risk collects only.
     */
    public boolean safeUsable(String safe, Set<String> allTracked, Set<String> atRisk) {
        if (!Node.validMx(safe)) return false;
        if (sameNodeMode()) return !atRisk.contains(canonAddr(safe));
        return !allTracked.contains(safe);
    }

    /**
     * Destination for a MANUAL sweep: honour the configured EXTERNAL safe when rescue is on, so
     * Recover lands funds where the automatic rescue does. Otherwise a fresh same-node clean address.
     */
    public Res sweepDestination() {
        final String safe = cfg.get(Cfg.SAFE_ADDRESS, "");
        if (cfg.is(Cfg.ENABLED, false) && !sameNodeMode() && Node.validMx(safe)) return Res.ok(safe);
        return cleanAddress();
    }

    public boolean vaultLocked() {
        final JSONObject resp = Node.obj(node.cmd("status"));
        return resp != null && resp.optBoolean("locked", false);
    }

    /* ================= retired addresses (Harden / Recover) ================= */

    private static final Pattern SIGNEDBY = Pattern.compile("SIGNEDBY\\((0x[0-9a-fA-F]+)\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PURE_SIGNEDBY = Pattern.compile("^RETURN\\s+SIGNEDBY\\((0x[0-9a-fA-F]+)\\)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * The signing key for an address, taken from its SIGNEDBY script.
     *
     * <p>Falls back to {@code auto} ONLY when the row still carries a real public key. For a RETIRED
     * address the row's key is nulled to 0x00, and {@code publickey:auto} then attaches nothing — so
     * "auto" there is not a fallback, it is a transaction guaranteed to post unsigned, burn a retry
     * and eventually trip the give-up alarm. Retired addresses are precisely what the stranded-coin
     * sweep exists to drain, so this is the common case, not the exotic one.
     *
     * @return the key to sign with, or null when this address cannot be signed for
     */
    public String signKeyFor(String addr) {
        final JSONObject row = scriptRowFor(addr);
        if (row == null) return null;
        final Matcher m = SIGNEDBY.matcher(row.optString("script", ""));
        if (m.find()) return m.group(1);
        final String pk = row.optString("publickey", "");
        return (!pk.isEmpty() && !"0x00".equals(pk)) ? "auto" : null;
    }

    /**
     * The node's own RETIRED addresses: script-row publickey nulled to 0x00, script EXACTLY
     * {@code RETURN SIGNEDBY(0x<key>)} of one of THIS node's keys. Excludes foreign/compound
     * contracts and other people's watch scripts. Retiring leaves the key in the keys table, so
     * these are still sweepable with the explicit key.
     */
    public List<String> retiredAddresses() {
        final List<String> out = new ArrayList<>();
        final JSONObject kr = node.cmd("keys action:list");
        final JSONObject kresp = Node.obj(kr);
        final JSONArray keys = kresp == null ? null : kresp.optJSONArray("keys");
        final Set<String> ours = new HashSet<>();
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                final JSONObject k = keys.optJSONObject(i);
                if (k != null) ours.add(k.optString("publickey", "").toLowerCase());
            }
        }
        final JSONArray arr = Node.arr(node.cmd("scripts"));
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            if (s.optBoolean("default", false)) continue;
            if (!"0x00".equals(s.optString("publickey", ""))) continue;
            final String addr = s.optString("address", "");
            if (addr.isEmpty()) continue;
            final Matcher m = PURE_SIGNEDBY.matcher(cleanScript(s.optString("script", "")));
            if (!m.matches()) continue;
            if (!ours.contains(m.group(1).toLowerCase())) continue;   // not a pure single-sig of OUR key
            out.add(addr);
        }
        return out;
    }

    /** Unspent coins sitting on an address. */
    public List<JSONObject> coinsAt(String addr) {
        final List<JSONObject> out = new ArrayList<>();
        if (!Node.validAddr(addr)) return out;
        final JSONObject r = node.cmd("coins address:" + addr);
        if (!Node.ok(r)) return out;
        final JSONArray a = Node.arr(r);
        for (int i = 0; i < a.length(); i++) {
            final JSONObject c = a.optJSONObject(i);
            if (c != null && !c.optBoolean("spent", false) && !c.optString("coinid", "").isEmpty()) out.add(c);
        }
        return out;
    }

    /** Retired addresses that STILL hold coins. */
    public List<String[]> retiredWithCoins() {
        final List<String[]> out = new ArrayList<>();
        for (String a : retiredAddresses()) {
            final int n = coinsAt(a).size();
            if (n > 0) out.add(new String[]{a, String.valueOf(n)});
        }
        return out;
    }

    /** One of the node's reused addresses, for the Harden panel. */
    public static final class ReusedAddr {
        public String hex, mx, script;
        public boolean isDefault;
        public double balance;
        public int coins;
    }

    /** The node's own reused addresses, deduped, with whether each is still an active default. */
    public List<ReusedAddr> reusedDefaults(JSONArray atRiskList) {
        final List<ReusedAddr> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < atRiskList.length(); i++) {
            final String a = atRiskList.optString(i, "");
            if (!Node.validAddr(a)) continue;   // remote-sourced — never build a command from it unchecked
            final JSONObject s = scriptRowFor(a);
            if (s == null) continue;
            final String hex = s.optString("address", "");
            if (hex.isEmpty() || !seen.add(hex.toLowerCase())) continue;
            // Only OUR addresses; someone else's reused payout we happen to track is not ours to retire.
            final String pk = s.optString("publickey", "");
            final boolean mine = (!pk.isEmpty() && !"0x00".equals(pk))
                    || SIGNEDBY.matcher(s.optString("script", "")).find();
            if (!mine) continue;

            final ReusedAddr ra = new ReusedAddr();
            ra.hex = hex;
            ra.mx = s.optString("miniaddress", "");
            ra.script = s.optString("script", "");
            ra.isDefault = s.optBoolean("default", false);
            for (JSONObject c : coinsAt(hex)) {
                ra.coins++;
                if ("0x00".equals(c.optString("tokenid", ""))) {
                    try { ra.balance += Double.parseDouble(c.optString("amount", "0")); } catch (Exception ignored) {}
                }
            }
            out.add(ra);
        }
        return out;
    }

    /** Live default change addresses, or -1 if unknown. */
    public int countDefaults() {
        final JSONObject r = node.cmd("scripts");
        if (!Node.ok(r)) return -1;
        final JSONArray a = Node.arr(r);
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            final JSONObject s = a.optJSONObject(i);
            if (s != null && s.optBoolean("default", false)) n++;
        }
        return n;
    }

    /**
     * Does any stake still pay out to this address? If so it must NOT be retired — collecting one
     * would deposit funds here, onto a nulled key.
     *
     * <p>This keeps the broad contract-address scan that detection deliberately dropped, and the
     * asymmetry is the point: this is a REFUSAL gate, so over-detecting costs the user a retire they
     * could have had, while under-detecting strands real funds. It is user-initiated, not per-block,
     * so the cost is irrelevant.
     */
    public boolean addressHasStakes(String payoutAddr) {
        final JSONObject srow = scriptRowFor(payoutAddr);
        final String target = canonAddr(srow != null ? srow.optString("address", payoutAddr) : payoutAddr);
        final String targetRaw = canonAddr(payoutAddr);

        if (scanForPayout(node.cmd("coins relevant:true"), target, targetRaw)) return true;
        final String contract = contractAddress();
        if (contract != null && scanForPayout(node.cmd("coins address:" + contract), target, targetRaw)) return true;
        // Also the hardcoded canonical address. Not redundant: the address is a hash of the exact
        // script TEXT, so a node that registered with a different `clean` setting derives a different
        // one, and this gate must not miss a stake because of that (see FutureCashContract).
        if (!FutureCashContract.KNOWN_ADDRESS.equalsIgnoreCase(contract)) {
            return scanForPayout(node.cmd("coins address:" + FutureCashContract.KNOWN_ADDRESS), target, targetRaw);
        }
        return false;
    }

    private static boolean scanForPayout(JSONObject r, String target, String targetRaw) {
        final JSONArray a = Node.arr(r);
        for (int i = 0; i < a.length(); i++) {
            final JSONObject c = a.optJSONObject(i);
            if (c == null || c.optBoolean("spent", false)) continue;
            if (!Guardian.looksLikeFutureCash(c)) continue;
            final String p = Node.state(c, FutureCashContract.ST_RECIPIENT);
            if (p == null) continue;
            final String cp = canonAddr(p);
            if (cp.equals(target) || cp.equals(targetRaw)) return true;
        }
        return false;
    }

    /**
     * Retire ONE reused default: refuse if a stake still pays to it, refuse while it holds coins,
     * refuse if it would starve the change pool, then flip it to watch-only non-default.
     *
     * <p>Minima routes ALL automatic change through a random pick from the node's default addresses,
     * and there is no "avoid" flag — so change keeps landing on reused addresses. We can't heal a
     * reused address (the leaf is already exposed), but we CAN stop the node ever funding it again.
     * Moves no funds. Survives restarts, but NOT a reseed (the defaults regenerate deterministically).
     */
    public Res retireAddress(String addr) {
        if (addressHasStakes(addr)) {
            return Res.err("Stakes still pay out to this address — they'd land here when they mature "
                    + "and get stuck on the nulled key. Leave it live; the guardian keeps sweeping.");
        }
        final JSONObject s = scriptRowFor(addr);
        if (s == null) return Res.err("Address not found on this node.");
        if (!s.optBoolean("default", false)) {
            return Res.err("Not an active default change address (already retired, or not one of yours).");
        }
        final String script = s.optString("script", "");
        if (!script.toUpperCase().contains("SIGNEDBY") || script.contains("\"")) {
            return Res.err("Unexpected script for a default address — not retiring.");
        }
        final String hex = s.optString("address", "");

        final JSONObject cr = node.cmd("coins address:" + hex);
        // Fail SAFE: a bad or empty reply must never be read as "empty".
        if (!Node.ok(cr) || cr.optJSONArray("response") == null) {
            return Res.err("Couldn't check this address's coins right now — try again in a moment.");
        }
        int unspent = 0;
        final JSONArray coins = cr.optJSONArray("response");
        for (int i = 0; i < coins.length(); i++) {
            final JSONObject c = coins.optJSONObject(i);
            if (c != null && !c.optBoolean("spent", false)) unspent++;
        }
        if (unspent > 0) {
            return Res.err("This address still holds " + unspent + " coin(s). Sweep them to safety "
                    + "first (they're on an exposed key), then retire it.");
        }
        final int nDef = countDefaults();
        if (nDef < 0) return Res.err("Couldn't check your change-address pool — try again.");
        if (nDef <= MIN_DEFAULTS_KEEP) {
            return Res.err("Keep at least " + MIN_DEFAULTS_KEEP + " change addresses. Restart your node "
                    + "to generate fresh clean ones, then retire more.");
        }
        final JSONObject nr = node.cmd("newscript trackall:true script:\"" + script + "\"");
        if (Node.pending(nr)) {
            return Res.err("The embedded node did not authorize the request. Check its status before retrying.");
        }
        if (!Node.ok(nr)) return Res.err(Node.error(nr));
        final JSONObject v = scriptRowFor(hex);
        if (v != null && v.optBoolean("default", false)) {
            return Res.err("Retire didn't take effect — the address is still a default.");
        }
        cfg.log(Cfg.LVL_INFO, "Retired a reused default address — off the change rotation, "
                + "kept watch-only | address:" + hex);
        return Res.ok(hex);
    }

    /**
     * Sweep every coin off an address to the rescue destination, signing with that address's real
     * key once per coin. Acceptable because the address has already been reused and speed is the
     * protection. Naturally idempotent — spent coins don't reappear.
     */
    public SweepReport sweepAddressToClean(String reusedAddr) {
        final SweepReport rep = new SweepReport();
        if (!Node.validAddr(reusedAddr)) { rep.error = "Invalid address."; return rep; }
        final JSONObject s = scriptRowFor(reusedAddr);
        if (s == null) { rep.error = "Address not found on this node."; return rep; }
        final String pk = s.optString("publickey", "");
        final boolean mine = (!pk.isEmpty() && !"0x00".equals(pk))
                || SIGNEDBY.matcher(s.optString("script", "")).find();
        if (!mine) { rep.error = "That isn't one of your signable addresses."; return rep; }

        final String fromHex = s.optString("address", "");
        final String signKey = signKeyFor(fromHex);
        if (signKey == null) {
            rep.error = "This node has no signing key for that address — it can't be swept from here.";
            return rep;
        }

        final Res dest = sweepDestination();
        if (!dest.ok) { rep.error = dest.error; return rep; }
        final String destAddr = dest.value;
        if (canonAddr(destAddr).equals(canonAddr(fromHex))
                || (cleanAddressHex() != null && canonAddr(cleanAddressHex()).equals(canonAddr(fromHex)))) {
            rep.error = "Destination equals the source address.";
            return rep;
        }

        final List<JSONObject> coins = coinsAt(fromHex);
        if (coins.isEmpty()) { rep.ok = true; rep.dest = destAddr; return rep; }

        for (JSONObject c : coins) {
            final String coinid = c.optString("coinid", "");
            final Tx.Result r = Tx.sweep(node, coinid, destAddr, Node.outAmount(c),
                    c.optString("tokenid", "0x00"), signKey);
            if (r.ok) {
                rep.swept++;
                cfg.log(Cfg.LVL_INFO, "Swept " + Node.outAmount(c) + " | coin:" + coinid
                        + " | from:" + fromHex + " | to safe:" + destAddr);
            } else if (r.notPaired) {
                rep.error = r.error;
                return rep;   // every remaining coin would fail identically
            } else {
                rep.failed++;
                rep.error = r.error;
                cfg.log(Cfg.LVL_WARN, "Couldn't sweep | coin:" + coinid + " | on:" + fromHex + " — " + r.error);
            }
        }
        rep.ok = rep.swept > 0;
        rep.dest = destAddr;
        cfg.log(Cfg.LVL_INFO, "Swept " + rep.swept + " coin(s) off a reused address"
                + (rep.failed > 0 ? " (" + rep.failed + " failed)" : "")
                + " | from:" + fromHex + " | to:" + destAddr);
        return rep;
    }

    public static final class SweepReport {
        public boolean ok;
        public int swept, failed;
        public String dest, error;
    }

    /** Sweep every stuck coin off retired addresses. Undoes the retire damage without a reseed. */
    public SweepReport recoverRetiredCoins() {
        final SweepReport total = new SweepReport();
        for (String[] a : retiredWithCoins()) {
            final SweepReport r = sweepAddressToClean(a[0]);
            total.swept += r.swept;
            total.failed += r.failed;
            total.dest = r.dest != null ? r.dest : total.dest;
            if (r.error != null && r.swept == 0) total.error = r.error;
        }
        total.ok = total.swept > 0 || total.error == null;
        return total;
    }
}
