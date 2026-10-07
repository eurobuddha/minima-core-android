package com.eurobuddha.minimacore.integrated;

/** Explicit allowlist: navigation never launches an external package or untrusted class name. */
public final class PandamoniumDestinations {
    private PandamoniumDestinations() {}
    public static final String[] NAMES = {
        "Minima Core", "ETH Wallet", "AtomiX", "PandaPools", "Future Cash Next", "PandaDEX",
        "Zero Edge Casino", "Minima Explorer", "Filez", "Minima Vestr", "Entropy", "Terminal IDE", "MinimaMail"
    };
    public static final String[] CLASSES = {
        "com.eurobuddha.minimacore.main.MainActivity", "com.eurobuddha.ethwallet.MainActivity",
        "com.eurobuddha.atomix.MainActivity", "com.eurobuddha.pandapools.MainActivity",
        "com.eurobuddha.futurecashnext.MainActivity", "com.eurobuddha.pandadex.MainActivity",
        "com.eurobuddha.casino.MainActivity", "com.eurobuddha.blockexplorer.MainActivity",
        "com.eurobuddha.filez.MainActivity",
        "com.eurobuddha.vestr.MainActivity",
        "com.eurobuddha.entropy.MainActivity", "com.eurobuddha.terminalide.MainActivity", "com.eurobuddha.mail.MainActivity"
    };
    public static int indexFor(String activityClass) {
        for (int i = 0; i < CLASSES.length; i++) {
            if (CLASSES[i].equals(activityClass)) return i;
        }
        if (activityClass.equals("com.eurobuddha.terminalide.ide.ScriptEditorActivity")) return indexFor("com.eurobuddha.terminalide.MainActivity");
        return -1;
    }
}
