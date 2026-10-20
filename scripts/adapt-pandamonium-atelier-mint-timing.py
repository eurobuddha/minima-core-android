"""Reuse Atelier's live form notes and translated message binding for mint duration estimates."""
from pathlib import Path

def adapt(root):
    p=root/'atelier/src/main/java/com/eurobuddha/statenft/MainActivity.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert s.count(old)==1,('Atelier timing drift',old,s.count(old));s=s.replace(old,new)
    change('Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_collection_help),',
           'Language.join(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_collection_help), "\\n\\n", Language.msg(l10n_atelier_collection_mint_time_hint)),')
    change('        size.addTextChangedListener(watch(sv -> createSize = sv));','''        TextView timeBySize=Design.note(this,collectionMintTime(createSize));
        TextView timeBeforeMint=Design.note(this,collectionMintTime(createSize));
        body.addView(timeBySize,lpm(0,4,0,12));
        size.addTextChangedListener(watch(sv -> {
            createSize=sv;
            Language.setText(timeBySize,collectionMintTime(sv));
            Language.setText(timeBeforeMint,collectionMintTime(sv));
        }));''')
    change('        TextView go = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_mint), true);',
           '        body.addView(timeBeforeMint,lpm(0,12,0,6));\n        TextView go = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_collection_mint), true);')
    change('    private int clampSize(int n)','''    private CharSequence collectionMintTime(String raw) {
        int count=parseIntSafe(raw);
        CharSequence hint=Language.msg(l10n_atelier_collection_mint_time_hint);
        if(count<2 || count>20) return hint;
        return Language.join(Language.msg(l10n_atelier_collection_mint_time_total,count,count*3,count*5), "\\n", hint);
    }

    private int clampSize(int n)''')
    p.write_text(s)
