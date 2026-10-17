"""Translate Filez display text with shared retained dialogs and existing menu refresh patterns."""
from pathlib import Path
import json,re,importlib.util,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/filez-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'filez/src/main/java/com/eurobuddha/filez';p=folder/'MainActivity.java';s=p.read_text()
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(key)+('' if not args else ', '+', '.join(args))+')'
 def change(old,new,count=None):
  nonlocal s
  assert old in s,('Filez display source drift',old)
  if count is not None:assert s.count(old)==count,(old,s.count(old))
  s=s.replace(old,new)
 change('package com.eurobuddha.filez;','package com.eurobuddha.filez;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n',1)
 change('private static final String MSG_NO_NODE = "The embedded node did not respond. Check its status in Minima Core.";','private static final CharSequence MSG_NO_NODE = Language.msg(l10n_terminal_ide_timeout);',1)
 change('setContentView(R.layout.pm_filez_activity_main);','setContentView(R.layout.pm_filez_activity_main);\n        Language.apply(findViewById(R.id.pm_filez_main));',1)
 change('count + (count == 1 ? " item" : " items")\n                + (root ? "  ·  backups, exports & imports live here" : "")','Language.join(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_filez_items, count), root ? '+msg('rootHint')+' : "")',1)
 # Only the local absent-field fallback is translated; node-provided errors stay literal.
 change('String err = j.optString("error", j.optString("response", "File action failed"));','String err = j.optString("error", j.optString("response", null));\n        CharSequence display = err == null ? '+msg('fileFailed')+' : err;',1)
 change('if (err.contains("ADMIN"))','if (err != null && err.contains("ADMIN"))',1)
 change('status.setText(err);\n        Toast.makeText(this, err, Toast.LENGTH_LONG).show();','status.setText(display);\n        Toast.makeText(this, display, Toast.LENGTH_LONG).show();',1)
 change('String err = j.optString("error", j.optString("message", "Backup failed"));','String raw = j.optString("error", j.optString("message", null));\n                    CharSequence err = raw == null ? '+msg('backupFailed')+' : raw;',1)
 change('"✓ Terminal will find this path (" + Util.formatBytes(j.optLong("size", 0)) + ")"',msg('pathFound','Util.formatBytes(j.optLong("size", 0))'),1)
 change('StringBuilder sb = new StringBuilder("✗ Terminal will NOT find this path!");','Language.TextBuilder sb = new Language.TextBuilder().append('+msg('pathMissing')+');',1)
 change('check.setText(sb.toString());','check.setText(sb.build());',1)
 change('"Exported " + Util.formatBytes(copied)',msg('exported','Util.formatBytes(copied)'),1)
 change('"Warning: expected " + size + " bytes, wrote " + copied',msg('sizeWarning','size','copied'),1)
 change('"Export failed: " + exc.getMessage()',msg('exportError','FilezMessages.error(exc)'),1)
 change('"Imported " + Util.formatBytes(j.optLong("size", 0))',msg('imported','Util.formatBytes(j.optLong("size", 0))'),1)
 change('"Import failed: " + exc.getMessage()',msg('importError','FilezMessages.error(exc)'),1)
 change('"Rename " + e.name',msg('renameTitle','e.name'),1)
 change(r'"Delete the folder \"" + e.name + "\" and EVERYTHING in it from the node?"',msg('deleteFolder','e.name'),1)
 change(r'"Delete \"" + e.name + "\" from the node?"',msg('deleteFile','e.name'),1)
 change('"Backup created: " + new File(file).getName()',msg('backupFile','new File(file).getName()'),1)
 change('Util.formatBytes(e.size)\n                    + (e.modified > 0 ? "  ·  " + Util.formatDate(e.modified) : "")','Language.join(Util.formatBytes(e.size), e.modified > 0 ? Language.join("  ·  ", Language.stamp(e.modified, "yMMMdHm")) : "")',1)
 change('detailSection(box, "File Type", Util.getExtension(e.name));','detailSection(box, "File Type", e.name.lastIndexOf(\'.\') < 0 || e.name.endsWith(".") ? '+msg('genericType')+' : Util.getExtension(e.name));',1)
 change('copyToClipboard("path",','copyToClipboard(Language.text(this, l10n_filez_path_label),',2)
 dynamic={'items','rootHint','fileFailed','backupFailed','pathFound','pathMissing','exported','sizeWarning','exportError','imported','importError','renameTitle','deleteFolder','deleteFile','backupFile','streamError','up','menu','entryMenu','genericType','pathLabel'}
 for key in labels:
  if key not in dynamic:change(json.dumps(catalog[labels[key]]['en'],ensure_ascii=False),msg(key))
 change('"The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry."','Language.msg(l10n_explorer_node_retry)')
 for old,new in [('final String doneMsg =','final CharSequence doneMsg ='),('String msg = Language.msg','CharSequence msg = Language.msg'),('String msg = NodeApi.ERR_TIMEOUT','CharSequence msg = NodeApi.ERR_TIMEOUT'),('String warn = e.isdir','CharSequence warn = e.isdir'),('simpleRefreshCb(String okMsg)','simpleRefreshCb(CharSequence okMsg)'),('promptText(String titleText,','promptText(CharSequence titleText,'),('detailSection(LinearLayout parent, String label, String value)','detailSection(LinearLayout parent, CharSequence label, CharSequence value)'),('toast(String msg)','toast(CharSequence msg)')]:change(old,new)
 change('new AlertDialog.Builder(this)','languageDialog()')
 # A literal name still uses the shared wrapping title widget; it never becomes a translation key.
 change('.setTitle(e.name)','.setTitle(Language.join(e.name))',1)
 # Match the existing retained-dialog/menu lifetimes. No edit, navigation or file operation on refresh.
 hook='    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();\n    private final java.util.Map<PopupMenu, java.util.Map<android.view.MenuItem, CharSequence>> languageMenus = new java.util.LinkedHashMap<>();\n    private LanguageDialog languageDialog() {\n        return new LanguageDialog(this) {\n            @Override public AlertDialog create() {\n                AlertDialog value = super.create();\n                languageDialogs.add(value);\n                value.setOnDismissListener(d -> languageDialogs.remove(value));\n                return value;\n            }\n        };\n    }\n    private void showLanguageMenu(PopupMenu menu) {\n        java.util.Map<android.view.MenuItem, CharSequence> labels = new java.util.LinkedHashMap<>();\n        for (int i=0;i<menu.getMenu().size();i++) {\n            android.view.MenuItem item = menu.getMenu().getItem(i);\n            labels.put(item,item.getTitle());\n        }\n        languageMenus.put(menu,labels);\n        menu.setOnDismissListener(m -> languageMenus.remove(m));\n        menu.show();\n    }\n    @Override public void onConfigurationChanged(android.content.res.Configuration config) {\n        super.onConfigurationChanged(config);\n        Language.configurationChanged(this);\n        for (PopupMenu menu : new java.util.ArrayList<>(languageMenus.keySet())) {\n            java.util.Map<android.view.MenuItem, CharSequence> labels = languageMenus.get(menu);\n            // Recreate only the popup window so its cached width follows the new labels.\n            // Keep the menu items, action listeners and original translation descriptors.\n            menu.setOnDismissListener(null);\n            menu.dismiss();\n            for (java.util.Map.Entry<android.view.MenuItem, CharSequence> entry : labels.entrySet())\n                entry.getKey().setTitle(entry.getValue().toString());\n            menu.setOnDismissListener(m -> languageMenus.remove(m));\n            menu.show();\n        }\n    }\n\n'
 change('    public static final String NODE_PKG',hook+'    public static final String NODE_PKG',1)
 change('        m.show();','        showLanguageMenu(m);',2)
 change('        super.onDestroy();','        for (AlertDialog value : new java.util.ArrayList<>(languageDialogs)) value.dismiss();\n        languageDialogs.clear();\n        for (PopupMenu value : new java.util.ArrayList<>(languageMenus.keySet())) value.dismiss();\n        languageMenus.clear();\n        super.onDestroy();',1)
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s)
 change('h.more.setOnClickListener(v -> showEntryMenu(v, e));','Language.description(h.more, '+msg('entryMenu','e.name')+');\n            h.more.setOnClickListener(v -> showEntryMenu(v, e));',1)
 s=s.replace('a Language.msg(l10n_filez_copy_path) button that flips to Language.msg(l10n_filez_clipboard)', 'a \"Copy path\" button that flips to \"Copied to clipboard\"')
 p.write_text(s)
 # Reuse ETH's fixed dark Material surfaces to match Filez's existing dark browser palette.
 for res in ['values','values-night']:
  p=root/f'filez/src/main/res/{res}/themes.xml';s=p.read_text();assert 'Theme.Material3.DayNight.NoActionBar' in s
  s=s.replace('Theme.Material3.DayNight.NoActionBar','Theme.Material3.Dark.NoActionBar')
  s=s.replace('    </style>','        <item name="colorPrimary">#F7931A</item>\n        <item name="colorAccent">#F7931A</item>\n        <item name="colorSurface">#15151F</item>\n        <item name="colorSurfaceContainerHigh">#15151F</item>\n        <item name="colorOnSurface">#FFFFFF</item>\n        <item name="colorOnSurfaceVariant">#9A9AA8</item>\n        <item name="android:colorBackground">#0A0A0F</item>\n        <item name="android:textColorPrimary">#FFFFFF</item>\n        <item name="android:textColorSecondary">#9A9AA8</item>\n    </style>',1);p.write_text(s)
 p=folder/'FilezDesign.java';s=p.read_text();assert '#E74C3C' in s;p.write_text(s.replace('#E74C3C','#FF5B5B'))
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1]);p=root/'filez/src/main/res/layout/pm_filez_activity_main.xml';tree=E.parse(p)
 mapping={catalog[labels[k]]['en']:resource(k) for k in ['loading','notEnabled','openCore']};mapping['The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.']='l10n_explorer_node_retry'
 for v in tree.iter():
  raw=v.get(a+'text')
  if raw in mapping:v.set(a+'text','@string/'+mapping[raw]);v.set(a+'tag','l10n:text='+mapping[raw])
  ident=v.get(a+'id','')
  for suffix,key in [('backBtn','up'),('refreshBtn','refresh'),('menuBtn','menu')]:
   if ident.endswith(suffix):v.set(a+'contentDescription','@string/'+resource(key));v.set(a+'tag','l10n:description='+resource(key))
 E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
 spec=importlib.util.spec_from_file_location('filez_message_builder',project/'scripts/adapt-pandamonium-wallet-error-languages.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 source=module.build_source({'fixed':{'Could not open stream':'filez.streamError'},'patterns':[]},catalog).replace('com.eurobuddha.wallet','com.eurobuddha.filez').replace('WalletMessages','FilezMessages')
 source=source.replace('Explicit wallet failures only.','Explicit Filez local failures only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-filez-languages.py')
 (folder/'FilezMessages.java').write_text(source)
