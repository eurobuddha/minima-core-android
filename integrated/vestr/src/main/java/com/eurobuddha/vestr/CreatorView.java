package com.eurobuddha.vestr;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Creator tab — Calculate / Create + your vesting contracts. */
public class CreatorView extends BaseView {

    private final LinearLayout container;

    /** Inflate the shared page layout, grab its scroll container, and draw the initial contract list. */
    public CreatorView(MainActivity a) {
        super(a, R.layout.pm_vestr_view_page);
        container = find(R.id.pm_vestr_pageContainer);
        refresh();
    }

    /** When the tab becomes visible, pull fresh node data then redraw — keeps the contract list current on return. */
    @Override public void onShown() { act.requestReload(); refresh(); }

    /** Rebuild the whole tab: Create + Calculate actions, then the user's contracts (or an empty-state hint). */
    @Override
    public void refresh() {
        container.removeAllViews();

        // Create flow hands the vesting script address to the activity so it can lock funds to that contract.
        container.addView(button("＋  Create a contract", true, v -> {
            Intent i = new Intent(act, CreateContractActivity.class);
            i.putExtra("script", act.scriptAddress());
            act.startActivity(i);
        }));
        container.addView(button("≈  Calculate a contract", false,
                v -> act.startActivity(new Intent(act, CalculatorActivity.class))));

        TextView h = new TextView(act);
        h.setText("My contracts");
        h.setTextColor(VestrDesign.TEXT);
        h.setTextSize(16f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dp(20), 0, dp(12));
        container.addView(h);

        List<Contract> contracts = act.contracts();
        if (contracts.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("You have no vesting contracts yet.");
            empty.setTextColor(VestrDesign.DIM);
            empty.setTextSize(14f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, 0);
            container.addView(empty);
        } else {
            // Reuse the shared card renderer so Creator and Collector show identical contract rows.
            for (Contract c : contracts) container.addView(ContractUi.card(act, c));
        }
    }

    /** Build a rounded full-width action button (TextView, not Button, for the flat look); {@code primary} = yellow accent. */
    private View button(String text, boolean primary, View.OnClickListener onClick) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextColor(primary ? VestrDesign.ON_YELLOW : VestrDesign.TEXT_ON_DARK);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(14), 0, dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? VestrDesign.YELLOW : VestrDesign.BLACK);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(onClick);
        return b;
    }
}
