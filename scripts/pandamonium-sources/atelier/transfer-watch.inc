    /** NFT Wallet's exact-output evidence; an absent input is never proof of delivery. */
    private void watchDeparture(String tokenid, JSONObject receipt, long generation, Runnable ok, Runnable unsure) {
        final String outputid, destination;
        try {
            BurialEvidence.journal(new JSONArray().put(receipt), tokenid);
            outputid = BurialEvidence.id(receipt, "output");
            destination = BurialEvidence.id(receipt, "address");
        } catch (RuntimeException e) { unsure.run(); return; }
        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (!transferScreenActive(generation) || !departureWatchers.contains(poll[0])) return;
            node.cmd("coins coinid:" + outputid, new NodeApi.Cb() {
                private boolean active() {
                    return transferScreenActive(generation) && departureWatchers.contains(poll[0]);
                }
                @Override public void onResult(JSONObject json) {
                    if (!active()) return;
                    try {
                        JSONArray coins = BurialEvidence.coins(json, tokenid);
                        if (coins.length() == 1 && BurialEvidence.confirmed(receipt, coins.optJSONObject(0), destination)) {
                            departureWatchers.remove(poll[0]); ok.run(); return;
                        }
                    } catch (RuntimeException e) { /* Incomplete evidence stays unconfirmed. */ }
                    reschedule();
                }
                @Override public void onError(String message) { if (active()) reschedule(); }
                private void reschedule() {
                    if (++tries[0] >= 20) { departureWatchers.remove(poll[0]); unsure.run(); }
                    else main.postDelayed(poll[0], 20000);
                }
            });
        };
        departureWatchers.add(poll[0]);
        main.postDelayed(poll[0], 20000);
    }
