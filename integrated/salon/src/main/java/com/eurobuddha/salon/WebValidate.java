package com.eurobuddha.salon;

import android.app.Activity;
import android.view.View;
import java.util.function.Consumer;
import com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.Result;

/** Use the host wallet verifier. The owning view retains its weak subscription. */
public final class WebValidate {
    private WebValidate() {}

    public static Boolean status(String tokenid, String url) {
        return com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.status(tokenid, url);
    }

    public static void ensure(Activity activity, View owner, String tokenid, String url, Runnable onDone) {
        final int tag = com.eurobuddha.minimaapi.R.id.embedded_web_validation_callback;
        // Rebinding to an already-cached URL also invalidates the previous subscription.
        owner.setTag(tag, null);
        if (activity.isFinishing() || activity.isDestroyed()
                || !com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.needsCheck(tokenid, url)) return;
        Consumer<Result> callback = new Consumer<Result>() {
            @Override public void accept(Result result) {
                // A recycled view may already own a newer request.
                if (owner.getTag(tag) != this) return;
                owner.setTag(tag, null);
                if (!activity.isFinishing() && !activity.isDestroyed() && onDone != null) onDone.run();
            }
        };
        owner.setTag(tag, callback);
        com.eurobuddha.minimacore.main.views.balance.tokens.WebValidate.ensure(tokenid, url, callback);
    }
}
