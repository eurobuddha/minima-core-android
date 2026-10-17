"""My LP notices and backup results; reuse verified-export cleanup and native language bindings."""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 fixed=json.loads((project/'localization/pools-messages.json').read_text())['fixed']
 keys=json.loads((project/'localization/pools-labels.json').read_text())
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(keys['lp'+key])+(''if not args else ', '+', '.join(args))+')'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('My LP notice source drift',old,s.count(old));return s.replace(old,new)
 token=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"')
 def labels(s):
  def replace(m):
   try:v=json.loads(m[0])
   except ValueError:return m[0]
   return 'Language.msg('+resource(fixed[v])+')'if v in fixed and v not in ['pool','token','address','pool address','payout address']else m[0]
  return token.sub(replace,s)
 p=root/'pandapools/src/main/java/com/eurobuddha/pandapools/MyLpView.java';s=p.read_text()
 start=s.index('    private View backupCard()');end=s.index('    private TextView actionBtn(',start)
 part=s[start:end]
 a=part.index('        detail.setText(never');b=part.index('\n        detail.setTextIsSelectable',a)
 part=part[:a]+'        Language.setText(detail, never ? '+msg('BackupMissingBody','owned')+' : '+msg('BackupChangedBody')+');'+part[b:]
 a=part.index('        detail.setText("Your pool has confirmed');b=part.index('\n        detail.setTextIsSelectable',a)
 part=part[:a]+'        Language.setText(detail, '+msg('ProofsAvailable','addr')+');'+part[b:]
 # Clear proofless markers only after the existing verified read-back confirms the exported proofs.
 part=change(part,'again.setOnClickListener(v -> { clearProofless(addr); doBackup(); });','again.setOnClickListener(v -> doBackup());')
 a=part.index('        detail.setText((unknown');b=part.index('\n        detail.setTextIsSelectable',a)
 part=part[:a]+'''        Language.setText(detail, Language.join(
                unknown ? '''+msg('ExitUncertain')+' : '+msg('ExitStopped')+''',
                "\\n\\n", '''+msg('ExitCheck')+''', "\\n\\n", '''+msg('PoolId','r.address')+''',
                r.closeTxpowid == null || r.closeTxpowid.isEmpty() ? "" : Language.join("\\n", '''+msg('Transaction','r.closeTxpowid')+'''),
                r.reason == null || r.reason.isEmpty() ? "" : Language.join("\\n\\n", PoolsMessages.message(r.reason))));'''+part[b:]
 a=part.index('        detail.setText((stranded');b=part.index('\n        detail.setTextIsSelectable',a)
 part=part[:a]+'''        Language.setText(detail, Language.join(
                stranded ? '''+msg('CollectStrandedBody')+' : '+msg('CollectPendingBody')+''',
                "\\n\\n", e.oadr,
                e.lastError == null || e.lastError.isEmpty() ? "" : Language.join("\\n\\n", PoolsMessages.message(e.lastError))));'''+part[b:]
 part=change(part,'String msg = !known ?','CharSequence msg = !known ?')
 part=change(part,'"Pool re-published ✓ " + txpowid\n                            + " — fresh reserves + registry beacon; other nodes will rediscover it shortly."',msg('RepublishSubmitted','txpowid'))
 part=change(part,'"Re-publish: " + message',msg('RepublishError','PoolsMessages.message(message)'))
 part=labels(part)
 part=re.sub(r'\b(back|again|later|now)\.setText\(',r'Language.setText(\1, ',part)
 # Long translations can wrap; equal-width actions remain reachable on the Fold's narrow display.
 part=change(part,'row.addView(again); row.addView(later); card.addView(row);','''again.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        later.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(again); row.addView(later); card.addView(row);''')
 part=change(part,'        btn.setOnClickListener','        btn.setMinHeight(Ui.dp(act, 48));\n        btn.setMaxWidth(Ui.dp(act, 150));\n        btn.setGravity(Gravity.CENTER);\n        btn.setOnClickListener')
 s=s[:start]+part+s[end:]
 start=s.index('    private void doBackup()');end=s.index('\n    /**',start);part=s[start:end]
 part=change(part,'"Backup NOT verified: " + problem',msg('BackupUnverified','PoolsMessages.message(problem)'))
 a=part.index('                                "PandaPools saved the file');b=part.index('\n                        return;',a)
 part=part[:a]+'                                '+msg('BackupReadbackFailed','PoolsMessages.message(problem)')+');'+part[b:]
 a=part.index('                            "PandaPools read the file back');b=part.index('\n                    act.pools().refresh()',a)
 part=part[:a]+'                            '+msg('BackupVerifiedBody','owned.size()','json.contains("proof_warning") ? '+msg('BackupProofsMissing')+' : '+msg('BackupProofsExpire') )+');'+part[b:]
 part=change(part,'status(msg);','status(PoolsMessages.message(msg));')
 part=labels(part);s=s[:start]+part+s[end:]
 # Existing info dialogs keep selectable text and now retain their language bindings while open.
 s=change(s,'private void info(String title, String msg)','private void info(CharSequence title, CharSequence msg)')
 s=change(s,'AlertDialog dialog = new AlertDialog.Builder(act).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();','androidx.appcompat.app.AlertDialog dialog = act.languageDialog().setTitle(title).setMessage(msg).setPositiveButton(Language.msg(l10n_pools_ok), null).show();')
 p.write_text(s)
