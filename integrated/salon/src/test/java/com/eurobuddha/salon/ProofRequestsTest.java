package com.eurobuddha.salon;

import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;

public class ProofRequestsTest {
    private long now;
    private ProofRequests requests;
    private final List<Consumer<ProofRequests.Result>> completions=new ArrayList<>();
    private final List<ProofRequests.Result> values=new ArrayList<>();
    private final Consumer<ProofRequests.Result> callback=values::add;
    private final ProofRequests.Result yes=new ProofRequests.Result(true,"verified holding",false),no=new ProofRequests.Result(false,"incomplete proof",false);
    @Before public void setup(){requests=new ProofRequests(()->now);}
    @Test public void duplicateChecksShareOneRequestAndEachListenerCompletesOnce(){
        List<ProofRequests.Result> other=new ArrayList<>();Consumer<ProofRequests.Result> second=other::add;
        requests.request("key",false,completions::add,callback);requests.request("key",false,completions::add,callback);requests.request("key",false,completions::add,second);assertEquals(1,completions.size());completions.get(0).accept(yes);completions.get(0).accept(no);assertEquals(Arrays.asList(yes),values);assertEquals(Arrays.asList(yes),other);
    }
    @Test public void forceRetrySupersedesOldReplyAndCarriesExistingListeners(){
        List<ProofRequests.Result> other=new ArrayList<>();Consumer<ProofRequests.Result> second=other::add;
        requests.request("key",false,completions::add,callback);requests.request("key",true,completions::add,second);assertEquals(2,completions.size());completions.get(0).accept(yes);assertTrue(values.isEmpty());completions.get(1).accept(no);assertEquals(Arrays.asList(no),values);assertEquals(Arrays.asList(no),other);requests.request("key",false,completions::add,callback);assertSame(no,values.get(1));assertEquals(2,completions.size());
    }
    @Test public void cachedVerdictsExpireAndForceInvalidatesThemImmediately(){
        requests.request("key",false,completions::add,callback);completions.get(0).accept(yes);now=29_999;requests.request("key",false,completions::add,callback);assertEquals(1,completions.size());now=30_000;requests.request("key",false,completions::add,callback);assertEquals(2,completions.size());completions.get(1).accept(yes);requests.request("key",true,completions::add,callback);requests.request("key",false,completions::add,callback);assertEquals(3,completions.size());assertEquals(3,values.size());completions.get(2).accept(no);assertSame(no,values.get(3));
    }
    @Test public void exactEvidenceBindingAndOwnerDetermineTheKeyButDisplayNamesDoNot()throws Exception{
        JSONObject item=new JSONObject().put("tokenid","0xaa").put("coinid","0xbb").put("coinproof","0xcc").put("script","script").put("publickey","0xdd").put("sig","0xee").put("name","Literal");
        String key=ProofRequests.key(item,"binding","owner");assertEquals(64,key.length());JSONObject snapshot=ProofRequests.snapshot(item);assertFalse(snapshot.has("name"));item.put("name","New label");assertEquals(key,ProofRequests.key(item,"binding","owner"));
        for(String field:new String[]{"tokenid","coinid","coinproof","script","publickey","sig"}){JSONObject changed=new JSONObject(item.toString()).put(field,"different");assertNotEquals(key,ProofRequests.key(changed,"binding","owner"));}
        assertNotEquals(key,ProofRequests.key(item,"other","owner"));assertNotEquals(key,ProofRequests.key(item,"binding","other"));item.put("sig","changed later");assertEquals("0xee",snapshot.getString("sig"));assertEquals("",ProofRequests.snapshot(null).getString("sig"));
    }
    @Test public void overloadIsBoundedAndRecoversAfterARequestFinishes(){
        for(int i=0;i<128;i++)requests.request("key"+i,false,completions::add,callback);requests.request("overflow",false,completions::add,callback);assertEquals(128,completions.size());assertFalse(values.get(0).verified);completions.get(0).accept(yes);requests.request("overflow",false,completions::add,callback);assertEquals(129,completions.size());
    }
    @Test public void closeSuppressesLateCallbacksAndFailedStarterCanRetry(){
        requests.request("failed",false,done->{throw new IllegalStateException();},callback);assertFalse(values.get(0).verified);requests.request("failed",true,completions::add,callback);requests.close();completions.get(0).accept(yes);requests.request("new",false,completions::add,callback);assertEquals(1,values.size());assertEquals(1,completions.size());
    }
}
