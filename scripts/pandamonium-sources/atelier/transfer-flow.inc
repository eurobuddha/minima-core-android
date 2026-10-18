    private boolean transferScreenActive(long generation) {
        return !isFinishing() && !isDestroyed() && screen == Screen.TRANSFER && generation == screenGeneration;
    }

    private void doTransfer(StateNft.Meta meta, StateNft.Item it, EditText to, TextView msg, TextView go, long generation) {
        if (!transferScreenActive(generation) || !go.isEnabled()) return;
        String addr = to.getText().toString().trim();
        if (!Util.isValidAddress(addr)) {
            Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_airdrop_invalid_address)); return;
        }
        if (!LocalStore.canDepart(this, meta.tokenid)) {
            Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_actions_mint_unfinished)); return;
        }
        final JSONObject coin, intent;
        try {
            if (!it.owned || StateNft.stamped(it.coin) == null) throw BurialEvidence.invalid();
            coin = new JSONObject(it.coin.toString());
            intent = BurialEvidence.intent(coin, meta.tokenid);
        } catch (Exception e) {
            Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_actions_invalid_departure)); return;
        }
        // A post error can mean an unknown outcome. Never silently enable another submission.
        go.setEnabled(false);
        to.setEnabled(false);
        Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_building));
        String txn = "statenft" + java.util.UUID.randomUUID().toString().replace("-", "");
        MintEngine.postTxnWithReceipt(node, txn, StateNft.transferCommands(txn, meta.tokenid, coin, addr), posted -> {
            if (!transferScreenActive(generation)) return;
            final JSONObject receipt;
            try { receipt = BurialEvidence.receipt(posted, intent, addr); }
            catch (RuntimeException e) {
                Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_nft_actions_output_unverified)); return;
            }
            Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_posted));
            watchDeparture(meta.tokenid, receipt, generation, () -> {
                toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_confirmed, it.index));
                openCollection(meta);
            }, () -> Language.setText(msg, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_unconfirmed)));
        }, e -> {
            if (transferScreenActive(generation)) Language.setText(msg,
                    Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_transfer_failed, e));
        });
    }
