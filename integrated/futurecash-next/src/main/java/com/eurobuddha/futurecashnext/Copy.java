package com.eurobuddha.futurecashnext;

/**
 * User-facing copy, transcribed verbatim from the KeyUses MiniDapp
 * ({@code mds/keyuses/minidapp/index.html} v0.1.51). Kept in one place so the APK and the dapp can
 * be diffed word for word.
 *
 * <p>Light HTML ({@code <b>}, {@code <br>}, {@code <li>}) is preserved and rendered with
 * HtmlCompat, so the emphasis the dapp uses survives the port.
 */
final class Copy {

    private Copy() {}

    static final String BADGE = "MINIMA · KEY SECURITY AUDIT";
    static final String TITLE = "KeyUses";

    /** index.html:79-85 */
    static final String SUBTITLE =
            "Minima signatures are <b>stateful</b>: each of your 64 keys must move to a fresh leaf "
            + "every time it signs. If a seed re-sync set <tt>keyuses</tt> too low, your node can "
            + "<b>re-use a leaf it already spent on-chain</b> — which weakens that key. This tool "
            + "checks, for each key, how many times it has actually signed on-chain and compares "
            + "that to your node's own counter.";

    /** index.html:96-101 */
    static final String PRIVACY =
            "<b>Privacy:</b> your 64 <i>public</i> keys are sent to eurobuddha.com, which returns "
            + "how often each of your addresses was spent. Public keys can't move funds, but they "
            + "do reveal your addresses to that server.";

    /** index.html:176 */
    static final String ERR_KEYS = "Could not read keys from the embedded node. Check Minima Core’s status.";

    static final String ERR_PAIRING_TITLE = "The embedded node is not ready";
    static final String ERR_PAIRING_BODY = "Open Minima Core from the menu, check its status, then run the audit again.";

    /* ---------- verdicts (index.html:264-327) ---------- */

    static final String RECO_LABEL = "RECOMMENDED KEYUSES FOR YOUR NEXT SEED RE-SYNC";

    static String recoCommand(long keyuses) {
        return "archive action:import file:<your-backup> phrase:\"<your 24 words>\" keyuses:" + keyuses;
    }

    static final String WHY_LABEL = "WHY THIS HAPPENED (ALMOST ALWAYS HUMAN ERROR)";
    static final String WHY_LIST =
            "•  Two nodes/signers run on the <b>same seed</b> (each issues leaves 0,1,2… independently).<br><br>"
            + "•  A restore/re-sync set <tt>keyuses</tt> <b>below</b> the key's true prior usage.<br><br>"
            + "•  A wallet snapshot was <b>rolled back</b> past signatures already broadcast.";

    static final String REUSE_RISK_TITLE = "⛔ Key re-use detected — migrate your funds";

    static String reuseRiskBody(String worst) {
        return "One or more of your keys has signed several transactions with the <b>same one-time "
                + "leaf</b> (worst case: <b>" + worst + " uses</b>). Each reuse leaks more of that key, "
                + "so forging a new signature gets <b>exponentially easier</b> — at this level it "
                + "approaches practical for anyone who also obtains the raw signatures. Those "
                + "signatures are pruned from the chain and not publicly available today, so this is "
                + "a <b>latent</b> exposure, not a confirmed theft — but it should not be relied on. "
                + "<b>Move all funds from the affected address(es) to a wallet from a brand-new seed, "
                + "and retire the old key.</b>";
    }

    static final String REUSE_WARN_TITLE = "⚠ Key re-use detected — reduced security";

    static String reuseWarnBody(String worst) {
        return "One or more of your keys has signed more than once with the <b>same one-time leaf</b> "
                + "(worst case: <b>" + worst + " uses</b>). A one-time key is only fully secure if it "
                + "signs once, so this reuse reduces its security margin. Forging still requires the "
                + "raw signature values (pruned from the chain, not public) plus purpose-built "
                + "tooling, so it is not an immediate loss — but the margin is gone and further reuse "
                + "compounds it. <b>Migrate the affected funds to a fresh seed as a precaution</b> and "
                + "don't sign from the old key again.";
    }

    static final String RISK_TITLE = "⚠ Re-use detected — this node's key is at risk";
    static final String RISK_BODY =
            "On at least one key, the chain shows <b>more signatures than your node's counter</b>. "
            + "That means your node has re-issued (or is about to re-issue) a one-time leaf it "
            + "already spent. A re-used Minima signature is <b>publicly forgeable</b> — if you have "
            + "signed anything since the counter dropped, treat the key as <b>compromised</b> and "
            + "move the funds now.";

