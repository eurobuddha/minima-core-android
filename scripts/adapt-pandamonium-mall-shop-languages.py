"""miniMall Shop display bindings, reusing Mail's retained pages/dialogs and explicit messages."""
from pathlib import Path
import json,re,importlib.util

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/mall-shop-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'merchshop/src/main/java/com/eurobuddha/merchshop';p=folder/'MainActivity.java';s=p.read_text()
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(key)+('' if not args else ', '+', '.join(args))+')'
 def change(old,new,count=None):
  nonlocal s
  assert old in s and (count is None or s.count(old)==count),('Shop display drift',old,s.count(old));s=s.replace(old,new)
 change('package com.eurobuddha.merchshop;', 'package com.eurobuddha.merchshop;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 # Same dialog ownership as Mail; do not consume caller onShow/onDismiss callbacks.
 mail=(project/'scripts/adapt-pandamonium-mail-languages.py').read_text();begin=mail.index('    private final java.util.Set<AlertDialog> languageDialogs');end=mail.index('    private void showLanguageMenu',begin)
 hook=mail[begin:end];hook=re.sub(r'    private final java.util.Map<PopupMenu,[^\n]+\n','',hook)
 hook+='''    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        ensureChannel();
    }

'''
 change('    private static final String CH',hook+'    private static final String CH')
 change('        super.onDestroy();','        for (AlertDialog dialog : new java.util.ArrayList<>(languageDialogs)) dialog.dismiss();\n        languageDialogs.clear();\n        super.onDestroy();')
 change('new AlertDialog.Builder(this)','languageDialog()')
 # Translate only the known app-generated inquiry summaries at the display boundary.
 # Persisted/wire strings and all ordinary product summaries stay literal.
 change('o.product.isEmpty() ? o.ref : o.product','orderTitle(o)',3)
 change('    private View buildOrderDetail(String ref) {','''    private CharSequence orderTitle(MerchDb.Order o) {
        if (MerchDb.INQUIRY.equals(o.status) || (o.ref != null && o.ref.startsWith("INQ-"))) {
            if (o.shopName != null && !o.shopName.isEmpty() && ("Messages with " + o.shopName).equals(o.product))
                return Language.msg(l10n_mall_shop_messages_with_shop, o.shopName);
            if ("General enquiry".equals(o.product)) return Language.msg(l10n_mall_shop_general_enquiry);
        }
        return o.product.isEmpty() ? o.ref : o.product;
    }

    private View buildOrderDetail(String ref) {''')

 # Seed input retains the Mail protection; never capture a secret-bearing dialog.
 begin=s.index('    private void askForSeed()');end=s.index('    // ---- scanning ----',begin)
 seed=s[begin:end].replace('        languageDialog()', '        AlertDialog seedDialog = languageDialog()',1).replace('                .show();','                .create();\n        seedDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);\n        seedDialog.show();')
 s=s[:begin]+seed+s[end:]
 changes=[
 ('"Identity error: " + e.getMessage()',msg('identityError','ShopMessages.error(e)')),
 ('c.products.size() + " product" + (c.products.size() == 1 ? "" : "s") + "  ·  " + c.currency',msg('products','c.products.size()','c.currency')),
 ('"Added " + c.shopName',msg('added','c.shopName')),
 ('"Remove " + c.shopName + "?"',msg('removeTitle','c.shopName')),
 ('"NFT SHOP · PAYS IN " + catalog.currency.toUpperCase()',msg('nftPays','catalog.currency.toUpperCase(java.util.Locale.ROOT)')),
 ('"Pays in " + catalog.currency',msg('pays','catalog.currency')),
 ('String name = !first.nftCollection.isEmpty()', 'CharSequence name = !first.nftCollection.isEmpty()'),
 ('name.toUpperCase() + " · " + pieceCount + (pieceCount == 1 ? " PIECE" : " PIECES")','Language.upper('+msg('collectionPieces','name','pieceCount')+')'),
 ('final String tokenid, label;', 'final String tokenid; final CharSequence label;'),
 ('ColHeader(String tokenid, String label)', 'ColHeader(String tokenid, CharSequence label)'),
 ('private String editionChip(', 'private CharSequence editionChip('),
 ('"◈ complete · " + p.nftPieces + " pieces"',msg('completePieces','p.nftPieces')),
 ('"◈ ×" + p.maxUnits + " eds"',msg('editions','p.maxUnits')),
 ('count + " item" + (count == 1 ? "" : "s") + "  ·  " + cartTotal().toPlainString() + " " + catalog.currency',msg('cartSummary','count','cartTotal().toPlainString()','catalog.currency')),
 ('"Max " + p.maxUnits',msg('maximum','p.maxUnits')),
 ('"Complete collection — " + (p.nftPieces > 0 ? p.nftPieces + " pieces" : "every piece the seller holds")','p.nftPieces > 0 ? '+msg('bundleContents','p.nftPieces')+' : '+msg('allPieces')),
 ('"#" + p.nftStateIdx + (p.nftSupply > 0 ? " of " + p.nftSupply : "")','p.nftSupply > 0 ? '+msg('editionOf','p.nftStateIdx','p.nftSupply')+' : "#" + p.nftStateIdx'),
 ('"Buy · " + p.price + " " + catalog.currency',msg('buy','p.price','catalog.currency')),
 ('private String feeLabel(', 'private CharSequence feeLabel('),
 ('"  · free"','Language.join("  · ", '+msg('free')+')'),
 ('sh.label + feeLabel(sh)','Language.join(sh.label, feeLabel(sh))'),
 ('''(ship.signum() > 0
                    ? "Items   " + items.toPlainString() + " " + catalog.currency + "\\nShipping   " + ship.toPlainString() + " " + catalog.currency + "\\n" : "")
                    + "Total   " + grand.toPlainString() + " " + catalog.currency''','Language.join(ship.signum() > 0 ? Language.join('+msg('subtotal','items.toPlainString()','catalog.currency')+', "\\n", '+msg('shippingFee','ship.toPlainString()','catalog.currency')+', "\\n") : "", '+msg('grandTotal','grand.toPlainString()','catalog.currency')+')'),
 ('"Pay " + grand.toPlainString() + " " + catalog.currency',msg('payAmount','grand.toPlainString()','catalog.currency')),
 ('"Pay " + total + " " + catalog.currency + "?"',msg('payTitle','total','catalog.currency')),
 ('"This sends your encrypted order to " + catalog.shopName + " and pays " + total + " " + catalog.currency + ". Payment is final."',msg('payHelp','catalog.shopName','total','catalog.currency')),
 ('"Order failed: " + e',msg('orderError','ShopMessages.message(e)')),
 ('"Sending " + o.amount + " " + o.currency + "…"',msg('sendingPayment','o.amount','o.currency')),
 ('"Payment failed: " + e',msg('paymentError','ShopMessages.message(e)')),
 ('"Send failed: " + e',msg('sendError','ShopMessages.message(e)')),
 ('o.amount + " " + o.currency + "  ·  " + timeAgo(o.date)','Language.join(o.amount, " ", o.currency, "  ·  ", timeAgo(o.date))'),
 ('Design.pill(MainActivity.this, o.status,','Design.pill(MainActivity.this, ShopMessages.status(o.status),'),
 ('inquiry ? "ENQUIRY" : o.status','inquiry ? '+msg('enquiry')+' : ShopMessages.status(o.status)'),
 ('(incoming ? "Vendor: " : "You: ") + c[1]','incoming ? '+msg('vendorSays','c[1]')+' : '+msg('youSay','c[1]')),
 ('String[] names = {"Placed", "Paid", "Delivered"};','CharSequence[] names = {'+','.join(msg(k)for k in ['timelinePlaced','timelinePaid','timelineDelivered'])+'};')]
 for old,new in changes:change(old,new)
 start=s.index('    private static String timeAgo(');end=s.index('\n    private int dp(',start)
 s=s[:start]+'''    private static CharSequence timeAgo(long ms) { return ms <= 0 ? "" : Language.since(ms); }
''' + s[end:]
 # Only explicit UI constants are replaced. User/vendor text is passed as literal arguments.
 dynamic={'identityError','products','added','removeTitle','nftPays','pays','collectionPieces','completePieces','editions','cartSummary','maximum','bundleContents','editionOf','buy','subtotal','shippingFee','grandTotal','payAmount','payTitle','payHelp','orderError','sendingPayment','paymentError','vendorSays','youSay','sendError','free','allPieces','enquiry','timelinePlaced','timelinePaid','timelineDelivered','minus','plus','messageVendor','invalidPayment'}|{k for k in labels if k.startswith('status')}
 for key in labels:
  if key in dynamic:continue
  source = "Can't retry from here — open the shop and re-order." if key == 'retryUnavailable' else catalog[labels[key]]['en']
  literal=json.dumps(source,ensure_ascii=False)
  if literal in s:change(literal,msg(key))
  else:raise AssertionError(('Unused Shop label',key,literal))
 # Helpers retain descriptors; arbitrary strings are never matched against translated labels.
 for old,new in [('header(String title','header(CharSequence title'),('sectionLabel(String s)','sectionLabel(CharSequence s)'),('card(String s)','card(CharSequence s)'),('kv(String k, String v)','kv(CharSequence k, CharSequence v)'),('button(String text','button(CharSequence text'),('input(String hint)','input(CharSequence hint)'),('toast(String s)','toast(CharSequence s)'),('copyRow(String k, String shown','copyRow(CharSequence k, String shown'),('s.toUpperCase()','Language.upper(s)')]:change(old,new)
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setText\(',r'Language.setText(\1, ',s)
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setHint\(',r'Language.hint(\1, ',s)
 change('        b.setGravity(Gravity.CENTER); b.setPadding(dp(10), dp(2), dp(10), dp(2));', '''        Language.description(b, "‹".equals(glyph) ? '''+msg('back')+''' : "🧾".equals(glyph) ? '''+msg('orders')+''' : '''+msg('messageVendor')+''');
        b.setMinWidth(dp(48)); b.setMinHeight(dp(48));
        b.setGravity(Gravity.CENTER); b.setPadding(dp(10), dp(2), dp(10), dp(2));''')
 change('        b.setBackground(Design.roundBg(this, Design.SURFACE2, 8));','        Language.description(b, "+".equals(glyph) ? '+msg('plus')+' : '+msg('minus')+');\n        b.setBackground(Design.roundBg(this, Design.SURFACE2, 8));')
 change('new LinearLayout.LayoutParams(dp(38), dp(38))','new LinearLayout.LayoutParams(dp(48), dp(48))')
 # Preserve notification IDs, user settings and delivery flags; update only existing channel names.
 old='            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, '+msg('channel')+', NotificationManager.IMPORTANCE_DEFAULT));'
 change(old,'''            if (nm == null) return;
            NotificationChannel channel = nm.getNotificationChannel(CH);
            if (channel == null) channel = new NotificationChannel(CH, '''+msg('channel')+''', NotificationManager.IMPORTANCE_DEFAULT);
            channel.setName('''+msg('channel')+''');
            nm.createNotificationChannel(channel);''')
 p.write_text(s)
 p=folder/'Design.java';s=p.read_text();change('String text,','CharSequence text,');change('0xFF6A6A78','0xFF9A9AA8');change('        t.setText(text);','        com.eurobuddha.minimacore.i18n.Language.setText(t, text);');p.write_text(s)
 # Same bounded local-diagnostic mapper used by Mail/Future Cash. Remote arbitrary text stays literal.
 spec=json.loads((project/'localization/vestr-errors.json').read_text());spec['fixed'].update({'NOT_ENABLED':labels['nodeUnavailable'],'Node connection closed.':'vestr.transportClosed','box seed keypair failed':'mallShop.boxKeyFailed','sign seed keypair failed':'mallShop.signKeyFailed','HKDF failed':'mallShop.hkdfFailed','the node rejected the send':'mail.rejectedSend','sign failed':'mail.signFailed','seal failed':'mail.sealFailed','Invalid payment address, token or amount.':labels['invalidPayment']});spec['patterns']=[{'template':'encrypt failed: {0}','key':'mail.encryptFailed','kinds':['diagnostic']},{'template':'seal error: {0}','key':'mail.sealError','kinds':['diagnostic']}]
 loader=importlib.util.spec_from_file_location('shop_messages',project/'scripts/adapt-pandamonium-wallet-error-languages.py');module=importlib.util.module_from_spec(loader);loader.loader.exec_module(module)
 source=module.build_source(spec,catalog).replace('com.eurobuddha.wallet','com.eurobuddha.merchshop').replace('WalletMessages','ShopMessages').replace('Explicit wallet failures only.','Explicit Shop local failures only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-mall-shop-languages.py')
 status='    public static CharSequence status(String value) {\n        if (value == null) return "";\n        switch(value) {\n'
 for value,key in [('PENDING','statusPending'),('PAID','statusPaid'),('CONFIRMED','statusConfirmed'),('SHIPPED','statusShipped'),('DELIVERED','statusDelivered'),('UNDERPAID','statusUnderpaid'),('WRONG_TOKEN','statusWrongToken'),('INQUIRY','enquiry')]:status+='            case "'+value+'": return '+msg(key)+';\n'
 status+='            default: return value;\n        }\n    }\n'
 source=source.replace('    private ShopMessages() {}','    private ShopMessages() {}\n'+status);(folder/'ShopMessages.java').write_text(source)
