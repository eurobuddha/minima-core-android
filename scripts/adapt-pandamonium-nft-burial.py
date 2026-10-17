"""Use complete coin evidence and persistent public receipts before completing a StateNFT burial."""
from pathlib import Path
import shutil

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 source=project/'scripts/pandamonium-sources/NFTwallet'
 folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 def edit(name,fn):
  p=folder/(name+'.java');p.write_text(fn(p.read_text()))
 def change(s,old,new,n=1):
  assert s.count(old)==n,('NFT burial source drift',old,s.count(old),n)
  return s.replace(old,new)
 shutil.copy2(source/'BurialEvidence.java',folder/'BurialEvidence.java')
 def actions(s):
  start=s.index('    private static void gatherAndBury(');end=s.index('    // ===================== departure watch',start)
  s=s[:start]+(source/'burial-flow.inc').read_text()+s[end:]
  # A missing response cannot be presented as an empty transfer collection either.
  old='                org.json.JSONArray arr = json.optJSONArray("response");\n                final java.util.List<JSONObject> coins'
  new='''                final org.json.JSONArray arr;
                try { arr = BurialEvidence.coins(json, tokenid); }
                catch (RuntimeException e) {
                    setMessage(progress, Language.msg(l10n_nft_burial_coin_list_incomplete));
                    makeDismissable(progress); return;
                }
                final java.util.List<JSONObject> coins'''
  return change(s,old,new)
 edit('StateNftActions',actions)
 def local(s):
  s=change(s,'    private static SharedPreferences prefs(Context c) {',(source/'burial-store.inc').read_text()+'    private static SharedPreferences prefs(Context c) {')
  return change(s,'                try { arr.put(i, row); }', '''                // A delayed mint callback cannot undo a user-confirmed burial pause/completion.
                String phase = cur.optString("phase");
                if (("BURY".equals(phase) || "BURIED".equals(phase))
                        && !phase.equals(row.optString("phase"))) return;
                try { arr.put(i, row); }''')
 edit('LocalStore',local)
 def driver(s):
  start=s.index('    /** When the in-flight tick started');end=s.index('    private MintDriver()',start)
  s=s[:start]+'''    private static NodeApi activeNode;
    private static long generation;

'''+s[end:]
  start=s.index('    /** True while a tick');end=s.index('    /** Phases the engine',start)
  s=s[:start]+'''    /** A live node owns its tick until its callback; elapsed time cannot authorize overlapping writes. */
    private static synchronized boolean inFlight() {
        if (running && activeNode != null && activeNode.isClosed()) {
            running = false; activeNode = null; generation++;
        }
        return running;
    }

    static synchronized boolean pauseForBurial(Context ctx, String tokenid) {
        return !inFlight() && LocalStore.pauseForBurial(ctx, tokenid);
    }

    public static boolean hasPausedBurial(Context ctx) {
        JSONArray rows = LocalStore.load(ctx);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && "BURY".equals(row.optString("phase"))) return true;
        }
        return false;
    }

'''+s[end:]
  s=change(s,'public static Result tick(Context ctx, NodeApi node, final Done done)', 'public static synchronized Result tick(Context ctx, NodeApi node, final Done done)')
  s=change(s,'if (node == null) return Result.NOT_PAIRED;', 'if (node == null || node.isClosed()) return Result.NOT_PAIRED;')
  s=change(s,'        startedAt = System.currentTimeMillis();','        activeNode = node;\n        final long current = ++generation;')
  s=change(s,'            running = false;\n            if (done != null)', '            synchronized (MintDriver.class) {\n                if (current != generation) return;\n                running = false; activeNode = null;\n            }\n            if (done != null)')
  s=change(s,'            if (isStuck(rows.optJSONObject(i))) return true;','''            JSONObject row = rows.optJSONObject(i);
            if (row != null && !"BURY".equals(row.optString("phase")) && !"BURIED".equals(row.optString("phase")) && isStuck(row)) return true;''')
  s=s.replace('if ("DONE".equals(p) || "BURIED".equals(p)) continue;', 'if ("DONE".equals(p) || "BURIED".equals(p) || "BURY".equals(p)) continue;')
  return s
 edit('MintDriver',driver)
 def node(s):
  s=change(s,'    public boolean isEnabled()', '    public boolean isClosed() { return mReleased || dead(); }\n\n    public boolean isEnabled()')
  s=change(s,'        if (mReleased) {\n            // ALWAYS dispatch:', '        if (isClosed()) {\n            // ALWAYS dispatch:')
  s=change(s,'            if (dead()) return;\n            // The "paired', '            if (isClosed()) return;\n            // The "paired')
  s=change(s,'                    if (done[0]) return;','                    if (done[0] || isClosed()) return;')
  return s
 edit('NodeApi',node)
 def engine(s):
  s=change(s,'if ("DONE".equals(phase) || "BURIED".equals(phase)) {','if ("DONE".equals(phase) || "BURIED".equals(phase) || "BURY".equals(phase)) {')
  return change(s,'        else if ("BURY".equals(phase)) done.done("Bury is handled from the Bury screen");\n','')
 edit('MintEngine',engine)
 def service(s):
  s=change(s,'        } else if (!MintDriver.needsImages(this)) {','''        } else if (MintDriver.hasPausedBurial(this)) {
            Notifier.alert(this, com.eurobuddha.minimacore.i18n.Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_burial_paused_title).toString(),
                    com.eurobuddha.minimacore.i18n.Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_burial_paused).toString());
        } else if (!MintDriver.needsImages(this)) {''')
  return s
 edit('MintService',service)
 def view(s):
  if 'import com.eurobuddha.minimacore.i18n.Language;' not in s:s=change(s,'package com.eurobuddha.nftwallet;','package com.eurobuddha.nftwallet;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
  old='''                    () -> {
                        JSONObject fresh = LocalStore.findById(act, progressRowId);
                        if (fresh != null) {
                            try { fresh.put("phase", "BURIED"); fresh.put("error", ""); }
                            catch (Exception ignored) {}
                            LocalStore.upsert(act, fresh);   // stops the mint engine touching it
                        }
                        refresh();
                    }));'''
  s=change(s,old,'                    this::refresh));')
  s=change(s,'boolean needImage = "embed".equals(row.optString("mode")) && it.optString("image", "").isEmpty();','boolean needImage = !"BURY".equals(phase) && !"BURIED".equals(phase) && "embed".equals(row.optString("mode")) && it.optString("image", "").isEmpty();')
  s=change(s,'if (row.optInt("createstuck", 0) == 1) {','if (!"BURY".equals(phase) && !"BURIED".equals(phase) && row.optInt("createstuck", 0) == 1) {')
  s=change(s,'        if (!tokenid.isEmpty()) {\n            TextView buryAll', '        if (!tokenid.isEmpty() && !"BURIED".equals(phase)) {\n            TextView buryAll')
  s=change(s,'            buryAll.setText("✝  Bury this entire collection");','            Language.setText(buryAll, "BURY".equals(phase) ? Language.msg(l10n_nft_burial_check_retry) : "✝  Bury this entire collection");\n            buryAll.setMinHeight(dp(48));')
  s=change(s,'        if (!"DONE".equals(phase) && !MintDriver.isStuck(row)) {','''        if ("BURY".equals(phase) || "BURIED".equals(phase)) {
            TextView note = new TextView(act);
            Language.setText(note, Language.msg("BURY".equals(phase) ? l10n_nft_burial_paused : l10n_nft_burial_confirmed));
            note.setTextColor(Design.dim()); note.setTextSize(13f); note.setPadding(0, dp(14), 0, 0);
            container.addView(note);
        } else if (!"DONE".equals(phase) && !MintDriver.isStuck(row)) {''')
  return s
 edit('MintView',view)
 for name in ['BurialEvidenceTest','BurialFlowTest','BurialLifecycleTest']:
  p=project/'scripts/pandamonium-tests/NFTwallet/src/test/java/com/eurobuddha/nftwallet'/(name+'.java')
  if p.exists():shutil.copy2(p,root/'NFTwallet/src/test/java/com/eurobuddha/nftwallet'/p.name)
