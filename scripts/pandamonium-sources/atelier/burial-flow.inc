    /** NFT Wallet's persistent intent/receipt flow, hosted in Atelier's retained screen. */
    private void buryOwnedCoins(StateNft.Meta m, TextView msg, long generation) {
        try {
            LocalStore.burialJournal(this, m.tokenid);
            if (AirdropEngine.job(this, m.localId) != null) {
                Language.setText(msg, Language.msg(l10n_atelier_burial_airdrop_active)); return;
            }
            if (!MintEngine.pauseForBurial(this, m.tokenid)) {
                Language.setText(msg, Language.msg(l10n_nft_burial_pause_failed)); return;
            }
            m.phase = "BURY";
        } catch (RuntimeException e) {
            Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); return;
        }
        Language.setText(msg, Language.msg(l10n_nft_actions_finding_coins));
        node.cmd("coins relevant:true own:true tokenid:" + m.tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!burialScreenActive(generation)) return;
                final List<JSONObject> coins = new ArrayList<>();
                try {
                    JSONArray arr = BurialEvidence.coins(json, m.tokenid);
                    JSONArray journal = LocalStore.burialJournal(MainActivity.this, m.tokenid);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject coin = arr.getJSONObject(i);
                        if (BurialEvidence.same(StateNft.GRAVEYARD, coin.optString("address"))) throw BurialEvidence.invalid();
                        boolean preserve = StateNft.replayableState(coin);
                        if ((!preserve || StateNft.stamped(coin) == null) && !Util.isValidHexId(m.creatorPk)) throw BurialEvidence.invalid();
                        JSONObject old = burialEntry(journal, coin.optString("coinid"));
                        // Preserve a previously chosen stripped-state fallback on an explicit retry.
                        if (old != null) preserve = BurialEvidence.preserves(old);
                        JSONObject intent = BurialEvidence.burialIntent(coin, m.tokenid, preserve);
                        if (old == null) journal.put(intent);
                        else if (!old.optString("statehash").equals(intent.optString("statehash"))
                                || !old.optString("tokenamount").equals(intent.optString("tokenamount"))) throw BurialEvidence.invalid();
                        coins.add(coin);
                    }
                    if (!LocalStore.saveBurialJournal(MainActivity.this, m.tokenid, journal)) throw BurialEvidence.invalid();
                    if (coins.isEmpty()) {
                        if (journal.length() == 0) Language.setText(msg, Language.msg(l10n_nft_actions_no_coins));
                        else watchBurialCompletion(m, msg, generation);
                        return;
                    }
                } catch (Exception e) {
                    Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); return;
                }
                buryNext(m, coins, 0, msg, generation);
            }
            @Override public void onError(String message) {
                if (burialScreenActive(generation)) Language.setText(msg, Language.msg(l10n_atelier_burial_failed, message));
            }
        });
    }

    private static JSONObject burialEntry(JSONArray journal, String input) {
        for (int i = 0; i < journal.length(); i++) {
            JSONObject entry = journal.optJSONObject(i);
            if (entry != null && BurialEvidence.same(input, entry.optString("input"))) return entry;
        }
        return null;
    }

    private void saveBurialEntry(String tokenid, JSONObject entry) {
        try {
            JSONArray journal = LocalStore.burialJournal(this, tokenid);
            boolean replaced = false;
            for (int j = 0; j < journal.length(); j++) {
                if (BurialEvidence.same(entry.optString("input"), journal.getJSONObject(j).optString("input"))) {
                    journal.put(j, entry); replaced = true; break;
                }
            }
            if (!replaced || !LocalStore.saveBurialJournal(this, tokenid, journal)) throw BurialEvidence.invalid();
        } catch (Exception e) { throw BurialEvidence.invalid(); }
    }

    private void buryNext(StateNft.Meta m, List<JSONObject> coins, int i, TextView msg, long generation) {
        if (!burialScreenActive(generation)) return;
        if (i >= coins.size()) { watchBurialCompletion(m, msg, generation); return; }
        JSONObject coin = coins.get(i);
        final JSONObject intent;
        try {
            intent = burialEntry(LocalStore.burialJournal(this, m.tokenid), coin.optString("coinid"));
            if (intent == null) throw BurialEvidence.invalid();
            if (!intent.optString("output").isEmpty()) { buryNext(m, coins, i + 1, msg, generation); return; }
            if ((!BurialEvidence.preserves(intent) || StateNft.stamped(coin) == null) && !Util.isValidHexId(m.creatorPk)) throw BurialEvidence.invalid();
        } catch (RuntimeException e) {
            Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); return;
        }
        Language.setText(msg, Language.msg(l10n_nft_actions_burying_item, i + 1, coins.size()));
        runBury(m, coins, i, intent, msg, generation);
    }

    private void runBury(StateNft.Meta m, List<JSONObject> coins, int i, JSONObject intent, TextView msg, long generation) {
        if (!burialScreenActive(generation)) return;
        JSONObject coin = coins.get(i);
        boolean preserve = BurialEvidence.preserves(intent);
        String creator = !preserve || StateNft.stamped(coin) == null ? m.creatorPk : "";
        String txn = "bury" + java.util.UUID.randomUUID().toString().replace("-", "");
        MintEngine.postTxnWithReceipt(node, txn, StateNft.buryCommands(txn, m.tokenid, creator, coin, preserve), posted -> {
            try {
                // Persist an accepted receipt even if the user left the screen while posting.
                saveBurialEntry(m.tokenid, BurialEvidence.receipt(posted, intent));
            } catch (RuntimeException e) {
                if (burialScreenActive(generation)) Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing));
                return;
            }
            buryNext(m, coins, i + 1, msg, generation);
        }, error -> {
            if (!burialScreenActive(generation)) return;
            if (preserve && error != null && error.toLowerCase(Locale.ROOT).contains("size too large") && Util.isValidHexId(m.creatorPk)) {
                try {
                    JSONObject stripped = BurialEvidence.burialIntent(coin, m.tokenid, false);
                    saveBurialEntry(m.tokenid, stripped); // Durable before the fallback signature.
                    runBury(m, coins, i, stripped, msg, generation);
                } catch (RuntimeException e) { Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); }
            } else Language.setText(msg, Language.msg(l10n_atelier_burial_failed, error == null ? "" : error));
        });
    }

    private void watchBurialCompletion(StateNft.Meta m, TextView msg, long generation) {
        final JSONArray journal;
        try {
            journal = LocalStore.burialJournal(this, m.tokenid);
            if (journal.length() == 0) throw BurialEvidence.invalid();
            for (int i = 0; i < journal.length(); i++) BurialEvidence.id(journal.getJSONObject(i), "output");
        } catch (Exception e) {
            Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); return;
        }
        Language.setText(msg, Language.msg(l10n_nft_burial_waiting));
        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        final Runnable retry = () -> {
            if (!burialScreenActive(generation) || !departureWatchers.contains(poll[0])) return;
            if (++tries[0] >= 20) {
                departureWatchers.remove(poll[0]);
                Language.setText(msg, Language.msg(l10n_nft_burial_unconfirmed));
            } else main.postDelayed(poll[0], 20000);
        };
        poll[0] = () -> {
            if (!burialScreenActive(generation) || !departureWatchers.contains(poll[0])) return;
            verifyBurialOutputs(m, journal, 0, generation, () -> {
                node.cmd("coins relevant:true own:true tokenid:" + m.tokenid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json) {
                        if (!burialScreenActive(generation)) return;
                        try {
                            if (BurialEvidence.coins(json, m.tokenid).length() != 0) { retry.run(); return; }
                            if (!BurialEvidence.sameJournal(journal, LocalStore.burialJournal(MainActivity.this, m.tokenid), m.tokenid)) {
                                departureWatchers.remove(poll[0]);
                                Language.setText(msg, Language.msg(l10n_nft_burial_evidence_missing)); return;
                            }
                        } catch (RuntimeException e) { retry.run(); return; }
                        node.cmd("block", new NodeApi.Cb() {
                            @Override public void onResult(JSONObject block) {
                                if (!burialScreenActive(generation)) return;
                                JSONObject response = block.optJSONObject("response");
                                Object raw = response == null ? null : response.opt("block");
                                int tip = response == null ? 0 : response.optInt("block", 0);
                                if (!Boolean.TRUE.equals(block.opt("status")) || raw == null || !raw.toString().matches("[0-9]+") || tip <= 0) { retry.run(); return; }
                                try {
                                    if (!LocalStore.completeBurial(MainActivity.this, m.tokenid, journal, tip)) { retry.run(); return; }
                                } catch (RuntimeException e) { retry.run(); return; }
                                departureWatchers.remove(poll[0]);
                                m.phase = "BURIED";
                                MintEngine.clearWalletCoinCache();
                                loadLocalCollections(); lastDetailSig = "";
                                Language.setText(msg, Language.msg(l10n_nft_burial_confirmed));
                            }
                            @Override public void onError(String error) { retry.run(); }
                        });
                    }
                    @Override public void onError(String error) { retry.run(); }
                });
            }, retry);
        };
        departureWatchers.add(poll[0]);
        main.postDelayed(poll[0], 20000);
    }

    private void verifyBurialOutputs(StateNft.Meta m, JSONArray journal, int i, long generation, Runnable confirmed, Runnable retry) {
        if (!burialScreenActive(generation)) return;
        if (i >= journal.length()) { confirmed.run(); return; }
        JSONObject receipt = journal.optJSONObject(i);
        node.cmd("coins coinid:" + receipt.optString("output"), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!burialScreenActive(generation)) return;
                try {
                    JSONArray coins = BurialEvidence.coins(json, m.tokenid);
                    if (coins.length() != 1 || !BurialEvidence.confirmed(receipt, coins.optJSONObject(0))) { retry.run(); return; }
                } catch (RuntimeException e) { retry.run(); return; }
                verifyBurialOutputs(m, journal, i + 1, generation, confirmed, retry);
            }
            @Override public void onError(String error) { retry.run(); }
        });
    }
