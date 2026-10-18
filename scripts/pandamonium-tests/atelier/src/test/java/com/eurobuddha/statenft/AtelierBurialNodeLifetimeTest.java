package com.eurobuddha.statenft;
import android.content.Context;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.statenft.BurialEvidenceTest.*;
public class AtelierBurialNodeLifetimeTest {
    @Test public void closedNodeIgnoresAlreadyQueuedRepliesAndCannotDispatchNewWrites()throws Exception{
        List<Runnable> posted=new ArrayList<>();AtomicReference<com.eurobuddha.minimaapi.MinimaAPIListener> response=new AtomicReference<>();
        try(org.mockito.MockedConstruction<android.os.Handler> handlers=mockConstruction(android.os.Handler.class,(h,c)->{
            when(h.post(any(Runnable.class))).thenAnswer(i->{posted.add(i.getArgument(0));return true;});
        });org.mockito.MockedConstruction<com.eurobuddha.minimaapi.direct.DirectNodeApi> clients=mockConstruction(com.eurobuddha.minimaapi.direct.DirectNodeApi.class,(n,c)->{
            doAnswer(i->{response.set(i.getArgument(1));return null;}).when(n).Command(anyString(),any());
        })){
            NodeApi api=new NodeApi(mock(Context.class),null);NodeApi.Cb cb=mock(NodeApi.Cb.class);
            assertFalse(api.isClosed());api.cmd("coins",cb);posted.remove(0).run();
            response.get().response(object("status",true,"response",new JSONArray()));api.onDestroy();assertTrue(api.isClosed());posted.remove(0).run();verifyNoInteractions(cb);
            api.cmd("txnpost id:public-fixture",cb);posted.remove(0).run();posted.remove(0).run();verify(cb).onError("Node API released");
            verify(clients.constructed().get(0),never()).Command(eq("txnpost id:public-fixture"),any());
        }
    }
}
