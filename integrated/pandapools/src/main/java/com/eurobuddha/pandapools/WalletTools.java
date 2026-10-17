package com.eurobuddha.pandapools;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import org.json.JSONObject;
import java.math.BigDecimal;

/** Wallet tools reuse the node's standard consolidation, as in the UTXO wallet. */
final class WalletTools {
    private final MainActivity act;
    WalletTools(MainActivity act) { this.act = act; }

    void checkNode() {
        act.node().cmd("magic", new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                try {
                    if (!TxPost.truthy(reply, "status")) { checkLegacyNode(); return; }
                    String limit = reply.getJSONObject("response").getJSONObject("lastblock")
                            .getString("currentmaxtxpowsize");
                    long bytes = new BigDecimal(limit).longValueExact();
                    if (bytes <= 0) throw new IllegalArgumentException();
                    message(Language.msg(l10n_pools_chain_limit, bytes));
                } catch (Exception invalid) { message(Language.msg(l10n_pools_chain_limit_unreadable)); }
            }
            public void onError(String error) { message(error); }
        });
    }

    private void checkLegacyNode() {
        act.node().cmd("block", new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                JSONObject r = reply == null ? null : reply.optJSONObject("response");
                if (!TxPost.truthy(reply, "status") || r == null) { message(Language.msg(l10n_pools_chain_tip_unreadable)); return; }
                message(Language.msg(l10n_pools_legacy_connected, r.has("block") ? r.optString("block", "") : Language.msg(l10n_pools_unknown)));
            }
            public void onError(String error) { message(error); }
        });
    }

    void showConsolidate(String token, String name) {
        if (!FundingCoins.hex(token)) { message(Language.msg(l10n_pools_invalid_token_id)); return; }
        act.languageDialog(Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
                .setTitle(Language.msg(l10n_pools_consolidate_title, name))
                .setMessage(Language.msg(l10n_pools_consolidate_review, name, "consolidate tokenid:" + token))
                .setNegativeButton(Language.msg(l10n_pools_cancel), null)
                .setPositiveButton(Language.msg(l10n_utxo_consolidate), (d, w) -> autoConsolidate(token, name)).show();
    }

    /** UTXO WalletTools.autoConsolidate, enclosed in PandaPools' shared signing gate. */
    private void autoConsolidate(String token, String name) {
        TxPost.Done done = TxPost.gated(new TxPost.Done() {
            public void ok(String id) {
                ActivityLog.record(act, ActivityLog.CONSOLIDATE, "Consolidate " + name + " coins", id, act.chainBlock());
                message(Language.msg(l10n_pools_consolidation_submitted));
            }
            public void fail(String error) {
                ActivityLog.recordFailed(act, ActivityLog.CONSOLIDATE, "Consolidate " + name + " coins", error);
                message(error);
            }
        });
        TxPost.submit(() -> {
            OwnerKeyRecovery.checkConsolidation(act.node()::cmd, () -> OwnPoolStore.all(act), error -> {
                if (error != null) { done.fail(error); return; }
                act.node().cmd("consolidate tokenid:" + token, new NodeApi.Cb() {
                    public void onResult(JSONObject reply) {
                        if (!TxPost.truthy(reply, "status")) { done.fail(nodeError(reply)); return; }
                        String id = Util.extractTxpowid(reply, "");
                        ActivityLog.rememberSubmission(act, reply, id);
                        done.ok(id);
                    }
                    public void onError(String error) { done.fail(error); }
                });
            });
        });
    }
    void resolveInterruptedWrite() {
        final NodeApi recoveryNode = act.node();
        final String reviewedWrite = recoveryNode.interruptedWriteId();
        if (!recoveryNode.hasInterruptedWrite()) { message(Language.msg(l10n_pools_no_interrupted_write)); return; }
        act.languageDialog(Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert).setTitle(Language.msg(l10n_pools_resolve_write))
                .setMessage(Language.msg(l10n_pools_interrupted_review))
                .setNegativeButton(Language.msg(l10n_atomix_recovery_keep), null)
                .setPositiveButton(Language.msg(l10n_pools_restarted_checked), (d, w) -> { message(recoveryNode.acknowledgeInterruptedWrite(reviewedWrite) ? Language.msg(l10n_pools_safety_pause_cleared) : Language.msg(l10n_pools_keep_write_paused)); }).show();
    }
    private static String nodeError(JSONObject j) { return j.optString("error", j.optString("message", "No coins could be combined.")); }
    private void message(CharSequence text) {
        if (!act.isFinishing() && !act.isDestroyed()) act.languageDialog(Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert).setTitle(Language.msg(l10n_tab_wallet)).setMessage(PoolsMessages.message(text)).setPositiveButton(Language.msg(l10n_pools_ok), null).show();
    }
}
