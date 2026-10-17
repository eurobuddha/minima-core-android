"""Recovery dialogs reuse LanguageDialog and AtomiX's immutable report-line composition."""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 fixed=json.loads((project/'localization/pools-messages.json').read_text())['fixed'];keys=json.loads((project/'localization/pools-labels.json').read_text())
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(keys['recovery'+key])+(''if not args else ', '+', '.join(args))+')'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('Recovery dialog source drift',old,s.count(old));return s.replace(old,new)
 token=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"')
 def labels(s):
  def replace(m):
   try:v=json.loads(m[0])
   except ValueError:return m[0]
   return 'Language.msg('+resource(fixed[v])+')'if v in fixed and v not in ['pool','token','address','pool address','payout address','backup check']else m[0]
  return token.sub(replace,s)
 p=root/'pandapools/src/main/java/com/eurobuddha/pandapools/PoolsMessages.java';s=p.read_text()
 helper='''    /** App-generated reports only. Preserve unknown lines and exact separators/identifiers. */
    public static CharSequence report(String value) {
        if (value == null || value.isEmpty()) return "";
        Language.TextBuilder result = new Language.TextBuilder();
        boolean first = true;
        for (String line : value.split("\\n", -1)) {
            if (!first) result.append("\\n");
            first = false;
            boolean carriageReturn = line.endsWith("\\r");
            result.append(message(carriageReturn ? line.substring(0, line.length() - 1) : line));
            if (carriageReturn) result.append("\\r");
        }
        return result.build();
    }
'''
 s=change(s,'    public static CharSequence confirmation(',helper+'    public static CharSequence confirmation(');p.write_text(s)
 p=root/'pandapools/src/main/java/com/eurobuddha/pandapools/MyLpView.java';s=p.read_text()
 start=s.index('    private void showRecoveryDialog()');end=s.index('    private void doBackup()',start);part=s[start:end]
 part=change(part,'String[] items =','CharSequence[] items =')
 a=part.index('.setMessage(key + ');b=part.index('\n                .setNegativeButton',a)
 part=part[:a]+'.setMessage('+msg('ConfirmBody','key')+')'+part[b:]
 part=labels(part).replace('new AlertDialog.Builder(act)','act.languageDialog()');s=s[:start]+part+s[end:]
 start=s.index('    private void forceBackupAfterCreate(');end=s.index('    private boolean writeUri(',start);part=s[start:end]
 a=part.index('                .setMessage("Your pool is live');b=part.index('\n                .setCancelable',a)
 part=part[:a]+'                .setMessage('+msg('SaveNowBody','addr')+')'+part[b:]
 part=change(part,'AlertDialog dlg = new AlertDialog.Builder(act)','androidx.appcompat.app.AlertDialog dlg = act.languageDialog()')
 part=change(part,'"Checked " + total + (total == 1 ? " pool." : " pools.")',msg('Checked','total'))
 part=change(part,'private void copyableReport(String title, String report)','private void copyableReport(CharSequence title, String report)')
 part=change(part,'body.setText(report);','Language.setText(body, PoolsMessages.report(report));')
 part=change(part,'.setNeutralButton("Copy",','.setNeutralButton(Language.msg(l10n_wallet_copy),')
 part=change(part,'Ui.copyable(body, "backup check", report);\n                    body.performClick();','Ui.copy(body, "backup check", body.getText().toString());')
 a=part.index('.setMessage("Restoring will rebuild');b=part.index('\n                .setPositiveButton',a)
 part=part[:a]+'.setMessage('+msg('RestoreWarning')+')'+part[b:]
 part=change(part,'status("Restoring… " + line)','status('+msg('RestoreProgress','PoolsMessages.message(line)')+')')
 part=change(part,'act.runOnUiThread(() -> status(line));','act.runOnUiThread(() -> status(PoolsMessages.message(line)));',2)
 part=change(part,'"That file isn\'t a readable PandaPools backup — pick the "\n                                + "pandapools-backup.json you saved from Back up."',msg('PickBackup'))
 part=change(part,'"Reserve check finished ·  " + restored + " of " + total + " pool(s) verified."',msg('ReserveChecked','restored','total'))
 a=part.index('restored + " of " + total + " pool(s) have both reserves verified');b=part.index('\n                        loadKeys();',a)
 part=part[:a]+'Language.join('+msg('ReserveCount','restored','total')+', "\\n\\n", PoolsMessages.report(log.toString().trim()), "\\n\\n", withdraw ? '+msg('NoneWithdrawn')+' : '+msg('CheckOnly')+'));'+part[b:]
 part=change(part,'                    status(withdrawn + " pool(s) withdrawn, " + skipped + " not withdrawn.");','''                    int notSubmitted = Math.max(skipped, verified.size() - withdrawn);
                    status('''+msg('WithdrawCounts','withdrawn','notSubmitted')+');')
 a=part.index('                    info(skipped == 0 ?');b=part.index('\n                    loadKeys();',a)
 part=part[:a]+'''                    info(notSubmitted == 0 ? '''+msg('SubmittedTitle')+' : '+msg('PartialTitle')+''',
                            Language.join('''+msg('ReserveCount','restored','total')+''', "\\n\\n",
                                    '''+msg('WithdrawCounts','withdrawn','notSubmitted')+''', "\\n\\n", PoolsMessages.report(log.toString().trim()),
                                    "\\n\\n", '''+msg('WithdrawNote')+'''));'''+part[b:]
 a=part.index('        info("How pool recovery works",');b=part.index('\n    }',a)
 part=part[:a]+'        info('+msg('GuideTitle')+', '+msg('GuideBody')+');'+part[b:]
 part=labels(part).replace('new AlertDialog.Builder(act)','act.languageDialog()');s=s[:start]+part+s[end:]
 p.write_text(s)

 # Quote external report fields as single lines. They must not inject translated check results.
 p=root/'pandapools/src/main/java/com/eurobuddha/pandapools/BackupCheck.java';s=p.read_text()
 s=change(s, '.append(warn).append(\'\\n\');', '.append(JSONObject.quote(warn)).append(\'\\n\');')
 s=change(s, '.append(error).append(\'\\n\');', '.append(JSONObject.quote(error)).append(\'\\n\');', 3)
 s=change(s, 'addr.isEmpty() ? "(no address in this entry)" : addr',
          'addr.isEmpty() ? "(no address in this entry)" : FundingCoins.hex(addr) ? addr : JSONObject.quote(addr)')
 p.write_text(s)
