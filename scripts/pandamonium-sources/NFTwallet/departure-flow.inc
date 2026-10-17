    /** Validate the chosen item again at submission; a dialog may have outlived its mint state. */
    private static JSONObject departureIntent(MainActivity act, String tokenid, JSONObject coin, Sheet.Progress progress) {
        if (!LocalStore.canDepart(act, tokenid)) {
            setMessage(progress, Language.msg(l10n_nft_actions_mint_unfinished));
            makeDismissable(progress); return null;
        }
        try {
            if (StateNft.stamped(coin) == null) throw BurialEvidence.invalid();
            return BurialEvidence.intent(coin, tokenid);
        } catch (RuntimeException e) {
            setMessage(progress, Language.msg(l10n_nft_actions_invalid_departure));
            makeDismissable(progress); return null;
        }
    }

    private static void confirmDeparture(MainActivity act, String tokenid, JSONObject intent, String destination,
                                          JSONObject posted, Sheet.Progress progress, CharSequence successMsg) {
        try {
            JSONObject receipt = BurialEvidence.receipt(posted, intent, destination);
            watchDeparture(act, tokenid, receipt, progress, successMsg);
        } catch (RuntimeException e) {
            setMessage(progress, Language.msg(l10n_nft_actions_output_unverified));
            makeDismissable(progress);
        }
    }

    /** A post acknowledgement or missing input is insufficient: verify the exact intended output. */
    private static void watchDeparture(MainActivity act, String tokenid, JSONObject receipt,
                                       Sheet.Progress progress, CharSequence successMsg) {
        final String outputid, destination;
        try {
            BurialEvidence.journal(new org.json.JSONArray().put(receipt), tokenid);
            outputid = BurialEvidence.id(receipt, "output");
            destination = BurialEvidence.id(receipt, "address");
        } catch (RuntimeException e) {
            setMessage(progress, Language.msg(l10n_nft_actions_output_unverified));
            makeDismissable(progress); return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (act.isFinishing() || act.isDestroyed()) return;
            act.node().cmd("coins coinid:" + outputid, new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    try {
                        org.json.JSONArray coins = BurialEvidence.coins(json, tokenid);
                        if (coins.length() == 1 && BurialEvidence.confirmed(receipt, coins.optJSONObject(0), destination)) {
                            setMessage(progress, successMsg);
                            makeDismissable(progress);
                            act.reload(); return;
                        }
                    } catch (RuntimeException e) { /* Keep an incomplete response unconfirmed. */ }
                    reschedule();
                }
                @Override public void onError(String message) {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    if (NodeApi.ERR_TOO_LONG.equals(message)) {
                        setMessage(progress, Language.msg(l10n_nft_actions_unconfirmed_large));
                        makeDismissable(progress); return;
                    }
                    reschedule();
                }
                private void reschedule() {
                    if (++tries[0] >= WATCH_TRIES) {
                        setMessage(progress, Language.msg(l10n_nft_actions_unconfirmed_checks, WATCH_TRIES));
                        makeDismissable(progress);
                    } else h.postDelayed(poll[0], WATCH_INTERVAL_MS);
                }
            });
        };
        h.postDelayed(poll[0], WATCH_INTERVAL_MS);
    }

