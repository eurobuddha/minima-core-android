package com.eurobuddha.salon;

import android.widget.TextView;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class IdentityBalanceTest {
    private static final String ID="0x"+"ab".repeat(32);
    private JSONObject reply(Object response) throws Exception {return new JSONObject().put("status",true).put("response",response);}
    private JSONObject row(Object token) throws Exception {return new JSONObject().put("tokenid",ID).put("token",token);}
    private JSONObject meta() throws Exception {return new JSONObject().put("salon","1").put("handle","artist").put("name","Artist 中文 Українська");}
    @Test public void objectAndSerializedIdentityMetadataAreAdoptableWithoutMutatingSnapshot() throws Exception {
        for(Object token:new Object[]{meta(),meta().toString()}) {
            JSONObject input=reply(new JSONArray().put(row(token)));String before=input.toString();
            JSONObject existing=IdentityBalance.find(input,"artist");
            assertEquals(ID,existing.getString("tokenid"));assertEquals("artist",existing.getJSONObject("token").getString("handle"));
            assertEquals(before,input.toString());assertNotNull(IdentityBalance.find(input,null));
            assertNull(IdentityBalance.find(input,"another"));
        }
    }
    @Test public void completePlainTokenAndEmptySnapshotsProveNoMatchingIdentity() throws Exception {
        assertNull(IdentityBalance.find(reply(new JSONArray()),"artist"));
        assertNull(IdentityBalance.find(reply(new JSONArray().put(row("Minima"))),"artist"));
    }
    @Test public void malformedSnapshotsCannotBeMistakenForAbsence() throws Exception {
        for(JSONObject input:new JSONObject[]{null,new JSONObject(),new JSONObject().put("status",false),
                reply("bad"),reply(JSONObject.NULL),reply(new JSONArray().put("bad")),
                reply(new JSONArray().put(new JSONObject())),reply(new JSONArray().put(row(JSONObject.NULL))),
                reply(new JSONArray().put(row(true))),reply(new JSONArray().put(row(meta()).put("tokenid","invalid"))),
                reply(new JSONArray().put(row(new JSONObject().put("salon","1")))),
                reply(new JSONArray().put(row(meta())).put(JSONObject.NULL)),
                reply(new JSONArray()).put("status","true")}) {
            assertThrows(IllegalArgumentException.class,()->IdentityBalance.find(input,"artist"));
        }
    }
    @Test public void actualClaimContinuationNeverMintsOnRejectedMalformedOrTimedOutLookup() throws Exception {
        for(JSONObject input:new JSONObject[]{null,new JSONObject().put("status",false),reply("bad"),
                reply(new JSONArray().put(JSONObject.NULL))}) checkClaim(input,false);
    }
    @Test public void actualClaimContinuationMintsOnlyAfterSuccessfulAbsence() throws Exception {
        checkClaim(reply(new JSONArray().put(row("Minima"))),true);
    }
    private void checkClaim(JSONObject reply,boolean mayMint) throws Exception {
        MainActivity activity=mock(MainActivity.class,CALLS_REAL_METHODS);
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(activity).runOnUiThread(any());
        NodeApi node=mock(NodeApi.class);set(activity,"node",node);set(activity,"pubkey","0x1234");set(activity,"claiming",true);
        List<String> commands=new ArrayList<>();
        doAnswer(call->{
            String command=call.getArgument(0);commands.add(command);
            if("balance".equals(command)) {
                NodeApi.Cb cb=call.getArgument(1);
                if(reply==null)cb.onError("timeout");else cb.onResult(reply);
            }
            return null;
        }).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        try(MockedStatic<Language> language=mockStatic(Language.class)) {
            Method method=MainActivity.class.getDeclaredMethod("adoptOrMint",String.class,String.class,String.class,String.class,TextView.class);
            method.setAccessible(true);method.invoke(activity,"artist","Artist","Bio","https://example.invalid/profile.json",mock(TextView.class));
            assertEquals(mayMint,commands.stream().anyMatch(c->c.startsWith("tokencreate ")));
            if(!mayMint)assertEquals(false,get(activity,"claiming"));
        }
    }
    private static void set(MainActivity activity,String name,Object value) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);
    }
    private static Object get(MainActivity activity,String name) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);
    }
    @Test public void sparesReuseCompleteParserAndDeduplicateCaseInsensitively() throws Exception {
        String spare="0x"+"cd".repeat(32),upper="0x"+"CD".repeat(32);
        JSONObject input=reply(new JSONArray().put(row(meta())).put(row(meta().toString()).put("tokenid",spare))
                .put(row(meta()).put("tokenid",upper)).put(row("Minima").put("tokenid","0x00")));
        String original=input.toString();assertEquals(Collections.singletonList(spare),IdentityBalance.spares(input,"0x"+"AB".repeat(32)));assertEquals(original,input.toString());
    }
    @Test public void invalidActiveOrNativeIdentityOrLaterRowPreventsAllCleanup() throws Exception {
        JSONObject good=reply(new JSONArray().put(row(meta())));
        for(String keep:new String[]{null,"","0x00","0x0000","bad","0xaa address:evil"})
            assertThrows(IllegalArgumentException.class,()->IdentityBalance.spares(good,keep));
        for(JSONObject bad:new JSONObject[]{reply(new JSONArray().put(row(meta())).put(JSONObject.NULL)),
                reply(new JSONArray().put(row(meta()).put("tokenid","0x00"))),
                reply(new JSONArray().put(row(meta()).put("tokenid","0xaa;send"))),
                good.put("status",false)}) assertThrows(IllegalArgumentException.class,()->IdentityBalance.spares(bad,ID));
    }
}
