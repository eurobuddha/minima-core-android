"""Reuse NFT Wallet inside Pandamonium with isolated storage and checked direct replies."""

def adapt(root):
    base = root/'NFTwallet'
    java = base/'src/main/java/com/eurobuddha/nftwallet'
    def change(path, old, new, count=1):
        text = path.read_text()
        assert text.count(old) == count, ('NFT adaptation drift', path, old)
        path.write_text(text.replace(old, new))

    change(base/'build.gradle', '    buildFeatures { buildConfig true }',
           '    buildFeatures { buildConfig true }\n    testOptions { unitTests.returnDefaultValues = true }')
    change(base/'build.gradle', 'dependencies {', "dependencies {\n    testImplementation 'org.mockito:mockito-core:5.12.0'")
    change(java/'HistoryDb.java', '"utxo_history.db"', '"pandamonium_nft_history.db"')
    change(java/'MintService.java', 'int FG_ID = 3101;', 'int FG_ID = 7501;')
    change(java/'Notifier.java', 'private static int sAlertId = 3100;',
           'private static final java.util.concurrent.atomic.AtomicInteger sAlertId = new java.util.concurrent.atomic.AtomicInteger(3100);')
    change(java/'Notifier.java', 'nm.notify(sAlertId++,',
           'nm.notify("pandamonium.nftwallet", sAlertId.getAndIncrement(),')
    # Same main-handler boundary as Future Cash and the Mall clients. Keep the
    # own:true compatibility retry inside cmdOnMain, before validating delivery.
    change(java/'NodeApi.java', '    public void cmd(String command, long timeoutMs, Cb cb) {', '''    public void cmd(String command, long timeoutMs, Cb cb) {
        mMain.post(() -> cmdOnMain(command, timeoutMs, checked(cb)));
    }

    /** Reuse the bundled Vestr/Future Cash Boolean-status gate before reporting success. */
    private static Cb checked(Cb callback) {
        if (callback == null) return null;
        return new Cb() {
            @Override public void onResult(JSONObject result) {
                if (result == null || !(result.opt("status") instanceof Boolean)) {
                    callback.onError(result == null ? "Node result unavailable. Outcome unknown."
                            : result.optString("transporterror", "Node result unavailable. Outcome unknown."));
                } else if (!result.optBoolean("status", false)) {
                    callback.onError(result.optString("error", result.optString("message", "Node command failed.")));
                } else {
                    callback.onResult(result);
                }
            }
            @Override public void onError(String message) { callback.onError(message); }
        };
    }

    private void cmdOnMain(String command, long timeoutMs, Cb cb) {''')
    # A failed transport envelope does not prove the embedded node is available.
    change(java/'NodeApi.java', '                        noteEnabled(true);',
           '                        if (zResponse.opt("status") instanceof Boolean) noteEnabled(true);')
    # Absence is evidence only in a successful complete coin-list response.
    # NodeApi rejects failed envelopes; this check also rejects a malformed response payload.
    change(java/'StateNftActions.java', '                boolean present = false;\n                org.json.JSONArray arr = json.optJSONArray("response");', '''                org.json.JSONArray arr = json.optJSONArray("response");
                if (arr == null) { reschedule(); return; }
                boolean present = false;''')
    change(java/'StateNftActions.java',
           '                        if (c != null && coinid.equals(c.optString("coinid"))) { present = true; break; }',
           '                        if (c == null || !Util.isValidHexId(c.optString("coinid", ""))) {\n                            reschedule(); return;\n                        }\n                        if (coinid.equals(c.optString("coinid"))) present = true;')
    # Match CmdChain's existing balance gate: malformed coin entries cannot authorize signing.
    change(java/'MintEngine.java', 'if (c != null && !"0".equals(c.optString("difference"))) bad = true;',
           'if (c == null || !"0".equals(c.optString("difference"))) bad = true;')
    path = java/'SettingsDialog.java'
    text = path.read_text().replace('✓ Paired with Minima Core', '✓ Embedded node connected')
    text = text.replace(' + "\\nIPC: broadcast Intent · bounded paging · large replies via file hand-off"', '')
    text = text.replace(' + "Java + classic Views · node access only via minimaapi broadcast IPC."',
                        ' + "Uses the embedded node wallet."')
    path.write_text(text)