    static final String HOW_LABEL = "HOW THIS HAPPENS — CHECK YOUR OPERATIONS";
    static final String HOW_LIST =
            "•  More than one node/signer running on the <b>same seed</b> (each keeps its own counter "
            + "→ both issue the same leaves).<br><br>"
            + "•  A restore / re-sync that set <tt>keyuses</tt> <b>below</b> the key's true prior usage.<br><br>"
            + "•  A wallet-state snapshot/<b>rollback</b> to before signatures that were already broadcast.";

    static final String DO_LABEL = "DO THIS NOW";
    static final String DO_LIST =
            "•  <b>Migrate all funds to a wallet from a fresh seed</b> (safest — the existing key may "
            + "already be forgeable).<br><br>"
            + "•  Never run two signers on one seed; never re-sync below true usage; use one "
            + "authoritative counter.";

    static final String OK_TITLE = "✓ No re-use detected";
    static final String OK_BODY =
            "For every key, your node's counter is at or ahead of what the chain shows it has signed "
            + "— so your next signatures use fresh leaves. Keep the recommended value for any future "
            + "re-sync.";

    /* ---------- coverage footnote (index.html:254-260) ---------- */

    static String coverage(long archiveTip) {
        final String head = archiveTip > 0
                ? "On-chain signatures counted from the archive (to block " + archiveTip + "). The "
                  + "archive trails the tip by ~24h, but re-use is still caught: it can only arise "
                  + "from a re-sync set below a key's historical usage, and that history is older "
                  + "than 24h. Your node's live counter covers the rest. "
                : "On-chain signatures counted from the archive. ";
        return head
                + "Counts are per spend-block; a key signing two transactions in one block (rare) "
                + "counts once, so treat a key sitting exactly at on-chain == node-uses as 'verify', "
                + "not provably clear.\n\n"
                + "Only each key's DEFAULT address is checked — uses via custom-script or multisig "
                + "addresses controlled by the same key are not counted.";
    }

    /* ---------- additions beyond the dapp ---------- */

    static final String MISMATCH =
            "Address derivation mismatch: an address this app derived does not match the one the "
            + "audit service derived for the same key. Treat these results as unreliable and report "
            + "this — do not read a clean verdict as safe.";

    static final String EXHAUSTION =
            "One of your keys is close to its 262,144-signature limit. On exhaustion a Minima node "
            + "silently restarts at leaf 0 and keeps signing, which guarantees re-use. Migrate to a "
            + "fresh seed before that key runs out.";

    /* ---------- explainer (index.html:120-147) ---------- */

    static final String EXPLAIN_LABEL = "WHAT IS KEY RE-USE, AND WHY DOES IT MATTER?";

    static final String EXPLAIN_BODY =
            "Minima keys are <b>stateful</b>. Each key is a tree of 262,144 <i>one-time</i> "
            + "signatures (leaves). Signing safely means using a <b>fresh leaf every time</b> — your "
            + "node tracks this with a counter.<br><br>"

            + "<b>Re-using a leaf</b> — signing two different transactions with the same one-time key "
            + "— <b>weakens</b> that key. A one-time signature only stays secure if it signs once; "
            + "each reuse leaks more of the secret, so forging a fresh signature becomes "
            + "progressively easier — roughly <b>exponentially</b> with the number of reuses. One "
            + "reuse meaningfully reduces the security margin; many reuses can make forgery "
            + "practical.<br><br>"

            + "This is <b>not</b> an instant \"anyone can drain it\" break. To actually forge, an "
            + "attacker also needs the <b>raw signature values</b> (which are pruned from the chain "
            + "and not publicly available) <i>and</i> a purpose-built forging harness. So a re-used "
            + "key is best treated as a <b>latent vulnerability that grows with each reuse</b> — not "
            + "a guaranteed loss. The safe response is to migrate the funds to a fresh seed, and the "
            + "more times a key has been re-used, the more urgent that becomes.<br><br>"

            + "It almost always comes from <b>human error</b>, not a Minima bug:<br><br>"
            + "•  Running <b>two nodes/signers on the same seed</b> (each issues leaves 0,1,2… on its own).<br><br>"
            + "•  <b>Re-syncing</b> from your seed with <tt>keyuses</tt> set <b>below</b> your true usage.<br><br>"
            + "•  <b>Rolling back</b> wallet state to before signatures you already broadcast.<br><br>"

            + "<b>The fix:</b> one node/counter per seed; on a re-sync set <tt>keyuses</tt> safely "
            + "high; and if a key has already re-used, <b>migrate all funds to a brand-new seed</b> "
            + "and retire the old key.";
}
