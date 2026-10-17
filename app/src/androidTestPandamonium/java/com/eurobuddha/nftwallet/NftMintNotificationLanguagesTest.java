package com.eurobuddha.nftwallet;
import android.app.*;
import android.content.Context;
import android.content.res.Configuration;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftShellLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Uses FutureNotificationLanguagesTest's attached service fixture; no onCreate/node startup. */
@RunWith(AndroidJUnit4.class)
public class NftMintNotificationLanguagesTest {
    final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    NotificationManager manager;boolean authorized;
    @Before public void prepare(){guards.prepare();authorized=true;manager=context.getSystemService(NotificationManager.class);InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);clean();}
    void clean(){for(StatusBarNotification n:manager.getActiveNotifications())if(n.getTag()==null&&n.getId()==7501||"pandamonium.nftwallet".equals(n.getTag()))manager.cancel(n.getTag(),n.getId());}
    @After public void reset(){if(authorized){clean();JSONArray rows=LocalStore.load(context);assertTrue(rows.length()<=1);if(rows.length()==1){assertEquals(NftMintLanguagesTest.ID,rows.optJSONObject(0).optLong("id"));LocalStore.save(context,new JSONArray());}}guards.reset();}
    static class ServiceFixture extends MintService{void attach(Context c){attachBaseContext(c);}}
    static Object[] settings(NotificationChannel c){return new Object[]{c.getImportance(),c.getSound(),c.getAudioAttributes(),c.shouldVibrate(),Arrays.toString(c.getVibrationPattern()),c.canShowBadge(),c.getLockscreenVisibility(),c.canBypassDnd()};}
    Notification find(String expected)throws Exception{
        for(int attempt=0;attempt<100;attempt++){for(StatusBarNotification n:manager.getActiveNotifications())if(n.getTag()==null&&n.getId()==7501&&expected.contentEquals(n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT,"")))return n.getNotification();Thread.sleep(30);}throw new AssertionError("Missing mint progress notification");
    }
    @Test public void localeRefreshPreservesCollectionNameProgressAndChannelSettings()throws Exception{
        ServiceFixture service=new ServiceFixture();service.attach(context);Field active=MintService.class.getDeclaredField("foregroundActive");active.setAccessible(true);
        LocalStore.save(context,new JSONArray().put(object("id",NftMintLanguagesTest.ID,"name","CREATE","phase","STAMP","size",2,"mode","url","items",new JSONArray().put(object("idx",1,"coinid","public-stamped-id")).put(object("idx",2,"coinid","")))));
        String before=context.getSharedPreferences("nftwallet_statenft",0).getString("collections","");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->service.onConfigurationChanged(new Configuration(context.getResources().getConfiguration())));
        for(StatusBarNotification n:manager.getActiveNotifications())assertFalse(n.getTag()==null&&n.getId()==7501);
        Object[] settings=settings(manager.getNotificationChannel(Notifier.CH_FG));active.setBoolean(service,true);
        for(String code:NftMintLanguagesTest.CODES){
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Language.select(context,code);service.onConfigurationChanged(new Configuration(context.getResources().getConfiguration()));});
            Notification n=find(Language.msg(l10n_nft_mint_service_progress,"CREATE","1","2").toString());
            assertEquals(Language.msg(l10n_nft_mint_notification_title).toString(),n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());assertEquals(Notifier.CH_FG,n.getChannelId());assertTrue((n.flags&Notification.FLAG_ONGOING_EVENT)!=0);assertEquals(1,n.extras.getInt(Notification.EXTRA_PROGRESS));assertEquals(2,n.extras.getInt(Notification.EXTRA_PROGRESS_MAX));
            assertArrayEquals(settings,settings(manager.getNotificationChannel(Notifier.CH_FG)));assertEquals(Language.msg(l10n_nft_mint_background_channel).toString(),manager.getNotificationChannel(Notifier.CH_FG).getName().toString());assertEquals(before,context.getSharedPreferences("nftwallet_statenft",0).getString("collections",""));assertNull(read(MintService.class,service,"node"));
        }
        active.setBoolean(service,false);manager.cancel(7501);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->service.onConfigurationChanged(new Configuration(context.getResources().getConfiguration())));for(StatusBarNotification n:manager.getActiveNotifications())assertFalse(n.getTag()==null&&n.getId()==7501);
    }
}
