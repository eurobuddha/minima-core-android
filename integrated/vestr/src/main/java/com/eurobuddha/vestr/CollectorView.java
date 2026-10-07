package com.eurobuddha.vestr;

import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Collector tab — your vesting contracts; open one to collect what has vested. */
public class CollectorView extends BaseView {

    private final LinearLayout container;

    /** Inflate the shared page layout, grab its scroll container, and draw the initial contract list. */
    public CollectorView(MainActivity a) {
        super(a, R.layout.pm_vestr_view_page);
        container = find(R.id.pm_vestr_pageContainer);
        refresh();
    }

    /** When the tab becomes visible, pull fresh node data then redraw — vested amounts move with the chain tip. */
    @Override public void onShown() { act.requestReload(); refresh(); }

    /** Rebuild the whole tab: heading + blurb, then the user's contracts (or an empty-state hint) to tap and collect. */
    @Override
    public void refresh() {
        container.removeAllViews();

        TextView h = new TextView(act);
        h.setText("Collect");
        h.setTextColor(VestrDesign.TEXT);
        h.setTextSize(22f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, 0, 0, dp(6));
        container.addView(h);

        TextView blurb = new TextView(act);
        blurb.setText("Open a contract to withdraw the tokens that have vested.");
        blurb.setTextColor(VestrDesign.DIM);
        blurb.setTextSize(14f);
        blurb.setPadding(0, 0, 0, dp(16));
        container.addView(blurb);

        List<Contract> contracts = act.contracts();
        if (contracts.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("Nothing to collect yet.");
            empty.setTextColor(VestrDesign.DIM);
            empty.setTextSize(14f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, 0);
            container.addView(empty);
        } else {
            // Same shared card as the Creator tab; tapping a card opens the detail/collect screen.
            for (Contract c : contracts) container.addView(ContractUi.card(act, c));
        }
    }
}
