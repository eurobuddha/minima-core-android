package com.eurobuddha.minimacore.main;

import android.content.SharedPreferences;
import com.eurobuddha.minimacore.BuildConfig;
import java.util.List;

/** Saved startup policy. Reading it must never change the running node's globals. */
public final class StartupMode {
    public static final String PREF_CLASSIC_MODE = "PARAM_CLASSIC_MODE";

    private StartupMode() {}

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
