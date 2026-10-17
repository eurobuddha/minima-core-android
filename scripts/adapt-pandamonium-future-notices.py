"""Reuse the explicit wallet message generator for Future Cash notification display."""
from pathlib import Path
import importlib.util,json

def adapt(root):
    project=Path(__file__).resolve().parents[1]
    spec=importlib.util.spec_from_file_location('wallet_messages_builder',Path(__file__).with_name('adapt-pandamonium-wallet-error-languages.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    source=module.build_source(json.loads((project/'localization/future-messages.json').read_text()),json.loads((project/'localization/catalog.json').read_text()))
    source=source.replace('package com.eurobuddha.wallet;', 'package com.eurobuddha.futurecashnext;').replace('WalletMessages','FutureMessages')
    source=source.replace('Explicit wallet failures only.', 'Explicit Future Cash status and diagnostics only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json', 'adapt-pandamonium-future-notices.py from localization/future-messages.json')
    folder=root/'futurecash-next/src/main/java/com/eurobuddha/futurecashnext'
    (folder/'FutureMessages.java').write_text(source)
    p=folder/'Notifier.java';s=p.read_text()
    s=s.replace('package com.eurobuddha.futurecashnext;','package com.eurobuddha.futurecashnext;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
    old='''        final NotificationChannel fg = new NotificationChannel(CH_FG, "Guardian",
                NotificationManager.IMPORTANCE_MIN);
        fg.setDescription("Shows while the guardian is watching your stakes");'''
    new='''        NotificationChannel fg = nm.getNotificationChannel(CH_FG);
        if (fg == null) fg = new NotificationChannel(CH_FG, Language.text(ctx, l10n_future_guardian),
                NotificationManager.IMPORTANCE_MIN);
        fg.setName(Language.text(ctx, l10n_future_guardian));
        fg.setDescription(Language.text(ctx, l10n_future_notice_watching));'''
    assert old in s;s=s.replace(old,new)
    old='''        final NotificationChannel al = new NotificationChannel(CH_ALERT, "Stake alerts",
                NotificationManager.IMPORTANCE_DEFAULT);
        al.setDescription("Collections, rescues, and anything needing your attention");'''
    new='''        NotificationChannel al = nm.getNotificationChannel(CH_ALERT);
        if (al == null) al = new NotificationChannel(CH_ALERT, Language.text(ctx, l10n_future_notice_alerts),
                NotificationManager.IMPORTANCE_DEFAULT);
        al.setName(Language.text(ctx, l10n_future_notice_alerts));
        al.setDescription(Language.text(ctx, l10n_future_notice_alerts_help));'''
    assert old in s;s=s.replace(old,new)
    old='''        final Notification n = new NotificationCompat.Builder(ctx, CH_ALERT)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))'''
    new='''        final CharSequence displayTitle = FutureMessages.message(title);
        final CharSequence displayBody = FutureMessages.message(body);
        final Notification n = new NotificationCompat.Builder(ctx, CH_ALERT)
                .setContentTitle(displayTitle)
                .setContentText(displayBody)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(displayBody))'''
    assert old in s;s=s.replace(old,new);p.write_text(s)
    p=folder/'GuardianService.java';s=p.read_text()
    s=s.replace('package com.eurobuddha.futurecashnext;','package com.eurobuddha.futurecashnext;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
    old='''        final String text = auto
                ? "Watching your stakes — collecting matured ones, rescuing at-risk ones"
                : "Rescuing at-risk stakes only. Auto-collect is OFF — matured clean stakes are "
                  + "NOT collected until you turn it on in the app.";'''
    assert old in s;s=s.replace(old,'''        final String text = Language.text(ctx, auto ? l10n_future_notice_auto_on : l10n_future_notice_auto_off);''')
    s=s.replace('.setContentTitle("Future Cash guardian")','.setContentTitle(Language.text(ctx, l10n_future_notice_title))')
    old='    private volatile boolean started = false;';assert old in s
    s=s.replace(old,old+'\n    private boolean foregroundActive = false;')
    old='''            return true;
        } catch (Exception e) {''';assert s.count(old)==1
    s=s.replace(old,'''            foregroundActive = true;
            return true;
        } catch (Exception e) {''')
    old='    @Override public void onDestroy() {\n        super.onDestroy();';assert old in s
    s=s.replace(old,'''    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Notifier.ensureChannels(this);
        if (foregroundActive) refreshNotification(this);
    }

    @Override public void onDestroy() {
        foregroundActive = false;
        super.onDestroy();''')
    s=s.replace('    private void stopGracefully() {','    private void stopGracefully() {\n        foregroundActive = false;')
    p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text()
    old='private void toast(CharSequence s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }'
    new='private void toast(CharSequence s) { runOnUiThread(() -> Toast.makeText(this, s instanceof String ? FutureMessages.message((String) s) : s, Toast.LENGTH_LONG).show()); }'
    assert old in s;s=s.replace(old,new)
    old='msg = Language.join(err, " ",'
    assert old in s;s=s.replace(old,'msg = Language.join(FutureMessages.message(err), " ",')

    old='c.addView(Ui.copyable(this, r.address, Design.DIM(), 10));'
    new='if ("(address unavailable)".equals(r.address))\n                c.addView(Ui.text(this, Language.msg(l10n_future_address_unavailable), Design.DIM(), 10, false));\n            else c.addView(Ui.copyable(this, r.address, Design.DIM(), 10));'
    assert s.count(old)==1;s=s.replace(old,new)
    for index in [0,1]:
        old='Ui.text(this, parts['+str(index)+'],'
        assert s.count(old)==1
        s=s.replace(old,'Ui.text(this, FutureMessages.message(parts['+str(index)+']),')
    old='            tabsBar.addView(t);';assert s.count(old)==1
    s=s.replace(old,'''            // Keep the selected label visible after a tab change or a longer translation.
            // requestRectangleOnScreen reuses the existing PandaDEX scrolling approach; no focus move.
            if (active) t.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    view.post(() -> {
                        if (view.isAttachedToWindow() && tab.equals(id))
                            view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), true);
                    }));
            tabsBar.addView(t);''')
    p.write_text(s)


    # Android's requestRectangleOnScreen clamps against unpadded width. Keep the
    # same inset as outer margins so the selected tab can be fully revealed.
    p=root/'futurecash-next/src/main/res/layout/pm_futurecash_next_activity_main.xml'
    s=p.read_text()
    old='<HorizontalScrollView android:layout_width="match_parent" android:layout_height="wrap_content" android:scrollbars="none" android:paddingStart="16dp" android:paddingEnd="16dp"'
    new=old.replace('android:paddingStart=', 'android:layout_marginStart=').replace('android:paddingEnd=', 'android:layout_marginEnd=')
    assert s.count(old)==1
    p.write_text(s.replace(old,new))

if __name__=='__main__':
    import sys
    adapt(Path(sys.argv[1]))
