package com.eurobuddha.statenft;

import android.content.Context;
import com.eurobuddha.minimaapi.direct.LocalJson;
import org.minima.objects.base.MiniString;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33)
public class AtelierCreateRecoveryTest {
    private Context ctx; private NodeApi node; private JSONObject row; private int tip=100;
    private JSONObject balance; private boolean tokenFailure, postLost; private int posts;
    private final String pk="0x"+"ab".repeat(32), one="0x"+"11".repeat(32), two="0x"+"22".repeat(32);
    private static JSONObject obj(Object...v)throws Exception {JSONObject o=new JSONObject();for(int i=0;i<v.length;i+=2)o.put((String)v[i],v[i+1]);return o;}
    private static JSONObject ok(Object r)throws Exception{return obj("status",true,"response",r);}
    @Before public void setup()throws Exception {
        ctx=RuntimeEnvironment.getApplication();ctx.getSharedPreferences("statenft_suite",0).edit().clear().commit();LocalStore.save(ctx,new JSONArray());
        Field f=MintEngine.class.getDeclaredField("mintNode");f.setAccessible(true);f.set(null,null);
        row=obj("id",1,"name","Sculpture","mode","url","size",12,"description","Works","base","https://example.test/art/","ext",".jpg","icon","https://example.test/icon.jpg","creatorpk",pk,"creatoraddr",pk,"phase","CREATE","created",1,"tokenid","");
        LocalStore.upsert(ctx,row);balance=ok(new JSONArray());node=mock(NodeApi.class);
        doAnswer(a->{String command=a.getArgument(0);NodeApi.Cb cb=a.getArgument(1);
            if(command.equals("block")) cb.onResult(ok(obj("block",tip)));
            else if(command.equals("balance")) cb.onResult(balance);
            else if(command.startsWith("tokens tokenid:")) {if(tokenFailure) cb.onError("unavailable");else cb.onResult(ok(obj("script",StateNft.script(pk,"url"))));}
            else if(command.startsWith("tokencreate ")) {
                posts++; assertTrue("Intent must be durable before node write",ctx.getSharedPreferences("statenft_suite",0).contains("create:1"));
                if(postLost)cb.onError("response lost");else cb.onResult(ok(obj("txpowid","0x1234")));
            } else fail("Unexpected command: "+command);
            return null;
        }).when(node).cmd(anyString(),any(NodeApi.Cb.class));
    }
    private JSONObject token(String id,boolean embedded)throws Exception {
        JSONObject meta=StateNft.tokenMetadata(MintEngine.metaFromRow(row));
        org.minima.utils.json.JSONObject raw=new org.minima.utils.json.JSONObject();raw.put("tokenid",id);raw.put("token",new MiniString(meta.toString()));
        return embedded?(JSONObject)LocalJson.copy(raw):new JSONObject(raw.toString());
    }
    private void tick(){MintEngine.tick(ctx,node,m->{});}
    @Test public void realCoreMiniStringAndStandaloneSerializationFindTheSameToken()throws Exception {
        for(boolean embedded:new boolean[]{false,true}) {
            row.put("phase","CREATE").put("tokenid","");LocalStore.save(ctx,new JSONArray().put(row));
            balance=ok(new JSONArray().put(token(one,embedded)));tick();
            assertEquals(one,LocalStore.findById(ctx,1).getString("tokenid"));assertEquals("MOVE",LocalStore.findById(ctx,1).getString("phase"));assertEquals(0,posts);
        }
    }
    @Test public void malformedBalanceAndFailedContractReadNeverMint()throws Exception {
        for(JSONObject bad:new JSONObject[]{obj("status",true),ok(new JSONArray().put(JSONObject.NULL)),ok(new JSONArray().put(obj("tokenid",one,"token","{broken")))}) {balance=bad;tick();assertEquals(0,posts);}
        balance=ok(new JSONArray().put(token(one,true)));tokenFailure=true;tick();assertEquals(0,posts);
    }
    @Test public void lostResponseRecoveryTimeoutAndStaleRowCannotSubmitAgain()throws Exception {
        postLost=true;tick();assertEquals(1,posts);
        row=LocalStore.findById(ctx,1);row.put("posted",0).put("postedat",0);LocalStore.upsert(ctx,row);
        for(int i=0;i<30;i++){tip+=30;MintEngine.recover(ctx,node,LocalStore.findById(ctx,1),tip,m->{});tick();}
        assertEquals(1,posts);assertEquals("CREATE",LocalStore.findById(ctx,1).optString("phase"));
    }
    @Test public void legacySubmissionIsNeverAutomaticallyRepeated()throws Exception {
        row.put("posted",0).put("postedat",20);LocalStore.upsert(ctx,row);tip=1000;tick();assertEquals(0,posts);
    }
    @Test public void ambiguityRequiresChoiceAndMergesOnlyChosenGalleryRow()throws Exception {
        JSONObject adopted=obj("id",2,"tokenid",one,"phase","DONE","created",0),other=obj("id",3,"tokenid",two,"phase","DONE","created",0);
        LocalStore.save(ctx,new JSONArray().put(row).put(adopted).put(other));balance=ok(new JSONArray().put(token(one,true)).put(token(two,true)));tick();
        assertEquals(0,posts);assertEquals("",LocalStore.findById(ctx,1).getString("tokenid"));assertEquals(2,LocalStore.findById(ctx,1).getJSONArray("creatematches").length());
        assertFalse(LocalStore.chooseCreate(ctx,1,pk));assertTrue(LocalStore.chooseCreate(ctx,1,one));tick();
        assertEquals(one,LocalStore.findById(ctx,1).getString("tokenid"));assertNull(LocalStore.findById(ctx,2));assertEquals(two,LocalStore.findById(ctx,3).getString("tokenid"));assertEquals(0,posts);
    }
    @Test public void sameNameDifferentArtworkIsNotAdoptedAndLegacySubmissionStillWaits()throws Exception {
        JSONObject different=token(one,false);different.getJSONObject("token").put("base","https://other.test/");
        row.put("postedat",20);LocalStore.upsert(ctx,row);balance=ok(new JSONArray().put(different));tick();assertEquals("",LocalStore.findById(ctx,1).getString("tokenid"));assertEquals(0,posts);
    }
    @Test public void bindingCannotStealCreatedOrBurialCollection()throws Exception {
        for(String phase:new String[]{"MOVE","BURY","BURIED"}) {
            JSONObject held=obj("id",2,"tokenid",one,"phase",phase,"created",0);
            LocalStore.save(ctx,new JSONArray().put(row).put(held));assertFalse(LocalStore.bindCreate(ctx,row,one));assertEquals(2,LocalStore.load(ctx).length());
        }
    }
    @Test public void changedDraftOrMissingRecordCannotReserveSubmission()throws Exception {
        JSONObject changed=new JSONObject(row.toString()).put("base","https://changed.test/");assertFalse(LocalStore.reserveCreate(ctx,changed,100));
        LocalStore.save(ctx,new JSONArray());assertFalse(LocalStore.reserveCreate(ctx,row,100));assertEquals(0,posts);
    }
    @Test public void lateCreateCallbacksCannotEraseChoiceOrResurrectPendingMint()throws Exception {
        balance=ok(new JSONArray().put(token(one,true)).put(token(two,true)));tick();
        JSONObject stale=new JSONObject(LocalStore.findById(ctx,1).toString());
        assertTrue(LocalStore.chooseCreate(ctx,1,one));LocalStore.upsert(ctx,stale);
        assertEquals(one,LocalStore.findById(ctx,1).getString("createchoice"));
        JSONObject chosen=new JSONObject(LocalStore.findById(ctx,1).toString());assertTrue(LocalStore.bindCreate(ctx,chosen,one));
        LocalStore.upsert(ctx,stale);assertEquals("MOVE",LocalStore.findById(ctx,1).getString("phase"));assertEquals(one,LocalStore.findById(ctx,1).getString("tokenid"));
    }
}
