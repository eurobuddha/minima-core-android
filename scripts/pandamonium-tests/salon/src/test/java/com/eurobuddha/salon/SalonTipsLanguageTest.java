package com.eurobuddha.salon;

import android.content.Context;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
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

/** Real dialog and transport with a mocked node. No live transfer or signing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonTipsLanguageTest {
    private MainActivity activity; private NodeApi node;
    private final List<String> commands=new ArrayList<>();private final List<NodeApi.Cb> replies=new ArrayList<>();
    private static final String TO="0x"+"12".repeat(32),HANDLE="Artist 中文 Русский Українська";
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception{field(n).set(activity,v);}
    private Object call(String n,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private AlertDialog dialog(){return (AlertDialog)ShadowDialog.getLatestDialog();}
    private void open()throws Exception{call("tipDialog",new Class<?>[]{String.class,String.class},HANDLE,TO);idle();}
    private JSONObject balance(String amount)throws Exception{return new JSONObject().put("status",true).put("response",new JSONArray().put(new JSONObject().put("tokenid",TipTransport.MXUSD).put("sendable",amount)));}
    private View find(View v,String text){if(v instanceof TextView&&text.equals(((TextView)v).getText().toString()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    private List<EditText> edits(View v){List<EditText> result=new ArrayList<>();if(v instanceof EditText)result.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)result.addAll(edits(((ViewGroup)v).getChildAt(i)));return result;}
    private String upper(int id,Object...args){return Language.upper(Language.msg(id,args)).toString();}
    private void notice(int id,Object...args){assertEquals(Language.msg(id,args).toString(),ShadowToast.getTextOfLatestToast());}
    private void send(String address,String amount)throws Exception{call("sendTip",new Class<?>[]{String.class,String.class,String.class,String.class,String.class},HANDLE,address,TipTransport.MXUSD,amount,"literal note");}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        ((ExecutorService)field("io").get(activity)).shutdownNow();node=mock(NodeApi.class);set("node",node);set("nodeUp",true);
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();SalonStore.put(activity,"handle","sender_literal");
        doAnswer(c->{commands.add(c.getArgument(0));replies.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @After public void cleanup(){if(ShadowDialog.getLatestDialog()!=null)ShadowDialog.getLatestDialog().dismiss();Language.select(activity,"en");}
    @Test public void openDialogRetainsInputsCursorAndExactTransferValuesAcrossAllLanguages()throws Exception{
        open();AlertDialog d=dialog();View root=d.getWindow().getDecorView();List<EditText> inputs=edits(root);assertEquals(2,inputs.size());
        inputs.get(0).setText("12.50");inputs.get(0).setSelection(2);String note="Literal note 中文 Русский Українська";inputs.get(1).setText(note);inputs.get(1).setSelection(5);
        replies.get(0).onResult(balance("100.00"));idle();
        for(String code:CODES){Language.select(activity,code);idle();assertSame(d,dialog());assertSame(inputs.get(0),edits(root).get(0));assertSame(inputs.get(1),edits(root).get(1));
            assertEquals("12.50",inputs.get(0).getText().toString());assertEquals(2,inputs.get(0).getSelectionStart());assertEquals(note,inputs.get(1).getText().toString());assertEquals(5,inputs.get(1).getSelectionStart());
            assertNotNull(find(root,Language.msg(l10n_salon_tips_title,HANDLE).toString()));assertNotNull(find(root,upper(l10n_mail_amount)));assertNotNull(find(root,upper(l10n_mail_note)));
            assertNotNull(find(root,upper(l10n_salon_tips_currency,"mxUSD")));assertNotNull(find(root,Language.msg(l10n_salon_tips_available,"100.00","mxUSD").toString()));
            assertEquals(Language.msg(l10n_salon_tips_note_hint).toString(),inputs.get(1).getHint().toString());assertEquals(Language.msg(l10n_wallet_send).toString(),d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());assertEquals(1,commands.size());
        }
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertEquals("balance tokenid:"+TipTransport.MXUSD,commands.get(1));replies.get(1).onResult(balance("100"));idle();
        String send=commands.get(2);assertTrue(send.startsWith("send amount:12.50 address:"+TO+" tokenid:"+TipTransport.MXUSD+" state:"));
        JSONObject state=new JSONObject(send.substring(send.indexOf(" state:")+7));assertEquals("0x"+Hex.to("@sender_literal".getBytes(StandardCharsets.UTF_8)),state.getString("1"));assertEquals("0x"+Hex.to(note.getBytes(StandardCharsets.UTF_8)),state.getString("2"));
    }
    @Test public void currencyPreviewIgnoresOldRepliesEvenAfterSwitchingBack()throws Exception{
        open();View root=dialog().getWindow().getDecorView();View currency=find(root,upper(l10n_salon_tips_currency,"mxUSD"));assertNotNull(currency);
        currency.performClick();idle();assertEquals("balance tokenid:"+TipTransport.MINIMA,commands.get(1));currency.performClick();idle();assertEquals("balance tokenid:"+TipTransport.MXUSD,commands.get(2));
        replies.get(2).onResult(balance("7.5"));idle();replies.get(0).onResult(balance("999"));replies.get(1).onError("late failure");idle();
        for(String code:CODES){Language.select(activity,code);assertNotNull(find(root,Language.msg(l10n_salon_tips_available,"7.5","mxUSD").toString()));assertNotNull(find(root,upper(l10n_salon_tips_currency,"mxUSD")));}
        assertEquals(3,commands.size());
    }
    @Test public void rejectedMalformedAndFailedPreviewShowUnavailableInsteadOfZero()throws Exception{
        for(JSONObject reply:new JSONObject[]{new JSONObject().put("status",false),new JSONObject().put("status",true),balance("NaN"),balance("1").put("status","true")}){
            open();replies.get(replies.size()-1).onResult(reply);idle();for(String code:CODES){Language.select(activity,code);assertNotNull(find(dialog().getWindow().getDecorView(),Language.msg(l10n_salon_tips_unavailable).toString()));}dialog().dismiss();
        }
        open();replies.get(replies.size()-1).onError("unavailable");idle();assertNotNull(find(dialog().getWindow().getDecorView(),Language.msg(l10n_salon_tips_unavailable).toString()));assertTrue(commands.stream().allMatch(s->s.startsWith("balance ")));
    }
    @Test public void presetsAndCurrencySelectionSurviveLanguageChanges()throws Exception{
        open();View root=dialog().getWindow().getDecorView();EditText amount=edits(root).get(0);View preset=find(root,"$5");preset.performClick();assertEquals("5",amount.getText().toString());
        View currency=find(root,upper(l10n_salon_tips_currency,"mxUSD"));currency.performClick();idle();JSONObject nativeBalance=balance("3");nativeBalance.getJSONArray("response").getJSONObject(0).put("tokenid",TipTransport.MINIMA);replies.get(1).onResult(nativeBalance);idle();
        for(String code:CODES){Language.select(activity,code);assertEquals("5",amount.getText().toString());assertNotNull(find(root,upper(l10n_salon_tips_currency,"MINIMA")));assertNotNull(find(root,Language.msg(l10n_salon_tips_available,"3","MINIMA").toString()));}
        assertEquals(View.GONE,((View)preset.getParent()).getVisibility());assertEquals(2,commands.size());
    }
    @Test public void incomingTipRetainsAuthoredSenderNoteAndAmountWhileTextChanges()throws Exception{
        String note="Someone tipped you — 中文 Русский Українська";JSONArray state=new JSONArray().put(new JSONObject().put("port",1).put("data","0x"+Hex.to("@Someone".getBytes(StandardCharsets.UTF_8))))
                .put(new JSONObject().put("port",2).put("data","0x"+Hex.to(note.getBytes(StandardCharsets.UTF_8))));
        JSONObject coin=new JSONObject().put("tokenid",TipTransport.MXUSD).put("tokenamount","12.500").put("state",state);String before=coin.toString();
        CharSequence description=(CharSequence)call("describeTip",new Class<?>[]{JSONObject.class},coin);
        CharSequence unknown=(CharSequence)call("describeTip",new Class<?>[]{JSONObject.class},new JSONObject().put("amount","1"));
        for(String code:CODES){Language.select(activity,code);assertEquals("💰 "+Language.msg(l10n_creative_tip_from,"@Someone","12.500","mxUSD")+" — "+note,description.toString());
            assertEquals("💰 "+Language.msg(l10n_creative_tip_from,Language.msg(l10n_creative_sender),"1","MINIMA"),unknown.toString());assertEquals(before,coin.toString());}
        verifyNoInteractions(node);
    }
    @Test public void validationAndTransportFeedbackUseSelectedLanguageWithoutLiveTransfers()throws Exception{
        for(String code:CODES){Language.select(activity,code);
            send(TO,"bad");notice(l10n_utxo_valid_amount);send(TO,"0");notice(l10n_mail_enter_amount);
            set("mTipInFlight",true);send(TO,"1");notice(l10n_salon_tips_busy);set("mTipInFlight",false);
            send(TO,"1");notice(l10n_salon_tips_checking);replies.get(replies.size()-1).onResult(balance("0"));idle();notice(l10n_salon_tips_insufficient,"mxUSD","0");
            int before=commands.size();send("invalid address","1");assertEquals(before,commands.size());idle();notice(l10n_salon_tips_failed,Language.msg(l10n_salon_tips_bad_address));
            send(TO,"1");replies.get(replies.size()-1).onError("Invalid node result. Outcome remains unknown.");idle();notice(l10n_salon_tips_balance_failed,Language.msg(l10n_vestr_transport_invalid));
            send(TO,"1");replies.get(replies.size()-1).onResult(balance("2"));idle();notice(l10n_salon_tips_sending);replies.get(replies.size()-1).onResult(new JSONObject().put("pending",true));idle();notice(l10n_salon_tips_submitted,HANDLE);
        }
    }
    @Test public void fixedTipErrorsTranslateButUnknownDiagnosticTextStaysLiteral(){
        String[] errors={"that profile's tip address is malformed — not sending","bad token id","bad amount","the node rejected the tip"};int[] ids={l10n_salon_tips_bad_address,l10n_salon_tips_bad_token,l10n_utxo_valid_amount,l10n_utxo_rejected};
        for(String code:CODES){Language.select(activity,code);for(int i=0;i<ids.length;i++)assertEquals(Language.msg(ids[i]).toString(),HoldingMessages.message(errors[i]).toString());assertEquals("node detail 中文",HoldingMessages.message("node detail 中文"));}
    }
}
