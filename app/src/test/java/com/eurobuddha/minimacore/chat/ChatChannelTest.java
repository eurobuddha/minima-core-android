package com.eurobuddha.minimacore.chat;

import static org.junit.Assert.*;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import com.eurobuddha.minimacore.i18n.Language;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The chat channel must be HIGH, vibrate, carry the pssst sound, and retire the v1 id. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ChatChannelTest {

    private Context ctx;
    private NotificationManager nm;

    @Before public void setup() {
        ctx = RuntimeEnvironment.getApplication();
        Language.initialize(RuntimeEnvironment.getApplication(), () -> false);
        nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Test public void createsAudibleChannelAndRetiresV1() {
        nm.createNotificationChannel(new NotificationChannel("MinimaChatChannel", "old", NotificationManager.IMPORTANCE_HIGH));

        ChatDrainer.createChannel(ctx);

        NotificationChannel ch = nm.getNotificationChannel(ChatDrainer.CHANNEL_ID);
        assertNotNull(ch);
        assertEquals("MinimaChatChannel_v2", ChatDrainer.CHANNEL_ID);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.getImportance());
        assertTrue(ch.shouldVibrate());
        assertNotNull(ch.getSound());
        assertTrue(ch.getSound().toString(), ch.getSound().toString().endsWith("/raw/pssst"));
        assertTrue(ch.getSound().toString().startsWith("android.resource://" + ctx.getPackageName() + "/"));
        assertNull(nm.getNotificationChannel("MinimaChatChannel"));

        // idempotent: a second call keeps the channel and never throws
        ChatDrainer.createChannel(ctx);
        assertNotNull(nm.getNotificationChannel(ChatDrainer.CHANNEL_ID));
    }
}
