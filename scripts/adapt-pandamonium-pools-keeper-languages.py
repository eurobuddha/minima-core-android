"""Keeper text reuses AtomiX service refresh and existing channel preference preservation."""
from pathlib import Path

def adapt(root):
 base=root/'pandapools/src/main/java/com/eurobuddha/pandapools'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('Keeper source drift',old,s.count(old));return s.replace(old,new)
 def imports(s):return change(s,'package com.eurobuddha.pandapools;','package com.eurobuddha.pandapools;\n\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 p=base/'PoolKeepAliveService.java';s=imports(p.read_text())
 s=change(s,'"Keeping your pools discoverable to other devices"','Language.text(this, l10n_pools_keeper_running)')
 s=change(s,'''            if (nm != null) nm.createNotificationChannel(
                    new NotificationChannel(CH_FG, "Pool keep-alive", NotificationManager.IMPORTANCE_LOW));''','''            if (nm != null) {
                NotificationChannel channel = nm.getNotificationChannel(CH_FG);
                if (channel == null) channel = new NotificationChannel(CH_FG,
                        Language.text(this, l10n_pools_keeper_channel), NotificationManager.IMPORTANCE_LOW);
                channel.setName(Language.text(this, l10n_pools_keeper_channel));
                nm.createNotificationChannel(channel);
            }''')
 s=change(s,'    @Override public void onDestroy() {','''    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        try {
            createChannel();
            // Reuse AtomiX's active-service guard. A locale change does not start a maintenance pass.
            if (started) {
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) manager.notify(FG_ID, fgNotification());
            }
        } catch (RuntimeException unavailable) { /* Keep the existing notification if the OS refuses an update. */ }
    }

    @Override public void onDestroy() {
        started = false;''')
 p.write_text(s)
 p=base/'StrandingWatch.java';s=imports(p.read_text())
 s=change(s,'''        NotificationChannel ch = new NotificationChannel(CH_STRAND, "Pool going dark",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Warns you before one of your pools ages out of the chain's recent window.");''','''        NotificationChannel ch = nm.getNotificationChannel(CH_STRAND);
        if (ch == null) ch = new NotificationChannel(CH_STRAND,
                Language.text(ctx, l10n_pools_keeper_warning_channel), NotificationManager.IMPORTANCE_HIGH);
        ch.setName(Language.text(ctx, l10n_pools_keeper_warning_channel));
        ch.setDescription(Language.text(ctx, l10n_pools_keeper_warning_help));''')
 a=s.index('        String title = level ==');b=s.index('        // No estimated value',a)
 s=s[:a]+'''        String title = Language.text(ctx, level == Level.URGENT ? l10n_pools_keeper_urgent_title
                : level == Level.WARN ? l10n_pools_keeper_warn_title : l10n_pools_keeper_notice_title);
        String body;
        if (quarantined) {
            body = Language.text(ctx, l10n_pools_keeper_quarantined, age);
        } else if (level == Level.URGENT) {
            body = Language.text(ctx, l10n_pools_keeper_urgent, age, CASCADE_BLOCKS);
        } else if (level == Level.WARN) {
            body = Language.text(ctx, l10n_pools_keeper_warn, age);
        } else {
            body = Language.text(ctx, l10n_pools_keeper_notice, age, PoolRefresher.REFRESH_BLOCKS);
        }
'''+s[b:]
 s=change(s,'b.addAction(0, "Re-publish", pi);','b.addAction(0, Language.text(ctx, l10n_pools_keeper_republish), pi);')
 s=change(s,'b.addAction(0, "Open PandaPools", open);','b.addAction(0, Language.text(ctx, l10n_pools_keeper_open), open);')
 p.write_text(s)
