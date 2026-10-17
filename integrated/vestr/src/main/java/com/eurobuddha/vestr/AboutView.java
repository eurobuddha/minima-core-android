package com.eurobuddha.vestr;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** About tab — vestr intro + entry into the Creator / Collector flows. */
public class AboutView extends BaseView {

    private final LinearLayout container;

    /** Inflate the shared page layout, grab its scroll container, and build the static About content once. */
    public AboutView(MainActivity a) {
        super(a, R.layout.pm_vestr_view_page);
        container = find(R.id.pm_vestr_pageContainer);
        build();
    }

    /** No-op — the About tab is static, so a data reload has nothing to redraw. */
    @Override public void refresh() {}

    /** Compose the About screen programmatically: title, blurb, two CTAs, footer (no XML — keeps styling in VestrDesign). */
    private void build() {
        container.removeAllViews();

        TextView title = new TextView(act);
        Language.setText(title, "vestr");
        title.setTextColor(VestrDesign.TEXT);
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(title);

        TextView blurb = new TextView(act);
        Language.setText(blurb, Language.msg(l10n_vestr_intro));
        blurb.setTextColor(VestrDesign.DIM);
        blurb.setTextSize(15f);
        blurb.setLineSpacing(0, 1.3f);
        blurb.setPadding(0, dp(10), 0, dp(28));
        container.addView(blurb);

        // Two entry points into the main flows — primary (yellow) routes to Creator, secondary (black) to Collector.
        container.addView(cta(Language.msg(l10n_vestr_create), true, v -> act.goToTab(MainActivity.TAB_CREATOR)));
        container.addView(cta(Language.msg(l10n_vestr_collect_from), false, v -> act.goToTab(MainActivity.TAB_COLLECTOR)));

        TextView foot = new TextView(act);
        Language.setText(foot, Language.msg(l10n_vestr_footer));
        foot.setTextColor(VestrDesign.DIM_2);
        foot.setTextSize(11f);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(28), 0, 0);
        container.addView(foot);
    }

    /** Build a full-width call-to-action button; {@code primary} picks the yellow accent vs the black secondary style. */
    private Button cta(CharSequence text, boolean primary, android.view.View.OnClickListener onClick) {
        Button b = new Button(act);
        Language.setText(b, text);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? VestrDesign.YELLOW : VestrDesign.BLACK));
        b.setTextColor(primary ? VestrDesign.ON_YELLOW : VestrDesign.TEXT_ON_DARK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        b.setLayoutParams(lp);
        b.setOnClickListener(onClick);
        return b;
    }
}
