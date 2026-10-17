"""AtomiX interrupted-write display and durable acknowledgement, reusing PandaPools."""
from pathlib import Path
import importlib.util,json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 labels=json.loads((project/'localization/atomix-recovery-labels.json').read_text())
 catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'atomix/src/main/java/com/eurobuddha/atomix'
 def msg(key,*args):
  resource='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
  return 'Language.msg('+resource+('' if not args else ', '+', '.join(args))+')'
 p=folder/'MainActivity.java';s=p.read_text()
 start=s.index('        if (node != null && node.hasInterruptedWrite()) {');end=s.index('        LinearLayout minimaCard',start)
 part=s[start:end]
 for old,new in [
 ('"Resolve interrupted write"',msg('resolve')),
 ('"Keep paused"',msg('keep')),('"I have checked"',msg('checked')),
 ('"Pause cleared. Review balances before starting a new trade."',msg('cleared')),
 ('"A write is still active; keep paused."',msg('still'))]:
  assert old in part;part=part.replace(old,new)
 a=part.index('.setMessage(')+len('.setMessage(');b=part.index('\n                    .setNegativeButton',a)
 part=part[:a]+msg('body','detail == null ? '+msg('unknown')+' : RecoveryMessages.message(detail)')+')'+part[b:]
 part=part.replace('String detail = node.interruptedWriteDetail();','final com.eurobuddha.atomix.integratedcomms.NodeApi recoveryNode = node;\n            final String pausedWrite = recoveryNode.interruptedWriteId();\n            String detail = recoveryNode.interruptedWriteDetail();')
 part=part.replace('node.acknowledgeInterruptedWrite()', 'recoveryNode.acknowledgeInterruptedWrite(pausedWrite)')
 part=part.replace('            resolve.setOnClickListener','            resolve.setMinimumHeight(dp(48));\n            resolve.setOnClickListener')
 s=s[:start]+part+s[end:];p.write_text(s)
 loader=importlib.util.spec_from_file_location('recovery_message_builder',project/'scripts/adapt-pandamonium-wallet-error-languages.py')
 module=importlib.util.module_from_spec(loader);loader.loader.exec_module(module)
 source=module.build_source(json.loads((project/'localization/atomix-recovery-messages.json').read_text()),catalog)
 source=source.replace('package com.eurobuddha.wallet;','package com.eurobuddha.atomix;').replace('WalletMessages','RecoveryMessages').replace('Explicit wallet failures only.','Explicit interrupted-write explanations only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-atomix-recovery.py from localization/atomix-recovery-messages.json')
 (folder/'RecoveryMessages.java').write_text(source)
 p=root/'atomix/src/main/java/com/eurobuddha/comms/NodeApi.java';s=p.read_text()
 old='public boolean acknowledgeInterruptedWrite() { return WriteSafety.acknowledge(writePrefs()); }'
 assert old in s
 s=s.replace(old,'public String interruptedWriteId() { return writePrefs().getString("pending", ""); }\n    public boolean acknowledgeInterruptedWrite(String expected) { return WriteSafety.acknowledge(writePrefs(), expected); }')
 p.write_text(s)
 p=root/'atomix/src/main/java/com/eurobuddha/comms/WriteSafety.java';s=p.read_text()
 def change(old,new):
  nonlocal s
  assert old in s,('WriteSafety source drift',old);s=s.replace(old,new)
 change('    private static String active = "";','    private static String active = "";\n    private static boolean failedAcknowledgement;')
 change('if (!prefs.getString("pending", "").isEmpty()) return false;','if (failedAcknowledgement || !prefs.getString("pending", "").isEmpty()) return false;')
 change('if (id.isEmpty()) return null;','if (id.isEmpty()) return failedAcknowledgement ? "A node write lost its reply and may have completed." : null;')
 change('static synchronized void resetForTest() { active = ""; }','static synchronized void resetForTest() { active = ""; failedAcknowledgement = false; }')
 change('return active.isEmpty() && !prefs.getString("pending", "").isEmpty();','return active.isEmpty() && (failedAcknowledgement || !prefs.getString("pending", "").isEmpty());')
 old='''        return prefs.edit().remove("pending").remove("pending_cmd").remove("pending_at")
                           .remove("pending_why").commit();'''
 new='''        String previous = prefs.getString("pending", "");
        String marker = previous.isEmpty() ? java.util.UUID.randomUUID().toString() : previous;
        String command = prefs.getString("pending_cmd", "");
        String reason = prefs.getString("pending_why", "");
        long at = prefs.getLong("pending_at", 0);
        return commitAcknowledgement(() -> prefs.edit().remove("pending").remove("pending_cmd")
                        .remove("pending_at").remove("pending_why").commit(),
                () -> prefs.edit().putString("pending", marker).putString("pending_cmd", command)
                        .putLong("pending_at", at).putString("pending_why", reason).commit());'''
 change(old,new)
 # PandaPools NodeApi.commitWriteAcknowledgement: keep an in-memory latch even if rollback fails.
 insert='''
    // Reuse the wallet retry flow's expected-state check before clearing reviewed evidence.
    static synchronized boolean acknowledge(SharedPreferences prefs, String expected) {
        if (expected == null || !expected.equals(prefs.getString("pending", ""))) return false;
        return acknowledge(prefs);
    }

    static synchronized boolean commitAcknowledgement(java.util.function.BooleanSupplier clear, Runnable rollback) {
        failedAcknowledgement = true;
        try { if (clear.getAsBoolean()) { failedAcknowledgement = false; return true; } }
        catch (Exception e) { /* keep process latch */ }
        try { rollback.run(); } catch (Exception e) { /* keep process latch */ }
        return false;
    }
'''
 pos=s.rfind('}');s=s[:pos]+insert+s[pos:];p.write_text(s)
