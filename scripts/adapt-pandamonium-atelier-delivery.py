"""Durable Atelier airdrops using the existing transfer evidence and preference-store rules."""
from pathlib import Path

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    sources=project/'scripts/pandamonium-sources/atelier'
    folder=root/'atelier/src/main/java/com/eurobuddha/statenft'
    def change(s,old,new,n=1):
        assert s.count(old)==n,('Atelier delivery source drift',old,s.count(old),n)
        return s.replace(old,new)
    (folder/'AirdropEngine.java').write_text((sources/'AirdropEngine.java').read_text())
    p=folder/'LocalStore.java';s=p.read_text()
    s=change(s,'    private LocalStore() {}','    private LocalStore() {}\n'+(sources/'airdrop-store.inc').read_text());p.write_text(s)
    p=folder/'MintService.java';s=p.read_text()
    s=change(s,'''        JSONObject idx = LocalStore.loadDraft(c, "airdrop_index");
        return idx != null && idx.length() > 0;''','        return AirdropEngine.hasWork(c);');p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text()
    s=change(s,'            int[] prog = AirdropEngine.progress(dropJob);','''            int[] prog = AirdropEngine.progress(dropJob);
            final long dropGeneration = screenGeneration;
            final String dropIdentity = dropJob.optString("jobid");
            final boolean verifiedDrop = AirdropEngine.verifiedJob(dropJob);''')
    s=change(s,'''                String st = e.optString("status");
                String err = e.optString("error", "");''','''                String st = AirdropEngine.status(verifiedDrop, e);
                CharSequence err = AirdropEngine.errorText(e);''')
    s=change(s,'("FAIL".equals(st) && !err.isEmpty())','(err.length() > 0)')
    s=change(s,'            TextView clear = Design.button(this, prog[0] >= prog[1]','''            panel.addView(Design.note(this, Language.msg(l10n_atelier_delivery_help)), lpm(0, 8, 0, 0));
            boolean checkable = false;
            if (verifiedDrop && es != null) for (int i=0;i<es.length();i++) {
                JSONObject entry=es.optJSONObject(i);
                if (entry.has("receipt") && !"DONE".equals(entry.optString("status"))) checkable=true;
            }
            if (checkable) {
                TextView check = Design.button(this, Language.msg(l10n_atelier_delivery_check), false);
                check.setMinHeight(dp(48));
                check.setOnClickListener(v -> {
                    if (!collectionCurrent(m, dropGeneration)) return;
                    JSONObject current = AirdropEngine.job(this,m.localId);
                    if (current==null || !dropIdentity.equals(current.optString("jobid"))) return;
                    if (!AirdropEngine.checkJob(this,m.localId)) { toast(Language.msg(l10n_atelier_delivery_save_failed)); return; }
                    engageEngine(); refreshDetail();
                });
                panel.addView(check, lpm(0, 8, 0, 0));
            }
            TextView clear = Design.button(this, prog[0] >= prog[1]''')
    s=change(s,'                AirdropEngine.clearJob(this, m.localId);','''                if (!collectionCurrent(m, dropGeneration)) return;
                JSONObject current = AirdropEngine.job(this,m.localId);
                if (current==null || !dropIdentity.equals(current.optString("jobid"))) return;
                if (!AirdropEngine.clearJob(this, m.localId)) { toast(Language.msg(l10n_atelier_delivery_save_failed)); return; }''')
    s=change(s,'        if ("PENDING".equals(state)) return Language.msg','''        if ("UNKNOWN".equals(state) || "POSTING".equals(state)) return Language.msg(l10n_atelier_delivery_unverified);
        if ("PENDING".equals(state)) return Language.msg''')
    s=change(s,'            AirdropEngine.saveJob(this, job);','''            if (!AirdropEngine.saveJob(this, job)) { toast(Language.msg(l10n_atelier_delivery_save_failed)); return; }''',2)
    s=change(s,'String a = oneAddr.getText().toString().trim().replace(" ", "");','String a = oneAddr.getText().toString().trim();')
    p.write_text(s)
