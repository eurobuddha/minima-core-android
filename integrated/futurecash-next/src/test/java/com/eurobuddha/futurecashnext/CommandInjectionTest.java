package com.eurobuddha.futurecashnext;

import android.content.Context;
import android.os.Handler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** The node splits command text at every ";" and runs each piece as admin: Future Cash never sends one. */
public class CommandInjectionTest {
    static final String SEND = "send amount:1000 address:0x" + "66".repeat(32) + " tokenid:0x00";

    @Test public void aSemicolonOrControlCharacterNeverReachesTheNode() {
        List<Runnable> posted = Collections.synchronizedList(new ArrayList<>());
        try (MockedConstruction<Handler> handlers = mockConstruction(Handler.class, (h, c) ->
                when(h.post(any())).thenAnswer(i -> { posted.add(i.getArgument(0)); return true; }));
             MockedConstruction<DirectNodeApi> clients = mockConstruction(DirectNodeApi.class);
             MockedStatic<android.util.Log> log = mockStatic(android.util.Log.class)) {
            NodeApi api = new NodeApi(mock(Context.class), null);
            for (String bad : new String[]{"checkaddress address:Mx0;" + SEND, "coins address:0x01\n" + SEND,
                    "coins address:0x01\r", "coins address:0x01\u0000", "txnbasics id:a;txnpost id:a"}) {
                NodeApi.Cb cb = mock(NodeApi.Cb.class);
                api.cmd(bad, cb);
                while (!posted.isEmpty()) posted.remove(0).run();
                verify(cb).onError(contains("Unsafe command data"));
                verify(cb, never()).onResult(any());
            }
            verify(clients.constructed().get(0), never()).Command(anyString(), any());
            api.cmd("checkaddress address:Mx" + "A".repeat(60), null);
            while (!posted.isEmpty()) posted.remove(0).run();
            verify(clients.constructed().get(0)).Command(eq("checkaddress address:Mx" + "A".repeat(60)), any());
            api.onDestroy();
        }
    }
}
