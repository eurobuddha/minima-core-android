"""Protect market editor drafts using existing decimal rules and post-show validation."""
from pathlib import Path
import re

def adapt(root):
 project=Path(__file__).resolve().parents[1];folder=root/'atomix/src/main/java/com/eurobuddha/atomix'
 (folder/'MarketDraft.java').write_text((project/'scripts/pandamonium-sources/atomix/MarketDraft.java').read_text())
 p=folder/'MainActivity.java';s=p.read_text()
 assert 'new BigDecimal(v, new java.math.MathContext(10)).stripTrailingZeros().toPlainString()' in s, 'MarketDraft must match trimSig precision'
 def edit(name,transform):
  nonlocal s
  m=re.search(r'^    private [^\n=;]+\b'+name+r'\([^\n]*',s,re.M);assert m,name
  start=m.start();brace=s.index('{',m.start())
  token=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]')
  depth=0;end=None
  for t in token.finditer(s,brace):
   if t[0]=='{':depth+=1
   elif t[0]=='}':
    depth-=1
    if depth==0:end=t.end();break
  assert end,name
  part=s[start:end];updated=transform(part);s=s[:start]+updated+s[end:]

 def rep(part,old,new):
  assert old in part,('OTC method drift',old);return part.replace(old,new)


 def editor(part):
  part=rep(part,'        box.addView(pv);', '''        box.addView(pv);
        final boolean[] revealError = {false};
        // As in Future Cash tabs, reveal only after the translated view has its final layout.
        pv.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (revealError[0]) view.post(() -> {
                if (revealError[0] && view.isAttachedToWindow())
                    view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), true);
            });
        });''')
  part=rep(part,'        final Runnable upd = () -> updateLadderPreview(pv, askRows, bidRows);',
   '        final EditText[] parameters = {biasE, repE, midE, stepE, levelsE, askSizeE, bidSizeE, minE};\n'
   '        final Runnable upd = () -> {\n'
   '            revealError[0] = false;\n'
   '            if (!marketParametersValid(parameters, pegSw.isChecked())) { invalidMarketDraft(pv); return; }\n'
   '            updateLadderPreview(pv, askRows, bidRows);\n'
   '        };')
  part=rep(part,'        TextWatcher w = onChange(upd);','        TextWatcher w = onChange(upd);\n        repE.addTextChangedListener(w); minE.addTextChangedListener(w);')
  for line in ['        final Runnable fillFromPeg = () -> {','        final Runnable genManual = () -> {']:
   part=rep(part,line,line+'\n            if (!marketParametersValid(parameters, pegSw.isChecked())) { invalidMarketDraft(pv); return; }')
  # Oracle-derived bias may overflow even when each entered field is valid. Validate before mid/row writes.
  part=rep(part,'            double quoted = m * (1 + bias / 100.0);',
   '            double quoted = m * (1 + bias / 100.0);\n'
   '            if (!MarketDraft.generation(quoted, parseD(stepE.getText().toString(), 0), parseD(askSizeE.getText().toString(), 0), parseD(bidSizeE.getText().toString(), 0), Math.max(1, Math.min(Order.MAX_LEVELS, (int) parseD(levelsE.getText().toString(), 1))))) { invalidMarketDraft(pv); return; }')
  part=rep(part,'            if (filling[0]) return;',
   '            if (filling[0]) return;\n'
   '            revealError[0] = false;\n'
   '            if (!marketParametersValid(parameters, pegSw.isChecked())) { invalidMarketDraft(pv); return; }\n'
   '            upd.run();')
  # Reuse sendDialog's post-show listener, keeping invalid values and selections in the same dialog.
  start=part.index('                .setPositiveButton(Language.msg(l10n_common_save), (d, w2) -> {')
  end=part.index('                })\n                .setNegativeButton',start)
  body=part[start+len('                .setPositiveButton(Language.msg(l10n_common_save), (d, w2) -> {'):end]
  body='\n'.join(line[8:] if line.startswith('        ') else line for line in body.splitlines())
  part=part[:start]+'                .setPositiveButton(Language.msg(l10n_common_save), null)\n'+part[end+len('                })\n'):]
  part=rep(part,'        modalOpen = true;\n        dialog()', '        modalOpen = true;\n        final AlertDialog editor = dialog()')
  end=part.rindex('                .show();')+len('                .show();')
  listener='\n        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {\n'
  listener+='            if (!marketParametersValid(parameters, pegSw.isChecked()) || !marketRowsValid(askRows, bidRows)) { revealError[0] = true; invalidMarketDraft(pv); pv.post(() -> pv.requestRectangleOnScreen(new android.graphics.Rect(0, 0, pv.getWidth(), pv.getHeight()), true)); return; }\n'
  listener+=body+'\n            editor.dismiss();\n        });'
  part=part[:end]+listener+part[end:]
  return part
 edit('editOrderDialog',editor)
 def preview(part):
  return rep(part,'        int nA = 0, nB = 0;', '        if (!marketRowsValid(askRows, bidRows)) { invalidMarketDraft(pv); return; }\n        int nA = 0, nB = 0;')
 edit('updateLadderPreview',preview)
 helper=r"""    private boolean marketParametersValid(EditText[] fields, boolean pegged) {
        String[] raw = new String[fields.length];
        for (int i = 0; i < fields.length; i++) raw[i] = fields[i].getText().toString();
        if (!MarketDraft.parameters(raw)) return false;
        if (!pegged) return true;
        double bias = Math.max(-20, Math.min(20, MarketDraft.number(raw[0], true, 0)));
        double quoted = PriceOracle.mid() * (1 + bias / 100.0);
        int levels = Math.max(1, Math.min(Order.MAX_LEVELS, (int) MarketDraft.number(raw[4], false, 1)));
        return MarketDraft.generation(quoted, MarketDraft.number(raw[3], false, 0),
                MarketDraft.number(raw[5], false, 0), MarketDraft.number(raw[6], false, 0), levels);
    }

    private boolean marketRowsValid(EditText[][]... sides) {
        for (EditText[][] side : sides) {
            String[][] raw = new String[side.length][2];
            for (int i = 0; i < side.length; i++) {
                raw[i][0] = side[i][0].getText().toString();
                raw[i][1] = side[i][1].getText().toString();
            }
            if (!MarketDraft.rows(raw)) return false;
        }
        return true;
    }

    private void invalidMarketDraft(TextView preview) {
        Language.setText(preview, Language.msg(l10n_atomix_market_invalid_number));
        preview.setTextColor(Design.RED());
    }

"""
 anchor='    /** Live ladder summary in the editor:';assert anchor in s;s=s.replace(anchor,helper+anchor)
 p.write_text(s)
