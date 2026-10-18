package com.eurobuddha.salon;
import com.eurobuddha.minimacore.i18n.Language;

/** Fixed diagnostic lookup reused from NFT Wallet; never translate public profile content. */
final class HoldingMessages {
    private HoldingMessages() {}
    static CharSequence message(String reason) {
        if (reason == null) return "";
        switch (reason) {
            case "You don't hold a spendable coin of this asset.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_no_spendable);
            case "coin has no id/address": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_no_coin_id);
            case "could not export coin proof": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_export_failed);
            case "address is not a simple wallet address (can't prove holding)": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_not_simple);
            case "signing failed (is your node unlocked?)": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_sign_failed);
            case "no signature returned": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_no_signature);
            case "incomplete proof": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_incomplete);
            case "coin not found on-chain": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_coin_missing);
            case "wrong token": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_wrong_token);
            case "coin has been spent": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_spent);
            case "proof out of date — re-publish to refresh it": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_stale);
            case "key doesn't control the coin": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_wrong_key);
            case "script/address mismatch": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_wrong_script);
            case "signature not valid for this profile": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_wrong_signature);
            case "verify call failed": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_verify_failed);
            case "script check failed": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_script_failed);
            case "coin check failed (node busy?)": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_coin_failed);
            case "verified holding": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_verified);
            case "The embedded node did not respond. Check its status in Minima Core.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_error_node_timeout);
            case "Node result unavailable. Outcome unknown.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_error_node_unknown);
            case "The embedded node is not running. No command was sent.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_vestr_transport_stopped);
            case "Node connection is closed.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_vestr_transport_closed);
            case "Too many pending node requests. Nothing was sent.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_vestr_transport_busy);
            case "Node result was not received. Outcome remains unknown.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_vestr_transport_not_received);
            case "Node request could not complete. Outcome remains unknown.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_vestr_transport_request_failed);
            case "Node result is unavailable. Outcome remains unknown.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_vestr_transport_result_unavailable);
            case "Node request cancelled before execution. Nothing was sent.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_vestr_transport_cancelled);
            case "Invalid node result. Outcome remains unknown.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_vestr_transport_invalid);
            case "NOT_ENABLED": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_explorer_node_retry);
            case "The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_explorer_node_retry);
            case "Node rejected the command.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_utxo_rejected);
            case "Node reply exceeded the IPC limit — update Minima Core (needs 1.3.0+).": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_utxo_too_long);
            case "Proof request is no longer current.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_request_changed);
            case "A holding proof is already being created.": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_holdings_request_busy);
            case "that profile's tip address is malformed — not sending": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_bad_address);
            case "bad token id": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_bad_token);
            case "bad amount": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_utxo_valid_amount);
            case "the node rejected the tip": return Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_utxo_rejected);
            default: return reason;
        }
    }
}
