package com.eurobuddha.salon;

import android.content.Context;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** All node calls are captured mocks: this test cannot transfer real tokens. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonSpareCleanupTest {
    private MainActivity activity; private NodeApi node;
    private final List<String> commands=new ArrayList<>();private final List<NodeApi.Cb> replies=new ArrayList<>();
    private static final String KEEP="0x"+"ab".repeat(32),SPARE="0x"+"cd".repeat(32),SECOND="0x"+"ef".repeat(32);
    private static final String GRAVE="0xABA005476D2B3CD7F251B9783E64C124C9670BB358695F04D91B2057BB64CB49";
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception{field(n).set(activity,v);}
    private void call(String n)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n);m.setAccessible(true);m.invoke(activity);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void start()throws Exception{call("burnSpares");idle();}
    private JSONObject row(String id)throws Exception{return new JSONObject().put("tokenid",id).put("token",new JSONObject().put("salon","1").put("handle","literal Українська 中文"));}
    private JSONObject balance(String...ids)throws Exception{JSONArray rows=new JSONArray();for(String id:ids)rows.put(row(id));return new JSONObject().put("status",true).put("response",rows);}
    private AlertDialog dialog(){return (AlertDialog)ShadowDialog.getLatestDialog();}
    private void lookup(String...ids)throws Exception{replies.get(replies.size()-1).onResult(balance(ids));idle();}
    private void click(){dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();}
    private void notice(int id,Object...args){assertEquals(Language.msg(id,args).toString(),ShadowToast.getTextOfLatestToast());}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();SalonStore.put(activity,"tokenid",KEEP);SalonStore.put(activity,"handle","owner");
        ((ExecutorService)field("io").get(activity)).shutdownNow();node=mock(NodeApi.class);set("node",node);set("nodeUp",true);
        doAnswer(c->{commands.add(c.getArgument(0));replies.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @After public void cleanup()throws Exception{call("cancelSpareBurn");Language.select(activity,"en");}
    private boolean contains(View v,String text){if(v instanceof TextView&&text.equals(((TextView)v).getText().toString()))return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(contains(((ViewGroup)v).getChildAt(i),text))return true;return false;}
    @Test public void liveDialogChangesLanguageWithoutChangingTokenIdsOrSendingUntilConfirmed()throws Exception{
        start();lookup(KEEP,SPARE);AlertDialog d=dialog();Object operation=field("spareBurn").get(activity);
        for(String code:CODES){Language.select(activity,code);idle();assertSame(d,dialog());assertSame(operation,field("spareBurn").get(activity));
            assertTrue(contains(d.getWindow().getDecorView(),Language.msg(l10n_salon_spares_title,1).toString()));
            assertEquals(Language.msg(l10n_salon_spares_confirm,KEEP,"• "+SPARE+"\n").toString(),((TextView)d.findViewById(android.R.id.message)).getText().toString());
            assertEquals(Language.msg(l10n_salon_spares_burn).toString(),d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals(Language.msg(l10n_common_cancel).toString(),d.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());assertEquals(Collections.singletonList("balance"),commands);
        }
        click();assertEquals(Arrays.asList("balance","send amount:1 tokenid:"+SPARE+" address:"+GRAVE),commands);
    }
    @Test public void replacementAccountBeforeLookupCannotOpenConfirmation()throws Exception{
        start();SalonStore.put(activity,"tokenid",SPARE);lookup(KEEP,SPARE);assertNull(field("spareBurn").get(activity));assertEquals(1,commands.size());assertTrue(dialog()==null||!dialog().isShowing());
    }
    @Test public void identityKeyNodeOrScreenChangeAfterDialogPreventsEverySend()throws Exception{
        for(String kind:new String[]{"identity","key","node","screen","offline"}){
            set("node",node);set("nodeUp",true);SalonStore.put(activity,"tokenid",KEEP);start();lookup(KEEP,SPARE);
            if(kind.equals("identity"))SalonStore.put(activity,"tokenid",SPARE);
            if(kind.equals("key"))SalonStore.writeIdentity(activity,"wrapped:replacement","public:replacement");
            if(kind.equals("node"))set("node",mock(NodeApi.class));
            if(kind.equals("screen"))set("renderEpoch",10);
            if(kind.equals("offline"))set("nodeUp",false);
            click();notice(l10n_salon_spares_changed);assertNull(field("spareBurn").get(activity));
        }
        assertTrue(commands.stream().allMatch("balance"::equals));
    }
    @Test public void cancellationAndDuplicateCallbacksCannotStartOrClearReplacementOperation()throws Exception{
        start();NodeApi.Cb old=replies.get(0);call("cancelSpareBurn");start();Object current=field("spareBurn").get(activity);old.onResult(balance(KEEP,SPARE));idle();assertSame(current,field("spareBurn").get(activity));
        lookup(KEEP,SPARE);AlertDialog d=dialog();replies.get(1).onResult(balance(KEEP,SECOND));idle();assertSame(d,dialog());click();assertEquals(3,commands.size());assertTrue(commands.get(2).contains(SPARE));
    }
    @Test public void malformedBalanceNeverBecomesEmptyOrAllowsPartialCleanup()throws Exception{
        for(JSONObject bad:new JSONObject[]{new JSONObject().put("status",false),balance(KEEP,SPARE).put("response",new JSONArray().put(row(SPARE)).put(JSONObject.NULL)),balance(KEEP,"0x00"),balance(KEEP,"0xcd address:evil")}){
            start();replies.get(replies.size()-1).onResult(bad);idle();notice(l10n_salon_spares_lookup);assertNull(field("spareBurn").get(activity));
        }
        assertTrue(commands.stream().allMatch("balance"::equals));
    }
    @Test public void duplicateClicksAndResultsNeverSendTwiceOrReleasePendingBatchEarly()throws Exception{
        start();start();notice(l10n_salon_spares_busy);assertEquals(1,commands.size());lookup(KEEP,SPARE,SECOND);AlertDialog d=dialog();click();d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(3,commands.size());
        JSONObject ok=new JSONObject().put("status",true);replies.get(1).onResult(ok);replies.get(1).onError("late");idle();assertNotNull(field("spareBurn").get(activity));
        start();notice(l10n_salon_spares_busy);assertEquals(3,commands.size());replies.get(2).onError("timeout");idle();assertNull(field("spareBurn").get(activity));notice(l10n_salon_spares_unknown,SECOND);
    }
    @Test public void submittedUnknownEmptyAndMissingNodeMessagesFollowSelection()throws Exception{
        for(String code:CODES){Language.select(activity,code);
            set("nodeUp",false);start();notice(l10n_salon_spares_node);set("nodeUp",true);
            SalonStore.put(activity,"tokenid","");start();notice(l10n_salon_spares_identity);SalonStore.put(activity,"tokenid",KEEP);
            start();lookup(KEEP);notice(l10n_salon_spares_empty);
            start();lookup(KEEP,SPARE);click();replies.get(replies.size()-1).onResult(new JSONObject().put("status",true));idle();notice(l10n_salon_spares_submitted,SPARE);
            start();lookup(KEEP,SPARE);click();replies.get(replies.size()-1).onResult(new JSONObject().put("status","true"));idle();notice(l10n_salon_spares_unknown,SPARE);
        }
    }
    @Test public void cancelButtonReleasesLookupWithoutSendingAndAllowsAnotherCheck()throws Exception{
        start();lookup(KEEP,SPARE);dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();assertNull(field("spareBurn").get(activity));start();assertEquals(Arrays.asList("balance","balance"),commands);
    }
    @Test public void finishingActivityIgnoresConfirmationAndPostOutcome()throws Exception{
        start();lookup(KEEP,SPARE);activity.finish();click();assertEquals(1,commands.size());assertNull(field("spareBurn").get(activity));
    }
    @Test public void thrownLookupAndSendErrorsReleaseStateWithoutRetry()throws Exception{
        doThrow(new IllegalStateException("fixture")).when(node).cmd(eq("balance"),any());start();notice(l10n_salon_spares_lookup);assertNull(field("spareBurn").get(activity));
        doAnswer(c->{commands.add(c.getArgument(0));replies.add(c.getArgument(1));return null;}).when(node).cmd(eq("balance"),any());
        doThrow(new IllegalStateException("fixture")).when(node).cmd(startsWith("send "),any());start();lookup(KEEP,SPARE);click();notice(l10n_salon_spares_unknown,SPARE);assertNull(field("spareBurn").get(activity));verify(node,times(1)).cmd(startsWith("send "),any());
    }
}
