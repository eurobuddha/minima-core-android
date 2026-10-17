"""Run existing Settings vault/file operations on the session-owned wallet worker."""
from pathlib import Path

def adapt(root):
    base=root/'wallet/src/main/java/com/eurobuddha/wallet'
    def edit(name,old,new,n=1):
        p=base/name;s=p.read_text();assert s.count(old)==n,('Settings worker drift',name,old,s.count(old));p.write_text(s.replace(old,new))
    main='MainActivity.java'
    edit(main,'    private Runnable mAfterWork;','    private Runnable mAfterWork;\n    private java.util.function.Consumer<Exception> mWorkFailure;\n    private volatile Exception mCompletedWorkFailure;')
    old='''    public void runWalletWork(Runnable operation, Runnable after) {
        if (mSession.work().busy()) { toast(Language.msg(l10n_wallet_busy)); return; }
        mAfterWork = after;
        mWaitingForWork = true;
        if (!mSession.work().start(operation)) { mWaitingForWork = false; return; }
        showWalletWork();
    }'''
    new='''    public void runWalletWork(Runnable operation, Runnable after) {
        runWalletWork(operation, after, null);
    }

    private void runWalletWork(Runnable operation, Runnable after,
                               java.util.function.Consumer<Exception> failed) {
        if (mSession.work().busy() || mWaitingForWork) { toast(Language.msg(l10n_wallet_busy)); return; }
        mAfterWork = after;
        mWorkFailure = failed;
        mCompletedWorkFailure = null;
        mWaitingForWork = true;
        try {
            if (!mSession.work().start(() -> {
                try { operation.run(); }
                catch (RuntimeException failure) { mCompletedWorkFailure = failure; throw failure; }
            })) {
                mWaitingForWork = false; mAfterWork = null; mWorkFailure = null;
                return;
            }
        } catch (RuntimeException rejected) {
            mWaitingForWork = false; mAfterWork = null; mWorkFailure = null;
            if (failed != null) failed.accept(rejected);
            else toast(Language.msg(l10n_wallet_operation_failed));
            return;
        }
        if (mSession.work().busy()) showWalletWork();
        else walletWorkFinished();
    }

    /** Settings work belongs to the open vault; only UI delivery belongs to this Activity. */
    public <T> void runAuthenticatedWalletWork(java.util.concurrent.Callable<T> operation,
                                              java.util.function.Consumer<T> completed,
                                              java.util.function.Consumer<Exception> failed) {
        if (mSession.work().busy()) { toast(Language.msg(l10n_wallet_busy)); return; }
        if (mSession.shouldLockOnForeground(System.currentTimeMillis())) {
            mSession.lock(); showUnlock(); return;
        }
        final java.util.function.BooleanSupplier connection = mUiSession;
        if (!sessionAuthorization().getAsBoolean()) { toast(Language.msg(l10n_wallet_session_changed)); return; }
        final SeedVault vault = mVault;
        final java.util.function.BooleanSupplier authorized = vault.authorization();
        final java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier> resultAuthorization = new java.util.concurrent.atomic.AtomicReference<>();
        runWalletWork(() -> {
            try {
                if (!authorized.getAsBoolean()) throw new SeedVault.SigningNotAllowedException("Wallet session changed");
                result.set(operation.call());
                resultAuthorization.set(vault.authorization());
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }, () -> {
            java.util.function.BooleanSupplier completedSession = resultAuthorization.getAndSet(null);
            if (connection == mUiSession && vault == mVault && completedSession != null && completedSession.getAsBoolean())
                completed.accept(result.getAndSet(null));
            else result.set(null);
        }, error -> {
            result.set(null);
            resultAuthorization.set(null);
            Throwable cause = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
            if (connection == mUiSession && failed != null)
                failed.accept(cause instanceof Exception ? (Exception)cause : error);
        });
    }'''
    edit(main,old,new)
    edit(main,'        if (!mWaitingForWork || isFinishing() || isDestroyed()) return;', '''        if (!mWaitingForWork || !mForeground || isFinishing() || isDestroyed() || mSession.work().busy()) return;
        if (mSession.shouldLockOnForeground(System.currentTimeMillis())) mSession.lock();''')
    edit(main,'        Exception failure = mSession.work().failure();', '''        java.util.function.Consumer<Exception> failed = mWorkFailure; mWorkFailure = null;
        Exception failure = mWorkOnlyScreen ? mSession.work().failure() : mCompletedWorkFailure;
        mCompletedWorkFailure = null;''')
    edit(main,'            toast("Wrong wallet passphrase".equals(failure.getMessage()) ? Language.msg(l10n_wallet_wrong_passphrase) : Language.msg(l10n_wallet_operation_failed));', '''            if (failed != null) failed.accept(failure);
            else toast("Wrong wallet passphrase".equals(failure.getMessage()) ? Language.msg(l10n_wallet_wrong_passphrase) : Language.msg(l10n_wallet_operation_failed));''')
    edit(main,'        if (mSession != null && mSession.work().busy()) return;\n        if (mVault',
        '        if (mSession != null && mSession.work().busy()) return;\n        if (mWaitingForWork) walletWorkFinished();\n        if (mVault')
    edit(main,'        if (mVault != null && !mVault.isOpen() && (mWallet != null || mKeyUses != null)) showUnlock();', '''        if (mVault != null && !mVault.isOpen() && (mWallet != null || mKeyUses != null)) showUnlock();
        else if (mVault != null && mVault.isOpen() && mWallet != null && !mUiSession.getAsBoolean()) startMain();''')
    edit(main,'        mAfterWork = null;\n        if (mWorkDialog', '        mAfterWork = null; mWorkFailure = null;\n        if (mWorkDialog')
    edit(main,'.setTitle(Language.msg(l10n_wallet_preparing))\n                .setMessage(Language.msg(l10n_wallet_preparing_body))',
         '.setTitle(Language.msg(l10n_wallet_working))\n                .setMessage(Language.msg(l10n_wallet_working_body))')
    edit(main,'                    try {\n                        if (!mVault.verifyPassphrase(pass.getText().toString())) {', '''                    final SeedVault vault = mVault;
                    final String password = pass.getText().toString(); pass.setText("");
                    runAuthenticatedWalletWork(() -> vault.verifyPassphrase(password), valid -> {
                        if (!valid) {''')
    edit(main,'                    } catch (Exception failure) { toast(Language.msg(l10n_wallet_verify_failed)); }',
         '                    }, failure -> toast(Language.msg(l10n_wallet_verify_failed)));')
    edit(main,'        BiometricUnlock.unlock(this, new BiometricUnlock.UnlockCallback() {', '''        final java.util.function.BooleanSupplier screen = mUiSession;
        BiometricUnlock.unlock(this, () -> mForeground && screen == mUiSession && !mSession.work().busy(), new BiometricUnlock.UnlockCallback() {''')

    settings='SettingsView.java'
    edit(settings,'''                    act.runWalletWork(() -> vault.setStartingKeyUses(n), () -> {''',
         '''                    act.runAuthenticatedWalletWork(() -> { vault.setStartingKeyUses(n); return null; }, ignored -> {''')
    edit(settings,'''                        refresh();
                    });
                })''', '''                        refresh();
                    }, error -> Toast.makeText(act, Language.msg(l10n_wallet_counter_update_failed), Toast.LENGTH_LONG).show());
                })''')
    for method,msg in [('confirmBrandNewSeed','signing_enabled'),('attestKeyUsesUpToDate','signing_restored')]:
        old='''                    try { act.vault().METHOD(); }
                    catch (RuntimeException failure) {
                        Toast.makeText(act, Language.msg(l10n_wallet_counter_update_failed), Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(act, Language.msg(l10n_wallet.MESSAGE), Toast.LENGTH_LONG).show();
                    refresh();'''.replace('METHOD',method).replace('l10n_wallet.MESSAGE','l10n_wallet_'+msg)
        new='''                    final SeedVault vault = act.vault();
                    act.runAuthenticatedWalletWork(() -> { vault.METHOD(); return null; }, ignored -> {
                        Toast.makeText(act, Language.msg(l10n_wallet.MESSAGE), Toast.LENGTH_LONG).show();
                        refresh();
                    }, error -> Toast.makeText(act, Language.msg(l10n_wallet_counter_update_failed), Toast.LENGTH_LONG).show());'''.replace('METHOD',method).replace('l10n_wallet.MESSAGE','l10n_wallet_'+msg)
        edit(settings,old,new)
    old='''                    // Confirm the passphrase actually unlocks the stored vault (defence-in-depth).
                    try {
                        if (!act.vault().verifyPassphrase(pass.getText().toString())) {
                            Toast.makeText(act, Language.msg(l10n_wallet_wrong_passphrase), Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (SeedVault.VaultCorruptException corrupt) {
                        Toast.makeText(act, Language.msg(l10n_wallet_vault_damaged, corrupt.getMessage()), Toast.LENGTH_LONG).show();
                        return;
                    }
                    showSeedDialog(act.vault().phrase());'''
    edit(settings,old,'''                    authenticate(pass.getText().toString(), () -> showSeedDialog(act.vault().phrase()));
                    pass.setText("");''')
    old='''                    act.createBackupFile("minima-wallet-backup.mvlt", uri -> {
                        try {
                            byte[] bytes = act.vault().exportBytes(p);
                            act.writeBytes(uri, bytes);
                            Toast.makeText(act, Language.msg(l10n_wallet_backup_saved, bytes.length), Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(act, Language.msg(l10n_wallet_backup_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                        }
                    });'''
    edit(settings,old,'''                    pass.setText("");
                    final SeedVault vault = act.vault();
                    act.createBackupFile("minima-wallet-backup.mvlt", uri -> {
                        if (!currentSession()) return;
                        act.runAuthenticatedWalletWork(() -> {
                            byte[] bytes = vault.exportBytes(p);
                            act.writeBytes(uri, bytes);
                            return bytes.length;
                        }, size -> Toast.makeText(act, Language.msg(l10n_wallet_backup_saved, size), Toast.LENGTH_LONG).show(),
                           error -> Toast.makeText(act, Language.msg(l10n_wallet_backup_failed, error.getMessage()), Toast.LENGTH_LONG).show());
                    });''')
    p=base/settings;s=p.read_text();start=s.index('    private void restore() {');end=s.index('    // ---------------------------------------------------------------------------------------------\n    // Unlock & session actions',start)
    s=s[:start]+'''    private void restore() {
        if (!currentSession()) return;
        act.openBackupFile(uri -> {
            if (!currentSession()) return;
            act.runAuthenticatedWalletWork(() -> act.readBytes(uri), bytes -> {
                final EditText pass = passwordField(Language.msg(l10n_wallet_backup_passphrase));
                new WalletDialog(act)
                        .setTitle(Language.msg(l10n_wallet_restore_backup))
                        .setMessage(Language.msg(l10n_wallet_restore_backup_body))
                        .setView(wrap(pass))
                        .setPositiveButton(Language.msg(l10n_wallet_restore), (d,w) -> {
                            doRestore(bytes, pass.getText().toString()); pass.setText("");
                        })
                        .setNegativeButton(Language.msg(l10n_common_cancel), null).show();
            }, error -> Toast.makeText(act, Language.msg(l10n_wallet_read_backup_failed, error.getMessage()), Toast.LENGTH_LONG).show());
        });
    }

    private void doRestore(byte[] bytes, String filePass) {
        if (!currentSession()) return;
        final SeedVault vault = act.vault();
        final android.content.Context app = act.getApplicationContext();
        act.runAuthenticatedWalletWork(() -> {
            SeedVault.peekImport(bytes, filePass);
            vault.importVault(bytes, filePass, filePass);
            // Same replacement rule as MainActivity's imported/generated seed paths.
            BiometricUnlock.disable(app);
            return null;
        }, ignored -> {
            Toast.makeText(act, Language.msg(l10n_wallet_restored_untrusted), Toast.LENGTH_LONG).show();
            act.recreate();
        }, error -> Toast.makeText(act, Language.msg(l10n_wallet_restore_failed), Toast.LENGTH_LONG).show());
    }

    /** Verify on the session worker; read/reveal the phrase only during foreground completion. */
    private void authenticate(String passphrase, Runnable approved) {
        if (!currentSession()) return;
        final SeedVault vault = act.vault();
        act.runAuthenticatedWalletWork(() -> vault.verifyPassphrase(passphrase), valid -> {
            if (!valid) Toast.makeText(act, Language.msg(l10n_wallet_wrong_passphrase), Toast.LENGTH_SHORT).show();
            else if (currentSession()) approved.run();
        }, error -> Toast.makeText(act, error instanceof SeedVault.VaultCorruptException
                ? Language.msg(l10n_wallet_vault_damaged, error.getMessage())
                : Language.msg(l10n_wallet_verify_failed), Toast.LENGTH_LONG).show());
    }

'''+s[end:];p.write_text(s)
    old='''                    // Verify the passphrase actually opens the vault before storing it.
                    try {
                        if (!act.vault().verifyPassphrase(p)) {
                            Toast.makeText(act, Language.msg(l10n_wallet_wrong_passphrase), Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (SeedVault.VaultCorruptException e) {
                        Toast.makeText(act, Language.msg(l10n_wallet_vault_damaged, e.getMessage()), Toast.LENGTH_LONG).show();
                        return;
                    }
                    BiometricUnlock.enable(act, p, new BiometricUnlock.Callback() {'''
    edit(settings,old,'''                    pass.setText("");
                    authenticate(p, () -> BiometricUnlock.enable(act, p, new BiometricUnlock.Callback() {''')
    edit(settings,'                        @Override public void onCancelled() { refresh(); }\n                    });',
         '                        @Override public void onCancelled() { if (currentSession()) refresh(); }\n                    }));')
    # Keep the existing biometric refresh flow, but retire the old credential before replacement.
    p=base/settings;s=p.read_text();start=s.index('                    // Verify the CURRENT passphrase');end=s.index('                    // If biometric is on',start)
    s=s[:start]+'''                    final SeedVault vault = act.vault();
                    final android.content.Context app = act.getApplicationContext();
                    final boolean hadBiometric = BiometricUnlock.isEnabled(app);
                    cur.setText(""); n1.setText(""); n2.setText("");
                    act.runAuthenticatedWalletWork(() -> {
                        if (!vault.verifyPassphrase(c)) return false;
                        vault.changePassphrase(a);
                        BiometricUnlock.disable(app);
                        return true;
                    }, changed -> {
                        if (!changed) {
                            Toast.makeText(act, Language.msg(l10n_wallet_current_passphrase_wrong), Toast.LENGTH_SHORT).show();
                            return;
                        }
'''+s[end:]
    s=s.replace('                    if (BiometricUnlock.isEnabled(act)) {','                    if (hadBiometric) {')
    marker='''                        Toast.makeText(act, Language.msg(l10n_wallet_passphrase_changed), Toast.LENGTH_LONG).show();
                    }
                })'''
    assert s.count(marker)==1
    s=s.replace(marker,'''                        Toast.makeText(act, Language.msg(l10n_wallet_passphrase_changed), Toast.LENGTH_LONG).show();
                    }
                    }, error -> Toast.makeText(act, error instanceof SeedVault.VaultCorruptException
                            ? Language.msg(l10n_wallet_vault_damaged, error.getMessage())
                            : Language.msg(l10n_wallet_passphrase_change_failed, error.getMessage()), Toast.LENGTH_LONG).show());
                })''')
    p.write_text(s)
    # Fence the stored credential itself, not just UI callbacks after storage.
    edit(settings,'BiometricUnlock.enable(act, p, new BiometricUnlock.Callback() {',
         'BiometricUnlock.enable(act, p, act.sessionAuthorization(), new BiometricUnlock.Callback() {')
    edit(settings,'BiometricUnlock.enable(act, a, new BiometricUnlock.Callback() {',
         'BiometricUnlock.enable(act, a, act.sessionAuthorization(), new BiometricUnlock.Callback() {')
    bio='BiometricUnlock.java'
    edit(bio,'    private BiometricUnlock() {}', '''    private BiometricUnlock() {}
    private static final java.util.concurrent.atomic.AtomicLong requests = new java.util.concurrent.atomic.AtomicLong();
    private static boolean current(FragmentActivity activity, long request, java.util.function.BooleanSupplier authorized) {
        try { return request == requests.get() && !activity.isFinishing() && !activity.isDestroyed()
                && activity.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                && authorized.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }''')
    edit(bio,'    public static void disable(Context zContext) {\n        clearStored(zContext);',
         '    public static void disable(Context zContext) {\n        requests.incrementAndGet();\n        clearStored(zContext);')
    edit(bio,'    public static void enable(FragmentActivity zActivity, String zPassphrase, Callback zCallback) {', '''    public static void enable(FragmentActivity zActivity, String zPassphrase, Callback zCallback) {
        enable(zActivity, zPassphrase, () -> true, zCallback);
    }
    public static void enable(FragmentActivity zActivity, String zPassphrase,
                              java.util.function.BooleanSupplier authorized, Callback zCallback) {
        final long request = requests.incrementAndGet();
        if (!current(zActivity, request, authorized)) return;
        clearStored(zActivity);''')
    edit(bio,'    public static void unlock(FragmentActivity zActivity, UnlockCallback zCallback) {', '''    public static void unlock(FragmentActivity zActivity, UnlockCallback zCallback) {
        unlock(zActivity, () -> true, zCallback);
    }
    public static void unlock(FragmentActivity zActivity, java.util.function.BooleanSupplier authorized, UnlockCallback zCallback) {
        final long request = requests.incrementAndGet();
        if (!current(zActivity, request, authorized)) return;''')
    edit(bio,'            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {\n                try {',
         '            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {\n                if (!current(zActivity, request, authorized)) return;\n                try {',2)
    edit(bio,'            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {\n                if (isCancel(errorCode))',
         '            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {\n                if (!current(zActivity, request, authorized)) return;\n                if (isCancel(errorCode))',2)
    # Password replacement runs on the worker. Serialize its invalidation with callback storage
    # and key creation; a check before doFinal alone leaves a check/store race.
    edit(bio,'public static void disable(Context zContext)', 'public static synchronized void disable(Context zContext)')
    edit(bio,'public static void enable(FragmentActivity zActivity, String zPassphrase,\n',
         'public static synchronized void enable(FragmentActivity zActivity, String zPassphrase,\n')
    edit(bio,'public static void unlock(FragmentActivity zActivity, java.util.function.BooleanSupplier authorized,',
         'public static synchronized void unlock(FragmentActivity zActivity, java.util.function.BooleanSupplier authorized,')
    p=base/bio;s=p.read_text()
    for signature in ['public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {',
                      'public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {']:
        assert s.count(signature)==2
        cursor=0
        for _ in range(2):
            start=s.index(signature,cursor)+len(signature);end=start;depth=1
            while depth:
                if s[end]=='{':depth+=1
                elif s[end]=='}':depth-=1
                end+=1
            body=s[start:end-1]
            wrapped='\n                synchronized (BiometricUnlock.class) {'+body+'\n                }\n            '
            s=s[:start]+wrapped+s[end-1:];cursor=start+len(wrapped)+1
    p.write_text(s)
