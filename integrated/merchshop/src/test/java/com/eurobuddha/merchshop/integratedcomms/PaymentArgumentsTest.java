package com.eurobuddha.merchshop.integratedcomms;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Remote shop fields cannot inject extra node commands. No node or payment is used. */
public class PaymentArgumentsTest {
    @Test public void rejectsCommandsInAddressTokenAndAmountBeforeSubmission() {
        for (String[] values : new String[][]{
            {"0x1234;vault action:seed", "1", "0x00"},
            {"0x1234", "1", "0x00;send amount:1"},
            {"0x1234", "1;send amount:2", "0x00"},
            {"0x1234", "-1", "0x00"},
            {null, "1", "0x00"}, {"0x1234", null, "0x00"},
            {"0x1234", "1", null}}) {
            NodeApi node = mock(NodeApi.class);
            CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class);
            CommsTransport.sendPayment(node, values[0], values[1], values[2], "order", cb);
            verifyNoInteractions(node);
            verify(cb).onFailed(anyString());
            verify(cb, never()).onSent(anyString());
        }
    }
    @Test public void validDecimalPaymentPreservesAmountAndHexEncodesOrderReference() {
        NodeApi node = mock(NodeApi.class);
        CommsTransport.SendCb cb = mock(CommsTransport.SendCb.class);
        CommsTransport.sendPayment(node, "0x1234", "12.340000", "0x00", "ref;other", cb);
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(node).cmd(command.capture(), any());
        assertEquals("send amount:12.340000 address:0x1234 tokenid:0x00 state:{\"1\":\"0x7265663b6f74686572\"}", command.getValue());
        verifyNoInteractions(cb);
    }

    @Test public void incompleteOrPendingAcknowledgementsNeverReportSent()throws Exception {
        String txid="0x"+"ab".repeat(32);
        for(org.json.JSONObject reply:new org.json.JSONObject[]{null,new org.json.JSONObject(),new org.json.JSONObject().put("status",true),new org.json.JSONObject().put("status","true").put("response",new org.json.JSONObject().put("txpowid",txid)),new org.json.JSONObject().put("status",true).put("pending",true).put("response",new org.json.JSONObject().put("txpowid",txid)),new org.json.JSONObject().put("status",true).put("response",new org.json.JSONObject().put("txpowid","0x"+"00".repeat(32)))}) {
            NodeApi node=mock(NodeApi.class);CommsTransport.SendCb cb=mock(CommsTransport.SendCb.class);
            CommsTransport.sendPayment(node,"0x1234","1","0x00","ref",cb);ArgumentCaptor<NodeApi.Cb> result=ArgumentCaptor.forClass(NodeApi.Cb.class);verify(node).cmd(anyString(),result.capture());result.getValue().onResult(reply);
            verify(cb).onFailed(anyString());verify(cb,never()).onSent(anyString());
        }
        NodeApi node=mock(NodeApi.class);CommsTransport.SendCb cb=mock(CommsTransport.SendCb.class);CommsTransport.sendPayment(node,"0x1234","1","0x00","ref",cb);ArgumentCaptor<NodeApi.Cb> result=ArgumentCaptor.forClass(NodeApi.Cb.class);verify(node).cmd(anyString(),result.capture());result.getValue().onResult(new org.json.JSONObject().put("status",true).put("response",new org.json.JSONObject().put("txpowid",txid)));verify(cb).onSent(txid);verify(cb,never()).onFailed(anyString());
    }
}
