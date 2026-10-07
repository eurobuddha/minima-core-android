package com.eurobuddha.minimacore.integrated;
public final class PandamoniumHooks {
    private PandamoniumHooks() {}
    public static com.eurobuddha.minimacore.main.BaseView createUtilityTab(com.eurobuddha.minimacore.main.MainActivity activity) {
        return new com.eurobuddha.minimacore.main.views.terminal.TerminalView(activity);
    }
    public static void initialize(android.content.Context context) {}
    public static void install(android.app.Activity activity) {}
}
