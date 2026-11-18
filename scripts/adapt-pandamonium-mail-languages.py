"""Bind Mail display text to the shared selector without rebuilding drafts or changing its wire format."""
from pathlib import Path
import json,re,importlib.util

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/mail-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'mail/src/main/java/com/eurobuddha/mail';p=folder/'MainActivity.java';s=p.read_text()
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
 def msg(key,*args):return 'Language.msg('+resource(key)+('' if not args else ', '+', '.join(args))+')'
 def change(old,new):
  nonlocal s
  assert old in s,('Mail display source drift',old)
  s=s.replace(old,new)
 change('package com.eurobuddha.mail;', 'package com.eurobuddha.mail;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;\nimport androidx.appcompat.widget.PopupMenu;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 # Keep dialog ownership separate from onDismiss: the payment sheet already owns that callback.
 hook='''    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    private final java.util.Map<PopupMenu, java.util.Map<android.view.MenuItem, CharSequence>> languageMenus = new java.util.LinkedHashMap<>();
    private LanguageDialog languageDialog() {
        return new LanguageDialog(this) {
            @Override public AlertDialog create() {
                AlertDialog dialog = super.create();
                languageDialogs.add(dialog);
                dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) { languageDialogs.add(dialog); }
                    @Override public void onViewDetachedFromWindow(View v) { languageDialogs.remove(dialog); }
                });
                return dialog;
            }
        };
    }
    private void showLanguageMenu(PopupMenu menu) {
        java.util.Map<android.view.MenuItem, CharSequence> labels = new java.util.LinkedHashMap<>();
        for (int i=0;i<menu.getMenu().size();i++) {
            android.view.MenuItem item = menu.getMenu().getItem(i);
            labels.put(item,item.getTitle());
        }
        languageMenus.put(menu,labels);
        menu.setOnDismissListener(m -> languageMenus.remove(m));
        menu.show();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        updateFreshness();
        ensureChannel();
        for (PopupMenu menu : new java.util.ArrayList<>(languageMenus.keySet())) {
            java.util.Map<android.view.MenuItem, CharSequence> labels = languageMenus.get(menu);
            menu.setOnDismissListener(null);
            menu.dismiss();
            for (java.util.Map.Entry<android.view.MenuItem, CharSequence> entry : labels.entrySet())
                entry.getKey().setTitle(entry.getValue().toString());
            menu.setOnDismissListener(m -> languageMenus.remove(m));
            menu.show();
        }
    }

'''
 change('    private static final String CH',hook+'    private static final String CH')
 change('        super.onDestroy();','        for (AlertDialog value : new java.util.ArrayList<>(languageDialogs)) value.dismiss();\n        languageDialogs.clear();\n        for (PopupMenu value : new java.util.ArrayList<>(languageMenus.keySet())) value.dismiss();\n        languageMenus.clear();\n        super.onDestroy();')
 change('new AlertDialog.Builder(this)','languageDialog()');change('new AlertDialog.Builder(MainActivity.this)','languageDialog()')
 change('        pm.show();','        showLanguageMenu(pm);');change('        m.show();','        showLanguageMenu(m);')
 change('"connecting to your node…"',msg('connecting'));change('"(connecting to your node…)"',msg('connecting'))
 change('"you · " + MailAddress.phrase(myId)',msg('youKey','MailAddress.phrase(myId)'))
 change('"Enable Minima Mail in Minima Core → Apps."',msg('nodeRetry'))
 # Retain display descriptors through all compositions; subject/name/memo inputs remain literal.
 change('String blk = chainBlock > 0 ? "block " + String.format(java.util.Locale.ENGLISH, "%,d", chainBlock) : "the chain";', 'CharSequence blk = chainBlock > 0 ? '+msg('block','chainBlock')+' : '+msg('chain')+';')
 change('"Checking " + blk + "…"',msg('checking','blk'))
 change('String ago = m < 1 ? "just now" : m + " min ago";','CharSequence ago = Language.since(last);')
 change('"Checked " + blk + " · " + ago',msg('checked','blk','ago'))
 change('(t.incoming ? "" : "You: ") + previewText(t)','t.incoming ? previewText(t) : '+msg('youPreview','previewText(t)'))
 change('"Confirmed" + (m.sentblock > 0 ? " · block " + String.format(java.util.Locale.ENGLISH, "%,d", m.sentblock) : "")','Language.join('+msg('confirmed')+', m.sentblock > 0 ? Language.join(" · ", '+msg('block','m.sentblock')+') : "")')
 change('"On-chain" + (m.sentblock > 0 ? " · block " + String.format(java.util.Locale.ENGLISH, "%,d", m.sentblock) : "") + " — confirming"',msg('confirming','Language.join('+msg('onChain')+', m.sentblock > 0 ? Language.join(" · ", '+msg('block','m.sentblock')+') : "")'))
 change('"On-chain" + (r.m.sentblock > 0 ? " · blk " + String.format(java.util.Locale.ENGLISH, "%,d", r.m.sentblock) : "")','Language.join('+msg('onChain')+', r.m.sentblock > 0 ? Language.join(" · ", '+msg('block','r.m.sentblock')+') : "")')
 change('"Send failed: " + err + " — kept in Outbox."',msg('sendError','MailMessages.message(err)'))
 change('(nameFor(m.frompublickey) + " sent you")',msg('sentYou','nameFor(m.frompublickey)'))
 change('sel[1] + "   ·   balance " + sel[2]',msg('tokenBalance','sel[1]','sel[2]'))
 change('"You\'ll receive at: " + (myPayaddr.isEmpty() ? "(getting your address…)" : myPayaddr)',msg('youReceive','myPayaddr.isEmpty() ? '+msg('gettingAddress')+' : myPayaddr'))
 change('"Send funds to " + nameFor(otherKey)',msg('sendTo','nameFor(otherKey)'))
 change('"Enter " + nameFor(otherKey) + "\'s Minima receiving address (0x… or Mx…)."',msg('enterAddress','nameFor(otherKey)'))
 change('"Send " + amountStr + " " + tokenname + "?"',msg('confirmSend','amountStr','tokenname'))
 change(r'"To " + nameFor(otherKey) + "\n" + payaddr + "\n\nThis sends real funds and cannot be undone."',msg('confirmFunds','nameFor(otherKey)','payaddr'))
 change('"Sending " + amountStr + " " + tokenname',msg('sending','amountStr','tokenname'))
 change('"Couldn\'t validate the recipient address — not sending.\\n" + m','Language.join('+msg('validateFailed')+', "\\n", MailMessages.message(m))')
 change(r'"The node rejected the payment.\n" + j.optString("error", "")',msg('paymentRejected','j.optString("error", "")'))
 change('"Sent " + amountStr + " " + tokenname + " to " + nameFor(otherKey) + "."',msg('paid','amountStr','tokenname','nameFor(otherKey)'))
 change('"Payment failed: " + m',msg('paymentError','MailMessages.message(m)'))
 change(r'message + (ok && txid != null && !txid.isEmpty() ? "\n\nTx: " + txid : "")','Language.join(message, ok && txid != null && !txid.isEmpty() ? Language.join("\\n\\n", '+msg('tx','txid')+') : "")')
 change('"Image send failed: " + e + " — kept in Outbox."',msg('imageError','MailMessages.message(e)'))
 change('"Backup failed: " + e.getMessage()',msg('backupError','MailMessages.error(e)'))
 start=s.index('        t.setText("Minima Mail is on-chain');end=s.index(');',start)+2
 s=s[:start]+'        t.setText('+msg('help')+');'+s[end:]
 # The host owns its embedded node. Retry setup in place without launching the standalone companion settings.
 change('"Open Minima Core → Apps, enable Minima Mail and grant it Admin access."', msg('nodeRetry'))
 change('void showSetupMessage(String message)', 'void showSetupMessage(CharSequence message)')
 change('"Mail could not save your identity. Try again or restore a backup from Your key."', msg('identitySaveFailed'))
 # Use native locale-sensitive timestamps and the already tested relative-time implementation.
 start=s.index('    private static String clock(');end=s.index('\n    private int dp(',start)
 s=s[:start]+'''    private static CharSequence clock(long ms) { return Language.stamp(ms, "Hm"); }
    private static CharSequence dateLabel(long ms) {
        long now = System.currentTimeMillis();
        if (sameDay(ms, now)) return Language.msg(l10n_mail_today);
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (sameDay(ms, yesterday.getTimeInMillis())) return Language.msg(l10n_mail_yesterday);
        return Language.stamp(ms, "yMMMd");
    }
    private static CharSequence rel(long ms) {
        if (ms <= 0) return "";
        long age = System.currentTimeMillis() - ms;
        if (age < 3600000) return Language.since(ms);
        if (age < 86400000) return clock(ms);
        if (age < 7 * 86400000L) return Language.since(ms);
        return Language.stamp(ms, "MMMd");
    }
''' + s[end:]
 change('count == 1 ? "New mail" : count + " new messages"','Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_mail_new_messages, count)')
 dynamic={'youKey','block','chain','checking','checked','youPreview','toSubject','confirming','deleteContact','sendError','sentYou','tokenBalance','youReceive','sendTo','enterAddress','confirmSend','confirmFunds','sending','paymentRejected','paid','paymentError','tx','imageError','backupError','help','today','yesterday','newMessages','back','menu','more','attach','rejectedSend','encryptFailed','gettingAddress','onChain'}
 for key in labels:
  if key in dynamic:continue
  literal=json.dumps(catalog[labels[key]]['en'],ensure_ascii=False)
  # Some labels have already been consumed by a composed descriptor above.
  if literal in s:change(literal,msg(key))
  elif key not in {'confirmed', 'identitySaveFailed', 'seedHint', 'createIdentity', 'identityHelp', 'create', 'restoreInstead', 'toHint', 'validKeyFirst', 'theirKeyHint', 'contactInvalid', 'nodeNotReady', 'nodeRetry'}:raise AssertionError(('Unused Mail label',key))
 replacements=[('navItem(int iconRes, String label','navItem(int iconRes, CharSequence label'),('buildStatusList(String title','buildStatusList(CharSequence title'),('String label; int labelColor','CharSequence label; int labelColor'),('String label = null; int labelColor','CharSequence label = null; int labelColor'),('final String dateHeader','final CharSequence dateHeader'),('MailMessage m, String dateHeader','MailMessage m, CharSequence dateHeader'),('String header = null','CharSequence header = null'),('fieldRow(String label','fieldRow(CharSequence label'),('fieldChip(int iconRes, String label','fieldChip(int iconRes, CharSequence label'),('attachChip(int iconRes, String label','attachChip(int iconRes, CharSequence label'),('settingsGroup(String s)','settingsGroup(CharSequence s)'),('settingsRow(String title, String detail)','settingsRow(CharSequence title, CharSequence detail)'),('segOption(String label','segOption(CharSequence label'),('payResult(boolean ok, String message','payResult(boolean ok, CharSequence message'),('progressDialog(String title, String msg)','progressDialog(CharSequence title, CharSequence msg)'),('header(String title','header(CharSequence title'),('label(String s)','label(CharSequence s)'),('input(String hint)','input(CharSequence hint)'),('accentButton(String s)','accentButton(CharSequence s)'),('textButton(String s)','textButton(CharSequence s)'),('accentOutlineButton(String s)','accentOutlineButton(CharSequence s)'),('toast(String s)','toast(CharSequence s)'),('copy(String text, String label)','copy(String text, CharSequence label)'),('private String previewText','private CharSequence previewText'),('private String nameFor','private CharSequence nameFor'),('String name = nameFor','CharSequence name = nameFor'),('String toName = nameFor','CharSequence toName = nameFor'),('String otherName = nameFor','CharSequence otherName = nameFor'),('avatar(String key, String name','avatar(String key, CharSequence name'),('Avatars.view(this, key, name, sizeDp)','Avatars.view(this, key, name.toString(), sizeDp)'),('drawSwipeLabel(Canvas c, String text','drawSwipeLabel(Canvas c, CharSequence text'),('c.drawText(text, x, y, tp)','c.drawText(text.toString(), x, y, tp)'),('String[] items = {archivedView','CharSequence[] items = {archivedView'),('s.toUpperCase(java.util.Locale.ENGLISH)','Language.upper(s)')]
 for old,new in replacements:change(old,new)
 change('o.setPrompt(isAddress ? '+msg('scanMailAddress')+' : '+msg('scanMailKey')+');','o.setPrompt((isAddress ? '+msg('scanMailAddress')+' : '+msg('scanMailKey')+').toString());')
 # Bound the existing factories, including raw text. Strings are literal and are never looked up.
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s)
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setHint\(',r'Language.hint(\1, ',s)
 change('        if (click != null) iv.setOnClickListener(click);','''        int description = res == R.drawable.pm_mail_ic_back ? l10n_mail_back
                : res == R.drawable.pm_mail_ic_menu ? l10n_mail_menu
                : res == R.drawable.pm_mail_ic_dots ? l10n_mail_more
                : res == R.drawable.pm_mail_ic_plus ? l10n_mail_attach : l10n_mail_close;
        Language.description(iv, Language.msg(description));
        if (click != null) iv.setOnClickListener(click);''')
 change('fab.setOnClickListener(v -> push(buildCompose(null, null)));','Language.description(fab, '+msg('newMessage')+');\n        fab.setOnClickListener(v -> push(buildCompose(null, null)));')
 change('send.setLayoutParams(slp);','send.setLayoutParams(slp);\n        Language.description(send, '+msg('send')+');')
 # Keep Minima orange for fills; the paper palette needs a darker orange for small text.
 s=s.replace('setTextColor(Design.ACCENT)', 'setTextColor(Design.ACCENT_TEXT)')
 s=s.replace('sel ? Design.ACCENT : Design.TEXT', 'sel ? Design.ACCENT_TEXT : Design.TEXT')
 p.write_text(s)
 # The wrapper and DirectNodeApi provide these exact diagnostics. Remote error fields stay literal.
 spec=json.loads((project/'localization/vestr-errors.json').read_text())
 spec['fixed']['NOT_ENABLED']='mail.nodeRetry'
 spec['fixed']['the node rejected the send']='mail.rejectedSend'
 spec['fixed'].update({'sign failed':'mail.signFailed','seal failed':'mail.sealFailed'})
 spec['patterns']=[{'template':'encrypt failed: {0}','key':'mail.encryptFailed','kinds':['diagnostic']}, {'template':'seal error: {0}','key':'mail.sealError','kinds':['diagnostic']}]
 loader=importlib.util.spec_from_file_location('mail_messages',project/'scripts/adapt-pandamonium-wallet-error-languages.py');module=importlib.util.module_from_spec(loader);loader.loader.exec_module(module)
 # Keep the generator schema from the existing wallet diagnostic builder.
 source=module.build_source(spec,catalog).replace('com.eurobuddha.wallet','com.eurobuddha.mail').replace('WalletMessages','MailMessages').replace('Explicit wallet failures only.','Explicit Mail local failures only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-mail-languages.py')
 (folder/'MailMessages.java').write_text(source)
