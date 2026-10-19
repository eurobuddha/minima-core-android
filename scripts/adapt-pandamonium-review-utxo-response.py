"""Reuse NFT NodeApi strict replies/main-thread setup and explicit uncertain-write callbacks."""
from pathlib import Path

CHECKED = '    /** Reuse the bundled Vestr/Future Cash Boolean-status gate before reporting success. */\n    private static Cb checked(Cb callback) {\n        if (callback == null) return null;\n        return new Cb() {\n            @Override public void onResult(JSONObject result) {\n                if (result == null || !(result.opt("status") instanceof Boolean)) {\n                    String message = result == null ? "Node result unavailable. Outcome unknown."\n                            : result.optString("transporterror", "Node result unavailable. Outcome unknown.");\n                    if (nothingSent(message)) callback.onError(message);\n                    else callback.onUnknown(message);\n                } else if (!result.optBoolean("status", false)) {\n                    callback.onError(result.optString("error", result.optString("response", "Node rejected the command.")));\n                } else {\n                    callback.onResult(result);\n                }\n            }\n            @Override public void onError(String message) { callback.onError(message); }\n            @Override public void onUnknown(String message) { callback.onUnknown(message); }\n        };\n    }\n\n    /** Exact local transport rejections that occur before execution, from DirectNodeApi. */\n    private static boolean nothingSent(String message) {\n        return "The embedded node is not running. No command was sent.".equals(message)\n                || "Too many pending node requests. Nothing was sent.".equals(message)\n                || "Node request cancelled before execution. Nothing was sent.".equals(message)\n                || "Node connection is closed.".equals(message);\n    }\n\n'

def replace(s,old,new):
    assert s.count(old)==1, ('UTXO response adapter drift',old)
    return s.replace(old,new)

def repair_node(s):
    s=replace(s,'        void onError(String message);','        void onError(String message);\n        /** An executing command may have completed despite a missing usable reply. */\n        default void onUnknown(String message) { onError(message); }')
    s=replace(s,'    public void cmd(String command, Cb cb) {\n        if (mReleased) return;',CHECKED+"""    public boolean isClosed() { return mReleased || dead(); }

    public void cmd(String command, Cb cb) {
        mMain.post(() -> cmdOnMain(command, checked(cb)));
    }

    private void cmdOnMain(String command, Cb cb) {
        if (isClosed()) {
            if (cb != null) mMain.post(() -> cb.onError("Node API released"));
            return;
        }""")
    s=replace(s,'if (done[0] || dead()) return;','if (done[0] || isClosed()) return;')
    s=replace(s,'if (cb != null) cb.onError(ERR_TIMEOUT);','if (cb != null) cb.onUnknown(ERR_TIMEOUT);')
    s=replace(s,'        mApi.Command(command, new MinimaAPIListener() {','        try { mApi.Command(command, new MinimaAPIListener() {')
    s=replace(s,'if (done[0]) return;','if (done[0] || isClosed()) return;')
    start=s.index('                    // "enabled":false only appears')
    end=s.index('                });',start)
    s=s[:start]+"""                    try {
                        if (zResponse != null && Boolean.FALSE.equals(zResponse.opt("enabled"))) {
                            if (mPairing != null) mPairing.onEnabled(false);
                            if (cb != null) cb.onError(ERR_NOT_ENABLED);
                            return;
                        }
                        Object response = zResponse == null ? null : zResponse.opt("response");
                        String text = response instanceof String ? ((String) response).toLowerCase(java.util.Locale.ROOT) : "";
                        boolean tooLong = zResponse != null && Boolean.FALSE.equals(zResponse.opt("status"))
                                && (text.contains("too long") || text.contains("max(256000)"));
                        if (tooLong) { if (cb != null) cb.onUnknown(ERR_TOO_LONG); return; }
                        if (cb != null) cb.onResult(zResponse);
                    } catch (RuntimeException malformed) {
                        if (cb != null) { try { cb.onUnknown("Node result unavailable. Outcome unknown."); } catch (RuntimeException ignored) {} }
                    }
"""+s[end:]
    s=replace(s,'        });\n    }\n\n    public void onDestroy()',"""        }); } catch (RuntimeException transportFailure) {
            // The transport can throw after dispatch; this is not proof that nothing ran.
            if (done[0]) return;
            done[0] = true;
            mMain.removeCallbacks(timeout);
            mPending.remove(timeout);
            if (!isClosed() && cb != null) cb.onUnknown("Node result unavailable. Outcome unknown.");
        }
    }

    public void onDestroy()""")
    s=s.replace('if (dead()) return;\n                    if (mPairing', 'if (isClosed()) return;\n                    if (mPairing')
    return s

def repair_builder(s):
    start=s.index('        /** txnsign didn')
    end=s.index('\n    }',start)
    s=s[:start]+'        /** A possibly submitted transaction must never become a definite failure. */\n        void onUnknown(String message);'+s[end:]
    start=s.index('                    @Override public void onError(String message) {',s.index('    private void sign()'))
    end=s.index('                });',start)
    s=s[:start]+"""                    @Override public void onError(String message) { fail(message); }
                    @Override public void onUnknown(String message) {
                        // Reuse NFT TxnBuilder: an executing post may still finish. Do not delete or retry it.
                        done.onUnknown(message);
                    }
"""+s[end:]
    return s

def repair_tools(s,integrated):
    start=s.index('    private void autoConsolidate(')
    end=s.index('    /** Merge exactly',start)
    section=s[start:end]
    needle='            @Override public void onError(String message) {'
    unknown="""            @Override public void onUnknown(String message) {
                act.history().update(internalid, HistoryDb.STATUS_UNKNOWN, null, "Node result unavailable. Outcome unknown.");
                UNKNOWN_STATUS
                act.reload();
            }
""".replace('UNKNOWN_STATUS','status.show(UtxoMessages.message("Node result unavailable. Outcome unknown."), false);' if integrated else 'status.show("Node result unavailable. Outcome unknown. Check History before trying again.", false);')
    section=replace(section,needle,unknown+needle)
    return s[:start]+section+s[end:]

def adapt_app(folder,integrated=True):
    for name,fn in [('NodeApi',repair_node),('TxnBuilder',repair_builder)]:
        p=folder/(name+'.java');p.write_text(fn(p.read_text()))
    p=folder/'WalletTools.java';p.write_text(repair_tools(p.read_text(),integrated))

def adapt(root):
    adapt_app(root/'utxo/src/main/java/com/eurobuddha/utxo')
    p=root/'utxo/build.gradle';s=p.read_text();s=replace(s,"    testImplementation 'junit:junit:4.13.2'","    testImplementation 'org.mockito:mockito-core:5.12.0'\n    testImplementation 'junit:junit:4.13.2'");p.write_text(s)
