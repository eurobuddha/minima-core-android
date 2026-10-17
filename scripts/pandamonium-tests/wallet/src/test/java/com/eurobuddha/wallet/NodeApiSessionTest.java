package com.eurobuddha.wallet;

import android.content.Context;
import android.os.Handler;
import com.eurobuddha.wallet.comms.NodeApi;
import com.eurobuddha.minimaapi.MinimaAPIListener;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Reuses the bundled NFT handler fixture; no actual node backend or Android activity. */
public class NodeApiSessionTest {
    @Test public void queuedCommandCarriesTheSessionGuardAndDropsObsoleteReply() throws Exception {
        List<Runnable> posted = new ArrayList<>();
        AtomicBoolean current = new AtomicBoolean(true);
        try (MockedConstruction<Handler> handlers = mockConstruction(Handler.class,(h,c) -> {
            when(h.post(any())).thenAnswer(i -> { posted.add(i.getArgument(0));return true; });
        }); MockedConstruction<DirectNodeApi> clients = mockConstruction(DirectNodeApi.class)) {
            NodeApi api = new NodeApi(mock(Context.class),null,current::get);
            NodeApi.Cb callback = mock(NodeApi.Cb.class);
            api.cmd("txnpost id:disposable",callback);
            DirectNodeApi client = clients.constructed().get(0);
            verify(client,never()).Command(anyString(),any(BooleanSupplier.class),any());
            posted.remove(0).run();
            ArgumentCaptor<BooleanSupplier> guard = ArgumentCaptor.forClass(BooleanSupplier.class);
            ArgumentCaptor<MinimaAPIListener> reply = ArgumentCaptor.forClass(MinimaAPIListener.class);
            verify(client).Command(eq("txnpost id:disposable"),guard.capture(),reply.capture());
            assertTrue(guard.getValue().getAsBoolean());
            current.set(false);
            assertFalse(guard.getValue().getAsBoolean());
            reply.getValue().response(new JSONObject().put("status",true));
            posted.remove(0).run();
            verifyNoInteractions(callback);
        }
    }
    @Test public void releaseBeforeMainDispatchPreventsSubmission() {
        List<Runnable> posted = new ArrayList<>();
        try (MockedConstruction<Handler> handlers = mockConstruction(Handler.class,(h,c) -> {
            when(h.post(any())).thenAnswer(i -> { posted.add(i.getArgument(0));return true; });
        }); MockedConstruction<DirectNodeApi> clients = mockConstruction(DirectNodeApi.class)) {
            NodeApi api = new NodeApi(mock(Context.class),null,() -> true);
            api.cmd("txnpost id:disposable",null);
            api.onDestroy();
            posted.remove(0).run();
            verify(clients.constructed().get(0),never()).Command(anyString(),any(BooleanSupplier.class),any());
        }
    }
    @Test public void invalidSessionCannotReachMainDispatch() {
        List<Runnable> posted = new ArrayList<>();
        try (MockedConstruction<Handler> handlers = mockConstruction(Handler.class,(h,c) -> {
            when(h.post(any())).thenAnswer(i -> { posted.add(i.getArgument(0));return true; });
        }); MockedConstruction<DirectNodeApi> clients = mockConstruction(DirectNodeApi.class)) {
            NodeApi api = new NodeApi(mock(Context.class),null,() -> { throw new IllegalStateException("fixture changed"); });
            api.cmd("status",null);
            posted.remove(0).run();
            verify(clients.constructed().get(0),never()).Command(anyString(),any(BooleanSupplier.class),any());
        }
    }
}
