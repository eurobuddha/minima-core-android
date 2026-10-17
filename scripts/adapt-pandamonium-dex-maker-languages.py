"""Retain MakerTab's inputs/watchers and bind only display labels, hints and status text."""
from pathlib import Path
import re

def adapt(root):
 p=root/'pandadex/src/main/java/com/eurobuddha/pandadex/MakerTab.java';s=p.read_text()
 def change(old,new,count=1):
  nonlocal s
  assert s.count(old)==count,('PandaDEX maker source drift',old,s.count(old));s=s.replace(old,new)
 change('package com.eurobuddha.pandadex;','package com.eurobuddha.pandadex;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 change('private TextView tv(String s,','private TextView tv(CharSequence s,')
 change('        t.setText(s);','        Language.setText(t, DexMessages.message(s));')
 change('        e.setHint(hint);','''        Language.hint(e, DexMessages.message(hint));
        Language.description(e, DexMessages.message(hint));
        e.setMinHeight(dp(48));''')
 change('        clr.setGravity(Gravity.CENTER);','''        clr.setGravity(Gravity.CENTER);
        clr.setMinWidth(dp(48)); clr.setMinHeight(dp(48));
        Language.description(clr, Language.msg(l10n_atomix_market_clear, label));
        Language.description(price, Language.msg(l10n_dex_maker_price_description, label));
        Language.description(amount, Language.msg(l10n_dex_maker_amount_description, label));''')
 for name in ['prepBtn','armBtn','applyBtn']:
  change('        '+name+'.setGravity(Gravity.CENTER);','        '+name+'.setMinHeight(dp(48));\n        '+name+'.setGravity(Gravity.CENTER);')
 change('        pegSw.setTextColor','        pegSw.setMinHeight(dp(48));\n        pegSw.setTextColor')
 # Long safety states get their own full-width row, as in the retained TradeView header.
 change('        top.setGravity(Gravity.CENTER_VERTICAL);','        top.setOrientation(VERTICAL);\n        top.setGravity(Gravity.CENTER_VERTICAL);')
 change('''        top.addView(tv("MARKET MAKER", 13f, Design.TEXT(), Design.sansBold()),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));''','''        top.addView(tv("MARKET MAKER", 13f, Design.TEXT(), Design.sansBold()),
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));''')
 # Preserve engine results, IDs and tone; translate each entire app-generated status.
 start=s.index('        StringBuilder sb = new StringBuilder();',s.index('    private void updateStatus()'))
 end=s.index('    /** Reuse the last lookup',start)
 s=s[:start]+'''        Language.setText(slotsTv, statusLines(lines));
    }

    static CharSequence statusLines(java.util.List<MakerStatus.Line> lines) {
        Language.TextBuilder sb = new Language.TextBuilder();
        boolean first = true;
        for (MakerStatus.Line l : lines) {
            if (!first) sb.append('\\n');
            first = false;
            sb.append(l.tone == MakerStatus.OK ? "✓ " : l.tone == MakerStatus.FAIL ? "✗ " : "⏳ ");
            if (!l.slotId.isEmpty()) sb.append(l.slotId).append("  ");
            sb.append(DexMessages.message(l.text));
        }
        return sb.build();
    }

'''+s[end:]
 # No EditText values are rebound. Their setText calls and watchers stay untouched.
 for name in ['pegSw','pegPxTv','previewTv','slotsTv','stageTv','midTv','feedTv','stateTv','armBtn']:
  pattern=r'\b'+name+r'\.setText\((.*?)\);'
  s,count=re.subn(pattern,lambda m:'Language.setText('+name+', DexMessages.message('+m[1]+'));',s,flags=re.S)
  assert count,(name,'No display bindings')
 p.write_text(s)
