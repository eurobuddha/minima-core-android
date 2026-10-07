package com.eurobuddha.futurecashnext;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class TransactionReplyTest {
    private Tx.Result collectWith(JSONObject post) throws Exception {
        Node node=mock(Node.class);
        when(node.cmd(anyString())).thenAnswer(i -> ((String)i.getArgument(0)).startsWith("txnpost")
                ? post : new JSONObject().put("status", true));
        Tx.Result result=Tx.collect(node,"0xaa","0xbb","1","0x00");
        verify(node).cmd(startsWith("txndelete id:"));
        return result;
    }
    @Test public void transportFailureOnPostNeverReportsSuccess() throws Exception {
        Tx.Result result=collectWith(new JSONObject().put("transporterror","node stopped"));
        assertFalse(result.ok); assertTrue(result.error.contains("may have succeeded"));
    }
    @Test public void missingOrMalformedStatusNeverReportsSuccess() throws Exception {
        for(JSONObject reply:new JSONObject[]{new JSONObject(), new JSONObject().put("response",new JSONObject().put("istransaction",false)),new JSONObject().put("status","true")})
            assertFalse(collectWith(reply).ok);
        assertFalse(collectWith(null).ok);
    }
    @Test public void miningResponseAndDefinitiveRejectionRemainDistinct() throws Exception {
        assertTrue(collectWith(new JSONObject().put("status",true).put("response",new JSONObject().put("istransaction",false))).ok);
        Tx.Result rejected=collectWith(new JSONObject().put("status",false).put("error","fixture rejection"));
        assertFalse(rejected.ok); assertTrue(rejected.error.contains("fixture rejection"));
    }
    @Test public void unknownSigningResultStopsBeforePostingAndStillCleansUp() throws Exception {
        Node node=mock(Node.class);
        when(node.cmd(anyString())).thenAnswer(i -> new JSONObject().put(((String)i.getArgument(0)).startsWith("txnsign")?"transporterror":"status",true));
        assertFalse(Tx.sweep(node,"0xaa","0xbb","1","0x00","auto").ok);
        verify(node,never()).cmd(startsWith("txnpost")); verify(node).cmd(startsWith("txndelete"));
    }
}
