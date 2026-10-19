package com.eurobuddha.utxo;

import android.os.Handler;
import com.eurobuddha.minimaapi.MinimaAPIListener;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedConstruction;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.utxo.TransactionEvidenceTest.*;
import static com.eurobuddha.utxo.UncertainWriteTest.object;

/** Reuses TxnBuilderFlowTest's real NodeApi / mocked transport fixture. No node or signature runs. */
class PreparedFlowFixture {
    static class Flow implements AutoCloseable {
        final MainActivity act=mock(MainActivity.class);final HistoryDb history=mock(HistoryDb.class);final TxnBuilder.Done done=mock(TxnBuilder.Done.class);
        final List<String> commands=new ArrayList<>();final List<Runnable> timeouts=new ArrayList<>();
        JSONObject quote,postReply,signedReply;String hold="",reject="",signedPath="/public/node/signedtransaction-123.txn";
        JSONObject rejection=object("status",false,"error","Public fixture rejected");MinimaAPIListener late;
        final MockedConstruction<Handler> handlers=mockConstruction(Handler.class,(h,c)->{
            when(h.post(any())).thenAnswer(i->{((Runnable)i.getArgument(0)).run();return true;});
            when(h.postDelayed(any(Runnable.class),anyLong())).thenAnswer(i->{timeouts.add(i.getArgument(0));return true;});
            doAnswer(i->{timeouts.remove(i.getArgument(0));return null;}).when(h).removeCallbacks(any(Runnable.class));
        });
        final MockedConstruction<DirectNodeApi> clients=mockConstruction(DirectNodeApi.class,(api,c)->{
            doAnswer(i->{String command=i.getArgument(0);commands.add(command);
                if(!hold.isEmpty()&&command.startsWith(hold)){late=i.getArgument(1);return null;}
                JSONObject reply=object("status",true);
                if(!reject.isEmpty()&&command.startsWith(reject))reply=rejection;
                else if(command.startsWith("sendview file:nft_send_"))reply=quote;
                else if(command.startsWith("sendview file:signedtransaction-"))reply=signedReply==null?quote:signedReply;
                else if(command.startsWith("sendsign "))reply=object("status",true,"response",object("txpow",signedPath));
                else if(command.startsWith("sendpost "))reply=postReply==null?quote:postReply;
                else if(command.startsWith("scripts address:"))reply=object("status",true,"response",object("address",command.substring("scripts address:".length()),"default",true,"simple",true,"publickey",id('e')));
                ((MinimaAPIListener)i.getArgument(1)).response(reply);return null;
            }).when(api).Command(anyString(),any());
        });
        final NodeApi node=new NodeApi(act,null);
        Flow(){when(act.node()).thenReturn(node);when(act.history()).thenReturn(history);when(history.insertPosting(any())).thenReturn(true);when(history.prepareAttempt(anyString(),anyString(),any())).thenReturn(true);when(history.markAttempt(anyString(),anyString(),any())).thenReturn(true);}
        boolean sent(String prefix){return commands.stream().anyMatch(c->c.startsWith(prefix));}
        public void close(){node.onDestroy();clients.close();handlers.close();}
    }
}
