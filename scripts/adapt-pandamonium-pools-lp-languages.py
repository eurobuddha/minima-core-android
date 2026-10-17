"""My LP overview: reuse native bindings; leave ownership and all financial math unchanged."""
from pathlib import Path
import json,re,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 fixed=json.loads((project/'localization/pools-messages.json').read_text())['fixed']
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource('pools.lp'+key)+(''if not args else ', '+', '.join(args))+')'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('My LP source drift',old,s.count(old));return s.replace(old,new)
 p=root/'pandapools/src/main/java/com/eurobuddha/pandapools/MyLpView.java';s=p.read_text()
 s=change(s,'package com.eurobuddha.pandapools;','package com.eurobuddha.pandapools;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 # This adapter owns only the summary and pool cards through actionRow. Later financial forms stay intact.
 boundary=s.index('    /**\n     * Persistent amber card');head=s[:boundary];tail=s[boundary:]
 # Collapse adjacent literal fragments so each whole application sentence is one translation.
 token=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"')
 while True:
  tokens=list(token.finditer(head));joined=False
  for left,right in zip(tokens,tokens[1:]):
   if left[0].startswith('"') and right[0].startswith('"') and re.fullmatch(r'\s*\+\s*',head[left.end():right.start()]):
    head=head[:left.start()]+json.dumps(json.loads(left[0])+json.loads(right[0]),ensure_ascii=False)+head[right.end():]
    joined=True;break
  if not joined:break
 head=change(head,'"Scan error: " + msg',msg('ScanError','PoolsMessages.message(msg)'))
 head=change(head,'"Pool live ✓   MINIMA / " + pendingLabel + " is now earning fees."',msg('Live','pendingLabel'))
 head=change(head,'unavailable.size() + " saved pool(s) need a live reserve check."',msg('CheckCount','unavailable.size()'))
 head=change(head,'trim(totalMinima) + " MINIMA reserves"',msg('MinimaReserves','trim(totalMinima)'))
 head=change(head,'mine.size() + " visible pool(s) · token reserves shown below"\n                + (unavailable.isEmpty() ? "" : " · " + unavailable.size() + " unavailable")','Language.join('+msg('VisibleCount','mine.size()')+', unavailable.isEmpty() ? "" : '+msg('UnavailableCount','unavailable.size()')+')')
 head=change(head,'"Pool: " + p.address + (p.opk == null || p.opk.isEmpty() ? "" : "\\nOwner key: " + p.opk)','Language.join('+msg('PoolId','p.address')+', p.opk == null || p.opk.isEmpty() ? "" : Language.join("\\n", '+msg('OwnerKey','p.opk')+'))')
 head=change(head,'gone.size() + " closed pool(s) kept for recovery"',msg('RetiredCount','gone.size()'))
 head=change(head,'StringBuilder b = new StringBuilder();','Language.TextBuilder b = new Language.TextBuilder();\n            boolean first = true;')
 head=change(head,'''            for (Pool p : gone) b.append("Pool: ").append(p.address)
                    .append(p.opk == null || p.opk.isEmpty() ? "" : "\\nOwner key: " + p.opk).append("\\n\\n");''','''            for (Pool p : gone) {
                if (!first) b.append("\\n\\n");
                first = false;
                b.append('''+msg('PoolId','p.address')+''')
                        .append(p.opk == null || p.opk.isEmpty() ? "" : Language.join("\\n", '''+msg('OwnerKey','p.opk')+'''));
            }''')
 head=change(head,'new AlertDialog.Builder(act)','act.languageDialog()')
 head=change(head,'b.toString().trim()','b.build()')
 old='''"This node cannot currently see both of this pool's reserve coins. That is what a pool that has been closed looks like, and also what one looks like on a node that is still catching up.\\n\\n"
                + (mine(p) ? "The owner key for it is on this device."
                           : "The owner key for it is NOT on this device. Restore the matching MinimaCore wallet backup.")
                + (held ? " Owner signing is paused for it — see below." : "")'''
 head=change(head,old,msg('MissingNote','mine(p) ? '+msg('KeyHere')+' : '+msg('KeyMissing'),'held ? '+msg('PauseSuffix')+' : ""'))
 head=change(head,'"MINIMA / " + pendingLabel + "   ·   confirming…"',msg('ConfirmingPair','pendingLabel'))
 head=change(head,'''String st = el <= 3 ? ("Writing on-chain — block " + el + " of ~3")
                            : ("Writing on-chain — " + el + " blocks (taking a little longer than usual)");''','CharSequence st = el <= 3 ? '+msg('WritingBlock','el')+' : '+msg('WritingLong','el')+';')
 head=change(head,'fmt(il) + "%  vs holding"',msg('ComparedHolding','fmt(il)'))
 head=change(head,'ageBlocks + " blocks  (~" + fmt(ageBlocks * 50.0 / 3600.0) + " h)"',msg('Age','ageBlocks','fmt(ageBlocks * 50.0 / 3600.0)'))
 # Exact application-owned labels, never dynamic token names, IDs or numeric strings.
 def replace(m):
  try:v=json.loads(m[0])
  except ValueError:return m[0]
  return 'Language.msg('+resource(fixed[v])+')'if v in fixed and v not in ['keys','pool','token','address']else m[0]
 head=token.sub(replace,head)
 head=re.sub(r'\b(value|sub|ids|detail|confirm|note|show|recover|close)\.setText\(',r'Language.setText(\1, ',head)
 s=head+tail
 # CharSequence bindings preserve the existing view, input state and callback ownership.
 s=change(s,'private TextView actionBtn(String label,','private TextView actionBtn(CharSequence label,')
 s=change(s,'t.setText(label); t.setTextSize(13);','Language.setText(t, label); t.setMinHeight(Ui.dp(act, 48)); t.setTextSize(13);')
 s=change(s,'private TextView text(String s,','private TextView text(CharSequence s,')
 s=change(s,'t.setText(s); t.setTextColor(color);','Language.setText(t, s); t.setTextColor(color);')
 s=change(s,'private View kv(String k, String v)','private View kv(CharSequence k, CharSequence v)')
 s=change(s,'private View kvColored(String k, String v,','private View kvColored(CharSequence k, CharSequence v,')
 s=change(s,'private void status(String s)','private void status(CharSequence s)')
 s=change(s,'private void status(String s, String copyValue)','private void status(CharSequence s, String copyValue)')
 s=change(s,'st.setVisibility(s.isEmpty() ?','st.setVisibility(s.length() == 0 ?')
 s=change(s,'st.setText(s);','Language.setText(st, s);')
 p.write_text(s)
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1])
 p=root/'pandapools/src/main/res/layout/pm_pandapools_view_mylp.xml';tree=E.parse(p)
 for v in tree.iter():
  text=v.get(a+'text')
  if text in fixed:
   key=resource(fixed[text]);assert not v.get(a+'tag');v.set(a+'text','@string/'+key);v.set(a+'tag','l10n:text='+key)
  if v.get(a+'id','').endswith(('lpCreateBtn','lpRecoveryBtn','lpStatementBtn','lpCalcBtn')):v.set(a+'minHeight','48dp')
 tree.write(p,encoding='utf-8',xml_declaration=True);p.write_text('\n'.join(line.rstrip()for line in p.read_text().splitlines())+'\n')
