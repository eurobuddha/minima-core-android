"""Casino shell/House/Play display bindings; retain all betting state and raw stored history."""
from pathlib import Path
import json,re,importlib.util,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];folder=root/'casino/src/main/java/com/eurobuddha/casino'
 catalog=json.loads((project/'localization/catalog.json').read_text());spec=json.loads((project/'localization/casino-messages.json').read_text())
 def change(s,old,new,count=1):
  assert s.count(old)==count,('Casino source drift',old,s.count(old))
  return s.replace(old,new)
 imports='\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;\n'
 def read(name):
  p=folder/(name+'.java');return p,change(p.read_text(),'package com.eurobuddha.casino;','package com.eurobuddha.casino;'+imports)
 def save(p,s):
  s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*)\.setText\(',r'CasinoMessages.text(\1, ',s);p.write_text(s)
 build=importlib.util.spec_from_file_location('casino_messages_builder',project/'scripts/adapt-pandamonium-wallet-error-languages.py');m=importlib.util.module_from_spec(build);build.loader.exec_module(m)
 source=m.build_source(spec,catalog).replace('com.eurobuddha.wallet','com.eurobuddha.casino').replace('WalletMessages','CasinoMessages').replace('Explicit wallet failures only.','Explicit Casino display labels only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-casino-languages.py from localization/casino-messages.json')
 source=change(source,'    public static CharSequence message(String value)', '''    public static void text(android.widget.TextView view, CharSequence value) {
        Language.setText(view, message(value));
    }
    public static CharSequence message(CharSequence value) {
        return value instanceof String ? message((String)value) : value == null ? "" : value;
    }
    public static CharSequence message(String value)''')
 source=source.replace('(?:txnimport|txnbasics|txnpost)', '(?:txncreate|txninput|txnoutput|txnstate|txnsign|txnbasics|txnpost|txndelete)')
 source=change(source,'    private CasinoMessages() {}', '''    private CasinoMessages() {}
    public static CharSequence logLine(String line) {
        int split = line.indexOf("  ");
        return split < 0 ? line : Language.join(line.substring(0, split + 2), message(line.substring(split + 2)));
    }''')
 (folder/'CasinoMessages.java').write_text(source)
 p,s=read('Ui');s=s.replace('String s,','CharSequence s,').replace('String s)','CharSequence s)')
 s=change(s,'s.toUpperCase()','Language.upper(CasinoMessages.message(s))',2);save(p,s)
 p,s=read('MainActivity')
 s=change(s,'setContentView(R.layout.pm_casino_activity_main);','setContentView(R.layout.pm_casino_activity_main);\n        Language.apply(findViewById(R.id.pm_casino_main));')
 s=change(s,'new String[]{"PLAY", "HOUSE", "MY BETS", "HISTORY"}','new CharSequence[]{Language.upper(CasinoMessages.message("PLAY")), Language.upper(CasinoMessages.message("HOUSE")), Language.upper(CasinoMessages.message("MY BETS")), Language.upper(CasinoMessages.message("HISTORY"))}')
 s=change(s,'private void updateSoundBtn() { soundBtn.setText(Theme.sound() ? "SND" : "MUTE"); }','private void updateSoundBtn() { soundBtn.setText(Theme.sound() ? "SND" : "MUTE"); Language.description(soundBtn, Language.msg(Theme.sound() ? l10n_casino_sound_on : l10n_casino_sound_off)); }')
 s=change(s,'ccyBtn.setText(Currency.label());','ccyBtn.setText(Currency.label());\n            Language.description(ccyBtn, Language.msg(l10n_casino_currency_help, Currency.label()));')
 s=change(s,'Toast.makeText(this, msg,','Toast.makeText(this, CasinoMessages.message(msg),')
 s=change(s,'(type == LOG_WARN ? "⏳ " : type == LOG_ERR ? "✕ " : type == LOG_OK ? "✓ " : "› ") + msg','Language.join(type == LOG_WARN ? "⏳ " : type == LOG_ERR ? "✕ " : type == LOG_OK ? "✓ " : "› ", CasinoMessages.message(msg))')
 s=change(s,'new androidx.appcompat.app.AlertDialog.Builder(this)','languageDialog()')
 for old,new in [('.setTitle("Activity log")','.setTitle(CasinoMessages.message("Activity log"))'),('.setMessage(sb.toString())','.setMessage(CasinoMessages.message(sb.toString()))'),('.setPositiveButton("Close", null)','.setPositiveButton(CasinoMessages.message("Close"), null)')]:s=change(s,old,new)
 hook='''    private final java.util.Set<androidx.appcompat.app.AlertDialog> languageDialogs = new java.util.HashSet<>();
    private com.eurobuddha.minimacore.i18n.LanguageDialog languageDialog() {
        return new com.eurobuddha.minimacore.i18n.LanguageDialog(this) {
            @Override public androidx.appcompat.app.AlertDialog create() {
                androidx.appcompat.app.AlertDialog value = super.create();
                languageDialogs.add(value);
                value.setOnDismissListener(d -> languageDialogs.remove(value));
                return value;
            }
        };
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        // Rebind the existing pages; no rebuild, node read, secret generation or bet action.
        if (pager != null && pager.getAdapter() != null) for (int i=0;i<tabs.getTabCount();i++)
            tabs.getTabAt(i).setText(pager.getAdapter().getPageTitle(i).toString());
    }

'''
 s=change(s,'    public static final int TAB_PLAY',hook+'    public static final int TAB_PLAY')
 s=change(s,'        super.onDestroy();','        for (androidx.appcompat.app.AlertDialog value : new java.util.ArrayList<>(languageDialogs)) value.dismiss();\n        languageDialogs.clear();\n        super.onDestroy();')
 save(p,s)
 p=folder/'MainPager.java';s=p.read_text().replace('String[] titles','CharSequence[] titles');p.write_text(s)
 p,s=read('HouseView')
 s=change(s,'"Bet amount (" + Currency.label() + ")"','Language.msg(l10n_casino_bet_amount, Currency.label())')
 s=change(s,'g.icon + "\\n" + g.key.toUpperCase() + "\\n" + g.oddsAgainst() + ":1"','Language.join(g.icon, "\\n", CasinoMessages.message(g.key.toUpperCase(java.util.Locale.ROOT)), "\\n", g.oddsAgainst(), ":1")')
 s=change(s,'''"You lock " + Currency.show(Util.miniNum(stake)) + " · player stakes " + Currency.show(Util.miniNum(bet))
                + "\\nPot " + Currency.show(Util.miniNum(pot)) + " · player wins " + Currency.show(Util.miniNum(win))
                + " at " + selected.oddsAgainst() + ":1 (1/" + selected.range + " chance)"''','''Language.msg(l10n_casino_house_summary, Currency.show(Util.miniNum(stake)), Currency.show(Util.miniNum(bet)),
                Currency.show(Util.miniNum(pot)), Currency.show(Util.miniNum(win)), selected.oddsAgainst(), selected.range)''')
 save(p,s)
 p,s=read('PlayView')
 s=change(s,'g.icon + "  " + g.name','Language.join(g.icon, "  ", CasinoMessages.message(g.name))')
 s=change(s,'''"Stake " + Currency.show(b.betAmount, b.tokenid()) + " · pot "
                        + Currency.show(b.totalAmount, b.tokenid()) + " · win "
                        + Currency.show(Util.miniNum(Util.dec(b.betAmount).multiply(java.math.BigDecimal.valueOf(g.payout))), b.tokenid())''','''Language.msg(l10n_casino_stakes, Currency.show(b.betAmount, b.tokenid()), Currency.show(b.totalAmount, b.tokenid()),
                        Currency.show(Util.miniNum(Util.dec(b.betAmount).multiply(java.math.BigDecimal.valueOf(g.payout))), b.tokenid()))''')
 save(p,s)
 a='{http://schemas.android.com/apk/res/android}';auto='{http://schemas.android.com/apk/res-auto}';E.register_namespace('android',a[1:-1]);E.register_namespace('app',auto[1:-1])
 p=root/'casino/src/main/res/layout/pm_casino_activity_main.xml';tree=E.parse(p)
 def resource(key):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
 for v in tree.iter():
  text=v.get(a+'text')
  if text in spec['fixed']:
   key=resource(spec['fixed'][text]);v.set(a+'text','@string/'+key);v.set(a+'tag','l10n:text='+key)
  if v.get(a+'id','').endswith(('btnCcy','btnSound')):v.set(a+'minHeight','48dp');v.set(a+'minWidth','48dp')
  if v.get(a+'id','').endswith('_tabs'):v.set(auto+'tabMode','scrollable')
 E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)

 # Retain existing cards and animation objects while rebinding presentation only.
 p,s=read('MyBetsView')
 s=change(s,'g.icon + "  " + g.name + " · " + Currency.nameFor(b.tokenid())','Language.join(g.icon, "  ", CasinoMessages.message(g.name), " · ", Currency.nameFor(b.tokenid()))')
 s=change(s,'''String pickStr = b.pick >= 0
                ? (isHouse ? "Player picked  " : "Your pick  ") + g.pickLabel(b.pick)
                : "Waiting for a player to pick…";''','''CharSequence pickStr = b.pick >= 0
                ? Language.msg(isHouse ? l10n_casino_player_picked : l10n_casino_picked, CasinoMessages.message(g.pickLabel(b.pick)))
                : CasinoMessages.message("Waiting for a player to pick…");''')
 s=change(s,'''"Stake " + Currency.show(b.betAmount, b.tokenid())
                + "  ·  pot " + Currency.show(b.totalAmount, b.tokenid())
                + "  ·  win " + Currency.show(Util.miniNum(Util.dec(b.betAmount).multiply(java.math.BigDecimal.valueOf(g.payout))), b.tokenid())''','''Language.msg(l10n_casino_stakes, Currency.show(b.betAmount, b.tokenid()), Currency.show(b.totalAmount, b.tokenid()),
                Currency.show(Util.miniNum(Util.dec(b.betAmount).multiply(java.math.BigDecimal.valueOf(g.payout))), b.tokenid()))''')
 s=change(s,'private String statusText(Bet b, boolean isHouse)','private CharSequence statusText(Bet b, boolean isHouse)')
 # Keep phase rules, age and strict covenant boundary intact.
 s=change(s,'        if (b.phase >= 1 && b.coin.created >= 0) {','        CharSequence display = CasinoMessages.message(msg);\n        if (b.phase >= 1 && b.coin.created >= 0) {')
 s=change(s,'''            if (left > 0) msg += "\\n⏳ Timeout in " + left + " block" + (left == 1 ? "" : "s") + " (" + age + " elapsed)";
            else msg += b.canClaimTimeout(act.myKeys(), act.chainBlock())
                    ? "\\nTimed out — you can claim the pot."
                    : "\\nTimed out — the counterparty can claim the pot.";''','''            if (left > 0) display = Language.join(display, "\\n", Language.count(
                    com.eurobuddha.minimacore.i18n.R.plurals.l10n_casino_timeout_left,
                    (int)Math.min(Integer.MAX_VALUE, left), left, age));
            else display = Language.join(display, "\\n", CasinoMessages.message(b.canClaimTimeout(act.myKeys(), act.chainBlock())
                    ? "Timed out — you can claim the pot."
                    : "Timed out — the counterparty can claim the pot."));''')
 s=change(s,'        return msg;','        return display;')
 s=change(s,'(settled.won ? "WON +" : "LOST -") + settled.profit + " · payout confirming…"','Language.msg(l10n_casino_payout_confirm, Language.msg(settled.won ? l10n_casino_won_amount : l10n_casino_lost_amount, settled.profit))')
 s=change(s,'status.setText((iWon ? "WON +" : "LOST -") + Currency.show(Util.miniNum(profit), b.tokenid()));','status.setText(Language.msg(iWon ? l10n_casino_won_amount : l10n_casino_lost_amount, Currency.show(Util.miniNum(profit), b.tokenid())));')
 save(p,s)
 p,s=read('HistoryView')
 s=change(s,'    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());\n','')
 for imp in ['import java.text.SimpleDateFormat;\n','import java.util.Date;\n','import java.util.Locale;\n']:s=change(s,imp,'')
 s=change(s,'String caption = "Your " + n + " " + Currency.label() + " bet" + (n == 1 ? "" : "s") + ".";','CharSequence caption = Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_casino_history_count, n, n, Currency.label());')
 s=change(s,'String netTxt = "Net " + (sign < 0 ? "-" : "+") + Currency.show(Util.miniNum(net.abs()), tok);','CharSequence netTxt = Language.msg(l10n_casino_history_net, (sign < 0 ? "-" : "+") + Currency.show(Util.miniNum(net.abs()), tok));')
 s=change(s,'"No completed " + Currency.label() + " bets yet."','Language.msg(l10n_casino_history_empty, Currency.label())')
 s=change(s,'"+ " + (mine.size() - shown) + " older bets — counted in Net above, not shown here."','Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_casino_history_older, mine.size() - shown)')
 s=change(s,'r.game + "  ·  " + r.role','Language.join(CasinoMessages.message(r.game), "  ·  ", CasinoMessages.message(r.role))')
 s=change(s,'"Picked " + r.pickLabel + " · rolled " + r.resultLabel + "  ·  " + fmt.format(new Date(r.time))','Language.msg(l10n_casino_history_detail, CasinoMessages.message(r.pickLabel), CasinoMessages.message(r.resultLabel), Language.stamp(r.time, "MMMdHm"))')
 save(p,s)
 p,s=read('ResultOverlay')
 # Use the same full-width/wrap-height sizing as Ui.card so translated text measures at its final width.
 s=change(s,'ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);','ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);')
 s=change(s,'        clp.gravity = Gravity.CENTER;','        clp.gravity = Gravity.CENTER;\n        clp.leftMargin = clp.rightMargin = Ui.dp(act, 20);')
 s=change(s,'game.icon + "  " + game.name.toUpperCase()','Language.join(game.icon, "  ", Language.upper(CasinoMessages.message(game.name)))')
 s=change(s,'''(iWon ? "+" : "-") + Currency.show(profit, tokenid) + "\\nPicked "
                    + game.pickLabel(pick) + "  ·  Rolled "
                    + (result >= 0 ? game.pickLabel(result) : "—")''','''Language.msg(l10n_casino_result_detail, (iWon ? "+" : "-") + Currency.show(profit, tokenid),
                    CasinoMessages.message(game.pickLabel(pick)),
                    result >= 0 ? CasinoMessages.message(game.pickLabel(result)) : "—")''')
 save(p,s)
 p,s=read('CoinFlipView')
 s=change(s,'showHeads ? "H" : "T"','showHeads ? headsGlyph : tailsGlyph')
 s=change(s,'    private float angle = 0f;', '    private String headsGlyph, tailsGlyph;\n\n    private float angle = 0f;')
 s=change(s,'    private void init() {','    private void init() {\n        updateGlyphs();')
 s=change(s,'    /**\n     * Spin the coin', '''    private void updateGlyphs() {
        headsGlyph = Language.text(getContext(), l10n_casino_head_glyph);
        tailsGlyph = Language.text(getContext(), l10n_casino_tail_glyph);
    }

    /**
     * Spin the coin''')
 s=change(s,'    @Override protected void onDraw(Canvas cv) {','''    @Override protected void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        updateGlyphs();
        invalidate(); // redraw a settled coin without restarting or cancelling its animation
    }

    @Override protected void onDraw(Canvas cv) {''')
 save(p,s)

 # Retain the raw log history and pending state; compose display-only snapshots for the existing dialog.
 p=folder/'MainActivity.java';s=p.read_text()
 s=change(s,r'''        StringBuilder sb = new StringBuilder();
        for (String l : logLines) sb.append(l).append('\n');
        if (sb.length() == 0) sb.append("No activity yet.");''','''        Language.TextBuilder sb = new Language.TextBuilder();
        for (String l : logLines) sb.append(CasinoMessages.logLine(l)).append("\\n");
        if (logLines.isEmpty()) sb.append(CasinoMessages.message("No activity yet."));''')
 s=change(s,'.setMessage(CasinoMessages.message(sb.toString()))','.setMessage(sb.build())');p.write_text(s)

 # Follow the Future Cash channel pattern: rename an existing channel without changing user settings.
 p,s=read('TimeoutAlerts')
 s=change(s,'''        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Casino timeout claims",
                NotificationManager.IMPORTANCE_DEFAULT));''','''        NotificationChannel channel = nm.getNotificationChannel(CHANNEL);
        if (channel == null) channel = new NotificationChannel(CHANNEL, Language.text(context, l10n_casino_timeout_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setName(Language.text(context, l10n_casino_timeout_channel));
        nm.createNotificationChannel(channel);''')
 s=change(s,'String body = claims.summary() + ". Tap to open My Bets.";','CharSequence body = CasinoMessages.message(claims.summary() + ". Tap to open My Bets.");')
 s=change(s,'.setContentTitle("Casino timeout claims")','.setContentTitle(Language.text(context, l10n_casino_timeout_channel))');p.write_text(s)

 p,s=read('CasinoService')
 s=change(s,'    private int alertId = 2000;','    private int alertId = 2000;\n    private boolean foregroundActive = false;')
 s=change(s,'    @Override public void onDestroy() {\n        super.onDestroy();','''    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        createChannels();
        if (foregroundActive) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(FG_ID, foregroundNotification());
        }
    }

    @Override public void onDestroy() {
        foregroundActive = false;
        super.onDestroy();''')
 for const,english,key,importance in [('CH_FG','Casino background','background','LOW'),('CH_ALERT','Casino results','results','DEFAULT')]:
  old='''            nm.createNotificationChannel(new NotificationChannel('''+const+''', "'''+english+'''",
                    NotificationManager.IMPORTANCE_'''+importance+'''));'''
  var='foregroundChannel'if const=='CH_FG'else'alertChannel'
  new='''            NotificationChannel '''+var+''' = nm.getNotificationChannel('''+const+''');
            if ('''+var+''' == null) '''+var+''' = new NotificationChannel('''+const+''', Language.text(this, l10n_casino_'''+key+'''_channel), NotificationManager.IMPORTANCE_'''+importance+''');
            '''+var+'''.setName(Language.text(this, l10n_casino_'''+key+'''_channel));
            nm.createNotificationChannel('''+var+''');'''
  s=change(s,old,new)
 s=change(s,'            NotificationManager nm = getSystemService(NotificationManager.class);\n            NotificationChannel foregroundChannel', '            NotificationManager nm = getSystemService(NotificationManager.class);\n            if (nm == null) return;\n            NotificationChannel foregroundChannel')
 s=change(s,'''    private boolean startForegroundCompat() {
        Notification n = new NotificationCompat.Builder(this, CH_FG)''','''    private Notification foregroundNotification() {
        return new NotificationCompat.Builder(this, CH_FG)''')
 s=change(s,'.setContentText("Auto-revealing & resolving your bets")','.setContentText(Language.text(this, l10n_casino_background_watching))')
 s=change(s,'''                .setOngoing(true)
                .build();
        try {''','''                .setOngoing(true)
                .build();
    }

    private boolean startForegroundCompat() {
        Notification n = foregroundNotification();
        try {''')
 s=change(s,'            return true;\n        } catch (Exception unavailable)', '            foregroundActive = true;\n            return true;\n        } catch (Exception unavailable)')
 s=change(s,'    private void stopGracefully() {','    private void stopGracefully() {\n        foregroundActive = false;')
 s=change(s,'.setContentTitle(title)','.setContentTitle(CasinoMessages.message(title))')
 s=change(s,'.setContentText(body)','.setContentText(CasinoMessages.message(body))');p.write_text(s)
