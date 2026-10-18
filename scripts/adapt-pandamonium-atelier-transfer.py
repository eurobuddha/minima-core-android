"""Reuse NFT Wallet's output evidence and state replay in Atelier's retained transfer UI."""
from pathlib import Path
import re

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    folder=root/'atelier/src/main/java/com/eurobuddha/statenft'
    donor=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    def method(source, signature):
        start=source.index('    '+signature); end=source.index('\n    }', start)+6
        return source[start:end]
    def change(s, old, new, count=1):
        assert s.count(old)==count, ('Atelier transfer source drift',old,s.count(old),count)
        return s.replace(old,new)
    # The tested helper is kept byte-for-byte apart from its namespace.
    evidence=(project/'scripts/pandamonium-sources/NFTwallet/BurialEvidence.java').read_text()
    (folder/'BurialEvidence.java').write_text(evidence.replace('package com.eurobuddha.nftwallet;', 'package com.eurobuddha.statenft;'))
    p=folder/'Util.java';s=p.read_text();u=(donor/'Util.java').read_text()
    s=s.replace('    public static String shorten(', '\n\n'.join(method(u,'public static boolean '+name+'(String '+arg+') {') for name,arg in [('isValidHexId','id'),('isValidAmount','amt')])+'\n\n    public static String shorten(');p.write_text(s)
    p=folder/'StateNft.java';s=p.read_text();d=(donor/'StateNft.java').read_text()
    additions=method(d,'public static List<String[]> rawStateEntries(JSONObject coin) {')+'\n\n'+method(d,'public static String safeAmount(JSONObject coin) {')+'\n\n'
    old=method(s,'public static List<String> transferCommands(String txn, String tokenid, JSONObject coin, String to) {')
    new=method(d,'public static List<String> transferCommands(String txn, String tokenid, JSONObject coin, String to) {')
    # MintEngine owns transaction creation, balance checking and posting in Atelier.
    for command in ['txndelete','txncreate','txncheck','txnbasics','txnpost']:
        new=new.replace('        cmds.add("'+command+' id:" + txn);\n','')
    new=re.sub(r'        // Pre-delete:.*?        cmds.add\("txninput', '        cmds.add("txninput', new, flags=re.S)
    new=new.replace('        // Balance check BEFORE signing — an unbalanced txn posts "fine" and is rejected on-chain.\n','')
    s=change(s,old,additions+new)
    old=method(s,'public static boolean replayableState(JSONObject coin) {')
    s=change(s,old,'''    public static boolean replayableState(JSONObject coin) {
        try { BurialEvidence.stateHash(coin); return true; }
        catch (RuntimeException e) { return false; }
    }''')
    # Burial also promises verbatim state preservation; keep object-form ports there.
    a=s.index('    public static List<String> buryCommands(');b=s.index('    /** Wallet-standard',a)
    part=s[a:b]
    start=part.index('            JSONArray st = coin.optJSONArray("state");');end=part.index('        cmds.add("txnsign',start)
    part=part[:start]+'''            for (String[] e : rawStateEntries(coin)) {
                cmds.add("txnstate id:" + txn + " port:" + e[0] + " value:" + e[1]);
            }
        }
'''+part[end:]
    part=part.replace('coin.optString("tokenamount", "1")','safeAmount(coin)')
    s=s[:a]+part+s[b:];p.write_text(s)
    p=folder/'LocalStore.java';s=p.read_text();d=(donor/'LocalStore.java').read_text()
    addition=method(d,'private static JSONArray burialCollections(Context ctx) {')+'\n\n'+method(d,'static synchronized boolean canDepart(Context ctx, String tokenid) {')
    s=change(s,'    private LocalStore() {}','    private LocalStore() {}\n\n'+addition);p.write_text(s)
    p=folder/'MintEngine.java';s=p.read_text()
    signature='    static void postTxn(NodeApi node, String id, List<String> steps, Runnable ok, java.util.function.Consumer<String> fail) {'
    s=change(s,signature,signature+'\n        postTxnWithReceipt(node, id, steps, posted -> ok.run(), fail);\n    }\n\n    static void postTxnWithReceipt(NodeApi node, String id, List<String> steps, java.util.function.Consumer<JSONObject> ok, java.util.function.Consumer<String> fail) {')
    s=change(s,'private static void checkAndSign(NodeApi node, String id, List<String> signs, Runnable ok,','private static void checkAndSign(NodeApi node, String id, List<String> signs, java.util.function.Consumer<JSONObject> ok,')
    s=change(s,'if (c != null && !"0".equals(c.optString("difference"))) bad = true;','if (c == null || !"0".equals(c.optString("difference"))) bad = true;')
    old='''                cmds.add("txnpost id:" + id);
                runCommands(node, cmds, () -> {
                    cmd(node, "txndelete id:" + id, new Cb() {
                        @Override public void ok(JSONObject ignored) { ok.run(); }
                        @Override public void fail(String e) { ok.run(); }
                    });
                }, e -> cleanup(node, id, fail, e));'''
    new='''                runCommands(node, cmds, () -> cmd(node, "txnpost id:" + id, new Cb() {
                    @Override public void ok(JSONObject posted) {
                        cmd(node, "txndelete id:" + id, new Cb() {
                            @Override public void ok(JSONObject ignored) { ok.accept(posted); }
                            @Override public void fail(String e) { ok.accept(posted); }
                        });
                    }
                    @Override public void fail(String e) { cleanup(node, id, fail, e); }
                }), e -> cleanup(node, id, fail, e));'''
    s=change(s,old,new);p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text()
    a=s.index('    private void renderTransfer(');b=s.index('    /* ================= BURY',a)
    part=s[a:s.index('    private void doTransfer(',a)]
    def msg(key,args=''):
        key='atelierTransfer.'+key if '.' not in key else key
        name='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.'+name+args+')'
    part=change(part,'        appbar(String.format(Locale.US, "Lot %03d", it.index), true, false);','        final long generation = screenGeneration;\n        appbar('+msg('atelierGallery.lot',', String.format(Locale.US, "%03d", it.index)')+', true, false);')
    for literal,key in [('Deed of transfer','title'),('Every state port is replayed verbatim — the sealed identity survives the move.','help'),('Identity','identity'),('Image','image'),('hosted','hosted'),('embedded, travels with the coin','embedded'),('Recipient address','recipient'),('Mx… or 0x…','send.addressHint'),('Ready.','ready'),('Transfer this lot','submit')]:
        part=change(part,'"'+literal+'"',msg(key))
    part=change(part,'"port 0 = " + StateNft.state(it.coin, 0)',msg('port',', StateNft.state(it.coin, 0)'))
    part=change(part,'go.setOnClickListener(v -> doTransfer(it, to, status));','go.setOnClickListener(v -> doTransfer(m, it, to, status, go, generation));')
    part=change(part,'body.addView(to, lph(50, 0, 6, 0, 8));','to.setMinHeight(dp(50));\n        body.addView(to, lpm(0, 6, 0, 8));')
    part=change(part,'body.addView(go, lph(54, 0, 0, 0, 0));','go.setMinHeight(dp(54));\n        body.addView(go, lpm(0, 0, 0, 0));')
    flow=(project/'scripts/pandamonium-sources/atelier/transfer-flow.inc').read_text()
    s=s[:a]+part+flow+'\n'+s[b:]
    a=s.index('    /** Poll a single coin until it leaves');b=s.index('    private int distinctStamped(',a)
    s=s[:a]+(project/'scripts/pandamonium-sources/atelier/transfer-watch.inc').read_text()+'\n'+s[b:]
    s=change(s,'        verifyGateCancel = true;\n        main.removeCallbacks(rePair);','        verifyGateCancel = true;\n        for (Runnable r : departureWatchers) main.removeCallbacks(r);\n        departureWatchers.clear();\n        main.removeCallbacks(rePair);')
    p.write_text(s)
    test=project/'scripts/pandamonium-tests/NFTwallet/src/test/java/com/eurobuddha/nftwallet/BurialEvidenceTest.java'
    (root/'atelier/src/test/java/com/eurobuddha/statenft/BurialEvidenceTest.java').write_text(test.read_text().replace('package com.eurobuddha.nftwallet;', 'package com.eurobuddha.statenft;'))
