package com.eurobuddha.atomix.swap;

import com.eurobuddha.dexicon.integratedcomms.NodeApi;
import com.eurobuddha.dexicon.integratedcomms.SignGate;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Every real node refuses a send with coinage below 3 ("Coinage MUST be >= 3"): the wallet keeper's split must ask
 *  for coinage:3, or it never runs. */
public class KeeperSplitCoinageTest {
    @After public void cleanup() { SignGate.resetForTest(); }

    @Test public void theKeeperSplitAsksForCoinageThree() {
        SignGate.resetForTest();
        List<String> commands = new ArrayList<>();
        NodeApi node = mock(NodeApi.class);
        doAnswer(inv -> {
            commands.add(inv.getArgument(0));
            ((NodeApi.Cb) inv.getArgument(1)).onResult(new JSONObject().put("status", true)
                    .put("response", new JSONObject().put("txpowid", "0xAABB")));
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
        new MinimaHtlc(node).splitCoins(4, "10", new MinimaHtlc.PostCb() {
            public void ok(String id) {}
            public void err(String error) { fail(error); }
        });
        assertEquals(1, commands.size());
        String send = commands.get(0);
        assertTrue(send, send.startsWith("send amount:10 address:") && send.contains(" split:4 "));
        assertTrue(send, send.contains(" coinage:3 "));
        assertFalse(send, send.matches(".* coinage:[0-2](\\s.*)?"));
    }
}
