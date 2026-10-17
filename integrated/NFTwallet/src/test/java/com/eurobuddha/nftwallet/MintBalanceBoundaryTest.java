package com.eurobuddha.nftwallet;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercise the actual mint continuation: malformed balance evidence must stop before signing. */
public class MintBalanceBoundaryTest {
    @Test public void absentMalformedAndUnbalancedCoinsCannotReachASignature() throws Exception {
        for (JSONArray coins : new JSONArray[]{null, new JSONArray(),
                new JSONArray().put(JSONObject.NULL), new JSONArray().put("bad"),
                new JSONArray().put(new JSONObject().put("difference", "1")),
                new JSONArray().put(new JSONObject().put("difference", "0")).put(JSONObject.NULL)}) {
            check(coins, false);
        }
    }
    @Test public void completeBalancedEvidenceContinuesThroughTheExistingSignAndPostSteps() throws Exception {
        check(new JSONArray().put(new JSONObject().put("difference", "0")), true);
    }
    private void check(JSONArray coins, boolean maySign) throws Exception {
        NodeApi node = mock(NodeApi.class);
        List<String> commands = new ArrayList<>();
        JSONObject response = new JSONObject().put("status", true)
                .put("response", new JSONObject().put("coins", coins));
        doAnswer(call -> {
            String command = call.getArgument(0); commands.add(command);
            NodeApi.Cb callback = call.getArgument(1);
            callback.onResult(command.startsWith("txncheck") ? response : new JSONObject().put("status", true));
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
        Runnable ok = mock(Runnable.class);
        @SuppressWarnings("unchecked") Consumer<String> fail = mock(Consumer.class);
        Method method = MintEngine.class.getDeclaredMethod("checkAndSign", NodeApi.class,
                String.class, List.class, Runnable.class, Consumer.class);
        method.setAccessible(true);
        method.invoke(null, node, "fixture", Arrays.asList("txnsign id:fixture publickey:auto"), ok, fail);
        assertEquals(maySign, commands.stream().anyMatch(c -> c.startsWith("txnsign")));
        assertEquals(maySign, commands.stream().anyMatch(c -> c.startsWith("txnpost")));
        assertTrue(commands.contains("txndelete id:fixture"));
        if (maySign) { verify(ok).run(); verify(fail, never()).accept(anyString()); }
        else { verify(ok, never()).run(); verify(fail).accept(anyString()); }
    }
}
