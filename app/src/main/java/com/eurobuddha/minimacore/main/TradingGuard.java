package com.eurobuddha.minimacore.main;

import android.app.Activity;
import android.app.AlertDialog;

/** A wipe or a seed rebuild changes the node's keys. In Pandamonium, Dexicon's open positions (unsettled swaps, live
 *  orders, owned pools) can only be claimed, refunded or withdrawn with the keys they were made with - so say what is
 *  open and make "anyway" a deliberate second choice (apks/atomix CLAUDE.md: drain all in-flight swaps first). */
public final class TradingGuard {
    private TradingGuard() {}

    public static void run(Activity activity, Runnable proceed) {
        String open = com.eurobuddha.minimacore.integrated.PandamoniumHooks.tradingInFlight(activity);
        if (open == null) { proceed.run(); return; }
        new AlertDialog.Builder(activity)
                .setTitle("Trading still open")
                .setMessage("Dexicon still has: " + open + ".\n\nThey can only be settled with this wallet's current keys. "
                        + "Finish them first (claim or refund the swaps, withdraw the orders and pools), or they may be lost.")
                .setPositiveButton("Continue anyway", (d, w) -> proceed.run())
                .setNegativeButton("Cancel", null)
                .show();
    }
}
