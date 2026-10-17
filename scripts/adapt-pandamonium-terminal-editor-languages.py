"""Translate script library/editor display using existing bindings and diagnostic mapper.

The script engine, templates, storage and command strings remain canonical.
"""
from pathlib import Path
import json,re,importlib.util,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 labels=json.loads((project/'localization/terminal-editor-labels.json').read_text())
 catalog=json.loads((project/'localization/catalog.json').read_text())
 def res(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+res(key)+('' if not args else ', '+', '.join(args))+')'
 def quote(s):return json.dumps(s,ensure_ascii=False)
 def change(s,old,new):
  assert old in s,('Editor localization source drift',old)
  return s.replace(old,new)
 def fixed(s,keys):
  for key in keys:s=change(s,quote(catalog[labels[key]]['en']),msg(key))
  return s
 folder=root/'terminalide/src/main/java/com/eurobuddha/terminalide/ide'
 imports='\nimport com.eurobuddha.minimacore.i18n.Language;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n'
 def load(name):
  p=folder/(name+'.java');s=p.read_text();pos=s.index(';')+1
  return p,s[:pos]+imports+s[pos:]
 p,s=load('ScriptsView')
 s=change(s,'List<String> labels = new ArrayList<>();','List<CharSequence> labels = new ArrayList<>();')
 s=change(s,'String when = DateUtils.getRelativeTimeSpanString(s.modified).toString();','CharSequence when = Language.since(s.modified);')
 s=change(s,'labels.add(s.name + "\\n" + s.source.trim().length() + " chars · " + when + deployed);','labels.add(Language.join(s.name, "\\n", '+msg('chars','s.source.trim().length()')+', " · ", when, deployed));')
 s=change(s,'mList.setAdapter(new ArrayAdapter<>(mActivity, android.R.layout.simple_list_item_1, labels));','''mList.setAdapter(new ArrayAdapter<CharSequence>(mActivity, android.R.layout.simple_list_item_1, labels) {
            @Override public android.view.View getView(int position, android.view.View recycled, android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, recycled, parent);
                Language.setText(view.findViewById(android.R.id.text1), getItem(position));
                return view;
            }
        });''')
 s=change(s,'new AlertDialog.Builder(mActivity)','((com.eurobuddha.terminalide.MainActivity)mActivity).languageDialog()')
 names=', '.join(msg(k) for k in ['blank','signature','timelock','hashlock','multisig','random','covenant'])
 s=change(s,'Templates.names()', 'new CharSequence[]{'+names+'}')
 s=change(s,'name.setHint("Script name");','Language.hint(name, '+msg('name')+');')
 s=change(s,'new String[]{"Open", "Duplicate", "Delete"}','new CharSequence[]{"Open", "Duplicate", "Delete"}')
 s=change(s,'"Delete \'" + s.name + "\'?"',msg('deleteTitle','s.name'))
 s=change(s,'"This only deletes the local copy. A deployed script "\n                                        + "stays on the node (use removescript to untrack it)."',msg('deleteBody'))
 s=change(s,'String[] labels = new String[n];','CharSequence[] labels = new CharSequence[n];')
 s=change(s,'labels[i] = (def ? "[wallet] " : "") + shorten(addresses[i]) + "\\n" + snippet;','labels[i] = Language.join(def ? '+msg('wallet')+' : "", shorten(addresses[i]), "\\n", snippet);')
 s=change(s,'"Scripts on node (" + n + ")"',msg('nodeScripts','n'))
 s=change(s,'"Node error: " + message',msg('nodeError','com.eurobuddha.terminalide.terminal.TerminalMessages.message(message)'))
 s=fixed(s,['newFrom','nameTitle','create','cancel','open','duplicate','delete','deleted','close','loading','noNodeScripts','unreadable','import','copyAddress','copied'])
 p.write_text(s)
 p,s=load('ScriptEditorActivity')
 s=change(s,'        View editorRoot = findViewById(R.id.pm_terminalide_editor_root);','        View editorRoot = findViewById(R.id.pm_terminalide_editor_root);\n        Language.apply(editorRoot);')
 s=change(s,'tb.setTitle("Script Editor");','Language.toolbarTitle(tb, '+msg('title')+');')
 s=change(s,'        tb.getMenu().add(0, 1, 0, "KISS VM reference");\n        tb.getMenu().add(0, 2, 1, "Minima docs");','')
 s=change(s,'((Button) findViewById(R.id.pm_terminalide_editor_toggle_config)).setText("▼ Test inputs");','Language.setText((Button) findViewById(R.id.pm_terminalide_editor_toggle_config), '+msg('inputsOpen')+');')
 s=change(s,'toggle.setText(show ? "▼ Test inputs" : "▶ Test inputs");','Language.setText(toggle, show ? '+msg('inputsOpen')+' : '+msg('inputsClosed')+');')
 s=change(s,'mResults.setText(OutputFormatter.colored("⛓ Deployed at: " + script.address,\n                    OutputFormatter.COL_STRING));','setResults('+msg('deployedAt','script.address')+', OutputFormatter.COL_STRING);')
 # Results have a single color in this editor; retain native selectable TextView and use plain bindings.
 s=change(s,'private void setResults(String text, int color)', 'private void setResults(CharSequence text, int color)')
 s=change(s,'mResults.setText(OutputFormatter.colored(text, color));','Language.setText(mResults, text);\n        mResults.setTextColor(color);')
 s=change(s,'String ref;', 'CharSequence ref;')
 s=change(s,'tv.setText(ref);','Language.setText(tv, ref);')
 s=change(s,'new AlertDialog.Builder(this)','languageDialog()')
 s=change(s,'String sizecol = len > KissVm.MAX_SAFE_CHARS ? "OVER LIMIT" : (len + "/" + KissVm.MAX_SAFE_CHARS);\n        String line = sizecol + " chars";\n        if (errors > 0) line += " · ✖ " + errors;\n        if (warns > 0) line += " · ⚠ " + warns;\n        if (errors == 0 && warns == 0) line += " · ✓ no lint issues";\n        mStatusLine.setText(line);','''CharSequence size = len > KissVm.MAX_SAFE_CHARS ? '''+msg('overLimit')+''' : (len + "/" + KissVm.MAX_SAFE_CHARS);
        Language.TextBuilder line = new Language.TextBuilder().append('''+msg('chars','size')+''');
        if (errors > 0) line.append(" · ✖ ").append(errors);
        if (warns > 0) line.append(" · ⚠ ").append(warns);
        if (errors == 0 && warns == 0) line.append(" · ✓ ").append('''+msg('noIssues')+''');
        Language.setText(mStatusLine, line.build());''')
 s=change(s,'StringBuilder sb = new StringBuilder();\n        for (LintEngine.Lint l : mLints) sb.append(l.toString()).append("\\n\\n");','''Language.TextBuilder sb = new Language.TextBuilder();
        for (int i=0;i<mLints.size();i++) {
            LintEngine.Lint lint = mLints.get(i);
            if (i>0) sb.append("\\n\\n");
            sb.append(lint.error ? "✖ " : "⚠ ").append(EditorMessages.message(lint.message));
        }''')
 s=change(s,'.setMessage(sb.toString().trim())','.setMessage(sb.build())')
 s=change(s,'"Double quotes are not supported in scripts sent over the "\n                    + "command line — use [ ] string blocks"',msg('quotes'))
 s=change(s,'"✖ " + e.getMessage()','Language.join("✖ ", EditorMessages.error(e))')
 # Only app-owned fallback text is translated; arbitrary server errors stay literal.
 for cmd,key in [('runscript','runFailed'),('newscript','newscriptFailed')]:
  s=change(s,'"✖ " + json.optString("error", "'+cmd+' failed")','Language.join("✖ ", json.has("error") ? json.optString("error") : '+msg(key)+')')
 s=change(s,'StringBuilder sb = new StringBuilder();\n        sb.append(parseok', 'Language.TextBuilder sb = new Language.TextBuilder();\n        sb.append(parseok')
 s=change(s,'sb.append("\\nAddress: ").append(address);','sb.append("\\n").append('+msg('address','address')+');')
 s=change(s,'sb.append("\\nClean (").append(cleanScript.length()).append(" chars): ").append(cleanScript);','sb.append("\\n").append('+msg('clean','cleanScript.length()','cleanScript')+');')
 s=change(s,'sb.append("\\n\\nResult: ").append(success ? "✓ RETURN TRUE (spendable)" : "✖ RETURN FALSE");','sb.append("\\n\\n").append('+msg('result','success ? '+msg('spendable')+' : "✖ RETURN FALSE"')+');')
 s=change(s,'sb.append("\\nMonotonic: ").append(monotonic);','sb.append("\\n").append('+msg('monotonic','monotonic')+');')
 s=change(s,'sb.append("\\n\\nVariables:\\n").append(vars);','sb.append("\\n\\n").append('+msg('variables')+').append("\\n").append(vars.toString());')
 s=change(s,'sb.append("\\n\\nTrace:\\n").append(trace.toString().replace("\\\\n", "\\n"));','sb.append("\\n\\n").append('+msg('trace')+').append("\\n").append(trace.toString().replace("\\\\n", "\\n"));')
 s=change(s,'setResults(sb.toString(),','setResults(sb.build(),')
 start=s.index('"newscript registers this script');end=s.index('\n                .setView(v)',start)
 s=s[:start]+msg('deployBody')+')'+s[end:]
 s=change(s,'        CheckBox trackall =', '        Language.apply(v);\n        CheckBox trackall =')
 s=change(s,'"✓ Deployed — node now tracks coins at:\\n" + address\n                        + "\\n\\nSend funds to it with:\\nsend address:" + address + " amount:..."',msg('deployed','address','address'))
 s=change(s,'"The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry."','Language.msg(l10n_explorer_node_retry)')
 s=change(s,'"✖ " + message','Language.join("✖ ", com.eurobuddha.terminalide.terminal.TerminalMessages.message(message))')
 s=fixed(s,['reference','notFound','referenceUnavailable','noIssues','lintResults','ok','close','empty','checking','running','emptyResponse','parseOk','parseFailed','deployTitle','deploy','cancel','deploying'])
 # The existing menu survives locale changes just like the console. Dialogs share its ownership rule.
 hook='''    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 1, 0, '''+msg('reference')+''');
        menu.add(0, 2, 1, '''+msg('docs')+''');
        return true;
    }
    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    private LanguageDialog languageDialog() {
        return new LanguageDialog(this) {
            @Override public AlertDialog create() {
                AlertDialog dialog = super.create();
                languageDialogs.add(dialog);
                dialog.setOnDismissListener(d -> languageDialogs.remove(dialog));
                return dialog;
            }
        };
    }
    private void refreshLanguage() {
        Toolbar toolbar = findViewById(R.id.pm_terminalide_editor_toolbar);
        if (toolbar == null) return;
        if (toolbar.getMenu().findItem(1) != null) toolbar.getMenu().findItem(1).setTitle('''+msg('reference')+''');
        if (toolbar.getMenu().findItem(2) != null) toolbar.getMenu().findItem(2).setTitle('''+msg('docs')+''');
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        refreshLanguage();
    }
    @Override protected void onResume() { super.onResume(); refreshLanguage(); }
'''
 s=change(s,'    ScriptDB mDB;',hook+'\n    ScriptDB mDB;')
 s=change(s,'        super.onDestroy();','        for (AlertDialog dialog : new java.util.ArrayList<>(languageDialogs)) dialog.dismiss();\n        languageDialogs.clear();\n        super.onDestroy();')
 # JSON hints are literal protocol examples passed as values to translated explanatory text.
 for key,example in [('state','{"0":"0xFF..","6":"1"}'),('prevstate','{"0":"0xFF..","6":"0"}'),('globals','{"@COINAGE":"64","@AMOUNT":"10"}'),('signatures','["0xFF.."]')]:
  field={'state':'mState','prevstate':'mPrevState','globals':'mGlobals','signatures':'mSignatures'}[key]
  needle=field+' = findViewById(R.id.pm_terminalide_editor_'+key+');'
  s=change(s,needle,needle+'\n        Language.hint('+field+', '+msg(key,quote(example))+');')
 p.write_text(s)
 spec=importlib.util.spec_from_file_location('editor_diagnostic_generator',project/'scripts/adapt-pandamonium-wallet-error-languages.py')
 generator=importlib.util.module_from_spec(spec);spec.loader.exec_module(generator)
 source=generator.build_source(json.loads((project/'localization/terminal-editor-messages.json').read_text()),catalog)
 source=source.replace('com.eurobuddha.wallet','com.eurobuddha.terminalide.ide').replace('WalletMessages','EditorMessages').replace('Explicit wallet failures only.','Explicit editor lint and validation failures only.').replace('localization/wallet-errors.json','localization/terminal-editor-messages.json')
 (folder/'EditorMessages.java').write_text(source)
 a='{http://schemas.android.com/apk/res/android}';app='{http://schemas.android.com/apk/res-auto}'
 E.register_namespace('android',a[1:-1]);E.register_namespace('app',app[1:-1])
 mapping={'script name':'name','/* KISS VM script */\\nRETURN TRUE':'sourceHint','▶ Test inputs':'inputsClosed','Check':'check','Run':'run','Deploy':'deploy','New script':'new','On node':'onNode',catalog[labels['initialHelp']]['en']:'initialHelp',catalog[labels['emptyLibrary']]['en'].replace('\n','\\n'):'emptyLibrary'}
 for name in ['activity_script_editor','view_scripts','dialog_deploy']:
  p=root/f'terminalide/src/main/res/layout/pm_terminalide_{name}.xml';tree=E.parse(p)
  for view in tree.iter():
   for prop in ['text','hint']:
    value=view.get(a+prop);key=mapping.get(value)
    if name=='dialog_deploy' and prop=='text' and value:key='trackall'
    if key:view.set(a+prop,'@string/'+res(key));view.set(a+'tag','l10n:'+prop+'='+res(key))
   # Dynamic status is supplied immediately from the current script length.
   if view.get(a+'id','').endswith('editor_status'):view.attrib.pop(a+'text',None)
  E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
