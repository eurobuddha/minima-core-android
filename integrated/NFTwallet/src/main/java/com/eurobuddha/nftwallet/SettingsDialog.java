package com.eurobuddha.nftwallet;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.Set;

/** Settings: theme picker (all four Design modes), hidden-tokens manager, pairing status, about. */
public final class SettingsDialog {

    private SettingsDialog() {}

    public static void show(final MainActivity act) {
        final android.app.Dialog[] dlgRef = new android.app.Dialog[1];
        final Sheet sheet = Sheet.create(act, Language.msg(l10n_wallet_settings));
        LinearLayout box = sheet.body();

        // ---- theme ----
        box.addView(section(act, Language.upper(Language.msg(l10n_mail_theme))));
        RadioGroup group = new RadioGroup(act);
        Design.Mode[] modes = {Design.Mode.CURRENT, Design.Mode.CLEAN_LIGHT,
                Design.Mode.ORIGINAL_LIGHT, Design.Mode.ORIGINAL_DARK};
        CharSequence[] labels = {Language.msg(l10n_nft_wallet_theme_dark), Language.msg(l10n_nft_wallet_theme_clean),
                Language.msg(l10n_nft_wallet_theme_original_light), Language.msg(l10n_nft_wallet_theme_original_dark)};
        for (int i = 0; i < modes.length; i++) {
            final Design.Mode m = modes[i];
            RadioButton rb = new RadioButton(act);
            Language.setText(rb, labels[i]);
            rb.setTextColor(Design.text());
            rb.setTextSize(13f);
            rb.setChecked(Design.mode() == m);
            rb.setOnClickListener(v -> {
                if (dlgRef[0] != null) dlgRef[0].dismiss();   // avoid a WindowLeaked on recreate
                Design.set(act, m);
                act.recreate();
            });
            group.addView(rb);
        }
        box.addView(group);

        // ---- amount display ----
        box.addView(section(act, Language.msg(l10n_nft_wallet_decimal_places)));
        TextView dnote = new TextView(act);
        Language.setText(dnote, Language.msg(l10n_nft_wallet_decimal_help));
        dnote.setTextColor(Design.dim());
        dnote.setTextSize(11f);
        box.addView(dnote);

        RadioGroup dgroup = new RadioGroup(act);
        final int[] places = {2, 4, 8, Format.FULL};
        for (final int d : places) {
            RadioButton rb = new RadioButton(act);
            Language.setText(rb, d == Format.FULL ? Language.msg(l10n_nft_wallet_full_precision) : Language.msg(l10n_nft_wallet_precision_places, d));
            rb.setTextColor(Design.text());
            rb.setTextSize(13f);
            rb.setChecked(Format.decimals() == d);
            rb.setOnClickListener(v -> {
                Format.setDecimals(act, d);
                act.refreshAll();           // repaint every list with the new precision
            });
            dgroup.addView(rb);
        }
        box.addView(dgroup);

        // ---- hidden tokens ----
        box.addView(section(act, Language.msg(l10n_nft_wallet_hidden_tokens)));
        Set<String> hidden = HiddenTokens.all(act);
        if (hidden.isEmpty()) {
            TextView none = new TextView(act);
            Language.setText(none, Language.msg(l10n_nft_wallet_none_hidden));
            none.setTextColor(Design.dim());
            none.setTextSize(12f);
            box.addView(none);
        } else {
            for (final String tid : hidden) {
                LinearLayout r = new LinearLayout(act);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(0, dp(act, 5), 0, dp(act, 5));
                TextView t = new TextView(act);
                CharSequence name = tid;
                for (TokenBalance b : act.balances()) {
                    if (b.tokenid.equals(tid)) { name = Language.join(NftText.token(b), "  ·  ", Util.shorten(tid)); break; }
                }
                if (name.equals(tid)) name = Util.shorten(tid);
                Language.setText(t, name);
                t.setTextColor(Design.text());
                t.setTextSize(12f);
                t.setTypeface(android.graphics.Typeface.MONOSPACE);
                r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView un = new TextView(act);
                Language.setText(un, Language.msg(l10n_nft_wallet_unhide));
                un.setTextColor(Design.accentText());
                un.setTextSize(10f);
                un.setLetterSpacing(0.08f);
                un.setPadding(dp(act, 8), dp(act, 4), 0, dp(act, 4));
                un.setOnClickListener(v -> {
                    HiddenTokens.unhide(act, tid);
                    r.setAlpha(0.3f);
                    Language.setText(un, Language.msg(l10n_nft_wallet_unhidden));
                    act.refreshAll();
                });
                r.addView(un);
                box.addView(r);
            }
        }

        // ---- node ----
        box.addView(section(act, Language.upper(Language.msg(l10n_tab_node))));
        NodeApi node = act.node();
        TextView n = new TextView(act);
        boolean paired = node != null && node.isEnabled();
        long ok = node == null ? 0 : node.lastOkMs();
        CharSequence last = ok <= 0 ? Language.msg(l10n_nft_wallet_never) : Language.since(ok);
        Language.setText(n, Language.join(paired ? Language.msg(l10n_nft_wallet_connected) : Language.msg(l10n_explorer_node_retry), "\n", Language.msg(l10n_nft_wallet_last_reply, last)));
        n.setTextColor(paired ? Design.success() : Design.amber());
        n.setTextSize(12f);
        box.addView(n);

        // ---- about ----
        box.addView(section(act, Language.upper(Language.msg(l10n_mail_about))));
        TextView a = new TextView(act);
        String ver;
        try { ver = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName; }
        catch (Exception e) { ver = "?"; }
        Language.setText(a, Language.msg(l10n_nft_wallet_about, ver));
        a.setTextColor(Design.dim());
        a.setTextSize(12f);
        box.addView(a);

        sheet.action(Language.msg(l10n_language_close), Sheet.Style.SECONDARY, null);
        dlgRef[0] = sheet.show();
    }

    private static TextView section(MainActivity act, CharSequence label) {
        TextView t = new TextView(act);
        Language.setText(t, label);
        t.setTextColor(Design.dim());
        t.setTextSize(11f);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, dp(act, 14), 0, dp(act, 6));
        return t;
    }

    private static int dp(MainActivity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }
}
