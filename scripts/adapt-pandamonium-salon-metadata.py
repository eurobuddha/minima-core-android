"""Reuse the core/NFT Wallet JSON coercion for Salon's holdings and editions."""
from pathlib import Path


def adapt(root):
    folder = root/'salon/src/main/java/com/eurobuddha/salon'
    def change(text, old, new):
        assert text.count(old) == 1, ('Salon metadata source drift', old)
        return text.replace(old, new)
    donor = (root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet/TokenMeta.java').read_text()
    a = donor.index('    /** Core balance TokenMeta.asObject')
    b = donor.index('    private static String first(', a)
    p = folder/'Util.java'; s = p.read_text()
    s = change(s, '    /** URLs ride the tokencreate', donor[a:b]+'    /** URLs ride the tokencreate')
    p.write_text(s)
    p = folder/'MainActivity.java'; s = p.read_text()
    s = change(s, 'JSONObject tk = e0 == null ? null : e0.optJSONObject("token");',
               'JSONObject tk = e0 == null ? null : Util.tokenObject(e0.opt("token"));')
    s = change(s, 'JSONObject src = tk != null && tk.opt("name") instanceof JSONObject ? tk.optJSONObject("name") : tk;',
               'JSONObject src = tk == null ? null : Util.asObject(tk.opt("name"));\n                if (src == null) src = tk;')
    s = change(s, '                    if (tok instanceof JSONObject) {\n                        JSONObject tm = (JSONObject) tok;\n                        Object nm = tm.opt("name");', '                    JSONObject tm = Util.tokenObject(tok);\n                    if (tm != null) {\n                        Object nm = tm.opt("name");\n                        JSONObject parsedName = Util.asObject(nm);\n                        if (parsedName != null) nm = parsedName;')
    p.write_text(s)
