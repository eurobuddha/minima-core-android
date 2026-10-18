"""Reuse core/NFT wallet metadata coercion and retain Atelier viewer state across languages."""
from pathlib import Path

def adapt(root):
    folder=root/'atelier/src/main/java/com/eurobuddha/statenft'
    def change(s,old,new):
        assert s.count(old)==1,('Atelier viewer source drift',old)
        return s.replace(old,new)
    p=folder/'Util.java';s=p.read_text()
    s=change(s,'    public static String tokenName(Object token) {','''    public static String tokenName(Object token) {
        JSONObject object = tokenObject(token);
        if (object != null) token = object;''')
    s=change(s,'            Object name = ((JSONObject) token).opt("name");','''            Object name = ((JSONObject) token).opt("name");
            JSONObject nameObject = asObject(name);
            if (nameObject != null) name = nameObject;''')
    donor=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet/TokenMeta.java'
    proven=donor.read_text();a=proven.index('    /** Core balance TokenMeta.asObject');b=proven.index('    private static String first(',a)
    s=change(s,'    /** URLs ride the tokencreate',proven[a:b]+'    /** URLs ride the tokencreate')
    p.write_text(s)
    p=folder/'StateNft.java';s=p.read_text()
    s=change(s,'JSONObject root = tokenNode instanceof JSONObject ? (JSONObject) tokenNode : null;', 'JSONObject root = Util.asObject(tokenNode);')
    s=change(s,'''        if (root != null && root.opt("token") instanceof JSONObject) {
            JSONObject wrapped = root.optJSONObject("token");''','''        if (root != null) {
            JSONObject wrapped = Util.asObject(root.opt("token"));''')
    s=change(s,'''        JSONObject meta = null;
        if (root != null && root.opt("name") instanceof JSONObject) {
            meta = root.optJSONObject("name");
        }''','''        JSONObject meta = root == null ? null : Util.asObject(root.opt("name"));''')
    s=change(s,'m.name = first(src.optString("name", ""), root.optString("name", ""), "Collection");','m.name = first(src.optString("name", ""), "Collection");')
    p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text()
    s=change(s,'''        if (tok instanceof JSONObject) {
            Object n = ((JSONObject) tok).opt("name");
            if (n instanceof JSONObject) ticker = ((JSONObject) n).optString("ticker", "");
        }''','''        JSONObject tokenObject = Util.tokenObject(tok);
        if (tokenObject != null) {
            JSONObject n = Util.asObject(tokenObject.opt("name"));
            ticker = n == null ? "" : n.optString("ticker", "");
            if (ticker.isEmpty()) ticker = tokenObject.optString("ticker", "");
        }''')
    s=change(s,'''                    if (!(tok instanceof JSONObject)) { tokenRows.add(row); continue; }
                    JSONObject t = (JSONObject) tok;
                    Object nameObj = t.opt("name");
                    JSONObject metaObj = nameObj instanceof JSONObject ? (JSONObject) nameObj : t;''','''                    JSONObject t = Util.tokenObject(tok);
                    if (t == null) { tokenRows.add(row); continue; }
                    JSONObject metaObj = Util.asObject(t.opt("name"));
                    if (metaObj == null) metaObj = t;''')
    p.write_text(s)
    p=folder/'ViewerScreen.java';s=p.read_text()
    s=change(s,'''            if (it.imageUrl != null && !it.imageUrl.isEmpty()) {
                ImageLoader.loadFull(act, it.imageUrl, img);
            }''','''            // The loader also clears the previous request tag for an empty URL.
            ImageLoader.loadFull(act, it.imageUrl, img);''')
    s=change(s,'            LinearLayout traitRow = null;', '            LinearLayout traitRow = null;\n            int renderedTraits = 0;')
    s=change(s,'                if (i % 3 == 0) {','                if (renderedTraits % 3 == 0) {')
    s=change(s,'                if (i % 3 != 0) blp.leftMargin', '                if (renderedTraits % 3 != 0) blp.leftMargin')
    s=change(s,'                traitRow.addView(box, blp);','                traitRow.addView(box, blp);\n                renderedTraits++;')
    s=change(s,'            int rem = attrs.length() % 3;', '            int rem = renderedTraits % 3;')
    # Existing Design/Language bindings already preserve zoom and the selected lot.
    # Reuse the collection wizard minimum-height/wrap-content pattern for actions.
    for name in ['tr','save','share']:
        s=change(s,'actions.addView('+name+', weight(c, 46));',name+'.setMinHeight(Design.dp(c, 48));\n'+('            ' if name=='tr' else '        ')+'actions.addView('+name+', weight(c, -2));')
    s=change(s,'return new LinearLayout.LayoutParams(0, Design.dp(c, hDp), 1);','return new LinearLayout.LayoutParams(0, hDp < 0 ? hDp : Design.dp(c, hDp), 1);')
    s=change(s,'        top.addView(info, new LinearLayout.LayoutParams(-2, Design.dp(c, 48)));','''        info.setMinHeight(Design.dp(c, 48));
        top.addView(info, new LinearLayout.LayoutParams(-2, -2));''')
    # Existing row helper fills the parent, which pushed the coin hash out of the
    # horizontal strip. Give both hashes room to wrap on a narrow display.
    s=change(s,'        hashes.addView(tok);','        hashes.addView(tok, new LinearLayout.LayoutParams(0, -2, 1));')
    s=change(s,'            hashes.addView(coin, lp(c, 8, 0, 0, 0));','''            LinearLayout.LayoutParams coinLp = new LinearLayout.LayoutParams(0, -2, 1);
            coinLp.leftMargin = Design.dp(c, 8);
            hashes.addView(coin, coinLp);''')
    p.write_text(s)
