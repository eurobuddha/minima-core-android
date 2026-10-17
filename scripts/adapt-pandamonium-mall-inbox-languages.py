"""Inbox presentation: reuse Shop/Mail bindings; preserve protocol, CSV and delivery state."""
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/mall-inbox-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'merchinbox/src/main/java/com/eurobuddha/merchinbox';p=folder/'MainActivity.java';s=p.read_text()
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(key)+(''if not args else ', '+', '.join(args))+')'
 def change(old,new,count=None):
  nonlocal s
  assert old in s and (count is None or s.count(old)==count),('Inbox display drift',old,s.count(old));s=s.replace(old,new)
 change('package com.eurobuddha.merchinbox;','package com.eurobuddha.merchinbox;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 # Already generated Shop owns dialogs without replacing caller listeners.
 shop=(root/'merchshop/src/main/java/com/eurobuddha/merchshop/MainActivity.java').read_text();begin=shop.index('    private final java.util.Set<AlertDialog> languageDialogs');end=shop.index('    private static final String CH',begin)
 change('    private static final String CH',shop[begin:end]+'    private static final String CH')
 change('        super.onDestroy();','        for (AlertDialog dialog : new java.util.ArrayList<>(languageDialogs)) dialog.dismiss();\n        languageDialogs.clear();\n        super.onDestroy();')
 change('new AlertDialog.Builder(this)','languageDialog()')
 # Keep Inbox's optional vendor branding separate from the buyer Shop demo asset.
 change('getAssets().open("shop.json")', 'getAssets().open("pm_merchinbox_shop.json")')
 asset=root/'merchinbox/src/main/assets/shop.json'
 if asset.exists():asset.rename(asset.with_name('pm_merchinbox_shop.json'))

 begin=s.index('    private void askForSeed()');end=s.index('    // ---- scanning',begin)
 seed=s[begin:end].replace('        languageDialog()', '        AlertDialog seedDialog = languageDialog()',1).replace('                .show();','                .create();\n        seedDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);\n        seedDialog.show();');s=s[:begin]+seed+s[end:]
 # No language-change rebuild: existing stack, drafts, filter values and listeners survive.
 changes=[
 ('"Identity error: " + e.getMessage()',msg('identityError','InboxMessages.error(e)')),
 ('"✓ Ready to receive orders  ·  shop key " + shortId(myId)',msg('ready','shortId(myId)')),
 ('"⚡ Auto NFT delivery  ·  " + (auto ? "ON" : "OFF")','auto ? '+msg('autoOn')+' : '+msg('autoOff')),
 ('"Auto NFT delivery " + ("1".equals(next) ? "on" : "off")','"1".equals(next) ? '+msg('autoEnabled')+' : '+msg('autoDisabled')),
 ('Design.pill(this, s,','Design.pill(this, InboxMessages.status(s),'),
 ('Design.pill(MainActivity.this, o.status,','Design.pill(MainActivity.this, InboxMessages.status(o.status),'),
 ('inquiry ? "ENQUIRY" : o.status','inquiry ? '+msg('enquiry')+' : InboxMessages.status(o.status)'),
 ('button(s, s.equals(o.status))','button(InboxMessages.status(s), s.equals(o.status))'),
 ('shopPrefix + o.amount + " " + dispCurrency(o) + "   ·   " + shortId(o.counterparty) + "   ·   " + timeAgo(o.date)','Language.join(shopPrefix, o.amount, " ", dispCurrency(o), "   ·   ", shortId(o.counterparty), "   ·   ", timeAgo(o.date))'),
 ('"  paid " + o.paidAmount + " " + dispCurrency(o)',msg('paidAmount','o.paidAmount','dispCurrency(o)')),
 ('"Marked " + status',msg('marked','InboxMessages.status(status)')),
 ('final String label = bundle ? "the complete collection (\\\"" + name + "\\\")"','final CharSequence label = bundle ? '+msg('completeLabel','name')),
 ('"✓ Sent " + label + (("sent".equals(sentTx) || "posted".equals(sentTx)) ? "" : " · " + shortId(sentTx))',msg('sentLabel','label','(("sent".equals(sentTx) || "posted".equals(sentTx)) ? "" : " · " + shortId(sentTx))')),
 ('"Send " + label + " to buyer"',msg('sendBuyer','label')),
 ('"Send " + label + " (" + shortId(tokenid) + ") to " + shortId(o.payaddr) + "?\\n\\nThis transfers the NFT on-chain."',msg('sendHelp','label','shortId(tokenid)','shortId(o.payaddr)')),
 ('"NFT send failed: " + msg',msg('sendError','InboxMessages.message(msg)')),
 ('String msg = o.ref + " — " + postedIds.size() + " piece(s) delivered, " + sendableLeft + " remain"\n                        + (firstError != null ? " (first error: " + firstError + ")" : "") + " — retry to send the rest"','CharSequence msg = '+msg('partial','o.ref','postedIds.size()','sendableLeft','firstError == null ? "" : '+msg('firstError','InboxMessages.message(firstError)'))),
 ('private void notifyDelivery(String ref, boolean ok, String detail)','private void notifyDelivery(String ref, boolean ok, CharSequence detail)'),
 ('(incoming ? "Buyer: " : "You: ") + c[1]','incoming ? '+msg('buyerSays','c[1]')+' : '+msg('youSay','c[1]')),
 ('"Reply failed: " + e',msg('replyError','InboxMessages.message(e)')),
 ('"Exported " + db.orders().size() + " orders"',msg('exported','db.orders().size()')),
 ('"Export failed: " + e.getMessage()',msg('exportError','InboxMessages.error(e)')),
 ('n + " new order update(s)"',msg('newUpdates','n')),
 ('ref + " paid · NFT sent to the buyer"',msg('deliveredNotice','ref')),
 ('ref + " — open the order to retry"',msg('retryNotice','ref'))]
 for old,new in changes:change(old,new)
 # Display only generated inquiry titles; stored products/wire text remain literal.
 begin=shop.index('    private CharSequence orderTitle(');end=shop.index('    private View buildOrderDetail(',begin)
 change('    private View buildOrderDetail(',shop[begin:end]+'    private View buildOrderDetail(')
 change('(o.read ? "" : "●  ") + (o.product.isEmpty() ? o.ref : o.product)','Language.join(o.read ? "" : "●  ", orderTitle(o))')
 change('o == null ? "Order" : (o.product.isEmpty() ? o.ref : o.product)','o == null ? "Order" : orderTitle(o)')
 # Shipping codes remain stored as codes; only their known display labels translate.
 change('private String shippingLabel(', 'private CharSequence shippingLabel(')
 # TextBuilder snapshots literal products, size, counts and exact line totals.
 begin=s.index('    private String itemsText(');end=s.index('    private int statusColor',begin)
 items=s[begin:end].replace('private String itemsText','private CharSequence itemsText').replace('StringBuilder sb = new StringBuilder();','Language.TextBuilder sb = new Language.TextBuilder();\n            boolean any = false;').replace('sb.append("• ").append(it.optString("product", "item"));','any = true;\n                sb.append("• ").append(it.has("product") ? it.optString("product", "") : '+msg('item')+');').replace('sb.length() == 0','!any').replace('sb.toString()','sb.build()');s=s[:begin]+items+s[end:]
 begin=s.index('    private static String timeAgo(');end=s.index('    private String loadShopName()',begin);s=s[:begin]+'    private static CharSequence timeAgo(long ms) { return ms <= 0 ? "" : Language.since(ms); }\n'+s[end:]
 dynamic={'enquiry','ready','autoOn','autoOff','autoEnabled','autoDisabled','paidAmount','marked','completeLabel','sentLabel','sendBuyer','sendHelp','sendError','partial','firstError','buyerSays','replyError','exported','exportError','newUpdates','deliveredNotice','retryNotice','item','identityError','youSay','all','back','exportOrders','badAddress','badToken','badEdition','coinListLarge','nonePosted','outdatedBuyer','missingPiece','unsafeState','missingCollection'}|{k for k in labels if k.startswith('status')}
 for key in labels:
  if key in dynamic:continue
  literal=json.dumps(catalog[labels[key]]['en'],ensure_ascii=False)
  # Existing aliases with a different English phrase are explicitly handled here.
  if key=='connectFirst':literal='"Connect to your node first."'
  assert literal in s,('Unused Inbox label',key,literal)
  change(literal,msg(key))
 for old,new in [('header(String title','header(CharSequence title'),('label(String s)','label(CharSequence s)'),('sectionLabel(String s)','sectionLabel(CharSequence s)'),('card(String s)','card(CharSequence s)'),('kv(String k, String v)','kv(CharSequence k, CharSequence v)'),('button(String text','button(CharSequence text'),('input(String hint)','input(CharSequence hint)'),('toast(String s)','toast(CharSequence s)'),('copyRow(String k, String shown','copyRow(CharSequence k, String shown'),('s.toUpperCase()','Language.upper(s)')]:change(old,new)
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setText\(',r'Language.setText(\1, ',s);s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setHint\(',r'Language.hint(\1, ',s)
 change('        b.setGravity(Gravity.CENTER); b.setPadding(dp(10), dp(2), dp(10), dp(2));','        Language.description(b, "‹".equals(glyph) ? '+msg('back')+' : "⤓".equals(glyph) ? '+msg('exportOrders')+' : '+msg('vendorCard')+');\n        b.setMinWidth(dp(48)); b.setMinHeight(dp(48));\n        b.setGravity(Gravity.CENTER); b.setPadding(dp(10), dp(2), dp(10), dp(2));')
 # Two rows of status actions give long Russian/Ukrainian labels room at normal phone widths.
 change('        wrap.setOrientation(LinearLayout.HORIZONTAL);\n        String[] flow', '        wrap.setOrientation(LinearLayout.VERTICAL);\n        LinearLayout actionRow = null;\n        int actionIndex = 0;\n        String[] flow')
 change('        for (final String s : flow) {','        for (final String s : flow) {\n            if (actionIndex++ % 2 == 0) { actionRow = new LinearLayout(this); actionRow.setOrientation(LinearLayout.HORIZONTAL); wrap.addView(actionRow); }')
 change('            wrap.addView(b);','            actionRow.addView(b);')
 old='            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, '+msg('orders')+', NotificationManager.IMPORTANCE_DEFAULT));'
 change(old,'            if (nm == null) return;\n            NotificationChannel channel = nm.getNotificationChannel(CH);\n            if (channel == null) channel = new NotificationChannel(CH, '+msg('orders')+', NotificationManager.IMPORTANCE_DEFAULT);\n            channel.setName('+msg('orders')+');\n            nm.createNotificationChannel(channel);')
 p.write_text(s)
 p=folder/'Design.java';s=p.read_text();change('String text,','CharSequence text,');change('0xFF6A6A78','0xFF9A9AA8');change('        t.setText(text);','        com.eurobuddha.minimacore.i18n.Language.setText(t, text);');p.write_text(s)
 # Reuse the generated Shop diagnostic/status mapper, with Inbox-only local failures.
 source=(root/'merchshop/src/main/java/com/eurobuddha/merchshop/ShopMessages.java').read_text().replace('com.eurobuddha.merchshop','com.eurobuddha.merchinbox').replace('ShopMessages','InboxMessages').replace('Shop local','Inbox local').replace('mall-shop-languages.py','mall-inbox-languages.py')
 source=source.replace('        switch(value) {','        switch(value) {\n            case "ALL": return '+msg('all')+';',1)
 fixed='            case "transaction does not balance — refusing to sign": return Language.msg(l10n_mall_inbox_unbalanced);\n            case "TOO_LONG": return Language.msg(l10n_mall_inbox_coin_list_large);\n' 
 for key in ['badAddress','badToken','badEdition','coinListLarge','nonePosted','outdatedBuyer']:fixed+='            case '+json.dumps(catalog[labels[key]]['en'],ensure_ascii=False)+': return '+msg(key)+';\n'
 needle='        if (depth >= 8) return value;\n        switch(value) {';assert needle in source;source=source.replace(needle,needle+'\n'+fixed)
 # Small explicit display templates retain captured product names as literal arguments.
 template='''        java.util.regex.Matcher command = java.util.regex.Pattern.compile("(txndelete|txncreate|txninput|txnoutput|txnstate|txnbasics|txncheck|txnsign|txnpost) failed(?: : ((?s:.*)))?").matcher(value);
        if (command.matches()) return command.group(2) == null ? Language.msg(l10n_casino_command_failed, command.group(1)) : Language.msg(l10n_casino_command_failed_detail, command.group(1), message(command.group(2), depth + 1));
        java.util.regex.Matcher piece = java.util.regex.Pattern.compile("piece #([0-9]+) of ((?s:.*)) is not in this wallet — sold or moved\\\\?").matcher(value);
        if (piece.matches()) return %s;
        java.util.regex.Matcher state = java.util.regex.Pattern.compile("piece #([0-9]+) carries state this app won't replay").matcher(value);
        if (state.matches()) return %s;
        java.util.regex.Matcher collection = java.util.regex.Pattern.compile("no pieces of ((?s:.*)) are in this wallet — sold or moved\\\\?").matcher(value);
        if (collection.matches()) return %s;
'''%(msg('missingPiece','piece.group(1)','piece.group(2)'),msg('unsafeState','state.group(1)'),msg('missingCollection','collection.group(1)'))
 source=source.replace('        for(int i=0;i<PATTERNS.length;i++) {',template+'        for(int i=0;i<PATTERNS.length;i++) {');(folder/'InboxMessages.java').write_text(source)

 # Reuse Filez/ETH fixed dark dialogs for this fixed dark application.
 for res in ['values','values-night']:
  p=root/f'merchinbox/src/main/res/{res}/themes.xml';s=p.read_text();assert 'Theme.Material3.DayNight.NoActionBar' in s
  s=s.replace('Theme.Material3.DayNight.NoActionBar','Theme.Material3.Dark.NoActionBar')
  s=s.replace('    </style>','        <item name="colorPrimary">#F7931A</item>\n        <item name="colorAccent">#F7931A</item>\n        <item name="colorSurface">#15151F</item>\n        <item name="colorSurfaceContainerHigh">#15151F</item>\n        <item name="colorOnSurface">#FFFFFF</item>\n        <item name="colorOnSurfaceVariant">#9A9AA8</item>\n        <item name="android:colorBackground">#0A0A0F</item>\n        <item name="android:textColorPrimary">#FFFFFF</item>\n        <item name="android:textColorSecondary">#9A9AA8</item>\n    </style>',1);p.write_text(s)
