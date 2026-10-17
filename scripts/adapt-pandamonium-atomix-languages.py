'AtomiX shell/wallet bindings: keep drafts, protocol values and financial engines literal.'
from pathlib import Path
import json,re

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/atomix-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text())
 folder=root/'atomix/src/main/java/com/eurobuddha/atomix';p=folder/'MainActivity.java';s=p.read_text()
 def msg(key,*args):
  resource='l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[key]).replace('.','_').lower()
  return 'Language.msg('+resource+('' if not args else ', '+', '.join(args))+')'
 def change(old,new,count=None):
  nonlocal s
  assert old in s,('AtomiX source drift',old)
  if count is not None:assert s.count(old)==count,(old,s.count(old))
  s=s.replace(old,new)
 # Find one Java method, ignoring braces inside comments/literals. This constrains phrase substitutions
 # to reviewed display methods; storage, commands, order payloads, logs and CSV are not phrase-matched.
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
  assert old in part,('AtomiX method drift',old);return part.replace(old,new)
 def bodyarg(part,call,new):
  start=part.index(call)+len(call);end=part.index(');',start)
  return part[:start]+new+part[end:]
 methods=['toggleTheme','switchCurrencyDialog','buildTabBar','renderMismatchBlocker','buildHeader','mainnetChip','renderWalletTab','receiveDialog','sendDialog','sendReviewDialog','exportKeyDialog','revealKeyDialog','showWelcome','showInfo','showText','minimaBreakdown']
 literal=r'"(?:\\.|[^"\\])*"'
 def collapse(part):
  pattern=re.compile(literal+r'(?:\s*\+\s*'+literal+r')+')
  return pattern.sub(lambda m:json.dumps(''.join(json.loads(x)for x in re.findall(literal,m[0])),ensure_ascii=False),part)
 for name in methods:edit(name,collapse)
 def switch(part):
  start=part.index('.setMessage(')+len('.setMessage(');end=part.index('\n            .setPositiveButton',start)
  part=part[:start]+msg('switchBody','target.coinLabel','target.coinLabel','TradingContext.active().coinLabel')+')'+part[end:]
  return rep(rep(part,'"Switch to " + target.coinLabel + "?"',msg('switchTitle','target.coinLabel')),'"Switch to " + target.coinLabel',msg('switchAction','target.coinLabel'))
 edit('switchCurrencyDialog',switch)
 def header(part):
  part=bodyarg(part,'ccyPill.setContentDescription(',msg('tradingDescription','TradingContext.active().coinLabel','target.coinLabel'))
  part=rep(part,'"Pill glow: " + g.name().toLowerCase(java.util.Locale.ROOT)',msg('glow','glowLabel(g)'))
  part=bodyarg(part,'sub.setText(','Language.join("v", BuildConfig.VERSION_NAME, chainBlock > 0 ? Language.join("  ·  ", '+msg('block','chainBlock')+') : "", "  ·  ", '+msg('realFunds')+')')
  part=rep(part,'        TradingContext target =','        LinearLayout marketHeader = new LinearLayout(this);\n        marketHeader.setOrientation(LinearLayout.HORIZONTAL);\n        marketHeader.setGravity(Gravity.CENTER_VERTICAL);\n        TradingContext target =')
  part=rep(part,'header.addView(ccyPill, clp);','marketHeader.addView(ccyPill, clp);')
  part=rep(part,'        header.addView(mainnetChip());\n        col.addView(header);','        col.addView(header);\n        marketHeader.addView(mainnetChip());\n        col.addView(marketHeader);')
  part=rep(part,'theme.setOnClickListener','Language.description(theme, Design.isDark() ? '+msg('light')+' : '+msg('dark')+');\n        theme.setMinimumHeight(dp(48)); theme.setMinimumWidth(dp(48));\n        theme.setOnClickListener')
  part=rep(part,'ccyPill.setOnClickListener','ccyPill.setMinimumHeight(dp(48));\n        ccyPill.setOnClickListener')
  return part.replace('dp(44), dp(44)','dp(48), dp(48)')
 edit('buildHeader',header)
 def mismatch(part):
  start=part.index('        StringBuilder detail =');end=part.index('        TextView det =',start)
  part=part[:start]+'''        Language.TextBuilder detail = new Language.TextBuilder();
        if (IdentityWatch.minimaMismatch()) detail.append('''+msg('orphaned','IdentityWatch.orphanedPk()')+''').append("\\n\\n");
        if (IdentityWatch.ethMismatch()) detail.append('''+msg('stale','IdentityWatch.staleEth()','IdentityWatch.nodeEth()')+''');
'''+part[end:]
  part=rep(part,'detail.toString().trim()','detail.build()')
  return bodyarg(part,'warn.setText(',msg('mismatchWarning','unsettled'))
 edit('renderMismatchBlocker',mismatch)
 def wallet(part):
  part=rep(part,'minimaBreakdown() + "  ·  long-press for coins"','Language.join(minimaBreakdown(), "  ·  ", '+msg('coinHold')+')')
  part=rep(part,'count + " coins — buys are being declined. Consolidate."',msg('consolidateBlocked','count'))
  part=rep(part,'"Consolidate ▸ " + count + " coins into fewer"',msg('consolidateOffer','count'))
  part=rep(part,'String addrLine =','CharSequence addrLine =')
  start=part.index('            LinearLayout.LayoutParams gap =');end=part.index('            col.addView(walletActions);',start)
  part=part[:start]+'''            walletActions.setOrientation(LinearLayout.VERTICAL);
            for (TextView[] pair : new TextView[][]{{refreshBal, fund}, {send, exportKey}}) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (TextView action : pair) {
                    action.setMinimumHeight(dp(48));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(dp(2), dp(3), dp(2), dp(3));
                    row.addView(action, lp);
                }
                walletActions.addView(row);
            }
'''+part[end:]
  return part
 edit('renderWalletTab',wallet)
 def breakdown(part):
  part=part.replace('private String minimaBreakdown()','private CharSequence minimaBreakdown()')
  start=part.index('        String ago =');part=part[:start]+'''        CharSequence ago = lastMinimaUpdate == 0 ? '''+msg('never')+''' : Language.since(lastMinimaUpdate);
        return '''+msg('balanceDetails','Util.fmt5(minimaConfirmed)','locked','Util.fmt5(minimaUnconfirmed)','minimaCoins','ago')+''';
    }''';return part
 edit('minimaBreakdown',breakdown)
 def receive(part):
  part=rep(part,'"Same address on all EVM networks — fund it with " + net.label + " ETH and tokens."',msg('fundHelp','net.label'))
  return rep(part,'"Receive / Fund · " + net.label',msg('fundTitle','net.label'))
 edit('receiveDialog',receive)
 def review(part):
  part=rep(part,'"Review — Send " + (eth ? "ETH" : "USDT")',msg('reviewTitle','eth ? "ETH" : "USDT"'))
  start=part.index('.setMessage(')+len('.setMessage(');end=part.index('\n                .setPositiveButton',start)
  part=part[:start]+msg('reviewBody','amt','eth ? "ETH" : "USDT"','to','feeEth')+')'+part[end:]
  return rep(part,'"Send failed: " + e.getMessage()',msg('sendFailed','e.getMessage()'))
 edit('sendReviewDialog',review)
 def welcome(part):
  start=part.index('.setMessage(')+len('.setMessage(');end=part.index('\n                .setPositiveButton',start)
  return part[:start]+msg('welcomeBody','ccy()','ccy()','ccy()')+')'+part[end:]
 edit('showWelcome',welcome)
 # Replace only reviewed static labels inside the listed UI methods.
 lookup={catalog[v]['en']:msg(k)for k,v in labels.items()if '{'not in catalog[v]['en']}
 def static_labels(part):
  return re.sub(literal,lambda m:lookup.get(json.loads(m[0]),m[0]),part)
 for name in methods:edit(name,static_labels)
 change('private static final String[] TAB_LABELS = {"Swap", "Wallet", "Activity", "Market", "OTC"};','private static final CharSequence[] TAB_LABELS = {'+', '.join(msg(k)for k in ['swap','wallet','activity','market','otc'])+'};')
 for name,args in [('walletCard','String title, String big, String sub'),('kv','String k, String val'),('button','String label'),('dimNote','String s'),('showInfo','String title, String body'),('showText','String title, String body'),('toast','String s')]:
  change(name+'('+args,name+'('+args.replace('String ','CharSequence '))
 change('copy(String text, String toast)','copy(String text, CharSequence toast)')
 change('private String nodeOfflineText() {\n        return node == null ? NodeApi.offlineMessage(NodeApi.Offline.UNREACHABLE, false) : node.offlineMessage();','private CharSequence nodeOfflineText() {\n        if (node != null && node.offline() == NodeApi.Offline.REFUSED) return '+msg('nodeNotReady')+';\n        return node != null && node.everReplied() ? '+msg('nodeStopped')+' : '+msg('nodeWaiting')+';')
 change('t.setText(NodeApi.offlineMessage(NodeApi.Offline.UNREACHABLE, false));','t.setText(nodeOfflineText());')
 # Reuse the ETH Wallet lifecycle wrapper; no locale change rebuilds this Activity or its dialogs.
 change('import android.app.AlertDialog;','import androidx.appcompat.app.AlertDialog;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport com.eurobuddha.minimacore.i18n.LanguageDialog;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 change('android.app.AlertDialog','AlertDialog')
 change('new AlertDialog.Builder(this)','dialog()')
 change('    private AlertDialog.Builder dialog() { return new AlertDialog.Builder(this, Design.dialogTheme()); }','''    private final java.util.Set<AlertDialog> languageDialogs = new java.util.HashSet<>();
    private LanguageDialog dialog() {
        Context themed = new androidx.appcompat.view.ContextThemeWrapper(this, Design.dialogTheme());
        return new LanguageDialog(themed) {
            private android.content.DialogInterface.OnDismissListener dismissed;
            @Override public LanguageDialog setOnDismissListener(android.content.DialogInterface.OnDismissListener action) {
                dismissed = action; return this;
            }
            @Override public AlertDialog create() {
                setBackground(ContextCompat.getDrawable(themed, Design.isDark() ? R.drawable.pm_atomix_dialog_bg_dark : R.drawable.pm_atomix_dialog_bg_light));
                AlertDialog value = super.create(); languageDialogs.add(value);
                final android.content.DialogInterface.OnDismissListener callback = dismissed;
                value.setOnDismissListener(d -> { languageDialogs.remove(value); if (callback != null) callback.onDismiss(d); });
                return value;
            }
        };
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config); Language.configurationChanged(this);
    }
    private CharSequence glowLabel(Design.Glow value) {
        switch (value) {
            case OFF: return '''+msg('glowOff')+''';
            case CALM: return '''+msg('glowCalm')+''';
            case LIVELY: return '''+msg('glowLively')+''';
            default: return '''+msg('glowInsistent')+''';
        }
    }''')
 change('        super.onDestroy();','        for (AlertDialog value : new java.util.ArrayList<>(languageDialogs)) value.dismiss();\n        languageDialogs.clear();\n        super.onDestroy();',1)
 # Screenshots and recents must not reveal the private key; copied key gets the platform sensitive flag.
 def secret(part):
  part=rep(part,'        dialog()','        AlertDialog secret = dialog()')
  part=rep(part,'.show();','.create();\n        if (secret.getWindow() != null) secret.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);\n        secret.show();')
  return rep(part,'copy(pk, '+msg('keyCopied')+')','copyPrivateKey(pk)')
 edit('revealKeyDialog',secret)
 change('    private String shortAddr(','''    private void copyPrivateKey(String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText("AtomiX", value);
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        toast('''+msg('keyCopied')+''');
    }

    private String shortAddr(''')
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s)
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setHint\(',r'Language.hint(\1, ',s)
 s=s.replace('ccyPill.setContentDescription(', 'Language.description(ccyPill, ')
 s=s.replace('sel ? Design.ACCENT() : Design.DIM()', 'sel ? Design.TEXT() : Design.DIM()')
 s=s.replace('Design.ACCENT_SOFT(), Design.ACCENT());', 'Design.ACCENT_SOFT(), Design.TEXT());')
 edit('mainnetChip', lambda part: part.replace('t.setTextColor(Design.ACCENT())', 't.setTextColor(Design.TEXT())'))
 p.write_text(s)
 p=folder/'Design.java';s=p.read_text().replace('import android.content.Context;','import android.content.Context;\nimport com.eurobuddha.minimacore.i18n.Language;').replace('pill(Context c, String text,','pill(Context c, CharSequence text,').replace('t.setText(text);','Language.setText(t, text);')
 s=s.replace('pick(0xFF8B909C, 0xFF8B909C)','pick(0xFF8B909C, 0xFF5C6370)').replace('pick(0xFF6F7583, 0xFFA6ABB6)','pick(0xFF9098A6, 0xFF606775)')
 s=s.replace('return pick(TradingContext.active().onAccentDark, TradingContext.active().onAccentLight);','return TradingContext.active() == TradingContext.MXUSDT ? 0xFF0B0D12 : pick(TradingContext.active().onAccentDark, TradingContext.active().onAccentLight);')
 p.write_text(s)
 p=root/'atomix/src/main/res/values/themes.xml';s=p.read_text()
 for suffix,parent,bg,fg,dim in [('Dark','Theme.Material3.Dark.NoActionBar','#12151C','#EEF0F4','#8B909C'),('Light','Theme.Material3.Light.NoActionBar','#FFFFFF','#181B22','#6F7583')]:
  pattern=r'(<style name="pm_atomix_SwapDialog'+suffix+r'" parent=")[^"]+(">)';assert re.search(pattern,s)
  s=re.sub(pattern,r'\g<1>'+parent+r'\g<2>',s)
  needle='<style name="pm_atomix_SwapDialog'+suffix+'" parent="'+parent+'">'
  extra=''.join('\n        <item name="'+name+'">'+value+'</item>'for name,value in [('colorPrimary','#F7931A' if suffix=='Dark' else '#8F4A00'),('colorAccent','#F7931A' if suffix=='Dark' else '#8F4A00'),('colorSurface',bg),('colorSurfaceContainerHigh',bg),('colorOnSurface',fg),('colorOnSurfaceVariant',dim)])
  s=s.replace(needle,needle+extra)
 p.write_text(s)
