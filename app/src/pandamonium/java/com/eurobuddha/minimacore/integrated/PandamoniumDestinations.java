package com.eurobuddha.minimacore.integrated;

/** Explicit allowlist: navigation never launches an external package or untrusted class name. */
public final class PandamoniumDestinations {
    private PandamoniumDestinations() {}
    public static final String[] NAMES = {
        "Minima Core", "ETH Wallet", "AtomiX", "PandaPools", "Future Cash Next", "PandaDEX",
        "Zero Edge Casino", "Minima Explorer", "Filez", "Minima Vestr", "Entropy", "Terminal IDE", "MinimaMail"
    };
    // Existing launcher artwork, indexed by destination identity rather than display order.
    public static final int[] ICONS = {
        com.eurobuddha.minimacore.R.drawable.ic_minima,
        com.eurobuddha.ethwallet.R.mipmap.pm_ethwallet_ic_launcher,
        com.eurobuddha.atomix.R.mipmap.pm_atomix_ic_launcher,
        com.eurobuddha.pandapools.R.mipmap.pm_pandapools_ic_launcher,
        com.eurobuddha.futurecashnext.R.mipmap.pm_futurecash_next_ic_launcher,
        com.eurobuddha.pandadex.R.mipmap.pm_pandadex_ic_launcher,
        com.eurobuddha.casino.R.mipmap.pm_casino_ic_launcher,
        com.eurobuddha.blockexplorer.R.mipmap.pm_blockexplorer_ic_launcher,
        com.eurobuddha.filez.R.mipmap.pm_filez_ic_launcher,
        com.eurobuddha.vestr.R.mipmap.pm_vestr_ic_launcher,
        com.eurobuddha.entropy.R.mipmap.pm_entropy_ic_launcher,
        com.eurobuddha.terminalide.R.mipmap.pm_terminalide_ic_launcher,
        com.eurobuddha.mail.R.mipmap.pm_mail_ic_launcher
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
