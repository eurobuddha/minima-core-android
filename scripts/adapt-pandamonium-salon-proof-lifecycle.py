"""Keep proof verdicts scoped to exact evidence, active views and current identity."""
from pathlib import Path
import shutil

def adapt(root):
    base=root/'salon/src/main/java/com/eurobuddha/salon';src=Path(__file__).parent/'pandamonium-sources/salon'
    shutil.copy2(src/'ProofRequests.java',base/'ProofRequests.java')
    p=base/'MainActivity.java';s=p.read_text()
    a=s.index('    // Per-tokenid caches');b=s.index('    private final java.util.Map<String, String> mLiveIcon',a)
    s=s[:a]+'''    private final ProofRequests proofRequests = new ProofRequests(android.os.SystemClock::elapsedRealtime);
'''+s[b:]
    a=s.index('    private void verifyInto(');b=s.index('    /** Owner still holds the coin',a)
    s=s[:a]+(src/'ProofDisplay.java.inc').read_text()+s[b:]
    marker='        if (node != null) node.onDestroy();';assert marker in s;s=s.replace(marker,'        proofRequests.close();\n'+marker)
    p.write_text(s)
    # Reuse Atelier's snapshot-before-dispatch checks at each async signing boundary.
    p=base/'NftProof.java';s=p.read_text()
    a=s.index('    static void generate(');b=s.index('    /** Verify a showcase item',a);part=s[a:b]
    signature='    static void generate(final NodeApi node, final String tokenid, final String bindHex, final Gen cb) {'
    assert signature in part
    part=part.replace(signature,signature+'\n        generate(node, tokenid, bindHex, () -> true, cb);\n    }\n\n    static void generate(final NodeApi node, final String tokenid, final String bindHex,\n            final java.util.function.BooleanSupplier current, final Gen cb) {\n        if (!current.getAsBoolean()) { cb.fail("Proof request is no longer current."); return; }')
    for arg,indent in [('r',16),('e',24),('s',32),('sg',40)]:
        old='public void onResult(JSONObject '+arg+') {';assert old in part
        part=part.replace(old,old+'\n'+' '*indent+'if (!current.getAsBoolean()) { cb.fail("Proof request is no longer current."); return; }')
    s=s[:a]+part+s[b:];p.write_text(s)
    p=base/'MainActivity.java';s=p.read_text()
    a=s.index('    /** Owner still holds the coin');b=s.index('    private interface EditionsCb',a)
    s=s[:a]+(src/'ProofRefresh.java.inc').read_text()+s[b:]
    a=s.index('    private void proveNft(');b=s.index('    private String firstNonEmpty(',a);part=s[a:b]
    old='''        String myTid = SalonStore.get(this, "tokenid");'''
    assert old in part
    part=part.replace(old,'''        if (provingHolding) { toast(HoldingMessages.message("A holding proof is already being created.")); return; }
        final String myTid = SalonStore.get(this, "tokenid");
        final String assetDraft = asset.toString(), holdingsDraft = SalonStore.arr(this, "nfts").toString();
        final java.util.function.BooleanSupplier current = () -> !isFinishing() && !isDestroyed()
                && myTid.equals(SalonStore.get(this, "tokenid")) && assetDraft.equals(asset.toString())
                && holdingsDraft.equals(SalonStore.arr(this, "nfts").toString());''')
    old='NftProof.generate(node, asset.optString("tokenid"), NftProof.hexOf(myTid), new NftProof.Gen() {';assert old in part
    part=part.replace(old,'provingHolding = true;\n        NftProof.generate(node, asset.optString("tokenid"), NftProof.hexOf(myTid), current, new NftProof.Gen() {')
    old='''            @Override public void ok(JSONObject item) {''';assert old in part
    part=part.replace(old,old+'\n                if (!current.getAsBoolean()) { provingHolding = false; return; }')
    old='''                captureEditions(asset.optString("tokenid"), editions -> {
                    try {''';assert old in part
    part=part.replace(old,'''                captureEditions(asset.optString("tokenid"), editions -> {
                    if (!current.getAsBoolean()) { provingHolding = false; return; }
                    try {''')
    old='''runOnUiThread(() -> { JSONArray a = SalonStore.arr(MainActivity.this, "nfts");''';assert old in part
    part=part.replace(old,'''runOnUiThread(() -> { provingHolding = false; if (!current.getAsBoolean()) return; JSONArray a = SalonStore.arr(MainActivity.this, "nfts");''')
    old='''runOnUiThread(() -> toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_prove_failed, HoldingMessages.message(msg))));''';assert old in part
    part=part.replace(old,'''runOnUiThread(() -> { provingHolding = false; if (current.getAsBoolean()) toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_prove_failed, HoldingMessages.message(msg))); });''')
    s=s[:a]+'    private boolean provingHolding;\n\n'+part+s[b:];p.write_text(s)
