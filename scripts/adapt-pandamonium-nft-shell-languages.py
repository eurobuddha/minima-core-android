"""Bind NFT shell, settings and receive displays while retaining the existing pages and sheets."""
from pathlib import Path
import json,re,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 labels=json.loads((project/'localization/nft-shell-labels.json').read_text())
 catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(key)+('' if not args else ', '+', '.join(args))+')'
 def change(s,old,new):
  assert old in s,('NFT display source drift',old)
  return s.replace(old,new)
 def load(name):
  p=folder/(name+'.java')
  return p,p.read_text().replace('package com.eurobuddha.nftwallet;','package com.eurobuddha.nftwallet;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 def fixed(s,keys):
  for key in keys:s=change(s,json.dumps(catalog[labels[key]]['en'],ensure_ascii=False),msg(key))
  return s
 def save(p,s):
  s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s)
  p.write_text(s)
 p,s=load('MainActivity')
 s=change(s,'setContentView(R.layout.pm_nftwallet_activity_main);','setContentView(R.layout.pm_nftwallet_activity_main);\n        Language.apply(findViewById(R.id.pm_nftwallet_main));')
 s=fixed(s,['balances','gallery','mint','send','receive','coinsLabel','history','dark','light','replyLarge','cameraFailed','pickerFailed'])
 s=change(s,'"Minima Core is not installed."',msg('noCore'))
 s=change(s,'new String[]{\n                Language.msg','new CharSequence[]{\n                Language.msg')
 s=change(s,'"TOKENS · NFTS · STATE NFTS  ·  v" + ver',msg('brand','ver'))
 s=change(s,'"◐ " + designTag()','Language.join("◐ ", designTag())')
 s=change(s,'private String designTag()','private CharSequence designTag()')
 s=change(s,'opts.setPrompt("Scan a Minima address QR");','opts.setPrompt('+msg('scanAddress')+'.toString());')
 s=change(s,'dropped + " malformed coin(s) ignored — a token\'s metadata looks hostile."',msg('badCoins','dropped'))
 s=change(s,'dropped + " malformed balance row(s) ignored — a token\'s metadata looks hostile."',msg('badBalances','dropped'))
 s=change(s,'designToggle.setTextColor(Design.accent());','designToggle.setTextColor(Design.accentText());\n        Language.description(designToggle, '+msg('style')+');')
 s=change(s,'settingsBtn.setTextColor(Design.accent());','settingsBtn.setTextColor(Design.accentText());\n        Language.description(settingsBtn, '+msg('settings')+');')
 s=change(s,'left.setTextColor(Design.accent());','left.setTextColor(Design.accentText());\n        Language.description(left, '+msg('previousTabs')+');')
 s=change(s,'right.setTextColor(Design.accent());','right.setTextColor(Design.accentText());\n        Language.description(right, '+msg('nextTabs')+');')
 hook='''    // Reuse the UTXO activity's retained-tab binding; locale changes must not fetch or mint.
    private final java.util.Set<android.app.Dialog> languageDialogs = new java.util.HashSet<>();
    void trackLanguageDialog(android.app.Dialog dialog) {
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
        TabLayout tabs = findViewById(R.id.pm_nftwallet_tabs);
        if (pager != null) for (int i=0;i<tabs.getTabCount();i++)
            tabs.getTabAt(i).setText(pager.getPageTitle(i).toString());
    }

'''
 s=change(s,'    public static final String NODE_PKG',hook+'    public static final String NODE_PKG')
 s=change(s,'        super.onDestroy();','        for (android.app.Dialog d : new java.util.ArrayList<>(languageDialogs)) d.dismiss();\n        languageDialogs.clear();\n        super.onDestroy();')
 save(p,s)
 p=folder/'MainPager.java';s=p.read_text();s=change(s,'String[] titles','CharSequence[] titles');p.write_text(s)
 p,s=load('BaseView');s=change(s,'root = LayoutInflater.from(a).inflate(layoutRes, null);','root = LayoutInflater.from(a).inflate(layoutRes, null);\n        Language.apply(root);');save(p,s)
 p,s=load('Sheet')
 for old,new in [('String title','CharSequence title'),('subtitle(String s)','subtitle(CharSequence s)'),('action(String label','action(CharSequence label'),('text(String msg)','text(CharSequence msg)'),('String message)','CharSequence message)'),('!title.isEmpty()','title.length() > 0'),('!s.isEmpty()','s.length() > 0')]:s=change(s,old,new)
 s=fixed(s,['close'])
 s=change(s,'        dialog.setContentView(root);','        dialog.setContentView(root);\n        if (ctx instanceof MainActivity) ((MainActivity)ctx).trackLanguageDialog(dialog);')
 save(p,s)
 p,s=load('SettingsDialog')
 s=fixed(s,['settings','themeDark','themeClean','themeOriginalLight','themeOriginalDark','decimalPlaces','hiddenTokens','noneHidden','unhide','unhidden','never','connected','nodeRetry','close'])
 s=change(s,'String[] labels =','CharSequence[] labels =')
 for literal,key in [('THEME','theme'),('NODE','node'),('ABOUT','aboutHeading')]:s=change(s,'"'+literal+'"','Language.upper('+msg(key)+')')
 s=change(s,'"Minima carries up to 44 decimal places. Lists are trimmed to this many; "\n                + "detail sheets and transaction confirmations always show the exact figure."',msg('decimalHelp'))
 s=change(s,'Format.label(d)','d == Format.FULL ? '+msg('fullPrecision')+' : '+msg('precisionPlaces','d'))
 s=change(s,'String last = ok <= 0 ? '+msg('never')+' : ((System.currentTimeMillis() - ok) / 1000) + "s ago";','CharSequence last = ok <= 0 ? '+msg('never')+' : Language.since(ok);')
 s=change(s,'(paired ? '+msg('connected')+' : '+msg('nodeRetry')+')\n                + "\\nLast node reply: " + last\n               ','Language.join(paired ? '+msg('connected')+' : '+msg('nodeRetry')+', "\\n", '+msg('lastReply','last')+')')
 s=change(s,'"NFT Wallet v" + ver + " — the full-suite Minima companion.\\n"\n                + "Tokens · NFTs · State NFT locked editions.\\n"\n                + "Uses the embedded node wallet."',msg('about','ver'))
 s=change(s,'section(MainActivity act, String label)','section(MainActivity act, CharSequence label)')
 s=change(s,'un.setTextColor(Design.accent());','un.setTextColor(Design.accentText());')
 save(p,s)
 p,s=load('ReceiveView')
 s=fixed(s,['fetching','showing','addressCopied'])
 old='''                StringBuilder s = new StringBuilder();
                s.append(mine ? "✓ Belongs to this node" : "◦ Not recognised as this node's address");
                if (simple) s.append("   ·   ✓ Safe to use");
                safety.setText(s.toString());'''
 new='''                CharSequence report = mine ? %s : %s;
                safety.setText(simple ? Language.join(report, "   ·   ", %s) : report);'''%(msg('belongs'),msg('notBelongs'),msg('safe'))
 s=change(s,old,new)
 s=change(s,'CoinDetailDialog.copy(act, "Address", addr);','CoinDetailDialog.copy(act, '+msg('addressLabel')+', addr);')
 s=change(s,'refreshBtn.setTextColor(Design.accent());','refreshBtn.setTextColor(Design.accentText());\n        Language.description(qr, '+msg('qr')+');\n        listLabel.setTextColor(Design.dim());')
 s=change(s,'t.setTextColor(active ? Design.accent() : Design.text());','t.setTextColor(active ? Design.accentText() : Design.text());')
 s=change(s,'chip.setTextColor(Design.accent());','chip.setTextColor(Design.accentText());')
 save(p,s)
 # Copy the literal value unchanged; the label is display-only and can carry a resource binding.
 p,s=load('CoinDetailDialog')
 s=change(s,'copy(Context ctx, String label, String value)','copy(Context ctx, CharSequence label, String value)')
 s=change(s,'label + " copied"',msg('labelCopied','label'))
 # No other CoinDetailDialog labels are included in this scope.
 p.write_text(s)
 p=folder/'Design.java';s=p.read_text();s=change(s,'    public static int accent2()', '    public static int accentText() { return isDark() ? accent() : 0xFFB93D0B; }\n    public static int accent2()');p.write_text(s)
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1])
 mapping={catalog[labels[k]]['en']:resource(k) for k in ['yourAddress','copyAddress','nextAddress','addressPool','nodeNotReady','nodeRetry','openCore','brandHelp']}
 for layout in ['activity_main','view_receive']:
  p=root/f'NFTwallet/src/main/res/layout/pm_nftwallet_{layout}.xml';tree=E.parse(p)
  for v in tree.iter():
   raw=v.get(a+'text')
   if raw in mapping:
    assert v.get(a+'tag') is None
    v.set(a+'text','@string/'+mapping[raw]);v.set(a+'tag','l10n:text='+mapping[raw])
   if v.get(a+'id','').endswith(('designToggle','settingsBtn','tabScrollLeft','tabScrollRight')):
    v.set(a+'minHeight','48dp');v.set(a+'minWidth','48dp');v.set(a+'gravity','center')
  E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
