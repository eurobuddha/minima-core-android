package com.eurobuddha.vestr;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.math.BigDecimal;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Public synthetic contracts; fake callbacks never reach a real node, wallet or transaction. */
@RunWith(AndroidJUnit4.class)
public class VestrLanguagesTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private static final String[] CODES={"en","zh-Hans","ru","uk"};
 private static final String NAME="Token 中文 Русский Українська {amount}$1";
 private static final String HEX="0x"+"ab".repeat(32);
 private static final long START=1893492000000L,END=1894356000000L;
 private DirectNodeApi.Backend backend;
 @Before public void prepare(){
  assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
  assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{backend=(DirectNodeApi.Backend)read(DirectNodeApi.class,null,"backend");DirectNodeApi.install(null);AppLock.disable(context);Language.select(context,"en");});
 }
 @After public void reset(){
  if(!"ranchu".equals(Build.HARDWARE)||!"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Language.select(context,"en");DirectNodeApi.install(backend);});
  assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
 }
 static Object read(Class<?> c,Object o,String n){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
 static Object get(Object o,String n){return read(o.getClass(),o,n);}
 static void set(Object o,String n,Object v){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new AssertionError(e);}}
 static Object call(Object o,String n,Class<?>[] types,Object... args){try{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new AssertionError(e);}}
 static JSONObject object(Object... kv){JSONObject o=new JSONObject();try{for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
 static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(150);}
 static class Request {final String command;final NodeApi.Cb cb;Request(String c,NodeApi.Cb b){command=c;cb=b;}}
 static class FixtureNode extends NodeApi {final List<Request> requests=new ArrayList<>();FixtureNode(Context c){super(c,null);}@Override public void cmd(String c,Cb cb){requests.add(new Request(c,cb));}Request take(){assertFalse(requests.isEmpty());return requests.remove(0);}}
 static FixtureNode freeze(SubActivity a){a.node.onDestroy();FixtureNode n=new FixtureNode(a);a.node=n;return n;}
 static FixtureNode freeze(MainActivity a){((NodeApi)get(a,"node")).onDestroy();((Handler)get(a,"ui")).removeCallbacksAndMessages(null);FixtureNode n=new FixtureNode(a);set(a,"node",n);((View)get(a,"pairingBanner")).setVisibility(View.GONE);return n;}
 static List<TextView> texts(View v){List<TextView> l=new ArrayList<>();if(v instanceof TextView)l.add((TextView)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)l.addAll(texts(g.getChildAt(i)));}return l;}
 static TextView exact(View root,CharSequence expected){for(TextView t:texts(root))if(expected.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing label "+expected);}
 static void fits(TextView t){assertNotNull(t.getLayout());for(int i=0;i<t.getLayout().getLineCount();i++)assertEquals(t.getText().toString(),0,t.getLayout().getEllipsisCount(i));assertTrue(t.getText().toString(),t.getWidth()>0);}
 static JSONObject coin(){JSONArray state=new JSONArray();Object[] data={HEX,"100","1000","2000","1728","1577880123000",String.valueOf(START),"24",String.valueOf(END)};for(int i=0;i<data.length;i++)state.put(object("port",i,"data",data[i]));state.put(object("port",199,"data",HEX));return object("coinid",HEX,"tokenid","0xff","tokenamount","75","address",HEX,"created","1500","token",object("name",NAME),"state",state);}
 static Set<android.app.AlertDialog> pickers(SubActivity a){return ((Map<android.app.AlertDialog,?>)read(SubActivity.class,a,"languagePickers")).keySet();}
 static android.app.AlertDialog picker(SubActivity a){assertEquals(1,pickers(a).size());return pickers(a).iterator().next();}
 void screenshot(String n)throws Exception{settle();android.graphics.Bitmap bm=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bm);java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,n+".png"))){assertTrue(bm.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bm.recycle();}}
 @Test public void mainPagesAndContractDataSurviveLanguageChanges()throws Exception{
  AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<BaseView[]> pages=new AtomicReference<>();
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   settle();scenario.onActivity(a->{node.set(freeze(a));((List<JSONObject>)get(a,"contractCoins")).add(coin());pages.set((BaseView[])get(a,"views"));for(BaseView v:pages.get())v.refresh();});
   for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(pages.get(),get(a,"views"));assertEquals(0,a.currentTab());assertTrue(node.get().requests.isEmpty());assertEquals(NAME,a.contracts().get(0).tokenName);assertEquals(HEX,a.contracts().get(0).unlockAddr);for(BaseView v:pages.get())assertNotNull(v.getRoot());View root=a.findViewById(android.R.id.content);fits(exact(root,Language.msg(l10n_vestr_creator)));exact(pages.get()[0].getRoot(),Language.msg(l10n_vestr_my_contracts));exact(pages.get()[1].getRoot(),Language.msg(l10n_vestr_collect_help));exact(pages.get()[2].getRoot(),Language.msg(l10n_vestr_intro));});screenshot("vestr-contracts-"+code);}
   scenario.onActivity(a->{a.goToTab(2);((Handler)get(a,"ui")).removeCallbacksAndMessages(null);});settle();screenshot("vestr-about-uk");
  }
 }
 @Test public void calculatorRetainsDraftSelectionDatesAndComputedResult()throws Exception{
  AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<EditText> input=new AtomicReference<>();
  try(ActivityScenario<CalculatorActivity> scenario=ActivityScenario.launch(CalculatorActivity.class)){
   settle();scenario.onActivity(a->{node.set(freeze(a));input.set((EditText)get(a,"amountInput"));input.get().setText("100.00");input.get().setSelection(3);set(a,"startMs",START);set(a,"endMs",END);set(a,"tip",10000);Language.setText((TextView)get(a,"startBtn"),Language.stamp(START,"yMMMdHm"));Language.setText((TextView)get(a,"endBtn"),Language.stamp(END,"yMMMdHm"));((Spinner)get(a,"graceSpinner")).setSelection(1);call(a,"calculate",new Class<?>[]{});});
   for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(input.get(),get(a,"amountInput"));assertEquals("100.00",input.get().getText().toString());assertEquals(3,input.get().getSelectionStart());assertEquals(START,get(a,"startMs"));assertEquals(END,get(a,"endMs"));assertTrue(node.get().requests.isEmpty());assertEquals(1,((Spinner)get(a,"graceSpinner")).getSelectedItemPosition());exact(a.form,Language.stamp(START,"yMMMdHm"));exact(a.form,Language.msg(l10n_vestr_released,VestrText.grace(VestingContract.Grace.DAILY),"10"));exact(a.form,VestrText.grace(VestingContract.Grace.DAILY));});screenshot("vestr-calculator-"+code);}
  }
 }
 @Test public void createFieldsAndPendingValidationRetainLiteralValues()throws Exception{
  AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<Request> validation=new AtomicReference<>();
  try(ActivityScenario<CreateContractActivity> scenario=ActivityScenario.launch(new Intent(context,CreateContractActivity.class).putExtra("script",HEX))){
   settle();scenario.onActivity(a->{node.set(freeze(a));call(a,"fetchTokens",new Class<?>[]{});Request balance=node.get().take();assertEquals("balance",balance.command);balance.cb.onResult(object("status",true,"response",new JSONArray().put(object("tokenid","0x00","sendable","1")).put(object("tokenid","0xff","token","Token","sendable","2")).put(object("tokenid","0xfe","token",NAME,"sendable","3"))));((Spinner)get(a,"tokenSpinner")).setSelection(2);((EditText)get(a,"addressInput")).setText(HEX);((EditText)get(a,"amountInput")).setText("100.25");((EditText)get(a,"burnInput")).setText("0.1");((EditText)get(a,"passwordInput")).setText("public fixture only");((EditText)get(a,"passwordInput")).setSelection(4);set(a,"startMs",START);set(a,"endMs",END);set(a,"tip",10000);((Spinner)get(a,"graceSpinner")).setSelection(2);call(a,"submit",new Class<?>[]{});validation.set(node.get().take());assertEquals("checkaddress address:"+HEX,validation.get().command);});
   for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertEquals(HEX,((EditText)get(a,"addressInput")).getText().toString());assertEquals("100.25",((EditText)get(a,"amountInput")).getText().toString());assertEquals("0.1",((EditText)get(a,"burnInput")).getText().toString());assertEquals("public fixture only",((EditText)get(a,"passwordInput")).getText().toString());assertEquals(4,((EditText)get(a,"passwordInput")).getSelectionStart());assertEquals(2,((Spinner)get(a,"graceSpinner")).getSelectedItemPosition());assertEquals(2,((Spinner)get(a,"tokenSpinner")).getSelectedItemPosition());assertEquals(NAME+"  (3)",((Spinner)get(a,"tokenSpinner")).getSelectedItem().toString());assertEquals("0xfe",((List<String>)get(a,"tokenIds")).get(2));assertFalse(((View)get(a,"createBtn")).isEnabled());assertTrue(node.get().requests.isEmpty());exact(a.form,Language.msg(l10n_vestr_validating));exact(a.form,Language.msg(l10n_vestr_withdrawal));});}
   scenario.onActivity(a->{validation.get().cb.onResult(object("status",false));assertTrue(((View)get(a,"createBtn")).isEnabled());exact(a.form,Language.msg(l10n_vestr_invalid_address));assertTrue(node.get().requests.isEmpty());});
  }
 }
 @Test public void detailRetainsBurnAndAmountsBeforeFakeCollection()throws Exception{
  AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<EditText> burn=new AtomicReference<>();
  try(ActivityScenario<ContractDetailActivity> scenario=ActivityScenario.launch(new Intent(context,ContractDetailActivity.class).putExtra("coin",coin().toString()))){
   settle();scenario.onActivity(a->{node.set(freeze(a));set(a,"tip",1600);set(a,"mathsReady",true);set(a,"cancollect",new BigDecimal("12.5"));set(a,"change",new BigDecimal("62.5"));call(a,"render",new Class<?>[]{});burn.set((EditText)get(a,"burnInput"));burn.get().setText("0.25");burn.get().setSelection(2);});
   for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(burn.get(),get(a,"burnInput"));assertEquals("0.25",burn.get().getText().toString());assertEquals(2,burn.get().getSelectionStart());assertEquals(new BigDecimal("12.5"),get(a,"cancollect"));assertEquals(new BigDecimal("62.5"),get(a,"change"));assertTrue(node.get().requests.isEmpty());fits(exact(a.form,Language.msg(l10n_vestr_total_vested)));exact(a.form,"12.5 "+NAME);exact(a.form,Language.msg(l10n_vestr_collect_amount,"12.5",NAME));exact(a.form,HEX);});screenshot("vestr-detail-"+code);}
   scenario.onActivity(a->{((View)get(a,"collectBtn")).performClick();Request q=node.get().take();assertTrue(q.command.startsWith("txncreate id:vc"));q.cb.onResult(object("status",false,"error","Collect failed"));Request cleanup=node.get().take();assertEquals(q.command.replace("txncreate","txndelete"),cleanup.command);assertTrue(node.get().requests.isEmpty());exact(a.form,Language.msg(l10n_vestr_failed,"Collect failed"));});
  }
 }
 @Test public void nativeDateAndTimePickerKeepUnconfirmedValues()throws Exception{
  try(ActivityScenario<CalculatorActivity> scenario=ActivityScenario.launch(CalculatorActivity.class)){
   settle();scenario.onActivity(a->{freeze(a);((View)get(a,"startBtn")).performClick();((android.app.DatePickerDialog)picker(a)).updateDate(2030,0,2);});
   for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{android.app.DatePickerDialog d=(android.app.DatePickerDialog)picker(a);assertEquals(2030,d.getDatePicker().getYear());assertEquals(0,d.getDatePicker().getMonth());assertEquals(2,d.getDatePicker().getDayOfMonth());assertEquals(0L,get(a,"startMs"));assertEquals(Language.msg(android.R.string.ok).toString(),d.getButton(-1).getText().toString());});screenshot("vestr-date-"+code);}
   scenario.onActivity(a->picker(a).getButton(-1).performClick());settle();scenario.onActivity(a->{assertTrue(picker(a) instanceof android.app.TimePickerDialog);assertEquals(0L,get(a,"startMs"));((android.app.TimePickerDialog)picker(a)).updateTime(14,37);});
   for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertEquals(0L,get(a,"startMs"));assertEquals(Language.msg(android.R.string.cancel).toString(),picker(a).getButton(-2).getText().toString());assertEquals(14,picker(a).onSaveInstanceState().getInt("hour"));assertEquals(37,picker(a).onSaveInstanceState().getInt("minute"));});screenshot("vestr-time-"+code);}
   scenario.onActivity(a->picker(a).getButton(-2).performClick());settle();scenario.onActivity(a->{assertTrue(pickers(a).isEmpty());assertEquals(0L,get(a,"startMs"));});
  }
 }
 @Test public void tokenMetadataAndGraceSemanticsStayLiteral(){
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(String code:CODES){Language.select(context,code);assertEquals(Language.msg(l10n_wallet_error_node_timeout).toString(),VestrMessages.message("The embedded node did not respond. Check its status in Minima Core.").toString());assertEquals(Language.msg(l10n_wallet_error_node_unknown).toString(),VestrMessages.message("Node result unavailable. Outcome unknown.").toString());assertEquals(NAME,VestrMessages.message(NAME));assertEquals(Language.msg(l10n_vestr_transport_stopped).toString(),VestrMessages.message("The embedded node is not running. No command was sent.").toString());assertEquals("Token",VestrText.token("Token","0xff"));assertEquals(NAME,VestrText.token(object("name",object("name",NAME)),"0xff"));assertEquals(Language.msg(l10n_vestr_token).toString(),VestrText.token(object("name",object()),"0xff").toString());assertEquals("Minima",VestrText.token(null,"0x00"));assertEquals(24,VestingContract.Grace.DAILY.hours);assertEquals(1728,VestingContract.Grace.DAILY.blocks());assertEquals(Language.msg(l10n_vestr_daily).toString(),VestrText.grace(VestingContract.Grace.DAILY).toString());assertEquals("0.123456",ContractUi.amount(new BigDecimal("0.123456789")));}});
 }
}
