package com.eurobuddha.minimacore.integrated;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.card.MaterialCardView;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.service.MinimaService;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** One drawer destination for the three existing native Mall activities. */
public final class MallActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PandamoniumHooks.BG);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setTag("pandamonium.app.toolbar");
        toolbar.addView(label("miniMall", 22, PandamoniumHooks.TEXT));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(60)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(12), dp(20), dp(24));
        TextView intro = label(Language.msg(l10n_mall_intro), 16, PandamoniumHooks.MUTED);
        intro.setTag("pandamonium.mall.intro");
        intro.setPadding(0, 0, 0, dp(26));
        body.addView(intro);
        addChoice(body, "shop", "miniMall", l10n_mall_shop_description,
                com.eurobuddha.merchshop.R.mipmap.pm_merchshop_ic_launcher,
                com.eurobuddha.merchshop.MainActivity.class);
        addChoice(body, "studio", "miniMall Studio", l10n_mall_studio_description,
                com.eurobuddha.merchstudio.R.mipmap.pm_merchstudio_ic_launcher,
                com.eurobuddha.merchstudio.MainActivity.class);
        addChoice(body, "inbox", "miniMall Inbox", l10n_mall_inbox_description,
                com.eurobuddha.merchinbox.R.mipmap.pm_merchinbox_ic_launcher,
                com.eurobuddha.merchinbox.MainActivity.class);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            root.setPadding(0, 0, 0, bottom);
            return insets;
        });
    }

    private void addChoice(LinearLayout body, String tag, String name, int description,
                           int icon, Class<? extends Activity> destination) {
        MaterialCardView card = new MaterialCardView(this);
        card.setTag("pandamonium.mall." + tag);
        card.setCardBackgroundColor(PandamoniumHooks.PANEL);
        card.setStrokeColor(0xFF344144);
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(16));
        card.setCardElevation(0);
        card.setRippleColor(ColorStateList.valueOf(0x33DAE199));
        card.setContentPadding(dp(18), dp(20), dp(18), dp(20));
        card.setClickable(true);
        card.setFocusable(true);
        Language.description(card, Language.join(name, ". ", Language.msg(description)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(image, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(16), 0, dp(10), 0);
        text.addView(label(name, 18, PandamoniumHooks.TEXT));
        TextView detail = label(Language.msg(description), 14, PandamoniumHooks.MUTED);
        detail.setPadding(0, dp(6), 0, 0);
        text.addView(detail);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = label("›", 28, PandamoniumHooks.ACCENT);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow);
        card.addView(row);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(14);
        body.addView(card, params);
        card.setOnClickListener(view -> {
            if (MinimaService.minima == null || MinimaService.haveStartedShutdown()) {
                Toast.makeText(this, Language.text(this, l10n_drawer_start_node), Toast.LENGTH_LONG).show();
                return;
            }
            startActivity(new Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        });
    }

    private TextView label(CharSequence value, int size, int color) {
        return PandamoniumHooks.text(this, value, size, color);
    }
    private int dp(int value) { return PandamoniumHooks.dp(this, value); }
}
