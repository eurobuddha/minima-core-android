package com.eurobuddha.atomix;

import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.atomix.swap.*;
import com.eurobuddha.atomix.integratedcomms.*;
import com.goterl.lazysodium.LazySodium;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.web3j.crypto.Credentials;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual saved-config/edit handler; reuses ManualSendContextTest's unstarted activity fixture. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33)
public class OrderEditIdentityTest {
    static final Credentials OWNER=Credentials.create("01");
    static final String MINIMA_KEY="0x"+"ab".repeat(32);
    MainActivity activity; SwapEngine engine; LazySodium sodium; NodeApi node;
    TradingContext saved;
    Field field(String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    void set(String name,Object value)throws Exception {field(name).set(activity,value);}
    void push(Order o)throws Exception {Method m=MainActivity.class.getDeclaredMethod("pushOrderEdit",Order.class);m.setAccessible(true);m.invoke(activity,o);}
    @Before public void setup()throws Exception {
        saved=TradingContext.active();TradingContext.setActive(TradingContext.MINIMA,null);
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atomix_Theme_Utxo);
        ((ExecutorService)field("io").get(activity)).shutdownNow();set("modalOpen",true);
        set("prefs",activity.getSharedPreferences("edit-identity-fixture",0));
        EthWallet wallet=(EthWallet)field("wallet").get(activity);
        Field creds=EthWallet.class.getDeclaredField("creds");creds.setAccessible(true);creds.set(wallet,OWNER);
        set("myMinimaPk",MINIMA_KEY);
        set("identity",CommsIdentity.fromKeys(new byte[32],new byte[32],new byte[32],new byte[64]));
        engine=mock(SwapEngine.class);set("engine",engine);
        sodium=mock(LazySodium.class);set("ls",sodium);node=mock(NodeApi.class);set("node",node);
        ((Map<String,String>)field("tokenBals").get(activity)).put("USDT","750");
    }
    @After public void cleanup(){TradingContext.setActive(saved,null);}
    Order savedOrder(boolean enabled){
        Order original=new Order();Order.Pair pair=new Order.Pair(enabled,0,1,1);
        pair.bids.add(new Order.Level(1,500));original.pairs.put("USDT",pair);
        return Order.fromConfigJson(Order.toConfigJson(original));
    }
    @Test public void liveEditCarriesCurrentIdentitiesBeforeFundingChecks()throws Exception {
        Order o=savedOrder(true);assertNull(o.ethAddress);assertNull(o.minimaPublicKey);
        doAnswer(i->{Order reviewed=i.getArgument(0);assertEquals(MINIMA_KEY,reviewed.minimaPublicKey);
            assertEquals(OWNER.getAddress(),reviewed.ethAddress);assertEquals(500,reviewed.pairs.get("USDT").bids.get(0).amount,0);
            return null;}).when(engine).ensureLadderCoins(any(),anyBoolean(),any());
        push(o);verify(engine).ensureLadderCoins(same(o),anyBoolean(),any());
    }
    @Test public void withdrawalSignsIdentifiedEmptyOrderWithoutFundingReads()throws Exception {
        Order o=savedOrder(false);final JSONObject[] signed=new JSONObject[1];
        when(sodium.cryptoSignDetached(any(),any(),anyLong(),any())).thenAnswer(i->{
            signed[0]=new JSONObject(new String((byte[])i.getArgument(1),StandardCharsets.UTF_8));return true;});
        try(MockedStatic<CommsTransport> transport=mockStatic(CommsTransport.class)) {
            push(o);assertNotNull(signed[0]);assertEquals(MINIMA_KEY,signed[0].getString("mpk"));
            assertEquals(OWNER.getAddress(),signed[0].getString("eth"));
            assertFalse(Order.fromJson(signed[0]).hasLiquidity());verifyNoInteractions(node,engine);
        }
    }
    @Test public void haltedIdentityCannotPrepareOrSignAnEdit()throws Exception {
        try(MockedStatic<IdentityWatch> watch=mockStatic(IdentityWatch.class)) {
            watch.when(IdentityWatch::halted).thenReturn(true);push(savedOrder(true));
            verifyNoInteractions(engine,node,sodium);
        }
    }
}
