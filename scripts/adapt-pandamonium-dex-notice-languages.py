"""Reuse PandaPools service refresh and AtomiX notification/channel bindings."""
from pathlib import Path

def adapt(root):
 base=root/'pandadex/src/main/java/com/eurobuddha/pandadex'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('PandaDEX notification source drift',old,s.count(old));return s.replace(old,new)
 imports='package com.eurobuddha.pandadex;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;'
 p=base/'Notifier.java';s=change(p.read_text(),'package com.eurobuddha.pandadex;',imports)
 s=change(s,'''        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Trade alerts",
                NotificationManager.IMPORTANCE_DEFAULT));''','''        NotificationChannel channel = nm.getNotificationChannel(CH_ALERT);
        if (channel == null) channel = new NotificationChannel(CH_ALERT,
                Language.text(c, l10n_dex_notice_alerts), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setName(Language.text(c, l10n_dex_notice_alerts));
        nm.createNotificationChannel(channel);''')
 for old,new in [('.setContentTitle(title)','.setContentTitle(DexMessages.message(title))'),('.setContentText(body)','.setContentText(DexMessages.message(body))'),('.bigText(body)','.bigText(DexMessages.message(body))')]:s=change(s,old,new)
 p.write_text(s)
 p=base/'DexKeepAliveService.java';s=change(p.read_text(),'package com.eurobuddha.pandadex;',imports)
 pools=(root/'pandapools/src/main/java/com/eurobuddha/pandapools/PoolKeepAliveService.java').read_text()
 a=pools.index('    @Override public void onConfigurationChanged(');b=pools.index('    @Override public void onDestroy()',a)
 s=change(s,'    @Override public void onDestroy() {',pools[a:b]+'    @Override public void onDestroy() {')
 s=change(s,'''            if (nm != null) nm.createNotificationChannel(
                    new NotificationChannel(CH_FG, "Order watcher", NotificationManager.IMPORTANCE_LOW));''','''            if (nm != null) {
                NotificationChannel channel = nm.getNotificationChannel(CH_FG);
                if (channel == null) channel = new NotificationChannel(CH_FG,
                        Language.text(this, l10n_dex_notice_watcher), NotificationManager.IMPORTANCE_LOW);
                channel.setName(Language.text(this, l10n_dex_notice_watcher));
                nm.createNotificationChannel(channel);
            }''')
 s=change(s,'.setContentText("Watching the order book — keeping your GTC orders alive")','.setContentText(Language.text(this, l10n_dex_notice_watching))')
 p.write_text(s)
