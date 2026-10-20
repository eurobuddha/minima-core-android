package com.eurobuddha.minimacore.integrated;
public final class PandamoniumHooks {
    private PandamoniumHooks() {}
    public static boolean requiresSecureScreen(android.app.Activity activity) { return false; }
    public static com.eurobuddha.minimacore.main.BaseView createUtilityTab(com.eurobuddha.minimacore.main.MainActivity activity) {
        // BlackBear's utility tab is History (Terminal lives in the overflow menu).
        return new HistoryTabView(activity);
    }
    public static void initialize(android.content.Context context) {
        // History talks to the embedded node via DirectNodeApi and listens on DirectNodeEvents.
        // No integrated-app host, wallet or salon here — only the node API History needs.
        com.eurobuddha.minimaapi.direct.DirectNodeApi.install(new EmbeddedNodeTransport(context));
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        com.eurobuddha.minimaapi.direct.DirectNodeEvents.install(action -> main.post(action));
    }
    public static void install(android.app.Activity activity) {}
    // No integrated-app drawer in this build — the Apps tab shows External only.
    public static java.util.List<IntegratedApp> integratedApps(android.content.Context ctx) {
        return java.util.Collections.emptyList();
    }
    public static void setIntegratedEnabled(android.content.Context ctx, int id, boolean enabled) {}
}
