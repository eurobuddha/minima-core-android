"""Preserve identities and collections when node lookup evidence is unavailable."""
from pathlib import Path
import shutil

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    salon=root/'salon/src/main/java/com/eurobuddha/salon'
    shutil.copy2(project/'scripts/pandamonium-sources/salon/IdentityBalance.java',salon/'IdentityBalance.java')
    p=salon/'MainActivity.java';s=p.read_text()
    start=s.index('    private void adoptOrMint(');end=s.index('    private void mintIdentity(',start)
    s=s[:start]+'''    private void adoptOrMint(String h, String n, String b, String url, TextView status) {
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                final JSONObject existing;
                try { existing = IdentityBalance.find(reply, h); }
                catch (IllegalArgumentException unavailable) { onError(""); return; }
                runOnUiThread(() -> {
                    if (existing != null) finishClaim(existing.optString("tokenid"));
                    else {
                        Language.setText(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_identity_minting));
                        mintIdentity(h, n, b, url, status);
                    }
                });
            }
            @Override public void onError(String ignored) {
                runOnUiThread(() -> {
                    Language.setText(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_identity_lookup_failed));
                    claimFailed();
                });
            }
        });
    }

'''+s[end:]
    start=s.index('    private JSONObject findSalonEntry(');end=s.index('    private void adoptFromToken(',start)
    s=s[:start]+'''    private JSONObject findSalonEntry(JSONObject balanceJson, String handle) {
        try { return IdentityBalance.find(balanceJson, handle); }
        catch (IllegalArgumentException unavailable) { return null; }
    }
'''+s[end:]
    p.write_text(s)
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/MainActivity.java';s=p.read_text()
    start=s.index('    private void verifyPhantom(');end=s.index('    /** Hand the mint',start)
    part=s[start:end]
    old='''                JSONObject t = json.optJSONObject("response");
                String script = t == null ? "" : t.optString("script", "");
'''
    assert old in part
    part=part.replace(old,'''                JSONObject t = json == null ? null : json.optJSONObject("response");
                // A failed, missing, mismatched or incomplete lookup proves nothing about this collection.
                if (json == null || !Boolean.TRUE.equals(json.opt("status")) || t == null
                        || !(t.opt("tokenid") instanceof String)
                        || !tids.get(i).equalsIgnoreCase((String) t.opt("tokenid"))
                        || !(t.opt("script") instanceof String) || t.optString("script").trim().isEmpty()) {
                    onError(""); return;
                }
                String script = t.optString("script");
''')
    part=part.replace('@Override public void onError(String message) { verifyPhantom(ids, tids, i + 1, removed); }',
        '@Override public void onError(String message) { phantomsChecked = false; verifyPhantom(ids, tids, i + 1, removed); }')
    s=s[:start]+part+s[end:];p.write_text(s)
