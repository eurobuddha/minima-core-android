package com.eurobuddha.merchinbox;

import android.content.Context;
import android.os.Handler;
import android.widget.Toast;
import com.eurobuddha.merchinbox.integratedcomms.MerchDb;
import com.eurobuddha.merchinbox.integratedcomms.NodeApi;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class InboxCoinEvidenceTest {
    static final String TOKEN="0x"+"ab".repeat(32), ADDRESS="0x"+"12".repeat(32), COIN="0x"+"cd".repeat(32);
    static JSONObject object(Object... pairs) throws Exception {
        JSONObject j=new JSONObject(); for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);return j;
    }
    static JSONObject coin() throws Exception {return object("coinid",COIN,"tokenid",TOKEN,"address",ADDRESS,"tokenamount","1.0000","state",object("0","7"),"spent",false,"storestate",true);}
    static JSONObject list(JSONArray rows) throws Exception {return object("status",true,"response",rows);}
    static List<JSONObject> invalidReplies() throws Exception {
        List<JSONObject> bad=new ArrayList<>(Arrays.asList(null,object(),object("status",true),object("status","true","response",new JSONArray()),object("status",false,"response",new JSONArray()),object("status",true,"response",object()),list(new JSONArray().put(JSONObject.NULL)),list(new JSONArray().put("bad")),list(new JSONArray().put(object())),list(new JSONArray().put(coin()).put(coin())),list(new JSONArray().put(coin().put("tokenid","0x99")))));
        for(String field:new String[]{"coinid","tokenid","address","tokenamount"}){JSONObject c=coin();c.remove(field);bad.add(list(new JSONArray().put(c)));}
        return bad;
    }
    static void set(MainActivity activity,String name,Object value) throws Exception {
        Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(activity,value);
    }
    static class Host implements AutoCloseable {
        final MainActivity activity=mock(MainActivity.class,CALLS_REAL_METHODS);
        final NodeApi node=mock(NodeApi.class); final MerchDb db=mock(MerchDb.class);
        final ExecutorService io=mock(ExecutorService.class); final Handler ui=mock(Handler.class);
        final Set<String> sending=new HashSet<>(); final List<Runnable> polls=new ArrayList<>();
        final List<String> commands=new ArrayList<>(); final MerchDb.Order order=new MerchDb.Order();
        final MockedStatic<Toast> toasts=mockStatic(Toast.class);
        NodeApi.Cb callback;
        Host() throws Exception {
            doReturn(false).when(activity).isFinishing();doReturn(false).when(activity).isDestroyed();
            set(activity,"node",node);set(activity,"db",db);set(activity,"io",io);set(activity,"ui",ui);
            set(activity,"nftSending",sending);set(activity,"stack",new ArrayDeque<android.view.View>());
            order.ref="fixture-order";order.payaddr=ADDRESS;order.paid=true;
            toasts.when(()->Toast.makeText(any(Context.class),any(CharSequence.class),anyInt())).thenReturn(mock(Toast.class));
            when(db.getMeta(anyString(),anyString())).thenReturn("legacy-post");
            when(ui.postDelayed(any(Runnable.class),anyLong())).thenAnswer(call->{polls.add(call.getArgument(0));return true;});
            doAnswer(call->{commands.add(call.getArgument(0));callback=call.getArgument(1);return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        }
        void watch(boolean bundle) throws Exception {
            Method m;
            if(bundle){m=MainActivity.class.getDeclaredMethod("watchBundleDeparture",MerchDb.Order.class,String.class,String.class,List.class,String.class,boolean.class,int.class);m.setAccessible(true);m.invoke(activity,order,"fixture-key",TOKEN,Collections.singletonList(COIN),null,false,0);}
            else{m=MainActivity.class.getDeclaredMethod("watchDeparture",MerchDb.Order.class,String.class,String.class,String.class,String.class,boolean.class,int.class);m.setAccessible(true);m.invoke(activity,order,"fixture-key",TOKEN,COIN,"legacy-post",false,0);}
            sending.add("fixture-key");
        }
        void gather(boolean bundle) throws Exception {
            Method m=MainActivity.class.getDeclaredMethod(bundle?"collectionDeliver":"stateNftDeliver",MerchDb.Order.class,String[].class,boolean.class);m.setAccessible(true);
            m.invoke(activity,order,new String[]{"Fixture collection",TOKEN,"1","7",bundle?"1":"0"},false);
        }
        void poll(){assertEquals(1,polls.size());polls.remove(0).run();}
        void noCompletion(){verifyNoInteractions(io);verify(db,never()).setMeta(anyString(),anyString());}
        public void close(){toasts.close();}
    }
    @Test public void reusedValidatorRejectsUnknownEnvelopeMalformedRowsAndUnsafeAmounts() throws Exception {
        assertEquals(0,DeliveryCoinList.coins(list(new JSONArray()),TOKEN).length());
        assertEquals(1,DeliveryCoinList.coins(list(new JSONArray().put(coin())),TOKEN).length());
        for(JSONObject reply:invalidReplies())assertThrows(IllegalArgumentException.class,()->DeliveryCoinList.coins(reply,TOKEN));
        for(Object amount:new Object[]{"0","-1","1 burn:9",1,JSONObject.NULL}){
            JSONObject reply=list(new JSONArray().put(coin().put("tokenamount",amount)));
            assertThrows(IllegalArgumentException.class,()->DeliveryCoinList.coins(reply,TOKEN));
        }
    }
    @Test public void unknownReadMessageUsesTheTranslatedFailureLabel() throws Exception {
        Object text=InboxMessages.message(DeliveryCoinList.INVALID);assertTrue(text instanceof Language.Message);
        Field field=Language.Message.class.getDeclaredField("resource");field.setAccessible(true);
        assertEquals(com.eurobuddha.minimacore.i18n.R.string.l10n_mall_inbox_coin_list_unconfirmed,field.get(text));
    }
}
