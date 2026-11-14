package com.eurobuddha.dexicon;

import android.content.Context;

import com.eurobuddha.minimaapi.MinimaAPIListener;

/**
 * Host-node mode (owner, 14 Nov 2026): Dexicon embedded in a Minima node app - Pandamonium - trading with the HOST
 * NODE's own wallet, in-process ("the direct plumbing of that build"). The host installs its link once, before any
 * Dexicon class runs; from then on:
 * <ul><li>every command goes straight to the node ({@link NodeApi#cmd}): no Trading Account, no second seed, no deposit -
 *     the node signs everything and picks every one-time key itself, so a key cannot be reused across apps;
 * <li>identities and the AtomiX ETH key come from the node's seed (seedrandom on the node, as Dexicon 0.2.x did);
 * <li>everything that only makes sense for a separate app is off: account setup, deposit/withdraw, word reveal, the
 *     trading gate against sibling apps, pairing/ADMIN screens, uninstall / clear-storage buttons.</ul>
 * Standalone Dexicon never calls {@link #install}, so {@link #on()} is false and nothing changes.
 */
public final class HostMode {
    private HostMode() {}

    /** Opens the host's link; {@code registration} gets {@code {status, enabled, admin}} as a node register reply. */
    public interface LinkFactory { NodeLink open(Context ctx, MinimaAPIListener registration); }

    private static volatile LinkFactory factory;
    private static volatile String hostName = "Pandamonium";

    /** Called once by the host app (before Dexicon's first use). */
    public static void install(LinkFactory linkFactory, String host) {
        if (linkFactory == null) throw new IllegalArgumentException("no link");
        factory = linkFactory; hostName = host == null ? "" : host;
    }

    /** True inside a host node app. */
    public static boolean on() { return true; }   // Pandamonium: always the host node

    /** The host app's name ("Pandamonium"), for texts. */
    public static String host() { return hostName; }

    /** The node link: the host's in-process link in host mode, else the IPC transport. */
    static NodeLink open(Context ctx, MinimaAPIListener registration) {
        return new DirectLink(ctx, registration);   // Pandamonium: the embedded node, in-process
    }

    /** The host forwards node events (NEWBLOCK / NEWBALANCE) the IPC service would have rebroadcast. */
    public static void nodeEvent(Context ctx, String event) {
        if (!"NEWBLOCK".equals(event) && !"NEWBALANCE".equals(event)) return;
        ctx.sendBroadcast(new android.content.Intent("com.eurobuddha.dexicon.NODE_EVENT").setPackage(ctx.getPackageName())
                .putExtra("event", event));
    }

    /** Pandamonium: a notification opens the HOST on its Dexicon tab - never a second Dexicon screen. Extras (e.g. the
     *  pools page) reach the tab's screen through the host's onNewIntent. */
    public static android.content.Intent screenIntent(Context c) {
        return new android.content.Intent().setClassName(c.getPackageName(), "com.eurobuddha.minimacore.main.MainActivity")
                .putExtra("pandamonium_tab", "dexicon")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    /** Test seam. */
    static void reset() { factory = null; hostName = "Pandamonium"; }
}
