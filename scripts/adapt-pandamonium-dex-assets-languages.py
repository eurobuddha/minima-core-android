"""Assets labels and PandaPools' tested reviewed-write recovery boundary."""
from pathlib import Path

def adapt(root):
 base=root/'pandadex/src/main/java/com/eurobuddha/pandadex'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('PandaDEX assets source drift',old,s.count(old))
  return s.replace(old,new)
 p=base/'AssetsTab.java';s=p.read_text()
 s=change(s,'package com.eurobuddha.pandadex;','package com.eurobuddha.pandadex;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 s=change(s,'private TextView t(String s,','private TextView t(CharSequence s,')
 s=change(s,'        v.setText(s);','        Language.setText(v, DexMessages.message(s));')
 s=change(s,'addr.isEmpty() ? "Address not loaded" : addr','addr.isEmpty() ? DexMessages.message("Address not loaded") : Language.join(addr)')
 a=s.index('        c.addView(t("confirmed "');b=s.index('\n        }',a)
 s=s[:a]+'''        c.addView(t(balanceBreakdown(confirmed, locked, unconfirmed, coins, updatedAtMs),
                Design.DIM2(), 9.5f, Design.mono()));'''+s[b:]
 s=change(s,'    private static String age(long updatedAtMs) {','''    static CharSequence balanceBreakdown(BigDecimal confirmed, BigDecimal locked, BigDecimal unconfirmed, int coins, long updatedAtMs) {
        return Language.msg(l10n_dex_asset_breakdown, PriceMath.fmt(confirmed), PriceMath.fmt(locked),
                PriceMath.fmt(unconfirmed), coins, DexMessages.message(age(updatedAtMs)));
    }

    private static String age(long updatedAtMs) {''')
 for name in ['resolve','review']:s=change(s,'            '+name+'.setPadding','            '+name+'.setMinHeight(Design.dp(act, 48));\n            '+name+'.setPadding')
 s=change(s,'        if (!addr.isEmpty()) Design.pressable(recv);','''        if (!addr.isEmpty()) {
            recv.setMinimumHeight(Design.dp(act, 48));
            Language.description(recv, DexMessages.message("Tap to copy"));
            Design.pressable(recv);
        }''')
 p.write_text(s)
 # Directly reuse PandaPools' reviewed marker + durable clear/rollback + process latch.
 p=base/'NodeApi.java';s=p.read_text();pools=(root/'pandapools/src/main/java/com/eurobuddha/pandapools/NodeApi.java').read_text()
 s=change(s,'    private static String activeWrite = "";','    private static String activeWrite = "";\n    private static volatile boolean failedWriteAcknowledgement;')
 s=change(s,'private boolean hasPendingWrite() { return !writePrefs().getString("pending", "").isEmpty(); }','private boolean hasPendingWrite() { return failedWriteAcknowledgement || !writePrefs().getString("pending", "").isEmpty(); }')
 a=s.index('    public boolean acknowledgeInterruptedWrite()');b=s.index('    private static final long READ_TIMEOUT_MS',a)
 x=pools.index('    public String interruptedWriteId()');z=pools.index('    private static final long READ_TIMEOUT_MS',x)
 s=s[:a]+pools[x:z]+s[b:];p.write_text(s)
 p=base/'MainActivity.java';s=p.read_text();a=s.index('    public void resolveInterruptedWrite()');b=s.index('    private static void appendUnavailable',a);part=s[a:b]
 part=change(part,'        new AlertDialog.Builder(this, Design.dialogTheme())','''        final NodeApi recoveryNode = node;
        final String reviewedWrite = recoveryNode.interruptedWriteId();
        final String reviewedDetails = recoveryNode.interruptedWriteDetails();
        new LanguageDialog(this, Design.dialogTheme())''')
 x=part.index('                .setMessage(');z=part.index('                .setNegativeButton',x)
 part=part[:x]+'                .setMessage(Language.msg(l10n_dex_write_recovery_body, DexMessages.message(reviewedDetails)))\n'+part[z:]
 for label in ['Resolve interrupted write','Keep paused','Copy details','I restarted and checked']:
  part=change(part,'("'+label+'"','(DexMessages.message("'+label+'")')
 part=change(part,'"Interrupted PandaDEX write", node.interruptedWriteDetails()','"Interrupted PandaDEX write", reviewedDetails')
 part=change(part,'''                    makerCfg.reload(); makerCfg.armed = false; makerCfg.save();
                    toast(node.acknowledgeInterruptedWrite() ? "Safety pause cleared. Review your orders and balances." : "A write is still active. Keep paused.");''','''                    if (node != recoveryNode || !reviewedWrite.equals(recoveryNode.interruptedWriteId())) {
                        toast("The reviewed write changed, a write is still active, or the safety state could not be saved. Keep paused."); return;
                    }
                    if (!disarmMakerForRecovery(makerCfg)) {
                        toast("The maker could not be saved as disarmed. The safety pause remains; check storage and review again."); repaint(); return;
                    }
                    toast(recoveryNode.acknowledgeInterruptedWrite(reviewedWrite) ? "Safety pause cleared. Review your orders and balances." : "The reviewed write changed, a write is still active, or the safety state could not be saved. Keep paused.");''')
 part+='''    static boolean disarmMakerForRecovery(MakerConfig config) {
        config.reload();
        config.armed = false;
        return config.saveUserAction();
    }

'''
 s=s[:a]+part+s[b:];p.write_text(s)
