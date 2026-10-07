package com.eurobuddha.vestr;

import android.content.Context;
import android.os.Handler;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedConstruction;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.eurobuddha.minimaapi.MinimaAPIListener;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercise the real wrapper; no command reaches a node. */
public class NodeApiResponseTest {
    @Test public void incompleteRepliesNeverReachTheCollectionSuccessCallback() throws Exception {
        for (JSONObject reply : new JSONObject[]{new JSONObject(),
                new JSONObject().put("status", "true"),
                new JSONObject().put("transporterror", "Outcome unknown.")}) {
            check(reply, false);
        }
    }
    @Test public void completeNodeSuccessAndRejectionRemainAvailableToTheCaller() throws Exception {
        check(new JSONObject().put("status", true), true);
        check(new JSONObject().put("status", false).put("error", "Rejected"), true);
    }
    private void check(JSONObject reply, boolean complete) {
        try (MockedConstruction<Handler> handlers=mockConstruction(Handler.class,(h,c)->{
            when(h.post(any())).thenAnswer(i->{ ((Runnable)i.getArgument(0)).run(); return true; });
        }); MockedConstruction<DirectNodeApi> clients=mockConstruction(DirectNodeApi.class,(api,c)->{
            doAnswer(i->{ ((MinimaAPIListener)i.getArgument(1)).response(reply); return null; })
                    .when(api).Command(anyString(),any());
        })) {
            NodeApi node=new NodeApi(mock(Context.class),null);
            NodeApi.Cb callback=mock(NodeApi.Cb.class);
            node.cmd("txnpost id:fixture",callback);
            if (complete) { verify(callback).onResult(reply); verify(callback,never()).onError(anyString()); }
            else { verify(callback).onError(anyString()); verify(callback,never()).onResult(any()); }
            node.onDestroy();
        }
    }
}
