"""Reuse core balance TokenMeta's JSON coercion in UTXO and NFT display paths."""
from pathlib import Path


def adapt(root):
    def change(text, old, new):
        assert text.count(old) == 1, ('token metadata source drift', old)
        return text.replace(old, new)

    for app, package in [('utxo', 'utxo'), ('NFTwallet', 'nftwallet')]:
        folder = root / app / 'src/main/java/com/eurobuddha' / package
        p = folder / 'TokenMeta.java'
        s = p.read_text()
        s = change(s, '        TokenMeta m = new TokenMeta();', '''        JSONObject object = tokenObject(token);
        if (object != null) token = object;
        TokenMeta m = new TokenMeta();''')
        s = change(s, '            Object nameNode = t.opt("name");', '''            Object nameNode = t.opt("name");
            JSONObject nameObject = asObject(nameNode);
            if (nameObject != null) nameNode = nameObject;''')
        s = change(s, '    private static String first(String... vals) {', '''    /** Core balance TokenMeta.asObject, adapted from Minima JSON to org.json. */
    static JSONObject asObject(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return (JSONObject) value;
        String text = value.toString().trim();
        if (text.startsWith("{")) {
            try { return new JSONObject(text); }
            catch (org.json.JSONException ignored) { }
        }
        return null;
    }

    /** Balance metadata or the existing tokens-command envelope used by StateNft. */
    static JSONObject tokenObject(Object value) {
        JSONObject root = asObject(value);
        JSONObject nested = root == null ? null : asObject(root.opt("token"));
        return nested == null ? root : nested;
    }

    private static String first(String... vals) {''')
        p.write_text(s)

        p = folder / 'Util.java'
        s = p.read_text()
        for result, method in [('String', 'tokenName'), ('boolean', 'tokenNameProvided')]:
            marker = '    public static ' + result + ' ' + method + '(Object token, String tokenid) {\n'
            s = change(s, marker, marker + '''        JSONObject object = TokenMeta.tokenObject(token);
        if (object != null) token = object;
''')
        # Both name readers must decode nested metadata just as TokenMeta does.
        for expression in ['t.opt("name")', '((JSONObject)token).opt("name")']:
            old = '            Object name = ' + expression + ';'
            s = change(s, old, old + '''
            JSONObject nameObject = TokenMeta.asObject(name);
            if (nameObject != null) name = nameObject;''')
        p.write_text(s)

        p = folder / 'WalletView.java'
        s = p.read_text()
        s = change(s, '        tok.setTextColor(Design.dim());', '''        tok.setTextColor(Design.dim());
        // Match BalancesView's compact names, even for malformed or oversized metadata.
        tok.setMaxLines(1);
        tok.setEllipsize(android.text.TextUtils.TruncateAt.END);''')
        p.write_text(s)

    p = root / 'NFTwallet/src/main/java/com/eurobuddha/nftwallet/StateNft.java'
    s = p.read_text()
    s = change(s, 'JSONObject root = tokenNode instanceof JSONObject ? (JSONObject) tokenNode : null;',
               'JSONObject root = TokenMeta.asObject(tokenNode);')
    s = change(s, '''        if (root != null && root.opt("token") instanceof JSONObject) {
            envelope = root;
            root = root.optJSONObject("token");
        }
        JSONObject meta = null;
        if (root != null && root.opt("name") instanceof JSONObject) {
            meta = root.optJSONObject("name");
        }''', '''        JSONObject nested = root == null ? null : TokenMeta.asObject(root.opt("token"));
        if (nested != null) {
            envelope = root;
            root = nested;
        }
        JSONObject meta = root == null ? null : TokenMeta.asObject(root.opt("name"));''')
    s = change(s, 'm.name = first(src.optString("name", ""), root.optString("name", ""), "Collection");',
               'm.name = first(src.optString("name", ""), "Collection");')
    p.write_text(s)
