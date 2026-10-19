"""Use minimaWallet public ownership for Future Cash discovery; retain the existing collection engine."""
from pathlib import Path
import shutil

def adapt(root):
    def change(relative, old, new, count=1):
        p=root/relative;s=p.read_text()
        assert s.count(old)==count, ('Future wallet source drift',relative,old,s.count(old))
        p.write_text(s.replace(old,new))
    fc='futurecash-next/src/main/java/com/eurobuddha/futurecashnext/'
    wallet='wallet/src/main/java/com/eurobuddha/wallet/'
    src=Path(__file__).with_name('pandamonium-sources')
    shutil.copy2(src/'futurecash-next/StakeScan.java',root/fc/'StakeScan.java')
    shutil.copy2(src/'wallet/FutureCashAccess.java',root/wallet/'FutureCashAccess.java')
    change(wallet+'PublicAuditKeys.java','rows.put(new JSONObject().put("publickey",pk)',
           'rows.put(new JSONObject().put("index",index).put("publickey",pk)')
    change(wallet+'WalletSession.java','    // --- accessors',
           '    /** Never opens a vault or creates a signing session for background work. */\n    public static synchronized WalletSession current() { return sInstance; }\n\n    // --- accessors')
    change(fc+'Cfg.java','    public Cfg(Context ctx) {',"""    public boolean walletCanSweep(String address) {
        return com.eurobuddha.wallet.FutureCashAccess.canSweep(appContext, address);
    }
    public Tx.Result walletSweep(Node node, String coin, String address, String amount, String token,
                              String destination, String proof) {
        return com.eurobuddha.wallet.FutureCashAccess.sweep(appContext, coin, address,
                amount, token, destination, proof, data -> Tx.importedSweep(node,data));
    }

    public Cfg(Context ctx) {""")
    change(fc+'Wallet.java','        public final Set<String> allTracked',"""        public final Set<String> nodeOwned = new HashSet<>();
        public final Set<String> nodeScripts = new HashSet<>();
        public final Set<String> walletOwned = new HashSet<>();
        public final java.util.Map<String,MinimaAddress.Result> walletAddresses = new java.util.LinkedHashMap<>();
        public String walletFingerprint;
        public final Set<String> allTracked""")
    change(fc+'Wallet.java','        final AddressSets sets = new AddressSets();', '        final AddressSets sets = new AddressSets();')
    change(fc+'Wallet.java',"""        final AddressSets sets = new AddressSets();
        final JSONObject r = node.cmd("scripts");""","""        final AddressSets sets = new AddressSets();
        final JSONObject r = node.cmd("scripts");
        if (r == null || !Boolean.TRUE.equals(r.opt("status")) || Node.pending(r)
                || r.optJSONArray("response") == null)
            throw new IllegalStateException("Could not read wallet addresses. Refresh and retry.");""")
    change(fc+'Wallet.java','            if (!addr.isEmpty()) sets.allTracked.add(addr.toLowerCase());',
           '            if (!addr.isEmpty()) sets.nodeScripts.add(canonAddr(addr));\n            if (!addr.isEmpty()) sets.allTracked.add(addr.toLowerCase());')
    change(fc+'Wallet.java','        return sets;',"""        sets.nodeOwned.addAll(sets.ownDefault);
        for (String retired : retiredAddresses()) sets.nodeOwned.add(canonAddr(retired));
        sets.ownDefault.addAll(sets.nodeOwned);
        final JSONArray keys = cfg.walletAuditKeys();
        if (keys == null) throw new IllegalStateException("Could not read minimaWallet public audit keys. Unlock minimaWallet and retry.");
        sets.walletFingerprint = Audit.usesFingerprint(keys);
        for (int i=0;i<keys.length();i++) {
            JSONObject key=keys.optJSONObject(i);
            MinimaAddress.Result address=key==null?null:MinimaAddress.fromPublicKey(key.optString("publickey",""));
            if (address == null) throw new IllegalStateException("Could not read minimaWallet public audit keys. Unlock minimaWallet and retry.");
            sets.walletAddresses.put(address.hex,address);
            sets.walletOwned.add(canonAddr(address.hex));
            sets.walletOwned.add(canonAddr(address.mx));
            sets.allTracked.add(address.hex.toLowerCase(java.util.Locale.ROOT));
            sets.allTracked.add(address.mx);
        }
        sets.ownDefault.addAll(sets.walletOwned);
        return sets;""")
    change(fc+'Wallet.java','    public static boolean owns(Set<String> own, String addr) {',r"""    public boolean walletUnchanged(AddressSets sets) {
        try { return sets.walletFingerprint.equals(Audit.usesFingerprint(cfg.walletAuditKeys())); }
        catch (RuntimeException unavailable) { return false; }
    }

    /** Same watch-script registration used by minimaWallet NodeLink. Never replaces node-owned rows. */
    public void trackWallet(AddressSets sets) {
        for (MinimaAddress.Result address : sets.walletAddresses.values()) {
            if (sets.nodeScripts.contains(canonAddr(address.hex))) continue;
            if (!walletUnchanged(sets)) throw new IllegalStateException("minimaWallet changed during the audit. Run the audit again.");
            JSONObject added=node.cmd("newscript trackall:true script:\"" + address.script + "\"");
            if (added == null || !Boolean.TRUE.equals(added.opt("status")) || Node.pending(added))
                throw new IllegalStateException("Could not track minimaWallet addresses. Refresh and retry.");
        }
    }

    private boolean nodeCanSweep(String address, AddressSets sets) {
        return owns(sets.nodeOwned,address) && !vaultLocked() && signKeyFor(address)!=null;
    }
    public boolean canSweep(String address, AddressSets sets) {
        if (nodeCanSweep(address,sets)) return true;
        return owns(sets.walletOwned,address) && walletUnchanged(sets) && cfg.walletCanSweep(address);
    }

    public Tx.Result sweep(String coin, String address, String destination, String amount, String token,
                           AddressSets sets) {
        if (!canSweep(address,sets)) return Tx.Result.err("Unlock minimaWallet to rescue its reused-key payouts.");
        if (nodeCanSweep(address,sets)) return Tx.sweep(node,coin,destination,amount,token,signKeyFor(address));
        JSONObject exported=node.cmd("coinexport coinid:"+coin);
        JSONObject response=Node.obj(exported);
        if (exported == null || !Boolean.TRUE.equals(exported.opt("status")) || Node.pending(exported) || response==null)
            return Tx.Result.err("Could not export the payout coin. Refresh and retry.");
        try {
            return cfg.walletSweep(node,coin,address,amount,token,destination,response.optString("data",""));
        } catch (RuntimeException unavailable) {
            return Tx.Result.err("Could not prepare minimaWallet rescue. Unlock it, check key uses, and retry.");
        }
    }

    public static boolean owns(Set<String> own, String addr) {""")
    change(fc+'Tx.java','    /**\n     * Sweep: move',"""    /** minimaWallet signed this through its own authenticated, durable counter path. */
    public static Result importedSweep(Node node, String data) {
        if (data==null || !data.matches("0x[0-9a-fA-F]+") || (data.length()&1)!=0)
            return Result.err("Could not prepare minimaWallet rescue. Unlock it, check key uses, and retry.");
        List<String> steps=new ArrayList<>();
        steps.add("txnimport id:%ID% data:"+data);
        steps.add("txnbasics id:%ID%");
        steps.add("txnpost id:%ID% mine:true");
        return run(node,"WSWP",steps);
    }

    /**
     * Sweep: move""")
    # Guardian changes are kept as a small checked patch below.
    change(fc+'Guardian.java','    private void detect(long tip, Set<String> ownDefault) {',
           '    private void detect(long tip, Wallet.AddressSets sets) {')
    change(fc+'Guardian.java','        final JSONObject r = node.cmd("coins relevant:true");\n        final JSONArray arr = Node.arr(r);',
           '        final JSONArray arr = StakeScan.read(node, sets, StakeScan.contracts(wallet));')
    change(fc+'Guardian.java','if (!Wallet.owns(ownDefault, payout))','if (!Wallet.owns(sets.ownDefault, payout))')
    change(fc+'Guardian.java','boolean auditFresh, boolean canSweep, Set<String> ownDefault)',
           'boolean auditFresh, boolean canSweep, Wallet.AddressSets sets)')
    change(fc+'Guardian.java','if (!Wallet.owns(ownDefault, row.payout))', 'if (!Wallet.owns(sets.ownDefault, row.payout))')
    change(fc+'Guardian.java','            final boolean collectToSweep = row.atRisk && rescueEnabled && canSweep;',
           '            final boolean collectToSweep = row.atRisk && rescueEnabled && canSweep && wallet.canSweep(row.payout,sets);')
    change(fc+'Guardian.java','            final String posted = bareCollectSafe ?',
           '            if (!wallet.walletUnchanged(sets)) return;\n            final String posted = bareCollectSafe ?')
    change(fc+'Guardian.java','"coins relevant:true sendable:true address:" + row.payout',
           '"coins address:" + row.payout + " checkmempool:true"')
    change(fc+'Guardian.java','    private void sweepPending(long tip, String safe) {',
           '    private void sweepPending(long tip, String safe, Wallet.AddressSets sets) {')
    change(fc+'Guardian.java','            final String signKey = wallet.signKeyFor(row.address);\n            if (signKey == null) {','            if (!wallet.canSweep(row.address,sets)) {')
    change(fc+'Guardian.java','Tx.sweep(node, row.coinid, safe, row.amount, row.tokenid, signKey)',
           'wallet.sweep(row.coinid,row.address,safe,row.amount,row.tokenid,sets)')
    change(fc+'Guardian.java','        detect(tip, sets.ownDefault);',
           '        wallet.trackWallet(sets);\n        detect(tip, sets);')
    # Vault readiness is per payout: a locked node must not prevent an unlocked separate wallet rescue.
    change(fc+'Guardian.java','            vaultLocked = wallet.vaultLocked();','            canSweep = true;\n            vaultLocked = wallet.vaultLocked();')
    change(fc+'Guardian.java','canSweep && !badSafe, sets.ownDefault)', 'canSweep && !badSafe, sets)')
    change(fc+'Guardian.java','sweepPending(tip, safe);','sweepPending(tip, safe, sets);')
    change(fc+'Guardian.java','        final JSONArray arr = Node.arr(node.cmd("coins relevant:true"));',
           '        wallet.trackWallet(sets);\n        final JSONArray arr = StakeScan.read(node,sets,StakeScan.contracts(wallet));')
    change(fc+'Guardian.java','            st.ready = st.matureBlock <= tip;', '            st.ready = matured(c,tip);')
    change(fc+'Guardian.java','            st.atRisk = atRisk.contains(Wallet.canonAddr(payout));',
           '            st.atRisk = atRisk.contains(Wallet.canonAddr(payout));\n            st.wallet = Wallet.owns(sets.walletOwned,payout);\n            st.node = Wallet.owns(sets.nodeOwned,payout);\n            st.rescueAvailable = !st.atRisk || wallet.canSweep(payout,sets);')
    change(fc+'Guardian.java','        public boolean ready, atRisk;', '        public boolean ready, atRisk, wallet, node, rescueAvailable;')
    change(fc+'Guardian.java','        if (!looksLikeFutureCash(coin)) return Wallet.Res.err("Not a FutureCash stake.");',
           '        if (!StakeScan.isStake(coin,StakeScan.contracts(wallet))) return Wallet.Res.err("Not a FutureCash stake.");')
    change(fc+'Guardian.java','        if (atRisk.contains(Wallet.canonAddr(payout))) {',
           '        reloadAtRisk();\n        if (atRisk.contains(Wallet.canonAddr(payout))) {')
    change(fc+'Guardian.java','        final Tx.Result res = Tx.collect(node, coinid, payout, Node.outAmount(coin),',
           '        if (!wallet.walletUnchanged(sets)) return Wallet.Res.err("minimaWallet changed during the audit. Run the audit again.");\n        final Tx.Result res = Tx.collect(node, coinid, payout, Node.outAmount(coin),')
    change(fc+'MainActivity.java','            info.addView(amtRow);',"""            info.addView(amtRow);
            info.addView(Ui.text(this, Language.msg(s.wallet ? (s.node ? l10n_future_source_both : l10n_future_source_wallet)
                    : l10n_future_source_node), Design.DIM2(),11,false));
            if (s.wallet && s.atRisk && !s.rescueAvailable)
                info.addView(Ui.body(this,Language.msg(l10n_future_wallet_rescue_unlock)));""")
    change(fc+'MainActivity.java','    private void refresh() {', '    private String stakeError;\n    private void refresh() {')
    change(fc+'MainActivity.java','        submit(() -> {\n            final long t = guardian.tip();','        submit(() -> {\n            try {\n            final long t = guardian.tip();\n            if (t>0 && cfg.is(Cfg.GUARDIAN_ON,false) && cfg.rescueReady()) guardian.reconcile(t);')
    change(fc+'MainActivity.java','                tip = t; stakes = s; lastVerdict = v; lastStatus = st;\n                render();\n            });\n        });','                stakeError=null;\n                tip = t; stakes = s; lastVerdict = v; lastStatus = st;\n                render();\n            });\n            } catch (RuntimeException unavailable) {\n                runOnUiThread(() -> {\n                    stakeError=unavailable.getMessage();\n                    if (stakeError==null) stakeError="Could not read the complete stake list. Refresh and retry.";\n                    stakes=java.util.Collections.emptyList();\n                    render();\n                });\n            }\n        });')
    change(fc+'MainActivity.java','        content.removeAllViews();',"""        content.removeAllViews();
        if (stakeError!=null) {
            LinearLayout error=Ui.card(this,0);
            error.addView(Ui.body(this,FutureMessages.message(stakeError)));
            error.addView(Ui.ghost(this,Language.msg(l10n_future_scan_retry),v -> refresh()));
            content.addView(error);
            return;
        }""")
    change(fc+'MainActivity.java','        listCard.addView(head);','        listCard.addView(head);\n        listCard.addView(Ui.body(this,Language.msg(l10n_future_scan_sources)));')
    change(fc+'Wallet.java','return resp != null && resp.optBoolean("locked", false);',
           'return resp == null || !(resp.opt("locked") instanceof Boolean) || resp.optBoolean("locked", true);')
    change(wallet+'WalletSession.java','private long mLastActivity;', 'private volatile long mLastActivity;')
    change(wallet+'WalletSession.java','private long mBackgroundedAt;', 'private volatile long mBackgroundedAt;')
    change(fc+'Guardian.java','    public Wallet.Res collectNow(String coinid) {', '    public synchronized Wallet.Res collectNow(String coinid) {')
    change(fc+'Guardian.java','    public List<Stake> listStakes() {', '    public synchronized List<Stake> listStakes() {\n        reloadAtRisk();')
    p=root/fc/'Guardian.java';s=p.read_text()
    a=s.index('    /**\n     * Find this node');b=s.index('    private void detect',a)
    s=s[:a]+"""    /** Scan tracked stakes plus live-chain contracts for the node and minimaWallet public owners. */
"""+s[b:]
    s=s.replace("/** This node's stakes, for the Stakes tab. Relevant-only, same reasoning as detect(). */", "/** Both wallets' stakes, using the same discovery and maturity rules as the guardian. */")
    p.write_text(s)
    change(fc+'Guardian.java','    private boolean lockNotified = false;', '    private boolean lockNotified = false;\n    private boolean walletLocked = false;')
    change(fc+'Guardian.java','        final Audit.Verdict verdict = audit.usable();',"""        boolean walletBlocked=false;
        for (JSONObject coin : seenStakes.values()) {
            String payout=Node.state(coin,FutureCashContract.ST_RECIPIENT);
            if (atRisk.contains(Wallet.canonAddr(payout)) && Wallet.owns(sets.walletOwned,payout)
                    && !wallet.canSweep(payout,sets)) walletBlocked=true;
        }
        if (walletBlocked && !walletLocked) {
            cfg.log(Cfg.LVL_WARN,"Unlock minimaWallet to rescue its reused-key payouts.");
            notifier.notify("Future Cash: rescue is off","Unlock minimaWallet to rescue its reused-key payouts.");
        }
        walletLocked=walletBlocked;
        final Audit.Verdict verdict = audit.usable();""")
    change(fc+'Guardian.java','        public boolean vaultLocked;', '        public boolean vaultLocked, walletLocked;')
    change(fc+'Guardian.java','        s.vaultLocked = vaultLocked;', '        s.vaultLocked = vaultLocked;\n        s.walletLocked = walletLocked;')
    change(fc+'MainActivity.java','    private void renderGuardian() {',"""    private void renderGuardian() {
        if (lastStatus!=null && lastStatus.walletLocked) {
            LinearLayout warning=Ui.card(this,0);
            warning.addView(Ui.body(this,Language.msg(l10n_future_wallet_rescue_unlock)));
            content.addView(warning);
        }""")
    change(wallet+'TxnFactory.java','    /**\n     * SPLIT (send-to-self)',"""    /** Whole collected coin to the rescue destination, matching Future Cash's storestate:false sweep. */
    public BuiltTxn buildSweep(InputCoin coin, String destination, String id) {
        ArrayList<Output> outputs=new ArrayList<>();
        outputs.add(new Output(parseAddress(destination),coin.getAmount()));
        return build(java.util.Collections.singletonList(coin),outputs,1,coin.getTokenID(),MiniNumber.ZERO,id,false);
    }

    /**
     * SPLIT (send-to-self)""")
    change(wallet+'TxnFactory.java',"""                           MiniData zTokenID, MiniNumber zBurn, String zID) {

        if (zInputs == null""","""                           MiniData zTokenID, MiniNumber zBurn, String zID) {
        return build(zInputs,zRecipients,zSplit,zTokenID,zBurn,zID,true);
    }
    private BuiltTxn build(List<InputCoin> zInputs, ArrayList<Output> zRecipients, int zSplit,
                           MiniData zTokenID, MiniNumber zBurn, String zID, boolean storeState) {

        if (zInputs == null""")
    change(wallet+'TxnFactory.java','user.mAddress, splitamount, Token.TOKENID_MINIMA, true)', 'user.mAddress, splitamount, Token.TOKENID_MINIMA, storeState)')
    change(wallet+'TxnFactory.java','user.mAddress, currentdiff, Token.TOKENID_MINIMA, true)', 'user.mAddress, currentdiff, Token.TOKENID_MINIMA, storeState)')
    change(fc+'Node.java','r.optBoolean("status", false) && !r.optBoolean("pending", false)',
           'Boolean.TRUE.equals(r.opt("status")) && !r.optBoolean("pending", false)')
    change(fc+'Guardian.java','        final JSONArray arr = Node.arr(node.cmd("coins coinid:" + coinid));',"""        final JSONObject lookup=node.cmd("coins coinid:"+coinid);
        if (!Node.ok(lookup) || lookup.optJSONArray("response")==null)
            return Wallet.Res.err("Could not read the complete stake list. Refresh and retry.");
        final JSONArray arr = Node.arr(lookup);""")

    change(fc+'Wallet.java','        final JSONObject resp = Node.obj(node.cmd("status"));\n        return resp == null || !(resp.opt("locked") instanceof Boolean) || resp.optBoolean("locked", true);','        final JSONObject reply = node.cmd("status");\n        final JSONObject resp = Node.obj(reply);\n        return !Node.ok(reply) || resp == null || !(resp.opt("locked") instanceof Boolean) || resp.optBoolean("locked", true);')
    change(fc+'Guardian.java','    public synchronized void reloadAtRisk() {\n        atRisk.clear();','    public synchronized void reloadAtRisk() {\n        atRisk.clear();\n        for (String retired : wallet.retiredAddresses()) atRisk.add(Wallet.canonAddr(retired));')
