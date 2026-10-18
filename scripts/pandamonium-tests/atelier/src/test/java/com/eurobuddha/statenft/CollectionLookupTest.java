package com.eurobuddha.statenft;

import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercise the real cleanup callback, including its next-read and persistence boundary. */
public class CollectionLookupTest {
    private static final String ID="0x"+"ab".repeat(32);
    private JSONObject token(Object script) throws Exception {return new JSONObject().put("tokenid",ID).put("script",script);}
    private JSONObject reply(Object token) throws Exception {return new JSONObject().put("status",true).put("response",token);}
    @Test public void unknownOrMismatchedTokenReadsKeepCollectionAndAllowLaterRetry() throws Exception {
        for(JSONObject input:new JSONObject[]{null,new JSONObject(),new JSONObject().put("status",false),
                reply(token("RETURN TRUE")).put("status",false),reply(token("RETURN TRUE")).put("status","true"),
                reply("bad"),reply(new JSONObject()),reply(token("")),reply(token("   ")),
                reply(token(JSONObject.NULL)),reply(token(true)),reply(token("RETURN TRUE").put("tokenid","0x99"))})
            check(input,false,true);
    }
    @Test public void provenCollectionScriptsStayFiled() throws Exception {
        check(reply(token(StateNft.script("0x1234","url"))),false,false);
        check(reply(token("IF SIGNEDBY(0x1234) THEN RETURN TRUE ENDIF RETURN VERIFYOUT(@INPUT GETOUTADDR(@INPUT) @AMOUNT @TOKENID TRUE)")),false,false);
    }
    @Test public void confirmedRegularNftIsStillUnfiledFromCollections() throws Exception {
        check(reply(token("RETURN TRUE")),true,false);
        check(reply(token("RETURN TRUE").put("tokenid","0x"+"AB".repeat(32))),true,false);
    }
    private void check(JSONObject reply,boolean removes,boolean retries) throws Exception {
        MainActivity activity=mock(MainActivity.class,CALLS_REAL_METHODS);NodeApi node=mock(NodeApi.class);
        Field field=MainActivity.class.getDeclaredField("node");field.setAccessible(true);field.set(activity,node);
        Field checked=MainActivity.class.getDeclaredField("phantomsChecked");checked.setAccessible(true);checked.setBoolean(activity,true);
        doAnswer(call->{
            NodeApi.Cb cb=call.getArgument(1);if(reply==null)cb.onError("timeout");else cb.onResult(reply);return null;
        }).when(node).cmd(eq("tokens tokenid:"+ID),any(NodeApi.Cb.class));
        ArrayList<long[]> ids=new ArrayList<>(Arrays.asList(new long[]{11},new long[]{12}));
        ArrayList<String> tids=new ArrayList<>(Arrays.asList(ID,"0xcd"));
        int[] removed={0};
        try(MockedStatic<LocalStore> store=mockStatic(LocalStore.class)) {
            Method method=MainActivity.class.getDeclaredMethod("verifyPhantom",ArrayList.class,ArrayList.class,int.class,int[].class);
            method.setAccessible(true);method.invoke(activity,ids,tids,0,removed);
            store.verify(()->LocalStore.removeById(activity,11),times(removes?1:0));
            assertEquals(removes?1:0,removed[0]);assertEquals(!retries,checked.getBoolean(activity));
            verify(node).cmd(eq("tokens tokenid:0xcd"),any(NodeApi.Cb.class));
        }
    }
}
