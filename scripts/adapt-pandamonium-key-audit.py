"""Keep the pinned Future Cash auditor aware of both supported key tree families."""
from pathlib import Path


def adapt(root):
    folder = root / 'futurecash-next/src/main/java/com/eurobuddha/futurecashnext'

    def change(filename, old, new):
        path = folder / filename
        source = path.read_text()
        assert source.count(old) == 1, ('Dual-key audit source drift', filename, old)
        path.write_text(source.replace(old, new, 1))

    change('KeyAudit.java', '    /* ---------- inputs ---------- */', """    // Shapes and capacities match PandaPools KeyUses.capacityOf and the node's
    // TreeKey.createDefault. Inspect each row; never choose one family for the wallet.
    public enum Family { CLASSIC, BLOCK, UNKNOWN }

    /* ---------- inputs ---------- */""")
    change('KeyAudit.java', """        public final int maxUses;        // 262144 for the default 64/3 tree

        public LocalKey(String publicKey, int uses, int maxUses) {
            this.publicKey = publicKey;
            this.uses = uses;
            this.maxUses = maxUses;
        }""", """        public final int maxUses;
        public final Family family;
        public final boolean valid;
        public final boolean shapeValid;

        public LocalKey(String publicKey, int uses, int maxUses) {
            this(publicKey, uses, maxUses, 0, 0);
        }

        public LocalKey(String publicKey, int uses, int maxUses, int size, int depth) {
            this.publicKey = publicKey;
            this.uses = uses;
            // Older replies omit shape: accept only the explicit legacy capacity.
            if ((size == 64 && depth == 3) || (size == 0 && depth == 0 && maxUses == 262144)) {
                family = Family.CLASSIC;
                this.maxUses = 262144;
            } else if (size == 128 && depth == 4) {
                family = Family.BLOCK;
                this.maxUses = 268435456;
            } else {
                family = Family.UNKNOWN;
                this.maxUses = 0;
            }
            shapeValid = family != Family.UNKNOWN && (maxUses == 0 || maxUses == this.maxUses);
            valid = shapeValid && uses >= 0;
        }""")
    change('KeyAudit.java', 'public enum Status { OK, AT_RISK, REUSED }', 'public enum Status { OK, AT_RISK, REUSED, UNKNOWN }')
    change('KeyAudit.java', """        public final int local;          // node's uses counter""", """        public final int local;          // classic counter or block-tree leaf position
        public final Family family;
        public final boolean checked;
        /** Both public endpoints returned matching evidence, independent of a local counter. */
        public final boolean publicChecked;""")
    change('KeyAudit.java', """            boolean risk, boolean reused, long reuseCount) {
            this.index = index;""", """            boolean risk, boolean reused, long reuseCount, Family family, boolean checked, boolean publicChecked) {
            this.family = family;
            this.checked = checked;
            this.publicChecked = publicChecked;
            this.index = index;""")
    change('KeyAudit.java', """            if (risk) return Status.AT_RISK;
            return Status.OK;""", """            if (risk) return Status.AT_RISK;
            return checked ? Status.OK : Status.UNKNOWN;""")
    change('KeyAudit.java', """        public final boolean exhaustion;

        Result""", """        public final boolean exhaustion;
        /** Every requested key has a usable shape and explicit results from both endpoints. */
        public final boolean complete;
        public final boolean publicComplete;
        public final int classicKeys, blockKeys, unknownKeys;

        Result""")
    change('KeyAudit.java', """               boolean derivationMismatch, boolean exhaustion) {
            this.rows = rows;""", """               boolean derivationMismatch, boolean exhaustion) {
            int classic = 0, block = 0, unknown = 0;
            boolean all = !rows.isEmpty() && !derivationMismatch;
            boolean allPublic = all;
            for (Row row : rows) {
                if (row.family == Family.CLASSIC) classic++;
                else if (row.family == Family.BLOCK) block++;
                else unknown++;
                all &= row.checked;
                allPublic &= row.publicChecked;
            }
            classicKeys = classic; blockKeys = block; unknownKeys = unknown;
            complete = all;
            publicComplete = allPublic;
            this.rows = rows;""")
    change('KeyAudit.java', '            final boolean risk = sigs > local;', """            // Spend counts estimate a classic counter. They are not comparable
            // to a block-tree leaf position; witness-confirmed reuse applies to both.
            final boolean risk = k.valid && k.family == Family.CLASSIC && sigs > local;""")
    change('KeyAudit.java', '            recommended = Math.max(recommended, Math.max(sigs, local));', """            if (k.family == Family.CLASSIC && k.valid)
                recommended = Math.max(recommended, Math.max(sigs, local));""")
    change('KeyAudit.java', """                    risk, confirmedReuse, r == null ? 0 : r.reuseCount));""", """                    risk, confirmedReuse, r == null ? 0 : r.reuseCount, k.family,
                    k.valid && d != null && u != null && r != null
                            && d.hex.equalsIgnoreCase(u.address),
                    k.shapeValid && d != null && u != null && r != null
                            && d.hex.equalsIgnoreCase(u.address)));""")
    change('KeyAudit.java', """        return new Result(rows, verdict, worstReuse, recommended + KEYUSES_MARGIN,
                mismatch, exhaustion);""", """        boolean hasClassic = false;
        for (LocalKey key : localKeys) hasClassic |= key.family == Family.CLASSIC;
        return new Result(rows, verdict, worstReuse, hasClassic ? recommended + KEYUSES_MARGIN : 0,
                mismatch, exhaustion);""")
    change('AuditRunner.java', 'if (keys == null || keys.length() == 0) {', 'if (!Node.ok(r) || keys == null || keys.length() == 0) {')
    change('AuditRunner.java', 'local.add(new KeyAudit.LocalKey(pk, k.optInt("uses", 0), k.optInt("maxuses", 0)));', """local.add(new KeyAudit.LocalKey(pk, exactInt(k, "uses", -1),
                    exactInt(k, "maxuses", 0), exactInt(k, "size", 0), exactInt(k, "depth", 0)));""")
    change('AuditRunner.java', 'final boolean complete = !usage.isEmpty() && !reuse.isEmpty();', """boolean complete = result.complete;
                // A partial batch must not license a bare collect or a rescue to an
                // unchecked destination just because some other key was returned.
                for (String dest : dests) {
                    boolean found = false;
                    for (KeyAudit.Reuse ru : reuse) found |= ru.matches(dest);
                    complete &= found;
                }""")
    change('AuditRunner.java', '    /** The addresses this app sends money TO:', """    // Exact parsing reused from PandaPools KeyUses.extractUses: corrupt or fractional
    // counters remain unknown rather than silently becoming zero.
    private static int exactInt(JSONObject row, String name, int absent) {
        if (!row.has(name)) return absent;
        try { return new java.math.BigDecimal(row.get(name).toString()).intValueExact(); }
        catch (Exception invalid) { return -1; }
    }

    /** The addresses this app sends money TO:""")
    change('Audit.java', 'parts.add(pk.toUpperCase() + ":" + k.optInt("uses", 0));', """parts.add(pk.toUpperCase(java.util.Locale.ROOT) + ":" + k.opt("uses")
                        + ":" + k.opt("size") + ":" + k.opt("depth") + ":" + k.opt("maxuses"));""")
    change('MainActivity.java', """        for (KeyAudit.Row r : lastAudit.rows) {
            if (!r.risk && !r.reused) continue;   // only the interesting ones; the rest are noise""", """        c.addView(Ui.text(this, "Classic keys: " + lastAudit.classicKeys
                + " · Block keys: " + lastAudit.blockKeys
                + (lastAudit.unknownKeys == 0 ? "" : " · Unknown: " + lastAudit.unknownKeys),
                Design.DIM(), 12, false));
        c.addView(Ui.text(this, "Both key sets are checked for known signature reuse. "
                + "The spend-count estimate applies to classic keys only; block keys use leaf positions.",
                Design.DIM(), 12, false));
        for (KeyAudit.Row r : lastAudit.rows) {""")
    change('MainActivity.java', """            row.addView(Ui.text(this, r.reused ? "  RE-USED ×" + r.reuseCount : "  AT RISK",
                    r.reused ? Design.RED() : Design.WARN(), 12, true));""", """            String verdict = r.reused ? "  RE-USED ×" + r.reuseCount
                    : r.risk ? "  AT RISK" : r.checked ? "  No known reuse" : "  Not fully checked";
            row.addView(Ui.text(this, verdict,
                    r.reused ? Design.RED() : r.risk || !r.checked ? Design.WARN() : Design.TEXT(), 12, true));
            c.addView(Ui.text(this, (r.family == KeyAudit.Family.CLASSIC ? "Classic · counter "
                    : r.family == KeyAudit.Family.BLOCK ? "Block · leaf position " : "Unknown key type · ")
                    + r.local, Design.DIM(), 11, false));""")

    # Explicit complete fixtures: an omitted /reuse row now means unchecked.
    test = root / 'futurecash-next/src/test/java/com/eurobuddha/futurecashnext/KeyAuditTest.java'
    source = test.read_text()
    old = """new KeyAudit.Usage(PK2, addr(PK2), 3, 3)),
                Collections.<KeyAudit.Reuse>emptyList());"""
    new = """new KeyAudit.Usage(PK2, addr(PK2), 3, 3)),
                Arrays.asList(new KeyAudit.Reuse(addr(PK1), false, 0),
                              new KeyAudit.Reuse(addr(PK2), false, 0)));"""
    assert source.count(old) == 1, 'Complete audit fixture drift'
    test.write_text(source.replace(old, new, 1))
    change('AuditApi.java', """            if (pk.isEmpty()) continue;   // the backend inlines {publickey, error} for bad shapes""",
        """            if (pk.isEmpty() || o.has("error")) continue;""")
    change('AuditApi.java', """            final long sigs = o.has("spend_blocks")
                    ? o.optLong("spend_blocks", 0) : o.optLong("onchain_signatures", 0);
            final long coins = o.has("spent_coins")
                    ? o.optLong("spent_coins", 0) : o.optLong("spent_coins_upperbound", 0);
            out.add""", """            final long sigs = exactCount(o, o.has("spend_blocks")
                    ? "spend_blocks" : "onchain_signatures");
            final long coins = exactCount(o, o.has("spent_coins")
                    ? "spent_coins" : "spent_coins_upperbound");
            if (sigs < 0 || coins < 0) continue;
            out.add""")
    change('AuditApi.java', """            if (addr.isEmpty()) continue;
            out.add(new KeyAudit.Reuse(addr, o.optString("miniaddress", ""),
                    o.optBoolean("reused", false), o.optLong("reuse_count", 0)));""", """            long count = exactCount(o, "reuse_count");
            if (addr.isEmpty() || o.has("error") || !(o.opt("reused") instanceof Boolean) || count < 0) continue;
            boolean reused = (Boolean) o.opt("reused");
            if (!reused && count != 0) continue;
            out.add(new KeyAudit.Reuse(addr, o.optString("miniaddress", ""), reused, reused ? Math.max(1, count) : 0));""")
    change('AuditApi.java', '    /* ---------- transport ---------- */', """    private static long exactCount(JSONObject row, String field) {
        try {
            long count = new java.math.BigDecimal(row.get(field).toString()).longValueExact();
            return count >= 0 ? count : -1;
        } catch (Exception invalid) { return -1; }
    }

    /* ---------- transport ---------- */""")
    change('AuditRunner.java', '                cb.accept(0);', '                cb.accept(-1); // No row is an unknown result, not a clean destination.')

    change('Audit.java', '        if (!aok || at == 0) return new Verdict(false, at, at != 0 ? Mode.PARTIAL : Mode.NONE);',
        """        if (!aok || at == 0) return new Verdict(false, at, at != 0 ? Mode.PARTIAL : Mode.NONE);
        // Match AppLock: a backwards clock cannot make an old safety decision fresh.
        if (at < 0 || age < 0) return new Verdict(false, at, Mode.STALE);""")

    change('Audit.java', '    public enum Mode {', '    static final String FINGERPRINT_VERSION = "dual-key-v1|";\n\n    public enum Mode {')
    change('Audit.java', 'return String.join("|", parts);', 'return FINGERPRINT_VERSION + String.join("|", parts);')
    change('Audit.java', '        if (age < FRESH_MS) return new Verdict(true, at, Mode.FRESH);', """        final String storedFingerprint = cfg.get(Cfg.AUDIT_USES, "");
        if (storedFingerprint == null || !storedFingerprint.startsWith(FINGERPRINT_VERSION))
            return new Verdict(false, at, Mode.STALE);
        if (age < FRESH_MS) return new Verdict(true, at, Mode.FRESH);""")
    change('MainActivity.java', 'if (!force && at > 0 && System.currentTimeMillis() - at < 20 * 60_000L) return;', """final long age = System.currentTimeMillis() - at;
        if (!force && at > 0 && age >= 0 && cfg.get(Cfg.AUDIT_USES, "").startsWith(Audit.FINGERPRINT_VERSION)
                && age < 20 * 60_000L) return;""")
    change('KeyAudit.java', 'The value to put in {@code keyuses:} on the next resync (already includes the margin).',
        'Classic-only recovery estimate including the margin; zero if no classic keys were audited.')
    change('KeyAudit.java', '@param usage      /keyaudit rows (may be missing entries; missing == never spent)',
        '@param usage      /keyaudit rows (missing entries make the audit incomplete)')

    change('AuditRunner.java', '            if (k == null) continue;', """            if (k == null) {
                String message = "The node returned an unreadable key row. Audit remains incomplete.";
                audit.recordFailure(message);
                cb.onError(message);
                return;
            }""")
    change('Audit.java', '        cfg.set(Cfg.ATRISK_ADDRS, arr.toString());', """        if (!complete) {
            // Missing evidence cannot clear an earlier at-risk verdict. A complete
            // audit can replace it; a partial one may only add newly found risks.
            try {
                JSONArray previous = new JSONArray(cfg.get(Cfg.ATRISK_ADDRS, "[]"));
                java.util.Set<String> known = new java.util.HashSet<>();
                for (int i = 0; i < arr.length(); i++) known.add(arr.optString(i));
                for (int i = 0; i < previous.length(); i++) {
                    String address = previous.optString(i);
                    if (Node.validAddr(address) && known.add(address)) arr.put(address);
                }
            } catch (Exception unreadable) {
                // Keep the persisted set untouched when it cannot be parsed.
                cfg.setBool(Cfg.AUDIT_OK, false);
                return;
            }
        }
        cfg.set(Cfg.ATRISK_ADDRS, arr.toString());""")

    change('Audit.java', '        setSafeReused(badDestAddr, badDestCount);',
        '        if (complete || (badDestAddr != null && !badDestAddr.isEmpty()))\n            setSafeReused(badDestAddr, badDestCount);')

    change('MainActivity.java', '        } else if (!err.isEmpty()) {', """        } else if ((lastAudit != null && lastAudit.verdict == KeyAudit.Verdict.RISK_HEURISTIC)
                || cfg.atRisk().length() > 0) {
            msg = "Key-reuse risk is recorded for this wallet. Review the key details and your rescue destination.";
            colour = Design.WARN();
        } else if (!err.isEmpty()) {""")
    change('MainActivity.java', '        } else {\n            msg = "✓ No key-reuse risk. Your matured stakes can be collected safely.";', """        } else if (v == null || !v.ok || (lastAudit != null && !lastAudit.complete)) {
            msg = "The audit is incomplete or out of date. Re-audit before trusting the result.";
            colour = Design.WARN();
        } else {
            msg = "No known key reuse was found in the checked keys.";""")
