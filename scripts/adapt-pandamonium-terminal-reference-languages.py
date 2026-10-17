"""Translate bundled reference and newly created template prose, preserving executable code.

Saved names/comments are user content: language changes never rewrite them.
"""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'terminalide/src/main/java/com/eurobuddha/terminalide/ide'
 def change(text,old,new):
  assert old in text,('Terminal reference source drift',old)
  return text.replace(old,new)
 # The pinned reference remains the English source of truth. Its translated body
 # uses the editor's existing selectable TextView and weak language binding.
 assert (root/'terminalide/src/main/assets/kissvm.txt').read_text()==catalog['terminalReference.body']['en']
 p=folder/'ScriptEditorActivity.java';s=p.read_text()
 start=s.index('        CharSequence ref;\n',s.index('private void showKissReference()'))
 end=s.index('        TextView tv =',start)
 s=s[:start]+'        CharSequence ref = Language.msg(l10n_terminal_reference_body);\n'+s[end:]
 s=change(s,'if (name.isEmpty()) name = "untitled";', 'if (name.isEmpty()) name = Language.msg(l10n_terminal_template_untitled).toString();')
 p.write_text(s)
 # Derive only the leading prose comment from each unchanged donor template.
 # Import fails if a donor comment changes; translations must be reviewed again.
 source=(folder/'Templates.java').read_text();rows=[]
 for name,expr in re.findall(r'\{("(?:[^"\\]|\\.)*"),\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\}',source):
  rows.append((json.loads(name),''.join(json.loads(v) for v in re.findall(r'"(?:[^"\\]|\\.)*"',expr))))
 assert len(rows)==7
 for i,(_,body) in enumerate(rows[1:],1):
  assert body.startswith('/*') and body[2:body.index('*/')]==catalog['terminalTemplate.comment'+str(i)]['en']
  for text in catalog['terminalTemplate.comment'+str(i)].values():
   assert '/*' not in text and '*/' not in text, 'A translated comment must not change script syntax'
 (folder/'TemplateText.java').write_text('''package com.eurobuddha.terminalide.ide;

import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Localized prose is captured when a new editable script is created. */
public final class TemplateText {
    private TemplateText() {}
    private static final int[] NAMES = {
        l10n_terminal_template_untitled, l10n_terminal_editor_signature,
        l10n_terminal_editor_timelock, l10n_terminal_editor_hashlock,
        l10n_terminal_editor_multisig, l10n_terminal_editor_random,
        l10n_terminal_editor_covenant
    };
    private static final int[] COMMENTS = {
        0, l10n_terminal_template_comment1, l10n_terminal_template_comment2,
        l10n_terminal_template_comment3, l10n_terminal_template_comment4,
        l10n_terminal_template_comment5, l10n_terminal_template_comment6
    };
    public static String name(int index) { return Language.msg(NAMES[index]).toString(); }
    public static String source(int index) {
        String original = Templates.ALL[index][1];
        if (index == 0) return original;
        return "/*" + Language.msg(COMMENTS[index]) + "*/" + original.substring(original.indexOf("*/") + 2);
    }
}
''')
 p=folder/'ScriptsView.java';s=p.read_text()
 s=change(s,'if (n.isEmpty()) n = Templates.ALL[templateIdx][0].equals("Blank")\n                            ? "untitled" : Templates.ALL[templateIdx][0];','if (n.isEmpty()) n = TemplateText.name(templateIdx);')
 s=change(s,'long id = mDB.insert(n, Templates.ALL[templateIdx][1]);','long id = mDB.insert(n, TemplateText.source(templateIdx));')
 s=change(s,'mDB.insert(s.name + " copy", s.source);','mDB.insert(Language.msg(l10n_terminal_template_copy, s.name).toString(), s.source);')
 s=change(s,'mDB.insert("from node " + shorten(address), script);','mDB.insert(Language.msg(l10n_terminal_template_from_node, shorten(address)).toString(), script);')
 p.write_text(s)
