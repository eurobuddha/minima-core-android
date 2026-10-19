"""Freeze Studio exports before background image/file work; preserve schema-v3 readers."""
from pathlib import Path
import shutil

def adapt(root):
 project=Path(__file__).resolve().parents[1];folder=root/'merchstudio/src/main/java/com/eurobuddha/merchstudio'
 shutil.copy2(project/'scripts/pandamonium-sources/merchstudio/ShopExport.java',folder/'ShopExport.java')
 p=folder/'MainActivity.java';s=p.read_text()
 def change(old,new):
  nonlocal s
  assert old in s,('Studio export drift',old);s=s.replace(old,new)
 change('    private boolean exporting = false;','    private boolean exporting = false;\n    private boolean walletVerified = false;')
 change('        boolean coinsLoading = false, coinsLoaded = false, tooLong = false;','        boolean coinsLoading = false, coinsLoaded = false, tooLong = false, coinsVerified = false;')
 change('        paired = enabled;','        paired = enabled;\n        if (!enabled) walletVerified = false;')
 change('        nftsLoading = true;','        nftsLoading = true; walletVerified = false;')
 change('                nftsLoading = false; nftsLoaded = true;','                nftsLoading = false; nftsLoaded = true; walletVerified = true;')
 change('        col.coinsLoading = true;','        col.coinsLoading = true; col.coinsVerified = false;')
 change('                col.coinsLoading = false; col.coinsLoaded = true; col.tooLong = false;','                col.coinsLoading = false; col.coinsLoaded = true; col.tooLong = false; col.coinsVerified = true;')
 change('        export.setOnClickListener(v -> exportShop());','        export.setTag("exportShop");\n        export.setOnClickListener(v -> exportShop());')
 start=s.index('        exporting = true;',s.index('    private void exportShop()'))
 bodyStart=s.index('                JSONObject o = new JSONObject();',start)
 bodyEnd=s.index('                File dir = new File(getCacheDir(), "shops");',bodyStart)
 body=s[bodyStart:bodyEnd]
 body=''.join(line[8:] if line.startswith('        ') else line for line in body.splitlines(keepends=True))
 body=body.replace('            if (l.cachedB64.isEmpty()) l.cachedB64 = resolveImageB64(l);','')
 body=body.replace('            po.put("image", l.cachedB64);','''            po.put("image", l.cachedB64);
            if (l.cachedB64.isEmpty()) images.add(new ShopExport.Image("products", ps.length(), "image",
                    artUrlOf(l), identiconKey(l), 900, 512, 90000));''')
 body=body.replace('clampUnits(l, held).intValue()','ShopExport.count(clampUnits(l, held))').replace('wholeUnits(l.bal.total).intValue()','ShopExport.count(wholeUnits(l.bal.total))')
 body=body.replace('''                String iconB64 = resolveIconB64(c);
                if (!iconB64.isEmpty()) co.put("icon", iconB64);''','''                images.add(new ShopExport.Image("nftCollections", cols.length(), "icon",
                        IconResolver.resolve(c.meta.icon), c.tokenid, 320, 256, 24000));''')
 body=body.replace('po.put("nftSupply", l.snMeta.size);','po.put("nftSupply", ShopExport.count(BigInteger.valueOf(l.snMeta.size)));')
 body=body.replace('co.put("size", c.meta.size);','co.put("size", ShopExport.count(BigInteger.valueOf(c.meta.size)));')
 end=s.index('    // ---- helpers ----',bodyEnd)
 replacement=(project/'scripts/pandamonium-sources/merchstudio/export-flow.inc').read_text()
 s=s[:start]+replacement.replace('@@SERIALIZE@@',body)+s[end:]
 # Previously loaded rows remain editable after a refresh failure, but cannot be newly exported.
 needle='        for (NftListing l : sel) {\n            if (!priced(l))'
 change(needle,'''        if (!sel.isEmpty() && (!paired || !walletVerified || nftsLoading)) {
            toast(Language.msg(l10n_mall_studio_ui_refresh_export)); return;
        }
        for (NftListing l : sel) {
            StateCollection collection = collections.get(l.tokenid);
            if (!tokenRecords.containsKey(l.tokenid) || fetchingRecords.contains(l.tokenid)
                    || ((l.bundle || l.stateIdx > 0) && (collection == null
                        || !collection.coinsVerified || collection.coinsLoading))) {
                toast(Language.msg(l10n_mall_studio_ui_refresh_export)); return;
            }
            if (!priced(l))''')
 p.write_text(s)
 # Reuse the exact BigInteger bounds already used for piece indices; JSON Numbers must not wrap.
 p=folder/'StateNft.java';s=p.read_text()
 start=s.index('    private static int firstInt(Object... vals) {');end=s.index('    private static void put(',start)
 s=s[:start]+"""    private static int firstInt(Object... vals) {
        for (Object v : vals) {
            if (v == null || v == JSONObject.NULL || String.valueOf(v).trim().isEmpty()) continue;
            try {
                int count = new java.math.BigDecimal(String.valueOf(v).trim()).intValueExact();
                return count > 0 ? count : 0;
            } catch (IllegalArgumentException | ArithmeticException invalid) { return 0; }
        }
        return 0;
    }

"""+s[end:]
 p.write_text(s)
