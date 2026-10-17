package com.eurobuddha.casino;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Reuses FutureNotificationLanguagesTest and the guarded Casino shell fixture; never starts the service/node. */
public class CasinoNoticesLanguagesTest extends CasinoLanguagesTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};private NotificationManager manager;
 @Before public void notices(){
  assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
  InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
  manager=context.getSystemService(NotificationManager.class);clearNotices();context.getSharedPreferences("timeout_claims",0).edit().clear().commit();
 }
 @After public void clearNoticeFixture(){if(manager==null)return;clearNotices();context.getSharedPreferences("timeout_claims",0).edit().clear().commit();}
 private void clearNotices(){for(StatusBarNotification n:manager.getActiveNotifications())if("pandamonium.casino".equals(n.getTag())||"timeout_claims".equals(n.getTag())||n.getTag()==null&&n.getId()==5101)manager.cancel(n.getTag(),n.getId());}
 private static Object[] settings(NotificationChannel c){return new Object[]{c.getImportance(),c.getSound(),c.getAudioAttributes(),c.shouldVibrate(),Arrays.toString(c.getVibrationPattern()),c.canShowBadge(),c.getLockscreenVisibility(),c.canBypassDnd()};}
 private StatusBarNotification notice(String tag,int id,String body)throws Exception{
  long until=SystemClock.elapsedRealtime()+4000;
  do{for(StatusBarNotification n:manager.getActiveNotifications())if(Objects.equals(tag,n.getTag())&&(id<0||n.getId()==id)&&(body==null||body.contentEquals(n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT,""))))return n;SystemClock.sleep(30);}while(SystemClock.elapsedRealtime()<until);
  throw new AssertionError("Expected Casino notification missing: "+tag+" / "+body);
 }
 private void absent(String tag,int id)throws Exception{long until=SystemClock.elapsedRealtime()+3000;do{boolean found=false;for(StatusBarNotification n:manager.getActiveNotifications())if(Objects.equals(tag,n.getTag())&&n.getId()==id)found=true;if(!found)return;SystemClock.sleep(30);}while(SystemClock.elapsedRealtime()<until);fail("Unexpected Casino notification");}
 private static class ServiceFixture extends CasinoService{void attach(Context c){attachBaseContext(c);}}
 private static Object serviceCall(CasinoService s,String name,Class<?>[] types,Object...args){try{Method m=CasinoService.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(s,args);}catch(Exception e){throw new AssertionError(e);}}
 private static void active(CasinoService s,boolean value){try{Field f=CasinoService.class.getDeclaredField("foregroundActive");f.setAccessible(true);f.setBoolean(s,value);}catch(Exception e){throw new AssertionError(e);}}
 @Test public void activeServiceAndResultNoticesPreserveChannelSettingsAndLiteralValues()throws Exception{
  ServiceFixture service=new ServiceFixture();service.attach(context);serviceCall(service,"createChannels",new Class<?>[]{});
  Object[] fg=settings(manager.getNotificationChannel("casino_fg")),result=settings(manager.getNotificationChannel("casino_alert"));
  service.onConfigurationChanged(new Configuration(context.getResources().getConfiguration()));absent(null,5101);active(service,true);
  for(String lang:LANGS){
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Language.select(context,lang);service.onConfigurationChanged(new Configuration(context.getResources().getConfiguration()));});
   String watching=Language.msg(l10n_casino_background_watching).toString();Notification n=notice(null,5101,watching).getNotification();assertEquals("Zero Edge Casino",n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());assertTrue((n.flags&Notification.FLAG_ONGOING_EVENT)!=0);
   assertEquals(Language.msg(l10n_casino_background_channel).toString(),manager.getNotificationChannel("casino_fg").getName().toString());assertEquals(Language.msg(l10n_casino_results_channel).toString(),manager.getNotificationChannel("casino_alert").getName().toString());assertArrayEquals(fg,settings(manager.getNotificationChannel("casino_fg")));assertArrayEquals(result,settings(manager.getNotificationChannel("casino_alert")));
   serviceCall(service,"notifyAlert",new Class<?>[]{String.class,String.class},"You won +00012.34000 USD!","Coin Flip — rolled Tails");String body=Language.msg(l10n_casino_result_notice,Language.msg(l10n_casino_coin_flip),Language.msg(l10n_casino_tails)).toString();StatusBarNotification posted=notice("pandamonium.casino",-1,body);assertEquals(Language.msg(l10n_casino_won_notice,"00012.34000","USD").toString(),posted.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString());assertEquals("casino_alert",posted.getNotification().getChannelId());manager.cancel(posted.getTag(),posted.getId());assertNull(read(CasinoService.class,service,"node"));
  }
  String raw="unknown 0xAbCd Україна 中文 $1 {0}";serviceCall(service,"notifyAlert",new Class<?>[]{String.class,String.class},"Public fixture",raw);assertEquals("Public fixture",notice("pandamonium.casino",-1,raw).getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString());
  active(service,false);manager.cancel(5101);absent(null,5101);service.onConfigurationChanged(new Configuration(context.getResources().getConfiguration()));absent(null,5101);assertNull(read(CasinoService.class,service,"node"));
 }
 private static Bet claim(String id,String token){Coin c=new Coin();c.coinid=id;c.tokenid=token;c.amount="2";c.created=100;c.state.put(CasinoContract.P_HOUSE_PK,"public-house");c.state.put(CasinoContract.P_HOUSE_COMMIT,"public-commit");c.state.put(CasinoContract.P_PLAYER_PK,"public-player");c.state.put(CasinoContract.P_PHASE,"1");c.state.put(CasinoContract.P_TIMEOUT,"10");return new Bet(c);}
 @Test public void timeoutAlertsPreserveDeduplicationDismissalAndClaimIdentity()throws Exception{
  Set<String> mine=Collections.singleton("public-player");Object[] channelSettings=null;
  for(String lang:LANGS){
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,lang));List<Bet>bets=Arrays.asList(claim("minima-"+lang,"0x00"),claim("usd-"+lang,Currency.USD_TOKENID));TimeoutClaims claims=new TimeoutClaims(bets,mine,111);assertEquals("2 timeout claims available (1 Minima · 1 USD)",claims.summary());
   TimeoutAlerts.update(context,claims);String expected=Language.msg(l10n_casino_timeout_notice,Language.msg(l10n_casino_timeout_summary,"2","1 Minima · 1 USD")).toString();StatusBarNotification posted=notice("timeout_claims",1,expected);Notification n=posted.getNotification();assertEquals(Language.msg(l10n_casino_timeout_channel).toString(),n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());assertEquals(expected,n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString());assertNotNull(n.contentIntent);assertTrue(n.contentIntent.isImmutable());assertEquals(context.getPackageName(),n.contentIntent.getCreatorPackage());assertEquals(claims.coinids,context.getSharedPreferences("timeout_claims",0).getStringSet("coinids",null));
   NotificationChannel channel=manager.getNotificationChannel("casino_timeouts");assertEquals(Language.msg(l10n_casino_timeout_channel).toString(),channel.getName().toString());if(channelSettings==null)channelSettings=settings(channel);else assertArrayEquals(channelSettings,settings(channel));
   TimeoutAlerts.update(context,claims);assertEquals(posted.getPostTime(),notice("timeout_claims",1,expected).getPostTime());manager.cancel("timeout_claims",1);absent("timeout_claims",1);TimeoutAlerts.update(context,claims);absent("timeout_claims",1);
   TimeoutAlerts.update(context,new TimeoutClaims(Collections.emptyList(),mine,111));assertFalse(context.getSharedPreferences("timeout_claims",0).contains("coinids"));
  }
 }
 @Test public void retainedLogDialogTranslatesNestedMessagesAndPreservesRawPendingState()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<Dialog> kept=new AtomicReference<>();List<String> raw=new ArrayList<>();String desc="Created Dice bet (00012.34000 USD · 5:1)";
   s.onActivity(a->{node.set(freeze(a));((ArrayDeque<String>)get(a,"logLines")).clear();a.markPending(a.pendingCreate(),desc);a.log("Create failed: txnpost failed : Hash failed",MainActivity.LOG_ERR);a.log("Public diagnostic 0xAbCd Україна 中文 $1 {0}",MainActivity.LOG_WARN);raw.addAll((ArrayDeque<String>)get(a,"logLines"));call(a,"showLogDialog");kept.set(dialog(a));node.get().commands.clear();});settle();
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(kept.get(),dialog(a));String text=((TextView)dialog(a).findViewById(android.R.id.message)).getText().toString();assertTrue(text.contains(Language.msg(l10n_casino_pending_wait,Language.msg(l10n_casino_created_log,Language.msg(l10n_casino_dice),"00012.34000 USD","5")).toString()));assertTrue(text.contains(Language.msg(l10n_casino_create_failed,Language.msg(l10n_casino_command_failed_detail,"txnpost","Hash failed")).toString()));assertTrue(text.contains("Public diagnostic 0xAbCd Україна 中文 $1 {0}"));for(String line:raw)assertTrue(text.contains(line.substring(0,line.indexOf("  ")+2)));assertEquals(raw,new ArrayList<>((ArrayDeque<String>)get(a,"logLines")));assertEquals(desc,get(a,"pendingDesc"));assertEquals(a.pendingCreate(),get(a,"pendingType"));assertTrue(node.get().commands.isEmpty());});screenshot("casino-log-"+lang);}
   s.onActivity(a->{kept.get().dismiss();for(int i=0;i<61;i++)a.log("public row "+i,MainActivity.LOG_OK);ArrayDeque<String>lines=(ArrayDeque<String>)get(a,"logLines");assertEquals(60,lines.size());assertTrue(lines.getFirst().endsWith("public row 60"));assertTrue(lines.getLast().endsWith("public row 1"));assertTrue(node.get().commands.isEmpty());});
  }
 }
}
