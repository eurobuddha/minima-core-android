package com.eurobuddha.salon;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

public class SalonReadTransportTest {
    static final String ADDR="0x"+"12".repeat(32);
    @Test public void tipAcceptanceRequiresActualBooleanAndCompletesOnlyOnce()throws Exception{
        for(JSONObject reply:new JSONObject[]{null,new JSONObject().put("status","true"),new JSONObject().put("pending","true"),new JSONObject().put("pending",true)}){
            NodeApi node=mock(NodeApi.class);TipTransport.Cb cb=mock(TipTransport.Cb.class);TipTransport.tip(node,ADDR,"1",TipTransport.MINIMA,"@sender","note",cb);
            ArgumentCaptor<NodeApi.Cb> captured=ArgumentCaptor.forClass(NodeApi.Cb.class);verify(node).cmd(anyString(),captured.capture());NodeApi.Cb result=captured.getValue();result.onResult(reply);result.onResult(new JSONObject().put("status",true));result.onError("late");
            if(reply!=null && Boolean.TRUE.equals(reply.opt("pending"))){verify(cb).onSent("");verify(cb,never()).onFailed(any());}else{verify(cb).onFailed(any());verify(cb,never()).onSent(any());}
        }
    }
    @Test public void balanceParserAcceptsEmptyOrMatchingRowsAndRejectsAmbiguousData()throws Exception{
        assertEquals("0",TipTransport.sendable(new JSONObject().put("status",true).put("response",new JSONArray()),TipTransport.MINIMA));
        JSONObject row=new JSONObject().put("tokenid",TipTransport.MXUSD.toLowerCase(Locale.ROOT)).put("sendable","2.50");JSONObject reply=new JSONObject().put("status",true).put("response",new JSONArray().put(row));
        assertEquals("2.50",TipTransport.sendable(reply,TipTransport.MXUSD));assertThrows(IllegalArgumentException.class,()->TipTransport.sendable(reply,TipTransport.MINIMA));
        for(Object bad:new Object[]{"NaN","-1",2,JSONObject.NULL}){row.put("sendable",bad);assertThrows(IllegalArgumentException.class,()->TipTransport.sendable(reply,TipTransport.MXUSD));}
        row.put("sendable","2");reply.getJSONArray("response").put(row);assertThrows(IllegalArgumentException.class,()->TipTransport.sendable(reply,TipTransport.MXUSD));
    }
    @Test public void mailScanRejectsBadStatusAndUnsafeAddressAndConsumesOnlyOneReply()throws Exception{
        for(JSONObject reply:new JSONObject[]{null,new JSONObject().put("status","true"),new JSONObject().put("status",false)}){
            NodeApi node=mock(NodeApi.class);LocalEcCryptoProvider crypto=mock(LocalEcCryptoProvider.class);MinimaMail.Scanned cb=mock(MinimaMail.Scanned.class);
            MinimaMail.scan(node,crypto,ADDR,cb);ArgumentCaptor<NodeApi.Cb> capture=ArgumentCaptor.forClass(NodeApi.Cb.class);verify(node).cmd(eq("coins address:"+ADDR+" order:desc depth:200"),capture.capture());capture.getValue().onResult(reply);capture.getValue().onError("duplicate");verify(cb).onMessages(Collections.emptyList());verifyNoInteractions(crypto);
        }
        NodeApi node=mock(NodeApi.class);MinimaMail.scan(node,mock(LocalEcCryptoProvider.class),"0x12 burn:1",mock(MinimaMail.Scanned.class));verifyNoInteractions(node);
    }
    @Test public void malformedSealCannotHideLaterValidMessageAndWireFieldsRemainLiteral()throws Exception{
        NodeApi node=mock(NodeApi.class);LocalEcCryptoProvider crypto=mock(LocalEcCryptoProvider.class);MinimaMail.Scanned cb=mock(MinimaMail.Scanned.class);
        when(crypto.open("bad")).thenThrow(new IllegalArgumentException());
        JSONObject wire=new JSONObject().put("id","stable").put("from","author 中文").put("body","Русский Українська").put("replybody","quoted").put("replyfrom","literal").put("mxaddr","MAX#peer");
        when(crypto.open("cafe")).thenReturn(new Opened(true,"peer",wire.toString().getBytes(StandardCharsets.UTF_8)));
        JSONArray rows=new JSONArray();for(String value:new String[]{"bad","cafe"})rows.put(new JSONObject().put("coinid","coin-"+value).put("state",new JSONArray().put(new JSONObject().put("port",99).put("data","0x"+value))));
        MinimaMail.scan(node,crypto,ADDR,cb);ArgumentCaptor<NodeApi.Cb> result=ArgumentCaptor.forClass(NodeApi.Cb.class);verify(node).cmd(anyString(),result.capture());result.getValue().onResult(new JSONObject().put("status",true).put("response",rows));
        ArgumentCaptor<List<MinimaMail.Msg>> captured=ArgumentCaptor.forClass(List.class);verify(cb).onMessages(captured.capture());assertEquals(1,captured.getValue().size());MinimaMail.Msg m=captured.getValue().get(0);
        assertTrue(m.valid);assertEquals("peer",m.fromPublicId);assertEquals("stable",m.stableId);assertEquals("author 中文",m.fromHandle);assertEquals("Русский Українська",m.body);assertEquals("quoted",m.replyBody);assertEquals("literal",m.replyFrom);assertEquals("MAX#peer",m.peerMx);
    }
}
