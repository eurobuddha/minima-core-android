    private void publishSalon(TextView status) {
        if (announcement != null) { publicationStatus(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_busy)); return; }
        if (!nodeUp || !SalonStore.hasIdentity(this) || SalonStore.get(this, "profileUrl").isEmpty()) {
            publicationStatus(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_need_node)); return;
        }
        final String owner;
        try { owner = backupOwner(); } catch (Exception unavailable) { identityUnavailable(); return; }
        final String url = SalonStore.get(this, "profileUrl");
        final int epoch = renderEpoch;
        final Object request = new Object();
        announcement = request;
        publicationStatus(status, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_announcing));
        final SalonRegistry.Announced result = (ok, detail) -> runOnUiThread(() -> {
            if (announcement != request) return;
            announcement = null;
            if (isFinishing() || isDestroyed()) return;
            try { if (!owner.equals(backupOwner()) || !url.equals(SalonStore.get(this, "profileUrl"))) return; }
            catch (Exception unavailable) { return; }
            publicationStatus(epoch == renderEpoch ? status : null, Language.msg(ok
                    ? com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_submitted
                    : com.eurobuddha.minimacore.i18n.R.string.l10n_salon_publish_unknown));
        });
        try { SalonRegistry.announce(node, SalonStore.get(this, "tokenid"), url, SalonStore.get(this, "handle"), result); }
        catch (RuntimeException unavailable) { result.done(false, ""); }
    }
