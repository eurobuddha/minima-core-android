package com.eurobuddha.salon;

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
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonTipOwnershipTest {
    MainActivity activity;NodeApi node;List<String> commands=new ArrayList<>();List<NodeApi.Cb> replies=new ArrayList<>();
    static final String ADDR="0x"+"12".repeat(32);
    Field f(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    void call(String n,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);m.invoke(activity,args);}
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void send()throws Exception{call("sendTip",new Class<?>[]{String.class,String.class,String.class,String.class,String.class},"recipient",ADDR,TipTransport.MXUSD,"1.25","note 中文");}
    JSONObject balance(String token)throws Exception{return new JSONObject().put("status",true).put("response",new JSONArray().put(new JSONObject().put("tokenid",token).put("sendable","10")));}
    void replace(){assertTrue(SalonStore.writeIdentity(activity,"wrapped:new","new-key"));}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        ((ExecutorService)f("io").get(activity)).shutdownNow();node=mock(NodeApi.class);f("node").set(activity,node);f("nodeUp").setBoolean(activity,true);
        activity.getSharedPreferences("salon_identity",0).edit().clear().commit();assertTrue(SalonStore.writeIdentity(activity,"wrapped:fixture","old-key"));SalonStore.put(activity,"handle","sender");
        doAnswer(c->{commands.add(c.getArgument(0));replies.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @After public void cleanup()throws Exception{call("closeNodeWork",new Class<?>[]{});Language.select(activity,"en");}
    @Test public void changedAccountNodeOrScreenNeverUsesOldBalanceToSend()throws Exception{
        send();replace();replies.get(0).onResult(balance(TipTransport.MXUSD));idle();assertEquals(1,commands.size());assertFalse(f("mTipInFlight").getBoolean(activity));
        send();f("nodeReadEpoch").setLong(activity,1);replies.get(1).onResult(balance(TipTransport.MXUSD));idle();assertEquals(2,commands.size());
        send();call("cancelTipLookup",new Class<?>[]{});replies.get(2).onResult(balance(TipTransport.MXUSD));idle();assertEquals(3,commands.size());
    }
    @Test public void wrongTokenMalformedAndRejectedBalancesCannotSend()throws Exception{
        for(JSONObject reply:new JSONObject[]{null,balance(TipTransport.MINIMA),balance(TipTransport.MXUSD).put("status","true"),balance(TipTransport.MXUSD).put("status",false)}){
            send();replies.get(replies.size()-1).onResult(reply);idle();assertFalse(f("mTipInFlight").getBoolean(activity));
        }
        assertEquals(4,commands.size());assertTrue(commands.stream().allMatch(c->c.startsWith("balance ")));
    }
    @Test public void duplicateBalanceAndLateFailureDoNotRepeatSendOrReleaseNewOperation()throws Exception{
        send();NodeApi.Cb first=replies.get(0);JSONObject funds=balance(TipTransport.MXUSD);first.onResult(funds);first.onResult(funds);first.onError("late");idle();assertEquals(2,commands.size());assertTrue(f("mTipInFlight").getBoolean(activity));
        NodeApi.Cb post=replies.get(1);post.onError("timeout");idle();assertFalse(f("mTipInFlight").getBoolean(activity));send();post.onResult(new JSONObject().put("status",true));first.onResult(funds);idle();assertEquals(3,commands.size());assertTrue(f("mTipInFlight").getBoolean(activity));
    }
    @Test public void invalidAddressAndTokenNeverReachEvenBalanceCommand()throws Exception{
        for(String[] values:new String[][]{{"bad address",TipTransport.MXUSD},{ADDR,"0x00 burn:1"}})
            call("sendTip",new Class<?>[]{String.class,String.class,String.class,String.class,String.class},"recipient",values[0],values[1],"1","note");
        assertTrue(commands.isEmpty());
    }
    List<EditText> edits(View root){List<EditText> out=new ArrayList<>();if(root instanceof EditText)out.add((EditText)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(edits(((ViewGroup)root).getChildAt(i)));return out;}
    @Test public void oldDialogConsentCannotSendAfterKeyReplacementOrDismissal()throws Exception{
        call("tipDialog",new Class<?>[]{String.class,String.class},"recipient",ADDR);idle();AlertDialog d=(AlertDialog)ShadowDialog.getLatestDialog();edits(d.getWindow().getDecorView()).get(0).setText("1");replace();d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(1,commands.size());
        call("tipDialog",new Class<?>[]{String.class,String.class},"recipient",ADDR);idle();d=(AlertDialog)ShadowDialog.getLatestDialog();edits(d.getWindow().getDecorView()).get(0).setText("1");d.dismiss();idle();d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals(2,commands.size());
    }
    @Test public void completionAfterAccountChangeReleasesOnlyOwnBusyStateWithoutSuccessNotice()throws Exception{
        send();replies.get(0).onResult(balance(TipTransport.MXUSD));idle();String before=ShadowToast.getTextOfLatestToast();replace();replies.get(1).onResult(new JSONObject().put("pending",true));idle();assertEquals(before,ShadowToast.getTextOfLatestToast());assertFalse(f("mTipInFlight").getBoolean(activity));
    }
}
