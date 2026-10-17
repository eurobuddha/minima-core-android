    private static void gatherAndBury(final MainActivity act, final String tokenid,
                                      final String collectionName, final String creatorPk,
                                      final Runnable onDone) {
        final Sheet.Progress progress = progressDialog(act,
                Language.msg(l10n_nft_actions_burying_name, collectionName), Language.msg(l10n_nft_actions_finding_coins));
        try {
            LocalStore.burialJournal(act, tokenid);
            if (!MintDriver.pauseForBurial(act, tokenid)) {
                setMessage(progress, Language.msg(l10n_nft_burial_pause_failed));
                makeDismissable(progress); return;
            }
        } catch (RuntimeException e) {
            setMessage(progress, Language.msg(l10n_nft_burial_evidence_missing));
            makeDismissable(progress); return;
        }
        act.node().cmd("coins relevant:true own:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (act.isFinishing() || act.isDestroyed()) return;
                final java.util.List<JSONObject> coins = new java.util.ArrayList<>();
                try {
                    org.json.JSONArray arr = BurialEvidence.coins(json, tokenid);
                    org.json.JSONArray journal = LocalStore.burialJournal(act, tokenid);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject coin = arr.getJSONObject(i);
                        if (BurialEvidence.same(StateNft.GRAVEYARD, coin.optString("address"))) throw BurialEvidence.invalid();
                        if (StateNft.stamped(coin) == null && !Util.isValidHexId(creatorPk)) throw BurialEvidence.invalid();
                        JSONObject intent = BurialEvidence.intent(coin, tokenid);
                        JSONObject old = burialEntry(journal, coin.optString("coinid"));
                        if (old == null) journal.put(intent);
                        else if (!old.optString("statehash").equals(intent.optString("statehash"))
                                || !old.optString("tokenamount").equals(intent.optString("tokenamount"))) throw BurialEvidence.invalid();
                        coins.add(coin);
                    }
                    // Record every intended input before the first signature. Failed/unknown posts remain pending.
                    if (!LocalStore.saveBurialJournal(act, tokenid, journal)) throw BurialEvidence.invalid();
                    if (coins.isEmpty()) {
                        if (journal.length() == 0) {
                            setMessage(progress, Language.msg(l10n_nft_actions_no_coins));
                            makeDismissable(progress);
                        } else watchBurialCompletion(act, tokenid, progress, onDone);
                        return;
                    }
                } catch (Exception e) {
                    setMessage(progress, Language.msg(l10n_nft_burial_evidence_missing));
                    makeDismissable(progress); return;
                }
                buryNext(act, tokenid, creatorPk, coins, 0, new int[]{0}, new String[]{null}, progress, onDone);
            }
            @Override public void onError(String message) {
                setMessage(progress, NodeApi.ERR_TOO_LONG.equals(message)
                        ? Language.msg(l10n_nft_actions_bury_list_large)
                        : Language.msg(l10n_nft_actions_list_coins_failed, NftMessages.message(message)));
                makeDismissable(progress);
            }
        });
    }

    private static JSONObject burialEntry(org.json.JSONArray journal, String input) {
        for (int i = 0; i < journal.length(); i++) {
            JSONObject entry = journal.optJSONObject(i);
            if (entry != null && BurialEvidence.same(input, entry.optString("input"))) return entry;
        }
        return null;
    }

    private static void buryNext(final MainActivity act, final String tokenid, final String creatorPk,
                                 final java.util.List<JSONObject> coins, final int i, final int[] done,
                                 final String[] firstError, final Sheet.Progress progress, final Runnable onDone) {
        if (act.isFinishing() || act.isDestroyed()) return;
        if (i >= coins.size()) {
            setMessage(progress, Language.join(Language.msg(l10n_nft_actions_posted_burials, done[0], coins.size()),
                    firstError[0] == null ? "" : Language.join("\n\n", NftMessages.message(firstError[0]))));
            makeDismissable(progress);
            if (done[0] == coins.size()) watchBurialCompletion(act, tokenid, progress, onDone);
            return;
        }
        final JSONObject coin = coins.get(i);
        final JSONObject intent;
        try {
            intent = burialEntry(LocalStore.burialJournal(act, tokenid), coin.optString("coinid"));
            if (intent == null) throw BurialEvidence.invalid();
            if (!intent.optString("output").isEmpty()) {
                done[0]++;
                buryNext(act, tokenid, creatorPk, coins, i + 1, done, firstError, progress, onDone);
                return;
            }
        } catch (RuntimeException e) {
            setMessage(progress, Language.msg(l10n_nft_burial_evidence_missing));
            makeDismissable(progress); return;
        }
        setMessage(progress, Language.msg(l10n_nft_actions_burying_item, i + 1, coins.size()));
        final String txn = "bc" + shortId(coin.optString("coinid", ""));
        boolean stamped = StateNft.stamped(coin) != null;
        java.util.List<String> cmds = StateNft.buryCommands(txn, tokenid, stamped ? "" : creatorPk, coin, true);
        CmdChain.run(act.node(), cmds, "txndelete id:" + txn, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                act.node().cmd("txndelete id:" + txn, NOOP);
                try {
                    JSONObject receipt = BurialEvidence.receipt(last, intent);
                    org.json.JSONArray journal = LocalStore.burialJournal(act, tokenid);
                    boolean replaced = false;
                    for (int j = 0; j < journal.length(); j++) {
                        if (BurialEvidence.same(coin.optString("coinid"), journal.getJSONObject(j).optString("input"))) {
                            journal.put(j, receipt); replaced = true; break;
                        }
                    }
                    if (!replaced || !LocalStore.saveBurialJournal(act, tokenid, journal)) throw BurialEvidence.invalid();
                    done[0]++;
                } catch (Exception e) {
                    setMessage(progress, Language.msg(l10n_nft_burial_evidence_missing));
                    makeDismissable(progress); return;
                }
                buryNext(act, tokenid, creatorPk, coins, i + 1, done, firstError, progress, onDone);
            }
            @Override public void fail(String message) {
                if (firstError[0] == null) firstError[0] = message == null ? "" : message;
                buryNext(act, tokenid, creatorPk, coins, i + 1, done, firstError, progress, onDone);
            }
        });
    }

    /** Reuse the StateNFT Suite's bounded polling cadence, requiring exact graveyard outputs too. */
    private static void watchBurialCompletion(MainActivity act, String tokenid,
                                              Sheet.Progress progress, Runnable onDone) {
        final org.json.JSONArray journal;
        try {
            journal = LocalStore.burialJournal(act, tokenid);
            if (journal.length() == 0) throw BurialEvidence.invalid();
            for (int i = 0; i < journal.length(); i++) BurialEvidence.id(journal.getJSONObject(i), "output");
        } catch (Exception e) {
            setMessage(progress, Language.msg(l10n_nft_burial_evidence_missing));
            makeDismissable(progress); return;
        }
        setMessage(progress, Language.msg(l10n_nft_burial_waiting));
        makeDismissable(progress);
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        final Runnable retry = () -> {
            if (++tries[0] >= WATCH_TRIES) {
                setMessage(progress, Language.msg(l10n_nft_burial_unconfirmed));
                makeDismissable(progress);
            } else handler.postDelayed(poll[0], WATCH_INTERVAL_MS);
        };
        poll[0] = () -> {
            if (act.isFinishing() || act.isDestroyed()) return;
            verifyBurialOutputs(act, tokenid, journal, 0, () -> {
                act.node().cmd("coins relevant:true own:true tokenid:" + tokenid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject json) {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        try {
                            if (BurialEvidence.coins(json, tokenid).length() != 0) { retry.run(); return; }
                            // A newer burial attempt changes the journal; an old watcher cannot finalize it.
                            if (!BurialEvidence.sameJournal(journal, LocalStore.burialJournal(act, tokenid), tokenid)) return;
                            if (!LocalStore.completeBurial(act, tokenid, journal)) throw BurialEvidence.invalid();
                        } catch (RuntimeException e) { retry.run(); return; }
                        setMessage(progress, Language.msg(l10n_nft_burial_confirmed));
                        makeDismissable(progress);
                        act.reload();
                        if (onDone != null) onDone.run();
                    }
                    @Override public void onError(String message) { retry.run(); }
                });
            }, retry);
        };
        handler.postDelayed(poll[0], WATCH_INTERVAL_MS);
    }

    private static void verifyBurialOutputs(MainActivity act, String tokenid, org.json.JSONArray journal,
                                            int i, Runnable confirmed, Runnable retry) {
        if (act.isFinishing() || act.isDestroyed()) return;
        if (i >= journal.length()) { confirmed.run(); return; }
        JSONObject receipt = journal.optJSONObject(i);
        act.node().cmd("coins coinid:" + receipt.optString("output"), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (act.isFinishing() || act.isDestroyed()) return;
                try {
                    org.json.JSONArray coins = BurialEvidence.coins(json, tokenid);
                    if (coins.length() != 1 || !BurialEvidence.confirmed(receipt, coins.optJSONObject(0))) { retry.run(); return; }
                } catch (RuntimeException e) { retry.run(); return; }
                verifyBurialOutputs(act, tokenid, journal, i + 1, confirmed, retry);
            }
            @Override public void onError(String message) { retry.run(); }
        });
    }

