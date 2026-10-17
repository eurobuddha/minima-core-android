"""PandaPools tool dialogs and expected-write acknowledgement reused from AtomiX recovery."""
from pathlib import Path
import json,re

def adapt(root):
 folder=root/'pandapools/src/main/java/com/eurobuddha/pandapools';project=Path(__file__).resolve().parents[1]
 fixed=json.loads((project/'localization/pools-messages.json').read_text())['fixed']
 def change(s,old,new,count=1):
  assert s.count(old)==count,('PandaPools tools source drift',old,s.count(old));return s.replace(old,new)
 def msg(k,*args):return 'Language.msg(l10n_pools_'+k+('' if not args else ', '+', '.join(args))+')'
 p=folder/'MainActivity.java';s=p.read_text()
 old='''    com.eurobuddha.minimacore.i18n.LanguageDialog languageDialog() {
        return new com.eurobuddha.minimacore.i18n.LanguageDialog(this) {'''
 new='''    com.eurobuddha.minimacore.i18n.LanguageDialog languageDialog() { return languageDialog(this); }
    com.eurobuddha.minimacore.i18n.LanguageDialog languageDialog(int theme) {
        return languageDialog(new androidx.appcompat.view.ContextThemeWrapper(this, theme));
    }
    private com.eurobuddha.minimacore.i18n.LanguageDialog languageDialog(android.content.Context context) {
        return new com.eurobuddha.minimacore.i18n.LanguageDialog(context) {'''
 s=change(s,old,new);p.write_text(s)
 p=folder/'NodeApi.java';s=p.read_text()
 s=change(s,'''    public boolean acknowledgeInterruptedWrite() {
        if (!activeWrite.isEmpty()) return false;
        String previous = writePrefs().getString("pending", "");''','''    public String interruptedWriteId() { return writePrefs().getString("pending", ""); }
    public boolean acknowledgeInterruptedWrite(String expected) {
        String previous = interruptedWriteId();''')
 s=change(s,'return commitWriteAcknowledgement(() -> writePrefs().edit().remove("pending").commit(),','return acknowledgeReviewedWrite(expected, previous, activeWrite,\n                () -> writePrefs().edit().remove("pending").commit(),')
 marker='    static boolean commitWriteAcknowledgement('
 helper='''    /** UI and node callbacks run on the main Looper. Consent applies only to the reviewed marker. */
    static boolean acknowledgeReviewedWrite(String expected, String current, String active,
                                            java.util.function.BooleanSupplier clear, Runnable rollback) {
        if (expected == null || !expected.equals(current) || !active.isEmpty()) return false;
        return commitWriteAcknowledgement(clear, rollback);
    }

'''
 s=change(s,marker,helper+marker);p.write_text(s)
 p=folder/'WalletTools.java';s=p.read_text();s=change(s,'package com.eurobuddha.pandapools;','package com.eurobuddha.pandapools;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 s=change(s,'''"MinimaCore replied. Current chain transaction limit: " + bytes
                            + " bytes per TxPoW.\\n\\nThe node checks the final transaction, including proofs and signatures, "
                            + "before posting. This is separate from Android's reply-delivery limit. No transaction was sent."''',msg('chain_limit','bytes'))
 s=change(s,'''"MinimaCore is connected at block " + r.optString("block", "unknown")
                        + ". This node does not expose a separate transaction-size query. "
                        + "It checks the complete TxPoW against the live chain limit when posting. "
                        + "If rejected, PandaPools reports the node's actual size and limit. No transaction was sent."''',msg('legacy_connected','r.has("block") ? r.optString("block", "") : '+msg('unknown')))
 s=change(s,'.setTitle("Consolidate " + name)','.setTitle('+msg('consolidate_title','name')+')')
 s=change(s,'''.setMessage("Combine your " + name + " wallet coins automatically?\\n\\n"
                        + "MinimaCore selects the coins using its standard consolidation command. No burn fee.\\n\\n"
                        + "consolidate tokenid:" + token)''','.setMessage('+msg('consolidate_review','name','"consolidate tokenid:" + token')+')')
 s=change(s,'''.setMessage("A node write lost its reply. It may have completed. First restart MinimaCore to stop any old command, "
                        + "then check your transactions and balances. Clearing this safety pause does not undo or retry any transaction.")''','.setMessage('+msg('interrupted_review')+')')
 s=change(s,'new androidx.appcompat.app.AlertDialog.Builder(act, ','act.languageDialog(',3)
 s=change(s,'    void resolveInterruptedWrite() {','    void resolveInterruptedWrite() {\n        final NodeApi recoveryNode = act.node();\n        final String reviewedWrite = recoveryNode.interruptedWriteId();')
 s=change(s,'if (!act.node().hasInterruptedWrite())','if (!recoveryNode.hasInterruptedWrite())')
 s=change(s,'act.node().acknowledgeInterruptedWrite()','recoveryNode.acknowledgeInterruptedWrite(reviewedWrite)')
 s=change(s,'"A write is still active or the safety state could not be saved. Keep paused."',msg('keep_write_paused'))
 s=change(s,'private void message(String text)','private void message(CharSequence text)');s=change(s,'.setMessage(text)','.setMessage(PoolsMessages.message(text))')
 # Replace application-owned literals, keeping stored English receipts and node error contracts raw.
 def labels(m):
  try:v=json.loads(m[0])
  except ValueError:return m[0]
  if v not in fixed:return m[0]
  key='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',fixed[v]).replace('.','_').lower();return 'Language.msg('+key+')'
 s=re.sub(r'"(?:\\.|[^"\\])*"',labels,s)
 s=change(s,'j.optString("message", Language.msg(l10n_pools_nothing_combined))','j.optString("message", "No coins could be combined.")')
 p.write_text(s)
