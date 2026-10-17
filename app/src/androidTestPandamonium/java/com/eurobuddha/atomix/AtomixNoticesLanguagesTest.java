package com.eurobuddha.atomix;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Shared guarded fixture; service is attached only and never starts its node or workers. */
public class AtomixNoticesLanguagesTest extends AtomixLanguagesTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private final String[] languages={"en","zh-Hans","ru","uk"};
 private NotificationManager manager;
 @Before public void notices(){
  assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
  InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
  manager=context.getSystemService(NotificationManager.class);clearNotices();
 }
 @After public void cleanupNotices(){if(manager!=null)clearNotices();}
 // This dedicated seedless emulator has old notification fixtures from other bundled apps.
 // Clear the test package's notices and wait for system grouping/cancellation before posting new ones.
 void clearNotices(){
  assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
  manager.cancelAll();long until=SystemClock.elapsedRealtime()+4000;
  while(manager.getActiveNotifications().length!=0&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(30);
  assertEquals("Stale notification fixtures remain",0,manager.getActiveNotifications().length);settle();
 }
 static Object[] settings(NotificationChannel c){return new Object[]{c.getImportance(),c.getSound(),c.getAudioAttributes(),c.shouldVibrate(),Arrays.toString(c.getVibrationPattern()),c.canShowBadge(),c.getLockscreenVisibility(),c.canBypassDnd()};}
 StatusBarNotification notice(String tag,int id,String body){long until=SystemClock.elapsedRealtime()+4000;do{for(StatusBarNotification n:manager.getActiveNotifications())if(Objects.equals(tag,n.getTag())&&(id<0||n.getId()==id)&&(body==null||body.contentEquals(n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT,""))))return n;SystemClock.sleep(30);}while(SystemClock.elapsedRealtime()<until);StringBuilder diagnostic=new StringBuilder();for(StatusBarNotification n:manager.getActiveNotifications())diagnostic.append(" [").append(n.getTag()).append("/").append(n.getId()).append(": ").append(n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT)).append("]");throw new AssertionError("AtomiX notification missing: "+tag+" / "+body+"; locale="+Language.code()+" active="+diagnostic);}
 void absent(String tag,int id){long until=SystemClock.elapsedRealtime()+3000;do{boolean found=false;for(StatusBarNotification n:manager.getActiveNotifications())if(Objects.equals(tag,n.getTag())&&n.getId()==id)found=true;if(!found)return;SystemClock.sleep(30);}while(SystemClock.elapsedRealtime()<until);fail("Unexpected AtomiX notice");}
 static class ServiceFixture extends SwapService{void attach(Context c){attachBaseContext(c);}}
 static Object service(SwapService s,String name,Class<?>[] types,Object...args){try{Method m=SwapService.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(s,args);}catch(Exception e){throw new AssertionError(e);}}
 @Test public void serviceLocaleRefreshPreservesChannelsAndOnlyUpdatesActiveForeground(){
  ServiceFixture s=new ServiceFixture();s.attach(context);service(s,"createChannels",new Class<?>[]{});
  Map<String,Object[]>saved=new HashMap<>();for(String id:new String[]{"swap_fg","atomix","swap_offline"})saved.put(id,settings(manager.getNotificationChannel(id)));
  set(SwapService.class,s,"fgText","Republishing your market…");s.onConfigurationChanged(new Configuration());absent(null,4101);
  set(SwapService.class,s,"foregroundActive",true);
  for(String lang:languages){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,lang));settle();assertEquals(lang,Language.code());assertEquals(Language.msg(l10n_atomix_notice_alerts).toString(),manager.getNotificationChannel("atomix").getName().toString());InstrumentationRegistry.getInstrumentation().runOnMainSync(()->s.onConfigurationChanged(new Configuration()));
   Notification n=notice(null,4101,Language.msg(l10n_atomix_notice_republishing).toString()).getNotification();assertTrue((n.flags&Notification.FLAG_ONGOING_EVENT)!=0);assertNotNull(n.contentIntent);assertTrue(n.contentIntent.isImmutable());assertEquals(context.getPackageName(),n.contentIntent.getCreatorPackage());assertEquals("Republishing your market…",read(SwapService.class,s,"fgText"));
   assertEquals(Language.msg(l10n_atomix_notice_watcher).toString(),manager.getNotificationChannel("swap_fg").getName().toString());assertEquals(Language.msg(l10n_atomix_notice_alerts).toString(),manager.getNotificationChannel("atomix").getName().toString());assertEquals(Language.msg(l10n_atomix_notice_offline).toString(),manager.getNotificationChannel("swap_offline").getName().toString());for(String id:saved.keySet())assertArrayEquals(saved.get(id),settings(manager.getNotificationChannel(id)));assertNull(read(SwapService.class,s,"node"));
  }
  set(SwapService.class,s,"foregroundActive",false);manager.cancel(4101);absent(null,4101);s.onConfigurationChanged(new Configuration());absent(null,4101);
 }
 @Test public void serviceAlertsKeepFullPublicDataAndOfflineDeduplication(){
  ServiceFixture s=new ServiceFixture();s.attach(context);service(s,"createChannels",new Class<?>[]{});
  SharedPreferences prefs=context.getSharedPreferences("atomix",0);set(SwapService.class,s,"prefs",prefs);assertTrue(prefs.edit().putBoolean("auto_publish",true).commit());
  String address="0x0123456789abcdef0123456789abcdef01234567",amount="0000.0000000042";
  for(String lang:languages){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,lang));service(s,"alert",new Class<?>[]{int.class,String.class,String.class},5999,"Add ETH for gas","This swap needs about "+amount+" more ETH for gas. Send ETH on Ethereum to "+address);
   String body=Language.msg(l10n_atomix_notice_gas_address,amount,address).toString();Notification n=notice("pandamonium.atomix.service",5999,body).getNotification();assertEquals(Language.msg(l10n_atomix_notice_gas).toString(),n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());assertEquals(body,n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString());assertTrue(body.contains(address));assertTrue(body.contains(amount));
   set(SwapService.class,s,"unpairedSince",System.currentTimeMillis()-240000L);set(SwapService.class,s,"lastOfflineAlertMs",0L);service(s,"maybeOfflineAlert",new Class<?>[]{});StatusBarNotification offline=notice("pandamonium.atomix.service",4102,Language.msg(l10n_atomix_notice_offline_body).toString());assertTrue(offline.getNotification().contentIntent.isImmutable());long stamp=(Long)read(SwapService.class,s,"lastOfflineAlertMs");service(s,"maybeOfflineAlert",new Class<?>[]{});assertEquals(stamp,read(SwapService.class,s,"lastOfflineAlertMs"));assertEquals(offline.getPostTime(),notice("pandamonium.atomix.service",4102,null).getPostTime());service(s,"cancelOfflineAlert",new Class<?>[]{});absent("pandamonium.atomix.service",4102);assertNull(read(SwapService.class,s,"node"));
  }
 }
 @Test public void activityAlertsAndSafeWalletErrorsFollowLanguageWithoutReplacingViews()throws Exception{
  try(ActivityScenario<MainActivity>scenario=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<View>kept=new AtomicReference<>();scenario.onActivity(a->{freeze(a);set(a,"currentTab",1);set(a,"ethErr","Node returned an invalid Ethereum key. Not using it.");call(a,"render");kept.set(page(a));});
   for(String lang:languages){scenario.onActivity(a->{Language.select(a,lang);call(a,"postNotification",new Class<?>[]{String.class,String.class},"Swap refunded","Reclaimed 00012.34000 mxUSDT — confirmed on-chain (the counterparty never locked their side before the timeout)");});settle();scenario.onActivity(a->{assertSame(kept.get(),page(a));exact(page(a),Language.join("⚠ ",Language.msg(l10n_atomix_notice_key_invalid)));assertEquals("Node returned an invalid Ethereum key. Not using it.",get(a,"ethErr"));});String body=Language.msg(l10n_atomix_notice_reclaimed,"00012.34000","mxUSDT",Language.msg(l10n_atomix_notice_refund_unlocked)).toString();Notification n=notice("pandamonium.atomix.ui",-1,body).getNotification();assertEquals(Language.msg(l10n_atomix_notice_refunded).toString(),n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());assertEquals(body,n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString());}
  }
 }
}
