package com.eurobuddha.minimacore.integrated;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Native app screens retain their own activities and lifecycle within one APK.
 * DrawerLayout/container pattern reused from Minima Mail; this drawer opens on the left.
 */
public final class PandamoniumHooks {
    private static final String TAG = "pandamonium.navigation.shell";
    static final int BG = Color.rgb(18, 23, 27);
    static final int PANEL = Color.rgb(25, 32, 36);
    static final int TEXT = Color.rgb(244, 244, 235);
    static final int MUTED = Color.rgb(161, 174, 172);
    static final int ACCENT = Color.rgb(218, 225, 153);

    private static final String NAV_PREFS = "pandamonium_navigation";
    private static final String KEY_DISABLED = "app_disabled";

    private PandamoniumHooks() {}
    public static boolean requiresSecureScreen(android.app.Activity activity) {
        return (activity instanceof com.eurobuddha.wallet.MainActivity
                && ((com.eurobuddha.wallet.MainActivity) activity).isSensitiveScreen())
                || (activity instanceof com.eurobuddha.entropy.MainActivity
                && !((com.eurobuddha.entropy.MainActivity) activity).demoMode());
    }

    // ---- integrated apps (the Apps tab's Internal list) --------------------

    private static java.util.Set<Integer> disabledSet(android.content.SharedPreferences nav) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        String saved = nav.getString(KEY_DISABLED, "");
        if (saved != null && !saved.isEmpty()) for (String name : saved.split(",")) {
            int idx = PandamoniumDestinations.indexFor(name);
            if (idx > 0) out.add(idx);   // 0 (the host) can never be disabled
        }
        return out;
    }

    /** All of our integrated apps with their on/off state, for the Apps tab. */
    public static java.util.List<IntegratedApp> integratedApps(android.content.Context ctx) {
        android.content.SharedPreferences nav =
                ctx.getSharedPreferences(NAV_PREFS, android.content.Context.MODE_PRIVATE);
        java.util.Set<Integer> disabled = disabledSet(nav);
        java.util.List<IntegratedApp> out = new java.util.ArrayList<>();
        for (int i = 0; i < PandamoniumDestinations.NAMES.length; i++) {
            android.graphics.drawable.Drawable icon = androidx.appcompat.content.res.AppCompatResources
                    .getDrawable(ctx, PandamoniumDestinations.ICONS[i]);
            boolean locked = (i == 0);                       // Minima Core — the host
            boolean enabled = locked || !disabled.contains(i);
            out.add(new IntegratedApp(i, PandamoniumDestinations.NAMES[i], icon, enabled, locked));
        }
        return out;
    }

    /** Turn an integrated app on/off. Off apps drop out of the drawer until re-enabled. */
    public static void setIntegratedEnabled(android.content.Context ctx, int id, boolean enabled) {
        if (id <= 0) return;                                 // never disable the host
        android.content.SharedPreferences nav =
                ctx.getSharedPreferences(NAV_PREFS, android.content.Context.MODE_PRIVATE);
        java.util.Set<Integer> disabled = disabledSet(nav);
        if (enabled) disabled.remove(id); else disabled.add(id);
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int d : disabled) if (d > 0 && d < PandamoniumDestinations.CLASSES.length) {
            names.add(PandamoniumDestinations.CLASSES[d]);
        }
        nav.edit().putString(KEY_DISABLED, String.join(",", names)).apply();
    }

    /** The drawer's destination order with disabled apps removed (host always kept). */
    private static java.util.List<Integer> drawerOrder(android.content.SharedPreferences nav) {
        java.util.List<Integer> order = PandamoniumOrder.decode(nav.getString("app_order", ""));
        java.util.Set<Integer> disabled = disabledSet(nav);
        order.removeIf(d -> d != 0 && disabled.contains(d));
        return order;
    }

    public static void initialize(android.content.Context context) {
        com.eurobuddha.wallet.MainActivity.installScreenshotPolicy(() ->
                com.eurobuddha.minimacore.MinimaApplication.screenshotsAllowed(context));
        com.eurobuddha.minimaapi.direct.DirectNodeApi.install(new EmbeddedNodeTransport(context));
        com.eurobuddha.wallet.EmbeddedWallet.install(() ->
                EmbeddedNodeTransport.canExecute(org.minima.system.Main.getInstance())
                && (!com.eurobuddha.minimacore.lock.AppLock.isEnabled(context)
                    || com.eurobuddha.minimacore.lock.AppLock.get().isUnlocked()));
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        com.eurobuddha.minimaapi.direct.DirectNodeEvents.install(action -> main.post(action));
        com.eurobuddha.minimaapi.direct.DirectNodeEvents.subscribe(
                new com.eurobuddha.terminalide.receiver.MinimaNotifyReceiver(context),
                com.eurobuddha.minimacore.utils.BackgroundWork.pool(1, 8), "MINIMALOG");
    }

    public static void install(Activity raw) {
        if (!(raw instanceof AppCompatActivity)) return;
        AppCompatActivity activity = (AppCompatActivity) raw;
        int selected = PandamoniumDestinations.indexFor(activity.getClass().getName());
        if (selected < 0) return; // no drawer during seed entry, restoration or onboarding
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0 || content.findViewWithTag(TAG) != null) return;

        DrawerLayout drawer = new DrawerLayout(activity);
        drawer.setTag(TAG);
        drawer.setBackgroundColor(BG);
        new androidx.core.view.WindowInsetsControllerCompat(activity.getWindow(), activity.getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        drawer.setScrimColor(0x99000000);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(BG);
        header.setPadding(dp(activity, 16), 0, dp(activity, 8), 0);
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text(activity, "Minima Core", 16, TEXT);
        titles.addView(brand);
        titles.addView(text(activity, "Pandamonium", 12, MUTED));
        titles.setGravity(Gravity.CENTER);
        titles.setTag("pandamonium.navigation.brand");
        Button menu = new Button(activity);
        menu.setText("☰");
        menu.setTextSize(25);
        menu.setTextColor(ACCENT);
        menu.setBackgroundColor(Color.TRANSPARENT);
        menu.setPadding(0, 0, 0, 0);
        Language.description(menu, Language.msg(l10n_drawer_open));

        boolean coreScreen = activity instanceof com.eurobuddha.minimacore.main.MainActivity;
        boolean walletScreen = activity instanceof com.eurobuddha.wallet.MainActivity;
        if (!coreScreen) {
            if (walletScreen) header.addView(menu, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
            header.addView(titles, new LinearLayout.LayoutParams(0, dp(activity, 52), 1));
            column.addView(header);
        }
        LinearLayout original = new LinearLayout(activity);
        original.setOrientation(LinearLayout.VERTICAL);
        while (content.getChildCount() > 0) {
            View child = content.getChildAt(0);
            content.removeView(child);
            original.addView(child, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        if (!walletScreen) installMenu(activity, original, column, menu);
        if (coreScreen) {
            androidx.appcompat.widget.Toolbar toolbar = original.findViewById(com.eurobuddha.minimacore.R.id.toolbar);
            toolbar.addView(titles, new androidx.appcompat.widget.Toolbar.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }
        column.addView(original, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        drawer.addView(column, new DrawerLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PANEL);
        panel.setPadding(dp(activity, 16), dp(activity, 18), dp(activity, 16), dp(activity, 12));
        TextView heading = text(activity, "Minima Core", 25, TEXT);
        panel.addView(heading);
        TextView subtitle = text(activity, "Pandamonium", 12, MUTED);
        subtitle.setPadding(0, dp(activity, 4), 0, dp(activity, 16));
        panel.addView(subtitle);
        android.content.SharedPreferences navigation = activity.getSharedPreferences("pandamonium_navigation", android.content.Context.MODE_PRIVATE);
        RecyclerView entries = new RecyclerView(activity);
        entries.setTag("pandamonium.navigation.entries");
        entries.setLayoutManager(new LinearLayoutManager(activity));
        class AppList extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
            final java.util.List<Integer> order = drawerOrder(navigation);
            boolean dragging;
            AppList() { setHasStableIds(true); }
            void reload() {
                java.util.List<Integer> saved = drawerOrder(navigation);
                if (!dragging && !order.equals(saved)) { order.clear(); order.addAll(saved); notifyDataSetChanged(); }
            }
            @Override public long getItemId(int position) { return order.get(position); }
            @Override public int getItemCount() { return order.size(); }
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                TextView entry = text(activity, "", 16, TEXT);
                entry.setGravity(Gravity.CENTER_VERTICAL);
                entry.setPadding(dp(activity, 16), 0, dp(activity, 12), 0);
                entry.setCompoundDrawablePadding(dp(activity, 12));
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(activity, 50));
                lp.bottomMargin = dp(activity, 4);
                entry.setLayoutParams(lp);
                return new RecyclerView.ViewHolder(entry) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                final int destination = order.get(position);
                TextView entry = (TextView) holder.itemView;
                entry.setText(PandamoniumDestinations.NAMES[destination]);
                android.graphics.drawable.Drawable icon = androidx.appcompat.content.res.AppCompatResources
                        .getDrawable(activity, PandamoniumDestinations.ICONS[destination]).mutate();
                if (destination == 0) icon.setTint(destination == selected ? BG : TEXT);
                icon.setBounds(0, 0, dp(activity, 30), dp(activity, 30));
                entry.setCompoundDrawablesRelative(icon, null, null, null);
                entry.setTextColor(destination == selected ? BG : TEXT);
                Language.description(entry, destination == selected
                        ? Language.msg(l10n_drawer_selected, PandamoniumDestinations.NAMES[destination])
                        : PandamoniumDestinations.NAMES[destination]);
                entry.setSelected(destination == selected);
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(destination == selected ? ACCENT : PANEL);
                shape.setCornerRadius(dp(activity, 10));
                entry.setBackground(shape);
                entry.setOnClickListener(v -> {
                    if (dragging) return;
                    drawer.closeDrawer(Gravity.LEFT);
                    if (PandamoniumDestinations.CLASSES[destination].equals(activity.getClass().getName())) return;
                    if (destination != 0 && (MinimaService.minima == null || MinimaService.haveStartedShutdown())) {
                        Toast.makeText(activity, Language.text(activity, l10n_drawer_start_node), Toast.LENGTH_LONG).show();
                        return;
                    }
                    Intent intent = new Intent();
                    intent.setClassName(activity.getPackageName(), PandamoniumDestinations.CLASSES[destination]);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    activity.startActivity(intent);
                });
            }
        }
        AppList appList = new AppList();
        entries.setAdapter(appList);
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override public int getDragDirs(RecyclerView view, RecyclerView.ViewHolder holder) {
                return holder.getBindingAdapterPosition() > 0 ? super.getDragDirs(view, holder) : 0;
            }
            @Override public boolean onMove(RecyclerView view, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
                int start = from.getBindingAdapterPosition(), end = to.getBindingAdapterPosition();
                if (start <= 0 || end <= 0 || start >= appList.order.size() || end >= appList.order.size()) return false;
                appList.order.add(end, appList.order.remove(start));
                appList.notifyItemMoved(start, end);
                navigation.edit().putString("app_order", PandamoniumOrder.encode(appList.order)).apply();
                return true;
            }
            @Override public void onSwiped(RecyclerView.ViewHolder holder, int direction) {}
            @Override public void onSelectedChanged(RecyclerView.ViewHolder holder, int state) {
                appList.dragging = state == ItemTouchHelper.ACTION_STATE_DRAG;
                if (appList.dragging && holder != null) {
                    holder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    holder.itemView.setAlpha(.8f);
                }
                super.onSelectedChanged(holder, state);
            }
            @Override public void clearView(RecyclerView view, RecyclerView.ViewHolder holder) {
                super.clearView(view, holder);
                holder.itemView.setAlpha(1f);
            }
        }).attachToRecyclerView(entries);
        panel.addView(entries, new LinearLayout.LayoutParams(-1, 0, 1));
        Button language = new Button(activity);
        language.setTag("pandamonium.navigation.language");
        Language.setText(language, Language.msg(l10n_language_title));
        language.setAllCaps(false);
        language.setTextColor(ACCENT);
        language.setBackgroundColor(Color.TRANSPARENT);
        language.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        language.setMinHeight(dp(activity, 48));
        language.setOnClickListener(v -> {
            drawer.closeDrawer(Gravity.LEFT);
            Language.choose(activity, false);
        });
        panel.addView(language, new LinearLayout.LayoutParams(-1, -2));
        TextView mode = text(activity, modeLabel(), 11, MUTED);
        mode.setPadding(0, dp(activity, 12), 0, 0);
        panel.addView(mode);
        int width = Math.min(dp(activity, 330), (int)(activity.getResources().getDisplayMetrics().widthPixels * .88f));
        drawer.addView(panel, new DrawerLayout.LayoutParams(width, -1, Gravity.LEFT));
        drawer.setDrawerTitle(Gravity.LEFT, Language.text(activity, l10n_drawer_apps));
        menu.setOnClickListener(v -> {
            appList.reload();
            Language.setText(mode, modeLabel());
            drawer.openDrawer(Gravity.LEFT);
        });

        OnBackPressedCallback back = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { drawer.closeDrawer(Gravity.LEFT); }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, back);
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerOpened(View view) {
                appList.reload();
                Language.setText(mode, modeLabel());
                drawer.setDrawerTitle(Gravity.LEFT, Language.text(activity, l10n_drawer_apps));
                back.setEnabled(true);
            }
            @Override public void onDrawerClosed(View view) { back.setEnabled(false); menu.requestFocus(); }
        });
        content.addView(drawer, new ViewGroup.LayoutParams(-1, -1));
        ViewCompat.setOnApplyWindowInsetsListener(drawer, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // DrawerLayout positions children using margins, ignoring its own padding.
            // Keep both the header and drawer title below the status bar/cutout.
            DrawerLayout.LayoutParams bodyParams = (DrawerLayout.LayoutParams) column.getLayoutParams();
            DrawerLayout.LayoutParams panelParams = (DrawerLayout.LayoutParams) panel.getLayoutParams();
            if (bodyParams.topMargin != bars.top || bodyParams.leftMargin != bars.left || bodyParams.rightMargin != bars.right) {
                bodyParams.topMargin = bars.top; bodyParams.leftMargin = bars.left; bodyParams.rightMargin = bars.right;
                column.setLayoutParams(bodyParams);
            }
            if (panelParams.topMargin != bars.top || panelParams.leftMargin != bars.left) {
                panelParams.topMargin = bars.top; panelParams.leftMargin = bars.left;
                panel.setLayoutParams(panelParams);
            }
            panel.setPadding(dp(activity, 16), dp(activity, 18), dp(activity, 16), dp(activity, 12) + bars.bottom);
            // Existing app roots still handle bottom/IME insets. Avoid counting the status bar twice.
            return new WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, bars.bottom))
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.NONE).build();
        });
        ViewCompat.requestApplyInsets(drawer);
    }

    public static com.eurobuddha.minimacore.main.BaseView createUtilityTab(
            com.eurobuddha.minimacore.main.MainActivity activity) {
        return new HistoryTabView(activity);
    }

    private static void installMenu(AppCompatActivity activity, ViewGroup original,
                                    LinearLayout column, Button menu) {
        // An app that REBUILDS its header at runtime (AtomiX re-renders every ~30s)
        // draws its own nav button each rebuild and opts out here - anything we
        // insert once would die with the first rebuild.
        if (original.findViewWithTag("pandamonium.app.toolbar.self") != null) return;
        View target = activity instanceof com.eurobuddha.minimacore.main.MainActivity
                ? original.findViewById(com.eurobuddha.minimacore.R.id.toolbar)
                : original.findViewWithTag("pandamonium.app.toolbar");
        if (target instanceof androidx.appcompat.widget.Toolbar) {
            androidx.appcompat.widget.Toolbar toolbar = (androidx.appcompat.widget.Toolbar) target;
            if (activity instanceof com.eurobuddha.minimacore.main.MainActivity) toolbar.setTitle("");
            androidx.appcompat.graphics.drawable.DrawerArrowDrawable icon =
                    new androidx.appcompat.graphics.drawable.DrawerArrowDrawable(activity);
            icon.setColor(ACCENT);
            toolbar.setNavigationIcon(icon);
            Language.navigationDescription(toolbar, Language.msg(l10n_drawer_open));
            toolbar.setNavigationOnClickListener(v -> menu.performClick());
        } else if (target instanceof LinearLayout && ((LinearLayout) target).getOrientation() == LinearLayout.HORIZONTAL) {
            ((LinearLayout) target).addView(menu, 0, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        } else if (target != null && target.getParent() instanceof ViewGroup) {
            // Entropy's header is vertical; keep its controls together beside navigation.
            ViewGroup parent = (ViewGroup) target.getParent();
            int index = parent.indexOfChild(target);
            ViewGroup.LayoutParams params = target.getLayoutParams();
            parent.removeView(target);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(menu, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
            row.addView(target, new LinearLayout.LayoutParams(0, -2, 1));
            parent.addView(row, index, params);
        } else {
            // Secondary IDE editor has no app header tag; navigation remains below the brand.
            LinearLayout row = new LinearLayout(activity);
            row.addView(menu, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
            column.addView(row);
        }
    }

    private static CharSequence modeLabel() {
        return org.minima.system.params.GeneralParams.USE_BLOCK_AS_KEYUSES
                ? Language.msg(l10n_drawer_block_mode) : Language.msg(l10n_drawer_classic_mode);
    }

    static TextView text(Activity a, CharSequence value, int size, int color) {
        TextView view = new TextView(a); Language.setText(view, value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return view;
    }
    static int dp(Activity a, int value) { return Math.round(value * a.getResources().getDisplayMetrics().density); }
}
