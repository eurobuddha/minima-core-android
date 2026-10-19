    /** Called after Settings passphrase verification and the explicit typed RESET confirmation. */
    void resetWallet() {
        final java.util.function.BooleanSupplier ready = sessionAuthorization();
        if (!ready.getAsBoolean()) { toast(Language.msg(l10n_wallet_session_changed)); return; }
        final SeedVault vault = mVault;
        final java.util.function.BooleanSupplier authorized = vault.authorization();
        final java.util.function.BooleanSupplier connection = mUiSession;
        final android.content.Context app = getApplicationContext();
        runWalletWork(() -> {
            if (connection != mUiSession || !connection.getAsBoolean() || !authorized.getAsBoolean())
                throw new SeedVault.SigningNotAllowedException(Language.text(app, l10n_wallet_session_changed));
            if (!new SweepPending(app).transactions().isEmpty() || !NftView.pendingCoinIds().isEmpty())
                throw new IllegalStateException(Language.text(app, l10n_wallet_reset_pending));
            BiometricUnlock.disable(app);
            mSession.resetWallet();
        }, () -> {
            stopWalletConnection();
            toast(Language.msg(l10n_wallet_reset_done));
            recreate();
        }, error -> toast(Language.msg(l10n_wallet_reset_failed, WalletMessages.error(error))));
    }
