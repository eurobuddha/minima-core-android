package com.eurobuddha.utxo;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.utxo.UncertainWriteTest.object;
import static com.eurobuddha.utxo.TransactionEvidenceTest.*;

/** Drive the real loader using public JSON and a mocked command queue. */
public class CoinLoaderAdmissionTest {
    static class Flow {
        final MainActivity act=mock(MainActivity.class);final NodeApi node=mock(NodeApi.class);final CoinLoader.Done done=mock(CoinLoader.Done.class);
        final List<String> commands=new ArrayList<>();final List<NodeApi.Cb> callbacks=new ArrayList<>();
        Flow(){when(act.node()).thenReturn(node);doAnswer(i->{commands.add(i.getArgument(0));callbacks.add(i.getArgument(1));return null;}).when(node).cmd(anyString(),any());CoinLoader.load(act,done);}
        void answer(JSONArray coins){callbacks.remove(0).onResult(object("status",true,"response",coins));}
        void overflow(){callbacks.remove(0).onError(NodeApi.ERR_TOO_LONG);}
        void rejected(){verify(done).onError(anyString());verify(done,never()).onCoins(any(),any(),anyInt(),anyString());assertTrue(callbacks.isEmpty());}
    }
    static JSONObject coin(){return object("coinid",id('1'),"tokenid","0x00","amount","10","address",DEST);}
    @Test public void validWholeAndSendableListsKeepTheirEconomicsAndSpendability(){
        Flow f=new Flow();f.answer(new JSONArray().put(coin()));f.answer(new JSONArray().put(coin()));
        verify(f.done).onCoins(argThat(c->c.size()==1&&c.get(0).sendable&&c.get(0).amount.equals("10")&&c.get(0).rawAmount.equals("10")),anySet(),eq(-1),eq("whole"));assertEquals(2,f.commands.size());
    }
    @Test public void malformedCoinFieldsCannotReachASendableQuery()throws Exception {
        for(String key:new String[]{"coinid","tokenid","amount","address"}){Flow f=new Flow();JSONObject bad=coin();bad.put(key,"0x00;send");f.answer(new JSONArray().put(bad));f.rejected();assertEquals(1,f.commands.size());}
    }
    @Test public void incompleteSendabilityCannotBePresentedAsACompleteWallet(){
        Flow f=new Flow();f.answer(new JSONArray().put(coin()));f.callbacks.remove(0).onResult(object("status",true));f.rejected();
    }
    @Test public void tokenSliceArgumentsMustBeValidatedBeforeDispatch(){
        Flow f=new Flow();f.overflow();assertEquals("balance",f.commands.get(1));f.answer(new JSONArray().put(object("tokenid","0x00;send","coins",1)));f.rejected();assertEquals(2,f.commands.size());
    }
    @Test public void scriptSliceArgumentsMustBeValidatedBeforeDispatch(){
        Flow f=new Flow();f.overflow();f.answer(new JSONArray().put(object("tokenid","0x00","coins",1)));f.overflow();assertEquals("scripts",f.commands.get(3));f.answer(new JSONArray().put(object("address",DEST+";send")));f.rejected();assertEquals(4,f.commands.size());
    }
    @Test public void emptyOrMalformedFallbackRepliesCannotEraseTheDisplayedWallet(){
        Flow f=new Flow();f.overflow();f.callbacks.remove(0).onResult(object("status",true));f.rejected();
    }
    @Test public void nativeReadSlicesRemainAvailableWhenTheWholeReplyExceedsTheCap(){
        Flow f=new Flow();f.overflow();f.answer(new JSONArray().put(object("tokenid","0x00","coins",1)));f.answer(new JSONArray().put(coin()));f.answer(new JSONArray().put(coin()));
        verify(f.done).onCoins(argThat(c->c.size()==1&&c.get(0).sendable),anySet(),eq(1),eq("per-token"));assertEquals(4,f.commands.size());
    }
}
