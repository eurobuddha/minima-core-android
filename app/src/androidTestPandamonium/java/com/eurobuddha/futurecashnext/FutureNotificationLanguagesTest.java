package com.eurobuddha.futurecashnext;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Self-app notifications only, with public strings and no service/node startup. */
@RunWith(AndroidJUnit4.class)
public class FutureNotificationLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private NotificationManager manager;
    @Before public void guard() {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
        if(Build.VERSION.SDK_INT>=33)InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
        manager=context.getSystemService(NotificationManager.class);
        context.getSharedPreferences("futurecash_guardian",0).edit().clear().commit();
        cleanupNotifications();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Language.select(context,"en");Notifier.ensureChannels(context);});
    }
    @After public void cleanup() {
        if(!"ranchu".equals(Build.HARDWARE)||!"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
        cleanupNotifications();context.getSharedPreferences("futurecash_guardian",0).edit().clear().commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,"en"));
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
    }
    private void cleanupNotifications() {
        if(manager==null)return;
        for(StatusBarNotification n:manager.getActiveNotifications())if("pandamonium.futurecashnext".equals(n.getTag()) || n.getTag()==null&&n.getId()==3101)manager.cancel(n.getTag(),n.getId());
    }
    private static Object[] settings(NotificationChannel c) {
        return new Object[]{c.getImportance(),c.getSound(),c.getAudioAttributes(),c.shouldVibrate(),Arrays.toString(c.getVibrationPattern()),c.canShowBadge(),c.getLockscreenVisibility(),c.canBypassDnd()};
    }
    private StatusBarNotification find(String tag,int id,String expectedBody)throws Exception {
        long end=android.os.SystemClock.elapsedRealtime()+3000;
        do {
            for(StatusBarNotification n:manager.getActiveNotifications())if(Objects.equals(tag,n.getTag()) && (id<0 || n.getId()==id))
                if(expectedBody==null || expectedBody.contentEquals(n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT,"")))return n;
            Thread.sleep(30);
        }while(android.os.SystemClock.elapsedRealtime()<end);
        throw new AssertionError("Expected self-app notification was not posted");
    }
    @Test public void translatedAlertsKeepAmountsChannelSettingsAndForegroundIdentity()throws Exception {
        Object[] fg=settings(manager.getNotificationChannel(Notifier.CH_FG)),alerts=settings(manager.getNotificationChannel(Notifier.CH_ALERT));
        manager.notify(3101,new Notification.Builder(context,Notifier.CH_FG).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("public sentinel").setContentText("public fixture").build());
        for(String code:new String[]{"en","zh-Hans","ru","uk"}) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,code));
            NotificationChannel channel=manager.getNotificationChannel(Notifier.CH_ALERT);
            assertEquals(Language.text(context,l10n_future_notice_alerts),channel.getName().toString());assertEquals(Language.text(context,l10n_future_notice_alerts_help),channel.getDescription());
            assertArrayEquals(fg,settings(manager.getNotificationChannel(Notifier.CH_FG)));assertArrayEquals(alerts,settings(channel));
            String expected=Language.msg(l10n_future_notice_secured,"00012.34000").toString();
            Notifier.alert(context,"Future Cash","Secured 00012.34000 to your safe address");
            StatusBarNotification posted=find("pandamonium.futurecashnext",-1,expected);
            assertEquals("Future Cash",posted.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString());
            assertEquals(expected,posted.getNotification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString());assertEquals(Notifier.CH_ALERT,posted.getNotification().getChannelId());
            assertEquals("public sentinel",find(null,3101,"public fixture").getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString());
            manager.cancel(posted.getTag(),posted.getId());
        }
        String raw="Unrecognized diagnostic: 0xAbCd Україна 中文 $1 {0}";
        Notifier.alert(context,"Public fixture",raw);StatusBarNotification unknown=find("pandamonium.futurecashnext",-1,raw);
        assertEquals(raw,unknown.getNotification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString());
    }
    private static final class ServiceFixture extends GuardianService {
        void attach(Context context){attachBaseContext(context);}
    }
    @Test public void serviceConfigurationRefreshesOnlyAnActiveForegroundNotice()throws Exception {
        ServiceFixture service=new ServiceFixture();service.attach(context);
        Field active=GuardianService.class.getDeclaredField("foregroundActive");active.setAccessible(true);
        Cfg cfg=new Cfg(context);cfg.setBool(Cfg.GUARDIAN_ON,true);
        service.onConfigurationChanged(new Configuration(context.getResources().getConfiguration()));
        for(StatusBarNotification n:manager.getActiveNotifications())assertFalse(n.getTag()==null&&n.getId()==3101);
        active.setBoolean(service,true);
        for(String code:new String[]{"en","zh-Hans","ru","uk"})for(boolean auto:new boolean[]{false,true}) {
            cfg.setBool(Cfg.AUTO_COLLECT_SAFE,auto);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                Language.select(context,code);service.onConfigurationChanged(new Configuration(context.getResources().getConfiguration()));
            });
            String expected=Language.text(context,auto?l10n_future_notice_auto_on:l10n_future_notice_auto_off);
            Notification n=find(null,3101,expected).getNotification();
            assertEquals(Language.text(context,l10n_future_notice_title),n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
            assertEquals(expected,n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString());assertTrue((n.flags&Notification.FLAG_ONGOING_EVENT)!=0);
        }
        active.setBoolean(service,false);manager.cancel(3101);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->service.onConfigurationChanged(new Configuration(context.getResources().getConfiguration())));
        for(StatusBarNotification n:manager.getActiveNotifications())assertFalse(n.getTag()==null&&n.getId()==3101);
    }
}
