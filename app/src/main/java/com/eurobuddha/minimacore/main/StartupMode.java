package com.eurobuddha.minimacore.main;

import android.content.SharedPreferences;
import com.eurobuddha.minimacore.BuildConfig;
import java.util.List;

/** Saved startup policy. Reading it must never change the running node's globals. */
public final class StartupMode {
    public static final String PREF_CLASSIC_MODE = "PARAM_CLASSIC_MODE";
    /** Keets transport on/off (Startup Params). Off = classic Minima transport only: no `-keets`, no
     *  worklet/DHT, no Chat tab, no built-in Parlons client. Default on = the behaviour before 2.2.0. */
    public static final String PREF_KEETS = "keets_enabled";

    private StartupMode() {}

    public static boolean keetsEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_KEETS, true);
    }

    /** Keets "chat only" (Startup Params, with keets on): the sidecar keeps chat, media and the mailbox but
     *  carries no Minima traffic - blocks and transactions travel over classic P2P only. Default off. */
    public static final String PREF_KEETS_CHAT_ONLY = "keets_chat_only";

    public static boolean keetsChatOnly(SharedPreferences prefs) {
        return keetsEnabled(prefs) && prefs.getBoolean(PREF_KEETS_CHAT_ONLY, false);
    }

    /** The node arguments of the Keets selector: chat and network (-keets), chat only
     *  (-keets -keetschatonly) or classic only (nothing). */
    public static void addKeetsArguments(List<String> args, boolean keets, boolean chatOnly) {
        if (!keets) return;
        args.add("-keets");
        if (chatOnly) args.add("-keetschatonly");
    }

    /** Drop the flags the Keets selector owns (-keets, -keetschatonly, -keetssidecar, -keetssidecarargs)
     *  from stored extra params - a stored copy would override the selector. Whole tokens only: -keetsbootstrap,
     *  -keetsconnect, -keetsmaxconns, -keetsmailboxurl and -keetsnode stay the user's to set. */
    public static String withoutKeetsSwitchFlags(String extra) {
        return extra.replaceAll("(?<!\\S)-keets(?:sidecar(?:args)?|chatonly)?(?!\\S)", "");
    }

    /** The flags the RPC switch owns: -rpcenable and -rpcpassword, -rpcbind (the switch opens the RPC to the network
     *  the phone is on), and the node's Host / Origin allow-lists. Typed ones are refused in ParamsActivity. */
    static final String[] RPC_SWITCH_FLAGS = {"-rpcenable", "-rpcpassword", "-rpcbind", "-rpcallowhost", "-rpcalloworigin"};

    public static boolean isRpcSwitchFlag(String arg) {
        for (String f : RPC_SWITCH_FLAGS) if (f.equals(arg)) return true;
        return false;
    }

    /** Remove stored RPC flags (and the value after one) from older extra parameters, so only the switch sets them. */
    public static String withoutRpcSwitchFlags(String extra) {
        String[] tokens = extra.trim().split("\\s+");
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (isRpcSwitchFlag(tokens[i])) {
                if (i + 1 < tokens.length && !tokens[i + 1].startsWith("-")) i++;
            } else if (!tokens[i].isEmpty()) {
                if (kept.length() > 0) kept.append(' ');
                kept.append(tokens[i]);
            }
        }
        return kept.toString();
    }

    /** The node arguments of the RPC switch (ON): Basic auth always, and bound to every interface - minima-core
     *  1.1.2.31-euro13 binds the RPC to 127.0.0.1 unless -rpcbind says otherwise. */
    public static void addRpcArguments(java.util.List<String> args, String password) {
        args.add("-rpcenable");
        args.add("-rpcpassword");
        args.add(password);
        args.add("-rpcbind");
        args.add("all");
    }

    /** Tab geometry: Chat is the only optional tab. -1 = no Chat tab. */
    static int chatPosition(boolean pandaShell, boolean chat) {
        return !chat ? -1 : pandaShell ? 3 : 4;
    }

    /** The Node/Home tab: last in the panda shell (slides down when Chat goes), first in classic. */
    static int homePosition(boolean pandaShell, boolean chat) {
        return !pandaShell ? 0 : chat ? 4 : 3;
    }

    public static boolean usesBlockKeys(SharedPreferences prefs) {
        return usesBlockKeys(BuildConfig.PANDAMONIUM, BuildConfig.BLOCK_KEYUSES,
                prefs.getBoolean(PREF_CLASSIC_MODE, false));
    }

    static boolean usesBlockKeys(boolean pandamonium, boolean blockDefault, boolean classic) {
        return blockDefault && !(pandamonium && classic);
    }

    public static void addArguments(List<String> args, boolean blockMode) {
        if (blockMode) {
            args.add("-blockaskeyuses");
            args.add("-lowram");
        }
    }

    public static boolean isManagedArgument(String arg) {
        return "-blockaskeyuses".equals(arg) || "-lowram".equals(arg)
                || "-sqlcoindb".equals(arg) || "-sqltxblockdb".equals(arg);
    }

    /** Remove old free-text overrides, including their optional value, before validation.
     * The core accepts both bare switches and switches followed by true/false.
     */
    public static String withoutManagedArguments(String extra) {
        String[] tokens = extra.trim().split("\\s+");
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (isManagedArgument(tokens[i])) {
                if (i + 1 < tokens.length && !tokens[i + 1].startsWith("-")) i++;
            } else if (!tokens[i].isEmpty()) {
                if (kept.length() > 0) kept.append(' ');
                kept.append(tokens[i]);
            }
        }
        return kept.toString();
    }
}
