"""Reuse shared text descriptors and retained tab refresh for Entropy's display layer."""
from pathlib import Path
import json,re,importlib.util,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 labels=json.loads((project/'localization/entropy-labels.json').read_text())
 catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'entropy/src/main/java/com/eurobuddha/entropy'
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(key)+('' if not args else ', '+', '.join(args))+')'
 def replace(s,old,new,count=None):
  assert old in s,('Entropy display source drift',old)
  if count is not None:assert s.count(old)==count,(old,s.count(old))
  return s.replace(old,new)
 def load(name):
  p=folder/(name+'.java');s=p.read_text();i=s.index(';')+1
  s=s[:i]+'\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n'+s[i:]
  pattern=r'("(?:\\.|[^"\\])*"[ \t\r\n]*\+[ \t\r\n]*"(?:\\.|[^"\\])*")'
  while re.search(pattern,s):
   s=re.sub(pattern,lambda m:json.dumps(''.join(json.loads(x) for x in re.findall(r'"(?:\\.|[^"\\])*"',m[0])),ensure_ascii=False),s)
  return p,s
 def fixed(s,keys):
  for key in keys:s=replace(s,json.dumps(catalog[labels[key]]['en'],ensure_ascii=False),msg(key))
  return s
 def text(s):return re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setText\(',r'Language.setText(\1, ',s)
 p,s=load('Ui')
 s=s.replace('Context c, String s','Context c, CharSequence s').replace('s.toUpperCase()','Language.upper(s)')
 p.write_text(text(s))
 p,s=load('MainPager');s=s.replace('String[] titles','CharSequence[] titles');p.write_text(s)
 p,s=load('MainActivity')
 s=replace(s,'android.view.View root = findViewById(R.id.pm_entropy_root);','android.view.View root = findViewById(R.id.pm_entropy_root);\n        Language.apply(root);')
 s=replace(s,'new String[]{','new CharSequence[]{',1)
 s=replace(s,'"SELF-TEST FAILED — generation blocked: " + selfTestErr',msg('selfBanner','EntropyMessages.message(selfTestErr)'))
 s=fixed(s,['diceTab','wordTab','selfFailed','connecting','unavailable','verified','malformed','commandsFailed'])
 hook='    private void refreshLanguage() {\n        TabLayout tabs = findViewById(R.id.pm_entropy_tabs);\n        if (tabs == null) return;\n        CharSequence[] titles = {Language.msg(l10n_entropy_dice_tab), Language.msg(l10n_entropy_word_tab)};\n        for (int i=0;i<titles.length;i++) if (tabs.getTabAt(i) != null) tabs.getTabAt(i).setText(titles[i]);\n    }\n    @Override public void onConfigurationChanged(android.content.res.Configuration config) {\n        super.onConfigurationChanged(config);\n        Language.configurationChanged(this);\n        refreshLanguage();\n    }\n    @Override protected void onResume() {\n        super.onResume();\n        refreshLanguage();\n    }\n\n'
 s=replace(s,'    private void failVerify()',hook+'    private void failVerify()',1)
 p.write_text(text(s))
 p,s=load('DiceView')
 s=replace(s,'Math.min(b, target) + " / " + target + " bits — " + faces.size() + " rolls"',msg('progress','Math.min(b, target)','target','faces.size()'))
 s=replace(s,'"Blocked — self-test failed: " + act.selfTestError()',msg('blockedSelf','EntropyMessages.message(act.selfTestError())'))
 start=s.index('"Each digital face =');end=s.index(', Theme.dim(), 10, false);',start)
 command='runscript script:"LET h=SHA3(CONCAT(<A> <B>)) LET n=NUMBER(SUBSET(0 4 h)) LET r=n%6 RETURN TRUE"'
 s=s[:start]+msg('auditHint',json.dumps(command))+s[end:]
 s=replace(s,'StringBuilder parts = new StringBuilder();','Language.TextBuilder parts = new Language.TextBuilder();\n        boolean firstSource = true;',1)
 s=replace(s,'if (parts.length() > 0) parts.append(", ");','if (!firstSource) parts.append(", ");\n            firstSource = false;',1)
 s=replace(s,'.append(e.getKey());','.append(sourceLabel(e.getKey()));',1)
 start=s.index('        StringBuilder sum = new StringBuilder();');end=s.index('        if (src.containsKey',start)
 s=s[:start]+'        Language.TextBuilder sum = new Language.TextBuilder().append('+msg('summary','faces.size()','all.length()','ent.length()','ent.length() / 32','phrase.size()','parts.build()')+');\n'+s[end:]
 s=replace(s,'doneSummary.setText(sum.toString());','doneSummary.setText(sum.build());',1)
 start=s.index('        StringBuilder log = new StringBuilder();');end=s.index('        auditLog.setText(log.toString());',start)
 s=s[:start]+'        Language.TextBuilder log = new Language.TextBuilder();\n        for (int i = 0; i < audits.size(); i++) {\n            RollEngine.Audit a = audits.get(i);\n            if (RollEngine.SRC_PHYSICAL.equals(a.src)) log.append(Language.msg(l10n_entropy_audit_physical,i+1,a.face));\n            else if (RollEngine.SRC_DEVICE_ONLY.equals(a.src)) log.append(Language.msg(l10n_entropy_audit_device,i+1,a.face));\n            else log.append(Language.msg(l10n_entropy_audit_combined,i+1,a.a,a.b,a.n,a.face));\n        }\n'+s[end:]
 s=replace(s,'auditLog.setText(log.toString());','auditLog.setText(log.build());',1)
 helper='    private static CharSequence sourceLabel(String source) {\n        if (RollEngine.SRC_NODE_DEVICE.equals(source)) return Language.msg(l10n_entropy_source_node);\n        if (RollEngine.SRC_DEVICE_ONLY.equals(source)) return Language.msg(l10n_entropy_source_device);\n        if (RollEngine.SRC_PHYSICAL.equals(source)) return Language.msg(l10n_entropy_source_physical);\n        return source;\n    }\n\n'
 s=replace(s,'    private void renderWords()',helper+'    private void renderWords()',1)
 s=s.replace('setSourceLine(String s,','setSourceLine(CharSequence s,').replace('setStatus(String s,','setStatus(CharSequence s,')
 s=fixed(s,['phraseLength','diceMode','digital','physical','checking','security','collect','throw','autoOff','autoOn','physicalWarning','undo','yourPhrase','reveal','conceal','fairness','fairnessHint','showAudit','hideAudit','auditWarning','wipe','verifying','sourceError','phraseMismatch','singleWarning','distributionWarning','blockedDemo'])
 p.write_text(text(s))
 p,s=load('LastWordView')
 s=replace(s,'setStatus(res.error, true)','setStatus(EntropyMessages.message(res.error), true)')
 s=replace(s,'"Blocked — startup self-test failed: " + act.selfTestError()',msg('blockedStartup','EntropyMessages.message(act.selfTestError())'))
 s=replace(s,'res.candidates.size() + " valid last words (" + res.free + " free bits + " + res.cs +\n                "-bit checksum) — all checksum-validated"',msg('candidateStatus','res.candidates.size()','res.free','res.cs'))
 s=replace(s,r'"Phrase complete — word " + wordsTarget + " = \"" + w + "\" (" + how + "). Valid BIP39 ✓"',msg('complete','wordsTarget','w','how'))
 s=s.replace('pick(String w, String how)','pick(String w, CharSequence how)').replace('setStatus(String s,','setStatus(CharSequence s,')
 s=fixed(s,['lastCalculator','hide','show','clear','lastHint','calculate','validWords','tapWord','random','resultMismatch','manual','pickedNode','pickedDevice'])
 p.write_text(text(s))
 a='{http://schemas.android.com/apk/res/android}';app='{http://schemas.android.com/apk/res-auto}'
 E.register_namespace('android',a[1:-1]);E.register_namespace('app',app[1:-1])
 p=root/'entropy/src/main/res/layout/pm_entropy_activity_main.xml';tree=E.parse(p)
 mapping={catalog[labels[k]]['en']:resource(k) for k in ['demoBanner','subtitle']}
 mapping['The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.']='l10n_explorer_node_retry'
 for v in tree.iter():
  raw=v.get(a+'text')
  if raw in mapping:v.set(a+'text','@string/'+mapping[raw]);v.set(a+'tag','l10n:text='+mapping[raw])
  ident=v.get(a+'id','')
  for suffix,key in [('btnSound','sound'),('btnDemo','demo')]:
   if ident.endswith(suffix):v.set(a+'contentDescription','@string/'+resource(key));v.set(a+'tag','l10n:description='+resource(key))
  if v.tag.endswith('TabLayout'):v.set(app+'tabMode','scrollable')
  for prop in [a+'textColor',app+'tabTextColor']:
   if v.get(prop)=='#5A6080':v.set(prop,'#8A93AD')
 E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)

 spec=importlib.util.spec_from_file_location('entropy_message_builder',project/'scripts/adapt-pandamonium-wallet-error-languages.py')
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 source=module.build_source(json.loads((project/'localization/entropy-errors.json').read_text()),catalog)
 source=source.replace('com.eurobuddha.wallet','com.eurobuddha.entropy').replace('WalletMessages','EntropyMessages')
 source=source.replace('Explicit wallet failures only.','Explicit Entropy self-test and validation failures only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-entropy-languages.py from localization/entropy-errors.json')
 (folder/'EntropyMessages.java').write_text(source)
