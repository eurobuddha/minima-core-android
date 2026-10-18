    /** One owned backup operation; no plaintext secret is put in saved-instance state. */
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_BACKUP_REQUEST = new java.util.concurrent.atomic.AtomicInteger(1000);
    private static final class BackupOperation {
        final int code, requestCode;
        final String owner;
        volatile boolean cancelled;
        boolean picker;
        String content;
        BackupOperation(int code, String owner) {
            this.code = code; this.owner = owner;
            requestCode = code == 0 ? 0 : NEXT_BACKUP_REQUEST.getAndIncrement();
        }
    }
    private BackupOperation backupOperation;
    private volatile boolean backupClosed;

    private String backupOwner() {
        JSONObject record = SalonStore.identityRecord(this);
        return new JSONArray().put(record.optString("tokenid")).put(record.optString("handle"))
                .put(record.optString("msgpk")).put(record.optString("msgseed")).toString();
    }

    private BackupOperation beginBackup(int code) {
        if (backupClosed || isFinishing() || isDestroyed()) return null;
        if (backupOperation != null) { toast(MSG_BUSY); return null; }
        try {
            BackupOperation operation = new BackupOperation(code, backupOwner());
            if (operation.requestCode > 65535) { toast(MSG_UNAVAILABLE); return null; }
            return backupOperation = operation;
        } catch (SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); return null; }
    }

    private boolean backupCurrent(BackupOperation operation) {
        if (backupClosed || operation.cancelled) return false;
        try { return operation.owner.equals(backupOwner()); }
        catch (SalonComms.IdentityUnavailable unavailable) { return false; }
    }

    private void backupNotice(CharSequence message) {
        if (!backupClosed && !isFinishing() && !isDestroyed()) toast(message);
    }

    private void finishBackup(BackupOperation operation, CharSequence notice) {
        if (backupOperation != operation) return;
        backupOperation = null; operation.content = null;
        if (!backupClosed && !isFinishing() && !isDestroyed() && notice != null) toast(notice);
    }

    private void cancelBackup() {
        backupClosed = true;
        if (backupOperation != null) {
            backupOperation.cancelled = true; backupOperation.content = null; backupOperation = null;
        }
    }

    private void backupAccount() {
        final BackupOperation operation = beginBackup(SAVE_TEXT);
        if (operation == null) return;
        try {
            io.execute(() -> {
                try {
                    final String content;
                    // Serialize key changes; recheck the captured owner before launching the picker.
                    // Hosting decryption must not hold the profile-edit lock.
                    synchronized (SalonComms.class) {
                        if (!backupCurrent(operation)) { runOnUiThread(() -> finishBackup(operation, MSG_CHANGED)); return; }
                        content = buildBackupJson();
                    }
                    if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 20 * 1024 * 1024) {
                        runOnUiThread(() -> finishBackup(operation, MSG_TOO_LARGE)); return;
                    }
                    runOnUiThread(() -> launchBackupSave(operation, "salon-backup.json", content));
                } catch (Crypt.Unavailable unavailable) { runOnUiThread(() -> finishBackup(operation, MSG_UNAVAILABLE)); }
                catch (SalonComms.IdentityUnavailable unavailable) { runOnUiThread(() -> finishBackup(operation, MSG_IDENTITY)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishBackup(operation, MSG_UNAVAILABLE); }
    }

    private void backupMsgKey() {
        final BackupOperation operation = beginBackup(0);
        if (operation == null) return;
        try {
            io.execute(() -> {
                try {
                    final String seed;
                    synchronized (SalonComms.class) {
                        if (!backupCurrent(operation)) { runOnUiThread(() -> finishBackup(operation, MSG_CHANGED)); return; }
                        seed = SalonComms.exportSeed(this);
                    }
                    runOnUiThread(() -> {
                        if (backupOperation != operation) return;
                        if (!backupCurrent(operation) || isFinishing() || isDestroyed()) { finishBackup(operation, MSG_CHANGED); return; }
                        finishBackup(operation, null);
                        if (seed.isEmpty()) { toast(MSG_NO_KEY); return; }
                        LinearLayout box = dialogBox();
                        box.addView(Design.note(this, MSG_KEY_HELP), lp(0, 2, 0, 8));
                        TextView t = Design.text(this, seed, 12, Design.INK(), Design.mono());
                        t.setOnClickListener(v -> { if (backupDialogCurrent(operation)) copyMessagingKey(seed); }); box.addView(t);
                        new com.eurobuddha.minimacore.i18n.LanguageDialog(this).setTitle(MSG_BACKUP_KEY).setView(box)
                                .setPositiveButton(MSG_SAVE_FILE, (d, w) -> { if (backupDialogCurrent(operation)) saveTextFile("salon-messaging-key.txt", seed); })
                                .setNeutralButton(MSG_COPY, (d, w) -> { if (backupDialogCurrent(operation)) copyMessagingKey(seed); })
                                .setNegativeButton(MSG_CLOSE, null).show();
                    });
                } catch (SalonComms.IdentityUnavailable unavailable) { runOnUiThread(() -> finishBackup(operation, MSG_IDENTITY)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishBackup(operation, MSG_UNAVAILABLE); }
    }

    private boolean backupDialogCurrent(BackupOperation operation) {
        if (isFinishing() || isDestroyed() || backupClosed) return false;
        if (!backupCurrent(operation)) { toast(MSG_CHANGED); return false; }
        return true;
    }

    private void saveTextFile(String suggestedName, String content) {
        final BackupOperation operation = beginBackup(SAVE_TEXT);
        if (operation != null) launchBackupSave(operation, suggestedName, content);
    }

    private void launchBackupSave(BackupOperation operation, String suggestedName, String content) {
        if (backupOperation != operation) return;
        if (!backupDialogCurrent(operation)) { finishBackup(operation, null); return; }
        operation.content = content; operation.picker = true;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, suggestedName);
        try { startActivityForResult(intent, operation.requestCode); }
        catch (RuntimeException unavailable) { finishBackup(operation, MSG_NO_SAVE_APP); }
    }

    private void restoreFromFile() {
        final BackupOperation operation = beginBackup(OPEN_BACKUP);
        if (operation == null) return;
        operation.picker = true;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(intent, operation.requestCode); }
        catch (RuntimeException unavailable) { finishBackup(operation, MSG_NO_OPEN_APP); }
    }

    private void receiveBackupFile(int requestCode, int result, Intent data) {
        final BackupOperation operation = backupOperation;
        if (operation == null || operation.requestCode != requestCode || !operation.picker) {
            if (result == RESULT_OK && !backupClosed && !isFinishing() && !isDestroyed()) toast(MSG_RESELECT);
            return;
        }
        final int code = operation.code;
        operation.picker = false; // Consume once, including cancellation and malformed results.
        if (result != RESULT_OK || data == null || data.getData() == null) { finishBackup(operation, null); return; }
        if (!backupCurrent(operation)) { finishBackup(operation, MSG_CHANGED); return; }
        final Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) { finishBackup(operation, MSG_DOCUMENT); return; }
        final String content = operation.content; operation.content = null;
        try {
            io.execute(() -> {
                try {
                    if (!backupCurrent(operation)) { runOnUiThread(() -> finishBackup(operation, MSG_CHANGED)); return; }
                    if (code == SAVE_TEXT) {
                        // Match TradeExportWriter.writeTo: truncate, close, then report success.
                        if (content == null) throw new java.io.IOException("missing backup");
                        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                            if (out == null) throw new java.io.IOException("cannot open document");
                            if (!backupCurrent(operation)) throw new java.io.InterruptedIOException();
                            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.flush();
                        }
                        runOnUiThread(() -> finishBackup(operation, MSG_SAVED));
                    } else {
                        final String json = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                .decode(java.nio.ByteBuffer.wrap(readCapped(uri, 20))).toString();
                        restoreFromBackup(json, operation);
                        runOnUiThread(() -> finishBackup(operation, null));
                    }
                } catch (Exception error) {
                    runOnUiThread(() -> finishBackup(operation, code == SAVE_TEXT ? MSG_SAVE_INCOMPLETE
                            : "file too large (max 20 MB)".equals(error.getMessage()) ? MSG_TOO_LARGE : MSG_READ_FAILED));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishBackup(operation, MSG_UNAVAILABLE); }
    }

    private void restoreMsgKey() {
        final BackupOperation dialog;
        try { dialog = new BackupOperation(0, backupOwner()); }
        catch (SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); return; }
        LinearLayout box = dialogBox();
        final EditText input = field(box, MSG_PASTE_KEY, "", false, "");
        showDialog(MSG_RESTORE_KEY, box, MSG_RESTORE, () -> {
            if (!backupDialogCurrent(dialog)) return;
            final BackupOperation operation = beginBackup(0);
            if (operation == null) return;
            final String seed = text(input);
            try {
                io.execute(() -> {
                    final boolean ok;
                    synchronized (SalonComms.class) { synchronized (SalonStore.class) {
                        if (!backupCurrent(operation)) { runOnUiThread(() -> finishBackup(operation, MSG_CHANGED)); return; }
                        ok = SalonComms.importSeed(this, seed);
                    } }
                    runOnUiThread(() -> {
                        if (backupOperation != operation) return;
                        finishBackup(operation, ok ? MSG_RESTORED_KEY : MSG_RESTORE_FAILED);
                        if (ok && !backupClosed && !isFinishing() && !isDestroyed()) render();
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException unavailable) { finishBackup(operation, MSG_UNAVAILABLE); }
        });
    }

