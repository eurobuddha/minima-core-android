    /** Called by WebView's bridge thread; only a tiny result crosses Binder. */
    private void sendResult(String mode, String b64) {
        if (handoffClosed || !sending.compareAndSet(false,true)) return;
        try {
            FiltrHandoff.write(this,requestId,mode,b64);
            runOnUiThread(() -> {
                if (handoffClosed || isFinishing() || isDestroyed()) { FiltrHandoff.discard(this,requestId); return; }
                handoffSent = true;
                setResult(RESULT_OK,new Intent().putExtra(FiltrHandoff.ID,requestId).putExtra(FiltrHandoff.MODE,mode));
                finish();
            });
        } catch (Exception failure) {
            FiltrHandoff.discard(this,requestId); sending.set(false);
            runOnUiThread(() -> {
                if (!handoffClosed && !isFinishing() && !isDestroyed())
                    Toast.makeText(this,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_art_filtr_failed),Toast.LENGTH_SHORT).show();
            });
        }
    }

