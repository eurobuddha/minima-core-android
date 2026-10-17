"""Reuse the shared native bindings, dialogs and retained-menu lifecycle in Terminal IDE."""
from pathlib import Path
import json,re,shutil,importlib.util,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/terminal-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(key)+('' if not args else ', '+', '.join(args))+')'
 def literal(key):return json.dumps(catalog[labels[key]]['en'],ensure_ascii=False)
 folder=root/'terminalide/src/main/java/com/eurobuddha/terminalide'
 imports='\nimport com.eurobuddha.minimacore.i18n.Language;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n'
 def load(name):
  p=folder/name;s=p.read_text();pos=s.index(';')+1;return p,s[:pos]+imports+s[pos:]
 def replace(s,old,new,count=None):
  assert old in s,('Terminal localization drift',old)
  if count is not None:assert s.count(old)==count,(old,s.count(old))
  return s.replace(old,new)
 def fixed(s,keys):
  for k in keys:s=replace(s,literal(k),msg(k))
  return s
 p,s=load('MainActivity.java')
 s=replace(s,'        View root = findViewById(R.id.pm_terminalide_main);','        View root = findViewById(R.id.pm_terminalide_main);\n        Language.apply(root);')
 s=replace(s,'String[] titles = {"Terminal", "Scripts", "Txn", "Logs"};','CharSequence[] titles = {'+', '.join(msg(k) for k in ['terminal','scripts','txn','logs'])+'};')
 s=replace(s,'String[] actions =','CharSequence[] actions =')
 s=replace(s,'"Save failed: " + e.getMessage()',msg('saveFailed','e.getMessage()'))
 s=replace(s,'"Export session (" + (SessionExport.utf8Len(text) / 1024) + " KB)"',msg('exportTitle','SessionExport.utf8Len(text) / 1024'))
 start=s.index('"Professional tooling for the Minima protocol:');end=s.index('\n                    .setPositiveButton',start)
 s=s[:start]+msg('aboutBody')+')'+s[end:]
 s=fixed(s,['sessionSaved','offline','selectOutput','copyOutput','exportSession','clear','docs','about','exitSelect','nothingExport','saveFile','share','copyClipboard','noPicker','shareSession','cancel','ok'])
 s=replace(s,'"block " + block',msg('block','block'))
 s=replace(s,'mToolbar.setSubtitle(', 'setNodeSubtitle(')
 s=replace(s,'new AlertDialog.Builder(this)','languageDialog()')
 s=s.replace('e.getMessage())','com.eurobuddha.terminalide.terminal.TerminalMessages.error(e))')
 hook='''    private CharSequence mNodeSubtitle = "";
    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    public LanguageDialog languageDialog() {
        return new LanguageDialog(this) {
            @Override public AlertDialog create() {
                AlertDialog dialog = super.create();
                languageDialogs.add(dialog);
                dialog.setOnDismissListener(d -> languageDialogs.remove(dialog));
                return dialog;
            }
        };
    }
    private void setNodeSubtitle(CharSequence text) {
        mNodeSubtitle = text;
        mToolbar.setSubtitle(text);
    }
    private void refreshLanguage() {
        if (mToolbar == null) return;
        mToolbar.setSubtitle(mNodeSubtitle);
        Menu menu = mToolbar.getMenu();
'''
 for ident,key in [('MENU_SELECT','selectOutput'),('MENU_COPYALL','copyOutput'),('MENU_EXPORT','exportSession'),('MENU_CLEAR','clear'),('MENU_DOCS','docs'),('MENU_ABOUT','about')]:
  value=msg(key) if ident!='MENU_SELECT' else '(mAdapter != null && mAdapter.getTerminalView().isSelectionMode() ? '+msg('exitSelect')+' : '+msg(key)+')'
  hook+=f'        if (menu.findItem({ident}) != null) menu.findItem({ident}).setTitle({value});\n'
 hook+='''        TabLayout tabs = findViewById(R.id.pm_terminalide_tabs);
        CharSequence[] titles = {'''+', '.join(msg(k) for k in ['terminal','scripts','txn','logs'])+'''};
        for (int i=0;i<titles.length;i++) if (tabs.getTabAt(i) != null) tabs.getTabAt(i).setText(titles[i]);
        com.eurobuddha.terminalide.terminal.TerminalLabel.refreshTree(findViewById(android.R.id.content));
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        refreshLanguage();
    }
'''
 s=replace(s,'    private static final int MENU_CLEAR',hook+'\n    private static final int MENU_CLEAR')
 s=replace(s,'        super.onResume();','        super.onResume();\n        refreshLanguage();')
 s=replace(s,'        super.onDestroy();','        for (AlertDialog dialog : new java.util.ArrayList<>(languageDialogs)) dialog.dismiss();\n        languageDialogs.clear();\n        super.onDestroy();')
 p.write_text(s)
 p,s=load('BaseView.java');s=replace(s,'        mMainView = inflater.inflate(zViewResID, null);','        mMainView = inflater.inflate(zViewResID, null);\n        Language.apply(mMainView);');p.write_text(s)
 p,s=load('terminal/TerminalView.java')
 s=replace(s,'new AlertDialog.Builder(mActivity)','((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()')
 s=replace(s,'final String[] actions = {','final CharSequence[] actions = {')
 s=replace(s,'.setMessage(warning)','.setMessage(TerminalMessages.message(warning))')
 s=replace(s,'name.setHint("Name this favorite");','Language.hint(name, '+msg('favoriteName')+');')
 s=replace(s,'mParamHint.setText(res.paramHint + "   (tap for help)");','Language.setText(mParamHint, '+msg('tapHelp','res.paramHint')+');')
 # Existing completion values and descriptions remain literal; only the required marker is app-owned.
 s=replace(s,'OutputFormatter.colored(" required",','OutputFormatter.colored('+msg('required')+'.toString(),')
 s=replace(s,'TextView row = new TextView(mActivity);','TerminalLabel row = new TerminalLabel(mActivity);')
 s=replace(s,'row.setText(rowText(item));','row.bind(() -> rowText(item));')
 start=s.index('        appendEntry(OutputFormatter.colored(',s.index('    private void banner()'))
 end=s.index('\n    }',start)
 s=s[:start]+'''        TerminalLabel banner = (TerminalLabel) appendEntry("");
        banner.bind(() -> OutputFormatter.colored(
                "+------------------------------+\\n"
              + "|   MINIMA  TERMINAL  IDE      |\\n"
              + "+------------------------------+\\n" + '''+msg('banner')+''', OutputFormatter.COL_DIM));'''+s[end:]
 s=replace(s,'final TextView result = appendEntry(OutputFormatter.colored("… running", OutputFormatter.COL_DIM));','final TerminalLabel result = (TerminalLabel) appendEntry("");\n        result.bind(() -> OutputFormatter.colored('+msg('running')+'.toString(), OutputFormatter.COL_DIM));')
 start=s.index('                result.setText(rendered);');end=s.index('                scrollToBottom();',start)
 s=s[:start]+'''                final CharSequence raw = new android.text.SpannedString(rendered);
                result.bind(() -> {
                    android.text.SpannableStringBuilder display = new android.text.SpannableStringBuilder(raw);
                    if (failed) display.append(OutputFormatter.colored("\\n" + '''+msg('commandFailed')+''', OutputFormatter.COL_ERROR));
                    display.append(OutputFormatter.colored("\\n" + '''+msg('milliseconds','ms')+''', OutputFormatter.COL_DIM));
                    return display;
                });
'''+s[end:]
 s=replace(s,'result.setText(OutputFormatter.colored(\n                            "The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.",\n                            OutputFormatter.COL_ERROR));','result.bind(() -> OutputFormatter.colored(Language.msg(l10n_explorer_node_retry).toString(), OutputFormatter.COL_ERROR));')
 # Known local timeouts are app text. Other diagnostic payloads stay exactly as received.
 s=replace(s,'result.setText(OutputFormatter.colored("✖ " + message, OutputFormatter.COL_ERROR));','''if ("The embedded node did not respond. Check its status in Minima Core.".equals(message))
                        result.bind(() -> OutputFormatter.colored("✖ " + Language.msg(l10n_terminal_ide_timeout), OutputFormatter.COL_ERROR));
                    else result.setText(OutputFormatter.colored("✖ " + message, OutputFormatter.COL_ERROR));''')
 s=fixed(s,['noHistory','history','close','favoriteFirst','saveFavorite','save','saved','cancel','noFavorites','favorites','deleteAction','deleteFavorite','deleted','risky','runAnyway','selectionHelp','copyBlock','selectBlock','shareBlock','copySession','selectMode','outputBlock','shareOutput','selectCopy','copyAll'])
 # Only appendEntry creates live output labels; selection/help dialogs keep their immutable snapshots.
 start=s.index('    private TextView appendEntry(');part=s[start:];part=replace(part,'TextView tv = new TextView(mActivity);','TextView tv = new TerminalLabel(mActivity);');s=s[:start]+part
 p.write_text(s)
 p,s=load('terminal/SessionExport.java')
 for old,key,arg in [('"Copy failed: " + e.getMessage()','copyFailed','e.getMessage()'),('"Copied " + plain.length() + " chars"','copiedChars','plain.length()'),('"Share failed: " + e.getMessage()','shareFailed','e.getMessage()')]:s=replace(s,old,msg(key,arg))
 s=fixed(s,['nothingCopy','tooBig','noClipboard','nothingShare','noShareApp'])
 s=replace(s,'String chooserTitle)','CharSequence chooserTitle)');s=s.replace('e.getMessage())','TerminalMessages.error(e))');p.write_text(s)
 shutil.copy2(project/'scripts/pandamonium-sources/terminalide/TerminalLabel.java',folder/'terminal/TerminalLabel.java')
 spec=importlib.util.spec_from_file_location('message_generator',project/'scripts/adapt-pandamonium-wallet-error-languages.py')
 generator=importlib.util.module_from_spec(spec);spec.loader.exec_module(generator)
 source=generator.build_source(json.loads((project/'localization/terminal-messages.json').read_text()),catalog)
 source=source.replace('com.eurobuddha.wallet','com.eurobuddha.terminalide.terminal').replace('WalletMessages','TerminalMessages')
 source=source.replace('Explicit wallet failures only.','Explicit Terminal IDE warnings and local failures only.').replace('localization/wallet-errors.json','localization/terminal-messages.json')
 (folder/'terminal/TerminalMessages.java').write_text(source)

 a='{http://schemas.android.com/apk/res/android}';app='{http://schemas.android.com/apk/res-auto}'
 mappings={'Select mode — long-press output':'selectBar','COPY ALL':'copyAll','EXPORT':'export','DONE':'done','command…':'commandHint','Run':'run'}
 E.register_namespace('android',a[1:-1]);E.register_namespace('app',app[1:-1])
 for name in ['activity_main','view_terminal']:
  p=root/f'terminalide/src/main/res/layout/pm_terminalide_{name}.xml';tree=E.parse(p)
  for v in tree.iter():
   if v.tag.endswith('TabLayout'):v.set(app+'tabMode','scrollable')
   for prop in ['text','hint']:
    value=v.get(a+prop)
    res=resource(mappings[value]) if value in mappings else 'l10n_explorer_node_retry' if value=='The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.' else None
    if res:v.set(a+prop,'@string/'+res);v.set(a+'tag','l10n:'+prop+'='+res)
   ident=v.get(a+'id','')
   for suffix,key in [('hist_up','olderCommand'),('hist_down','newerCommand'),('terminal_tab','accept'),('terminal_fav','favoriteButton')]:
    if ident.endswith(suffix):v.set(a+'contentDescription','@string/'+resource(key));v.set(a+'tag','l10n:description='+resource(key))
  E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)

if __name__=='__main__':
 import sys
 adapt(Path(sys.argv[1]))
