package com.eurobuddha.salon;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.os.Handler;

import com.eurobuddha.minimaapi.direct.DirectNodeApi;

import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/** The identity claim's name, bio and hosted URL go into `tokencreate name:{...}`. The node splits it at every ";",
 *  counts { } [ ] with one depth counter even inside a JSON string, ends a quoted value at the first '"' and has
 *  no backslash escape: typed text holding any of them is refused, never escaped. */
public class IdentityClaimInjectionTest {
    static final String SEND = "send amount:1000 address:0x" + "66".repeat(32) + " tokenid:0x00";

    @Test public void typedNameAndBioRefuseEveryCharacterTheTokenizerActsOn() {
        for (String bad : new String[]{"Alice;" + SEND, "bio\u007f", "a\nb", "a\tb", "{x}", "a}b",
                "Painter, Kyiv — oil & ink [2026]", "Alice] amount:1 decimals:0 burn:1000 webvalidate:", "a[b",
                "say \"hi\" burn:1000", "back\\slash"})
            assertFalse(bad, Args.isSafeMeta(bad));
        for (String ok : new String[]{null, "", "Alice", "Painter, Kyiv — oil & ink (2026)", "Україна 中文", "50% 'q' a:b #1 @x"})
            assertTrue(String.valueOf(ok), Args.isSafeMeta(ok));
    }

    @Test public void theHostedUrlRefusesThemToo() {
        for (String bad : new String[]{null, "", "https://host.example/alice;" + SEND, "https://host.example/a b",
                "ftp://host.example/alice", "https://host.example/\"x", "javascript:alert(1)", "https://host.example/a'b",
                "https://host.example/a]b", "https://host.example/a[b", "https://[2001:db8::1]/alice.html",
                "https://host.example/a\\b", "https://host.example/a{b", "https://host.example/a}b", "https://host.example/a\tb"})
            assertFalse(String.valueOf(bad), Args.isSafeUrl(bad));
        assertTrue(Args.isSafeUrl("https://alice.github.io/salon/alice.html"));
        assertTrue(Args.isSafeUrl("http://192.168.1.4:8080/salon/alice.html?v=2"));
    }

    /** The same central refusal Atelier and Future Cash have: no command with ";" or a control character leaves Salon. */
    @Test public void noCommandWithASemicolonOrControlCharacterLeavesSalon() {
        try (MockedConstruction<Handler> handlers = mockConstruction(Handler.class, (h, c) ->
                when(h.post(any())).thenAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return true; }));
             MockedConstruction<DirectNodeApi> clients = mockConstruction(DirectNodeApi.class);
             MockedStatic<android.util.Log> log = mockStatic(android.util.Log.class)) {
            NodeApi api = new NodeApi(mock(Context.class), null);
            for (String bad : new String[]{"tokencreate name:{\"name\":\"Alice;" + SEND + "\"} amount:1 decimals:0",
                    "coins address:0x01\n" + SEND, "coins address:0x01\r", "coins address:0x01\t", "coins address:0x01\u0000", "balance\u007f"}) {
                NodeApi.Cb cb = mock(NodeApi.Cb.class);
                api.cmd(bad, cb);
                verify(cb).onError(contains("Unsafe command data"));
                verify(cb, never()).onResult(any());
            }
            verify(clients.constructed().get(0), never()).Command(anyString(), any());
            api.cmd("balance tokenid:0x00", mock(NodeApi.Cb.class));
            verify(clients.constructed().get(0)).Command(eq("balance tokenid:0x00"), any());
            api.onDestroy();
        }
    }
}
