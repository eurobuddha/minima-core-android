"""Translate only bundled help at display boundaries; keep ParamDocs/Suggest canonical."""
from pathlib import Path
import importlib.util,json

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 spec=json.loads((project/'localization/terminal-help.json').read_text())
 catalog=json.loads((project/'localization/catalog.json').read_text())
 helpdata=json.loads((root/'terminalide/src/main/assets/help.json').read_text())
 def strip(text):
  b=text.strip();i=b.find(' - ')
  if i<0:return b[2:].strip() if b.startswith('- ') else b
  head=b[:i].strip()
  return b[i+3:].strip() if not head or head[0] in '[(' and head[-1] in '])' else b
 assert set(spec['briefs'])==set(helpdata),'Review summaries when bundled commands change'
 for name,key in spec['briefs'].items():assert catalog[key]['en']==strip(helpdata[name]['help']),name
 for name,key in spec['pages'].items():assert spec.get('sourceOverrides',{}).get(key,catalog[key]['en'])==helpdata[name]['fullhelp'].strip(),name
 for raw,key in spec['fixed'].items():assert raw==spec.get('sourceOverrides',{}).get(key,catalog[key]['en']),key
 folder=root/'terminalide/src/main/java/com/eurobuddha/terminalide/terminal'
 loader=importlib.util.spec_from_file_location('help_message_generator',project/'scripts/adapt-pandamonium-wallet-error-languages.py')
 generator=importlib.util.module_from_spec(loader);loader.loader.exec_module(generator)
 source=generator.build_source(spec,catalog)
 source=source.replace('com.eurobuddha.wallet','com.eurobuddha.terminalide.terminal').replace('WalletMessages','HelpText')
 start=source.index('    public static CharSequence error(');end=source.index('    public static CharSequence message(',start)
 source=source[:start]+'''    public static CharSequence full(android.content.Context context, String command) {
        String raw = HelpStore.full(context, command);
        return raw == null ? null : message(raw);
    }
    public static CharSequence brief(android.content.Context context, String command) {
        String raw = HelpStore.brief(context, command);
        if (raw == null) return null;
        String description = ParamDocs.stripParams(raw);
        int at = raw.lastIndexOf(description);
        if (description.isEmpty() || at < 0) return raw;
        return Language.join(raw.substring(0, at), message(description), raw.substring(at + description.length()));
    }
'''+source[end:]
 start=source.index('/**');end=source.index('public final class',start)
 source=source[:start]+'''/** Display-only lookup for bundled command help. Unknown text remains literal.
 * Reuses the existing fixed message generator; never use for node error bodies or command input.
 * Generated from localization/terminal-help.json. */
'''+source[end:]
 (folder/'HelpText.java').write_text(source)
 def change(s,old,new):
  assert old in s,('Terminal help source drift',old)
  return s.replace(old,new)
 p=folder/'TerminalView.java';s=p.read_text()
 s=change(s,'"  " + item.desc','"  " + HelpText.message(item.desc)')
 s=change(s,'String full = HelpStore.full(mActivity, command);','CharSequence full = HelpText.full(mActivity, command);')
 s=change(s,'tv.setText(full);','Language.setText(tv, full);')
 # Preserve the complete callback payload before any caller can mutate it. Only a
 # recognized help result is rerendered; normal JSON output stays an immutable span.
 s=change(s,'CharSequence rendered = renderHelp(zCommand, json);','CharSequence rendered = renderHelp(zCommand, json);\n                final String helpSnapshot = rendered == null ? null : json.toString();')
 s=change(s,'new android.text.SpannableStringBuilder(raw);','new android.text.SpannableStringBuilder(\n                            helpSnapshot == null ? raw : refreshHelp(zCommand, helpSnapshot, raw));')
 needle='    private CharSequence renderHelp(String command, JSONObject json) {'
 s=change(s,needle,'''    private CharSequence refreshHelp(String command, String snapshot, CharSequence fallback) {
        try {
            CharSequence current = renderHelp(command, new JSONObject(snapshot));
            return current == null ? fallback : current;
        } catch (JSONException e) { return fallback; }
    }

'''+needle)
 s=change(s,'if (!command.trim().startsWith("help")) return null;', 'if (!command.trim().split("\\\\s+", 2)[0].equals("help") || command.contains(";")) return null;')
 s=change(s,'        JSONObject resp = json.optJSONObject("response");', '        if (!json.optBoolean("status", true)) return null;\n        JSONObject resp = json.optJSONObject("response");')
 s=change(s,'String fullhelp = resp.optString("fullhelp", "");','CharSequence fullhelp = resp.optString("fullhelp", "");')
 s=change(s,'String bundled = HelpStore.full(mActivity, name);','CharSequence bundled = HelpText.full(mActivity, name);')
 s=change(s,'String brief = HelpStore.brief(mActivity, name);','CharSequence brief = HelpText.brief(mActivity, name);')
 s=change(s,'if (!fullhelp.isEmpty()) {','if (fullhelp.length() != 0) {')
 s=change(s,'fullhelp.replace("\\\\n", "\\n")','fullhelp.toString().replace("\\\\n", "\\n")')
 s=change(s,'String line = HelpStore.brief(mActivity, n);','CharSequence line = HelpText.brief(mActivity, n);')
 p.write_text(s)
