package com.eurobuddha.minimacore.maxchat;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.eurobuddha.minimacore.R;
import com.eurobuddha.minimacore.chat.ChatDrainer;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.ChatMedia;
import com.eurobuddha.maxima.core.chat.ChatPay;
import com.eurobuddha.maxima.core.contacts.Contact;

/**
 * One notification per inbound Parlons message, on the same channel as the
 * keets chat so the user has ONE "Chat messages" switch to flip. Taps open
 * the Chat tab; thread deep-links come with the thread UI.
 */
final class MaxChatNotifier {

    private MaxChatNotifier() {
    }

    static void onInbound(Context zCtx, MaximaNode zNode, ChatEngine.Entry zEntry) {
        try {
            Contact c = zNode.contact(zEntry.sender);
            String who = c != null && !c.name.isEmpty() ? c.name : "Parlons contact";
            String what = ChatMedia.isMedia(zEntry.body) ? "📷 Photo"
                    : ChatPay.isPayment(zEntry.body) ? "💸 Payment"
                    : zEntry.body;

            Intent it = zCtx.getPackageManager()
                    .getLaunchIntentForPackage(zCtx.getPackageName());
            if (it == null) {
                return;
            }
            it.putExtra("open", "chat");
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(zCtx, 7, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new NotificationCompat.Builder(zCtx, ChatDrainer.CHANNEL_ID)
                    .setContentTitle(who)
                    .setContentText(what)
                    .setSmallIcon(R.drawable.ic_minima)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            NotificationManager nm = (NotificationManager)
                    zCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(zEntry.sender, 7, n);   // tag form: collision-free per peer
        } catch (Exception ignored) {
        }
    }
}
