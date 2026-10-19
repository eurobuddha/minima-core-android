package com.eurobuddha.minimacore.main.views.balance;
import android.app.Application;
import android.app.Activity;
import android.os.Looper;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.utils.MinimaCMD;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.minima.utils.json.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class BalanceRefreshTest {
 static JSONObject balance(String amount){
  JSONObject row=new JSONObject();row.put("tokenid","0x00");row.put("token","Minima");
  row.put("confirmed",amount);row.put("sendable",amount);row.put("unconfirmed","0");row.put("coins","1");
  JSONArray rows=new JSONArray();rows.add(row);JSONObject reply=new JSONObject();reply.put("status",true);reply.put("response",rows);return reply;
 }
 @Test public void visibleWalletRetriesMissedUpdatesWithoutChangingTabsAndStopsWhenHidden()throws Exception {
  Language.initialize(RuntimeEnvironment.getApplication(),()->false);
  Activity activity=Robolectric.buildActivity(Activity.class).create().get();
  activity.setTheme(com.eurobuddha.minimacore.R.style.Theme_MinimaCore);
  ArrayDeque<Runnable> work=new ArrayDeque<>();
  try(MockedStatic<MinimaCMD> node=mockStatic(MinimaCMD.class)) {
   node.when(MinimaCMD::readExecutor).thenReturn((java.util.concurrent.Executor)work::add);
   node.when(MinimaCMD::checkMinimaStarted).thenReturn(true);
   node.when(()->MinimaCMD.execute("balance")).thenReturn(balance("100"),balance("8"));
   BalanceView view=new BalanceView(activity);activity.setContentView(view.getMainView());
   view.setDisplayActive(true);assertEquals("—",view.mTotalBalance.getText().toString());work.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertEquals("100",view.mSendable);
   java.lang.reflect.Field field=BalanceView.class.getDeclaredField("mTick");field.setAccessible(true);
   ((Runnable)field.get(view)).run();
   assertEquals("A visible wallet must fetch fresh balance without a tab switch",1,work.size());
   work.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("8",view.mSendable);
   view.setDisplayActive(false);((Runnable)field.get(view)).run();assertTrue(work.isEmpty());
   view.setDisplayActive(true);
   assertEquals("—",view.mTotalBalance.getText().toString());
   assertEquals(1,work.size());
   view.setDisplayActive(false);work.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertEquals("—",view.mTotalBalance.getText().toString());
   view.onActivityDestroy();
  } finally {activity.finish();}
 }
 @Test public void failedForegroundReadIsLabelledAndNextTickRecovers()throws Exception {
  Language.initialize(RuntimeEnvironment.getApplication(),()->false);
  Activity activity=Robolectric.buildActivity(Activity.class).create().get();
  activity.setTheme(com.eurobuddha.minimacore.R.style.Theme_MinimaCore);
  ArrayDeque<Runnable> work=new ArrayDeque<>();
  try(MockedStatic<MinimaCMD> node=mockStatic(MinimaCMD.class)) {
   node.when(MinimaCMD::readExecutor).thenReturn((java.util.concurrent.Executor)work::add);
   node.when(MinimaCMD::checkMinimaStarted).thenReturn(true);
   JSONObject malformed=new JSONObject();malformed.put("status",false);
   node.when(()->MinimaCMD.execute("balance")).thenReturn(malformed,balance("8"));
   BalanceView view=new BalanceView(activity);activity.setContentView(view.getMainView());
   view.setDisplayActive(true);work.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertEquals("—",view.mTotalBalance.getText().toString());
   assertEquals(android.view.View.VISIBLE,view.mRefreshStatus.getVisibility());
   assertTrue(view.mRefreshStatus.getText().toString().contains("Retrying"));
   java.lang.reflect.Field f=BalanceView.class.getDeclaredField("mTick");f.setAccessible(true);((Runnable)f.get(view)).run();
   work.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertEquals("8",view.mSendable);assertEquals(android.view.View.GONE,view.mRefreshStatus.getVisibility());
   view.onActivityDestroy();
  } finally {activity.finish();}
 }

}
