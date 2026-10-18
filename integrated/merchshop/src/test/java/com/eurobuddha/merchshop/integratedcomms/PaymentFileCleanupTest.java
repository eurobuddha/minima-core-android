package com.eurobuddha.merchshop.integratedcomms;
import android.content.Context;
import android.os.Handler;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.eurobuddha.minimaapi.MinimaAPIListener;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedConstruction;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** NFT Wallet's guarded cleanup checks adapted to the Shop wrapper; all file calls are mocked. */
public class PaymentFileCleanupTest {
    static final String NAME="ms_pay_"+"a".repeat(32)+".txn";
    static JSONObject stat()throws Exception{return new JSONObject().put("status",true).put("action","stat").put("query",NAME).put("exists",true).put("isdir",false).put("size",1234L).put("base","/public/node").put("canonical","/public/node/"+NAME);}
    static class Fixture implements AutoCloseable {
        final List<String> calls=new ArrayList<>();JSONObject reply;MinimaAPIListener cb;
        final MockedConstruction<Handler> handlers=mockConstruction(Handler.class,(h,c)->{when(h.post(any())).thenAnswer(i->{((Runnable)i.getArgument(0)).run();return true;});});
        final MockedConstruction<DirectNodeApi> clients=mockConstruction(DirectNodeApi.class,(api,c)->{doAnswer(i->{String action=i.getArgument(0);calls.add(action+":"+i.getArgument(1));if("stat".equals(action)){cb=i.getArgument(4);cb.response(reply);}return null;}).when(api).FileCommand(anyString(),anyString(),isNull(),isNull(),any());});
        final NodeApi node=new NodeApi(mock(Context.class),null);
        Fixture(JSONObject reply){this.reply=reply;}
        public void close(){node.onDestroy();clients.close();handlers.close();}
    }
    @Test public void onlyAnExactVerifiedFileCanBeDeletedAndDuplicateReplyIsIgnored()throws Exception {
        try(Fixture f=new Fixture(stat())){f.node.discardPreparedFile(NAME);assertEquals(Arrays.asList("stat:"+NAME,"delete:"+NAME),f.calls);f.cb.response(stat());assertEquals(2,f.calls.size());}
        for(String field:new String[]{"status","action","query","exists","isdir","size","base","canonical"}){JSONObject bad=stat();bad.remove(field);try(Fixture f=new Fixture(bad)){f.node.discardPreparedFile(NAME);assertEquals(field,Collections.singletonList("stat:"+NAME),f.calls);}}
        for(JSONObject bad:new JSONObject[]{stat().put("isdir",true),stat().put("canonical","/public/other/"+NAME),stat().put("size","1234"),stat().put("status","true")})try(Fixture f=new Fixture(bad)){f.node.discardPreparedFile(NAME);assertEquals(1,f.calls.size());}
    }
    @Test public void foreignNamesAndReleasedHostsNeverReachTheFileApi()throws Exception {
        try(Fixture f=new Fixture(stat())){for(String name:new String[]{null,"","backup.bak","../"+NAME,"/public/node/"+NAME,"nft_send_"+"a".repeat(32)+".txn","signedtransaction-x.txn"})f.node.discardPreparedFile(name);assertTrue(f.calls.isEmpty());f.node.onDestroy();f.node.discardPreparedFile(NAME);assertTrue(f.calls.isEmpty());}
    }
}
