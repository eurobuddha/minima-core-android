"""Bind UTXO shell, Send and Receive displays without refreshing financial state on locale changes."""
from pathlib import Path
import json,re,importlib.util,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/utxo-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text());folder=root/'utxo/src/main/java/com/eurobuddha/utxo'
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(key)+('' if not args else ', '+', '.join(args))+')'
 def change(s,old,new):
  assert old in s,('UTXO display source drift',old)
  return s.replace(old,new)
 def load(name):
  p=folder/(name+'.java');return p,p.read_text().replace('package com.eurobuddha.utxo;','package com.eurobuddha.utxo;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 def fixed(s,keys):
  for key in keys:s=change(s,json.dumps(catalog[labels[key]]['en'],ensure_ascii=False),msg(key))
  return s
 def save(p,s):
  s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s)
  p.write_text(s)
 p,s=load('MainActivity')
 s=change(s,'setContentView(R.layout.pm_utxo_activity_main);','setContentView(R.layout.pm_utxo_activity_main);\n        Language.apply(findViewById(R.id.pm_utxo_main));')
 s=fixed(s,['wallet','balances','receive','send','history','light','dark','native'])
 s=change(s,'new String[]{Language.msg','new CharSequence[]{Language.msg')
 s=change(s,'"PICK YOUR COINS  ·  v" + ver',msg('brand','ver'))
 s=change(s,'"◐ " + designTag()','Language.join("◐ ", designTag())')
 s=change(s,'private String designTag()','private CharSequence designTag()')
 s=change(s,'"Minima Core is not installed."','Language.msg(l10n_filez_no_core)')
 s=change(s,'Toast.makeText(this, message, Toast.LENGTH_SHORT)','Toast.makeText(this, UtxoMessages.message(message), Toast.LENGTH_SHORT)')
 hook='''    private final java.util.Set<androidx.appcompat.app.AlertDialog> languageDialogs = new java.util.HashSet<>();
    void trackLanguageDialog(androidx.appcompat.app.AlertDialog dialog) {
        languageDialogs.add(dialog);
        dialog.setOwnerActivity(this);
        dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { languageDialogs.add(dialog); }
            @Override public void onViewDetachedFromWindow(View v) { languageDialogs.remove(dialog); }
        });
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        // Retitle tabs in place. Pager notifications would select/refresh Send and fetch a change address.
        TabLayout tabs = findViewById(R.id.pm_utxo_tabs);
        if (pager != null) for (int i=0;i<tabs.getTabCount();i++)
            tabs.getTabAt(i).setText(pager.getPageTitle(i).toString());
    }

'''
 s=change(s,'    public static final String NODE_PKG',hook+'    public static final String NODE_PKG')
 s=change(s,'        super.onDestroy();','        for (androidx.appcompat.app.AlertDialog d : new java.util.ArrayList<>(languageDialogs)) d.dismiss();\n        languageDialogs.clear();\n        super.onDestroy();')
 s=change(s,'designToggle.setTextColor(Design.accent());','designToggle.setTextColor(Design.accentText());\n        Language.description(designToggle, '+msg('style')+');')
 save(p,s)
 p=folder/'MainPager.java';p.write_text(p.read_text().replace('String[] titles','CharSequence[] titles'))
 p,s=load('BaseView');s=change(s,'root = LayoutInflater.from(a).inflate(layoutRes, null);','root = LayoutInflater.from(a).inflate(layoutRes, null);\n        Language.apply(root);');save(p,s)
 p,s=load('ReceiveView');s=fixed(s,['fetching','address','addressCopied']);s=change(s,'Toast.makeText(act, message, Toast.LENGTH_SHORT)','Toast.makeText(act, UtxoMessages.message(message), Toast.LENGTH_SHORT)')
 s=change(s,'refreshBtn.setTextColor(Design.accent());','refreshBtn.setTextColor(Design.accentText());\n        Language.description(qr, '+msg('qr')+');')
 save(p,s)
 p,s=load('SendView')
 # Reuse core BaseView/AppsView's generation check for pending change-address requests.
 s=change(s,'    private boolean changeFetching = false;','    private long changeGeneration;\n    private boolean changeFetching = false;')
 s=change(s,'if (!settingChange) changeTouched = true;','if (!settingChange) { changeTouched = true; changeGeneration++; changeFetching = false; }')
 s=change(s,'        changeFetching = true;\n        act.node().cmd("getaddress"','        final long generation = ++changeGeneration;\n        if (userInitiated) changeTouched = true;\n        changeFetching = true;\n        act.node().cmd("getaddress"')
 s=change(s,'                changeFetching = false;\n                JSONObject r','                if (generation != changeGeneration) return;\n                changeFetching = false;\n                JSONObject r')
 s=change(s,'@Override public void onError(String message) { changeFetching = false; }','@Override public void onError(String message) { if (generation == changeGeneration) changeFetching = false; }')
 s=change(s,'                settingChange = true; changeInput.setText(""); settingChange = false;','                changeGeneration++; changeFetching = false;\n                settingChange = true; changeInput.setText(""); settingChange = false;')
 # The stored error stays unchanged; only its display is localized.
 s=change(s,'? "The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry."','? Language.msg(l10n_explorer_node_retry)')
 s=change(s,'"Max sendable is " + Util.tidyAmount(maxSpendable.toPlainString()) + "."',msg('maxAmount','Util.tidyAmount(maxSpendable.toPlainString())'))
 s=change(s,'"This token supports at most " + dec + " decimals."',msg('decimals','dec'))
 s=change(s,'"Address check failed: " + message',msg('checkFailed','UtxoMessages.message(message)'))
 s=change(s,'"✓ Posted — " + Util.tidyAmount(amountStr) + " " + tokenName\n                        + " sent. It\'s at the top of History; the Explorer link appears once it confirms on-chain."',msg('posted','Util.tidyAmount(amountStr)','tokenName'))
 s=change(s,'"Failed: " + message',msg('failed','UtxoMessages.message(message)'))
 s=change(s,'"No reply from the node in time — the transaction may still have posted. "\n                        + "See \'posted?\' at the top of History; it settles itself over the next blocks."',msg('unknown'))
 s=change(s,').onProgress(label -> status(label, true)).run();',').onProgress(label -> status(UtxoMessages.message(label), true)).run();')
 s=fixed(s,['chooseCoins','coin','totalIn','burnOnly','selectFirst','recipientRequired','invalidAddress','invalidBurn','burnExceeds','validAmount','positiveAmount','changeRequired','invalidChange','checking','inputs','outputs','to','change','burn','warning','confirm','back','signPost','recording','copied'])
 # Preserve the raw semantic tag for the existing verify/red guard; translate only visible text.
 s=change(s,'l.setText(tag.isEmpty() ? label : label + "  " + tag);','l.setText(tag.isEmpty() ? label : Language.join(label, "  ", "(verify)".equals(tag) ? '+msg('verify')+' : '+msg('yours')+'));')
 for old,new in [('row(String left, String right','row(CharSequence left, String right'),('idRow(String label,','idRow(CharSequence label,'),('sectionLabel(String text)','sectionLabel(CharSequence text)'),('setNote(TextView note, String msg','setNote(TextView note, CharSequence msg'),('status(String msg','status(CharSequence msg')]:s=change(s,old,new)
 s=change(s,'note.setTextColor(act.getColor(colorRes));','note.setTextColor(colorRes == R.color.pm_utxo_ux_error ? Design.red() : Design.dim());')
 s=change(s,'        r.addView(rt);','        rt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));\n        rt.setGravity(Gravity.END);\n        r.addView(rt);')
 s=change(s,'        head.addView(rt);','        rt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));\n        rt.setGravity(Gravity.END);\n        head.addView(rt);')
 s=change(s,'        root.setBackgroundColor(Design.bg());','        root.setBackgroundColor(Design.bg());\n        themeLabels(root);')
 s=change(s,'            e.setTextColor(Design.text());','            e.setTextColor(Design.text());\n            e.setHintTextColor(Design.dim());')
 s=change(s,'        previewBtn.setTextColor(Design.onAccent());','        previewBtn.setTextColor(Design.onAccent());\n        ((TextView)find(R.id.pm_utxo_maxBtn)).setTextColor(Design.accentText());\n        ((TextView)find(R.id.pm_utxo_nextBtn)).setTextColor(Design.accentText());')
 s=change(s,'    /** Rebuilds the FROM','''    private void themeLabels(View view) {
        if (view instanceof TextView && !(view instanceof EditText) && !(view instanceof Button))
            ((TextView)view).setTextColor(Design.dim());
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)themeLabels(group.getChildAt(i));
        }
    }

    /** Rebuilds the FROM''')
 save(p,s)
 p=folder/'Design.java';s=p.read_text()
 s=change(s,'return new androidx.appcompat.app.AlertDialog.Builder(c, dialogTheme());','''return new com.eurobuddha.minimacore.i18n.LanguageDialog(c, dialogTheme()) {
            @Override public androidx.appcompat.app.AlertDialog create() {
                androidx.appcompat.app.AlertDialog dialog = super.create();
                if (c instanceof MainActivity) ((MainActivity)c).trackLanguageDialog(dialog);
                return dialog;
            }
        };''')
 s=change(s,'    public static int accent2()', '    public static int accentText() { return pick(0xFFB93D0B, 0xFFFF7847, 0xFFF7931A); }\n    public static int accent2()')
 s=s.replace('0xFFE74C3C','0xFFFF5B5B');s=change(s,'return pick(0xFFFF5A1F, 0xFFFF5A1F, 0xFF2ECC71);','return pick(0xFFB93D0B, 0xFFFF5A1F, 0xFF2ECC71);');p.write_text(s)
 p=root/'utxo/src/main/res/values/themes.xml';theme=p.read_text()
 # Dark orange fills stay unchanged; small text on white needs the existing darker accent.
 start=theme.index('<style name="pm_utxo_Theme.Utxo.Dialog.Light"');end=theme.index('</style>',start)
 theme=theme[:start]+theme[start:end].replace('#FFFF5A1F','#FFB93D0B')+theme[end:];p.write_text(theme)
 # XML labels only: editable values and protocol placeholders are deliberately literal.
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1])
 keys=['fromSelected','recipient','addressHint','amount','max','changeTo','next','burnLabel','preview','yourAddress','copyAddress','getAddress','nodeNotReady']
 mapping={catalog[labels[k]]['en']:resource(k) for k in keys}
 mapping.update({'Open Minima Core':'l10n_filez_open_core','The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.':'l10n_explorer_node_retry'})
 for layout in ['activity_main','view_send','view_receive']:
  p=root/f'utxo/src/main/res/layout/pm_utxo_{layout}.xml';tree=E.parse(p)
  for v in tree.iter():
   tags=[]
   for attr in ['text','hint']:
    raw=v.get(a+attr)
    if raw in mapping:v.set(a+attr,'@string/'+mapping[raw]);tags.append(attr+'='+mapping[raw])
   if tags:
    assert v.get(a+'tag') is None
    v.set(a+'tag','l10n:'+';'.join(tags))
   ident=v.get(a+'id','')
   if ident.endswith(('maxBtn','nextBtn','designToggle')):v.set(a+'minHeight','48dp');v.set(a+'minWidth','48dp');v.set(a+'gravity','center')
  E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
 # Reuse the explicit diagnostic mapper. Unknown node errors are shown literally.
 spec=json.loads((project/'localization/vestr-errors.json').read_text())
 spec['fixed'].update({'NOT_ENABLED':'explorer.nodeRetry','The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.':'explorer.nodeRetry',catalog[labels['building']]['en']:labels['building'],catalog[labels['signing']]['en']:labels['signing'],catalog[labels['rejected']]['en']:labels['rejected'],catalog[labels['tooLong']]['en']:labels['tooLong'],catalog[labels['coinInvalid']]['en']:labels['coinInvalid']})
 spec['patterns']=[{'template':'Adding inputs ({0}/{1})…','key':'utxo.addingInputs','kinds':['integer','integer']},{'template':'Adding outputs ({0}/{1})…','key':'utxo.addingOutputs','kinds':['integer','integer']}]
 extra=json.loads((project/'localization/utxo-history-messages.json').read_text())
 spec['fixed'].update(extra['fixed']);spec['patterns'].extend(extra['patterns'])
 loader=importlib.util.spec_from_file_location('utxo_messages',project/'scripts/adapt-pandamonium-wallet-error-languages.py');module=importlib.util.module_from_spec(loader);loader.loader.exec_module(module)
 source=module.build_source(spec,catalog).replace('com.eurobuddha.wallet','com.eurobuddha.utxo').replace('WalletMessages','UtxoMessages').replace('Explicit wallet failures only.','Explicit UTXO local diagnostics only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-utxo-languages.py')
 (folder/'UtxoMessages.java').write_text(source)
