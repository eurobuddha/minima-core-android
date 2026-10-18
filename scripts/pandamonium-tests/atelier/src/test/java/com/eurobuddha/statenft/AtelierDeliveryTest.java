package com.eurobuddha.statenft;

import android.content.Context;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.statenft.BurialEvidenceTest.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AtelierDeliveryTest {
    Context ctx; NodeApi node; MockedStatic<MintEngine> mint;
    final Map<String,ArrayDeque<NodeApi.Cb>> reads=new LinkedHashMap<>();
    final List<List<String>> posts=new ArrayList<>();
    Consumer<JSONObject> accepted; Consumer<String> failed;
    static final String DEST=StateNft.GRAVEYARD;
    static JSONObject newJob(int count) {
        StateNft.Meta meta=new StateNft.Meta();meta.localId=41;meta.tokenid=TOKEN;
        List<StateNft.Item> items=new ArrayList<>();List<String> addrs=new ArrayList<>();
        for(int i=0;i<count;i++){StateNft.Item item=new StateNft.Item();item.index=7;item.owned=true;item.coin=coin(i+1);items.add(item);addrs.add(DEST);}
        return AirdropEngine.createJob(meta,items,addrs);
    }
    @Before public void setup()throws Exception {
        ctx=RuntimeEnvironment.getApplication();Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(ctx,"en");
        ctx.getSharedPreferences("statenft_suite",0).edit().clear().commit();LocalStore.save(ctx,new JSONArray());
        Field owner=AirdropEngine.class.getDeclaredField("owner");owner.setAccessible(true);owner.set(null,null);
        node=mock(NodeApi.class);capture(node);
        mint=mockStatic(MintEngine.class);
        mint.when(()->MintEngine.postTxnWithReceipt(eq(node),anyString(),anyList(),any(),any())).thenAnswer(i->{
            JSONObject stored=AirdropEngine.job(ctx,41);assertTrue(AirdropEngine.verifiedJob(stored));
            boolean posting=false;JSONArray entries=stored.getJSONArray("entries");for(int k=0;k<entries.length();k++)posting|="POSTING".equals(entries.getJSONObject(k).getString("status"));assertTrue("Intent durable before post",posting);
            posts.add(i.getArgument(2));accepted=i.getArgument(3);failed=i.getArgument(4);return null;
        });
    }
    @After public void cleanup()throws Exception {mint.close();ctx.getSharedPreferences("statenft_suite",0).edit().clear().commit();LocalStore.save(ctx,new JSONArray());Field f=AirdropEngine.class.getDeclaredField("owner");f.setAccessible(true);f.set(null,null);Language.select(ctx,"en");}
    void capture(NodeApi n){doAnswer(i->{reads.computeIfAbsent(i.getArgument(0),k->new ArrayDeque<>()).add(i.getArgument(1));return null;}).when(n).cmd(anyString(),any(NodeApi.Cb.class));}
    void tick(){AirdropEngine.tick(ctx,node,null);}
    void queue(int n){assertTrue(AirdropEngine.saveJob(ctx,newJob(n)));}
    JSONObject entry(int n){return AirdropEngine.job(ctx,41).optJSONArray("entries").optJSONObject(n);}
    String status(){return entry(0).optString("status");}
    NodeApi.Cb take(String command){assertTrue("Missing command "+command,reads.containsKey(command)&&!reads.get(command).isEmpty());return reads.get(command).remove();}
    void answer(String command,JSONObject reply){take(command).onResult(reply);}
    String input(int n){return "coins relevant:true own:true coinid:"+coin(n).optString("coinid");}
    String outputRead(int n){return "coins coinid:"+output(coin(n)).optString("coinid");}
    void ready(int n){answer(input(n),list(new JSONArray().put(coin(n))));answer("tokens tokenid:"+TOKEN,object("status",true,"response",object("tokenid",TOKEN,"script","RETURN TRUE")));}
    void post(){queue(1);tick();ready(1);accepted.accept(posted(coin(1)));assertEquals("POSTED",status());}
    void proof(JSONObject coin){tick();answer(outputRead(1),list(new JSONArray().put(coin)));}

    @Test public void frozenIntentIsDurableAndOnlyOneLotPostsPerTick() {
        queue(2);tick();tick();assertEquals(1,reads.get(input(1)).size());ready(1);assertEquals(1,posts.size());
        assertEquals("POSTING",status());accepted.accept(posted(coin(1)));assertEquals("POSTED",status());assertEquals("PENDING",entry(1).optString("status"));assertFalse(reads.containsKey(input(2)));
        tick();answer(outputRead(1),list(new JSONArray().put(output(coin(1)))));ready(2);accepted.accept(posted(coin(2)));assertEquals(2,posts.size());assertEquals(1,AirdropEngine.progress(AirdropEngine.job(ctx,41))[0]);
    }
    @Test public void unknownOrMalformedReadsNeverMeanDeliveredOrAuthorizePost() {
        queue(1);
        for(JSONObject bad:new JSONObject[]{null,object("status",true),object("status",false,"response",new JSONArray()),list(new JSONArray().put(object())),list(new JSONArray().put(coin(2)))}){tick();answer(input(1),bad);assertEquals("PENDING",status());assertTrue(posts.isEmpty());}
        tick();answer(input(1),list(new JSONArray()));assertEquals("FAIL",status());assertEquals(0,AirdropEngine.progress(AirdropEngine.job(ctx,41))[0]);assertFalse(AirdropEngine.hasWork(ctx));
    }
    @Test public void freshCoinMustRetainStateAmountAndBeUnspent()throws Exception {
        queue(1);for(Object[] field:new Object[][]{{"state",object("0","8")},{"tokenamount","2"},{"spent",true},{"spent","false"}}){tick();answer(input(1),list(new JSONArray().put(coin(1).put((String)field[0],field[1]))));assertTrue(posts.isEmpty());assertEquals("PENDING",status());}
    }
    @Test public void exactUnspentOutputIsRequiredForDelivery()throws Exception {
        post();for(Object[] field:new Object[][]{{"address",ADDRESS},{"tokenamount","2"},{"state",object("0","8")},{"spent",true},{"storestate",false},{"coinid","0x99"}}){proof(output(coin(1)).put((String)field[0],field[1]));assertEquals("POSTED",status());assertEquals(0,AirdropEngine.progress(AirdropEngine.job(ctx,41))[0]);}
        proof(output(coin(1)));assertEquals("DONE",status());assertEquals(1,AirdropEngine.progress(AirdropEngine.job(ctx,41))[0]);assertFalse(AirdropEngine.hasWork(ctx));assertEquals(1,posts.size());
    }
    @Test public void missingOutputOrStatusFalseNeverConfirmsOrResends() {
        post();for(int i=0;i<20;i++){tick();answer(outputRead(1),i%2==0?list(new JSONArray()):object("status",false,"response",new JSONArray().put(output(coin(1)))));}
        assertEquals("UNKNOWN",status());assertFalse(AirdropEngine.hasWork(ctx));tick();assertEquals(1,posts.size());
        assertTrue(AirdropEngine.checkJob(ctx,41));assertTrue(AirdropEngine.hasWork(ctx));proof(output(coin(1)));assertEquals("DONE",status());assertEquals(1,posts.size());
    }
    @Test public void postTimeoutAndMalformedReceiptStayUnknownAcrossRestarts() {
        queue(1);tick();ready(1);failed.accept("timeout");assertEquals("UNKNOWN",status());assertFalse(AirdropEngine.checkJob(ctx,41));tick();assertEquals(1,posts.size());
        assertTrue(AirdropEngine.clearJob(ctx,41));queue(1);tick();ready(1);accepted.accept(object("status",true));assertEquals("UNKNOWN",status());tick();assertEquals(2,posts.size());
    }
    @Test public void crashAfterDurablePostingDoesNotSignAgain()throws Exception {
        JSONObject job=newJob(1);job.getJSONArray("entries").getJSONObject(0).put("status","POSTING");assertTrue(AirdropEngine.saveJob(ctx,job));assertTrue(AirdropEngine.hasWork(ctx));tick();assertEquals("UNKNOWN",status());assertTrue(reads.isEmpty());assertTrue(posts.isEmpty());assertFalse(AirdropEngine.hasWork(ctx));
    }
    @Test public void clearAndReplacementDuringReadCannotResurrectOrSendOldJob() {
        queue(1);tick();NodeApi.Cb old=take(input(1));assertTrue(AirdropEngine.clearJob(ctx,41));queue(1);String replacement=AirdropEngine.job(ctx,41).toString();old.onResult(list(new JSONArray().put(coin(1))));assertEquals(replacement,AirdropEngine.job(ctx,41).toString());assertTrue(posts.isEmpty());tick();assertEquals(1,reads.get(input(1)).size());
    }
    @Test public void latePostCallbackCannotOverwriteReplacementOrUnlockNewOwner() {
        queue(1);tick();ready(1);Consumer<JSONObject> old=accepted;assertTrue(AirdropEngine.clearJob(ctx,41));queue(1);String replacement=AirdropEngine.job(ctx,41).toString();when(node.isClosed()).thenReturn(true);
        NodeApi fresh=mock(NodeApi.class);capture(fresh);AirdropEngine.tick(ctx,fresh,null);old.accept(posted(coin(1)));assertEquals(replacement,AirdropEngine.job(ctx,41).toString());AirdropEngine.tick(ctx,fresh,null);assertEquals(1,reads.get(input(1)).size());
    }
    @Test public void metadataErrorsAndOversizeStatePreventSigning() {
        queue(1);for(JSONObject bad:new JSONObject[]{null,object("status",true),object("status",false,"response",object()),object("status",true,"response",object()),object("status",true,"response",new JSONArray()),object("status",true,"response",object("tokenid","0x99","script","RETURN TRUE")),object("status",true,"response",object("tokenid",TOKEN,"script",""))}){tick();answer(input(1),list(new JSONArray().put(coin(1))));answer("tokens tokenid:"+TOKEN,bad);assertTrue(posts.isEmpty());}
        tick();answer(input(1),list(new JSONArray().put(coin(1))));answer("tokens tokenid:"+TOKEN,object("status",true,"response",object("tokenid",TOKEN,"script","RETURN TRUE","name","x".repeat(70000))));assertEquals("FAIL",status());assertTrue(posts.isEmpty());
    }
    @Test public void pausedCollectionCannotPostAfterItsReadCompletes() {
        queue(1);tick();LocalStore.save(ctx,new JSONArray().put(object("id",41,"tokenid",TOKEN,"phase","BURY")));ready(1);assertTrue(posts.isEmpty());assertEquals("PENDING",status());
    }
    @Test public void legacyAndMalformedRecordsAreRetainedWithoutCommands() {
        for(String raw:new String[]{"bad","{} trailing","{\"entries\":[{\"status\":\"DONE\"}]}","{\"entries\":[null]}"}) {
            ctx.getSharedPreferences("statenft_suite",0).edit().putString("draft_airdrop_41",raw).putString("draft_airdrop_index","{\"41\":1}").commit();tick();assertFalse(AirdropEngine.hasWork(ctx));assertEquals(0,AirdropEngine.progress(AirdropEngine.job(ctx,41))[0]);assertEquals(raw,ctx.getSharedPreferences("statenft_suite",0).getString("draft_airdrop_41",""));assertTrue(reads.isEmpty());assertTrue(posts.isEmpty());
        }
    }
    @Test public void storedSchemaCannotCoerceIdsOrReuseAPostedReceiptAsPending()throws Exception {
        for(Object id:new Object[]{41.5,"41",0,JSONObject.NULL})assertFalse(AirdropEngine.verifiedJob(newJob(1).put("localid",id)));
        JSONObject job=newJob(1),entry=job.getJSONArray("entries").getJSONObject(0);entry.put("confirmed",true);assertFalse(AirdropEngine.verifiedJob(job));entry.remove("confirmed");
        JSONObject receipt=BurialEvidence.receipt(posted(coin(1)),entry.getJSONObject("intent")).put("destination",DEST);entry.put("receipt",receipt);assertFalse(AirdropEngine.verifiedJob(job));entry.put("status","POSTED");assertTrue(AirdropEngine.verifiedJob(job));entry.put("status","DONE");assertFalse(AirdropEngine.verifiedJob(job));
        JSONObject dup=newJob(1);dup.getJSONArray("entries").put(copy(dup.getJSONArray("entries").getJSONObject(0)));assertFalse(AirdropEngine.verifiedJob(dup));
    }
    @Test public void failedCommitRollsBackEvenWhenAndroidMemoryChanged()throws Exception {
        AtelierBurialStoreTest.Prefs p=new AtelierBurialStoreTest.Prefs();
        when(p.editor.commit()).thenAnswer(i->{p.flush();return p.writable;});
        p.writable=false;assertFalse(AirdropEngine.saveJob(p.ctx,newJob(1)));assertNull(AirdropEngine.job(p.ctx,41));assertFalse(AirdropEngine.hasWork(p.ctx));
        p.writable=true;assertTrue(AirdropEngine.saveJob(p.ctx,newJob(1)));String previous=AirdropEngine.job(p.ctx,41).toString();p.writable=false;assertFalse(AirdropEngine.clearJob(p.ctx,41));assertEquals(previous,AirdropEngine.job(p.ctx,41).toString());
        AirdropEngine.tick(p.ctx,node,null);answer(input(1),list(new JSONArray().put(coin(1))));answer("tokens tokenid:"+TOKEN,object("status",true,"response",object("tokenid",TOKEN,"script","RETURN TRUE")));assertTrue(posts.isEmpty());assertEquals(previous,AirdropEngine.job(p.ctx,41).toString());
    }
    @Test public void allRecoveryMessagesRetainBindingsInFourLanguages() {
        List<CharSequence> labels=new ArrayList<>();for(String key:new String[]{"read","missing","large","invalid","unknown"})labels.add(AirdropEngine.errorText(object("errorKey",key,"error","Literal 中文")));
        for(String code:new String[]{"zh-Hans","ru","uk","en"}){Language.select(ctx,code);for(CharSequence text:labels)assertTrue(text.length()>0);assertTrue(labels.get(0).toString().contains("Literal 中文"));}
        assertEquals("Literal 中文",AirdropEngine.errorText(object("error","Literal 中文")));
    }
    @Test public void failedReceiptSaveLeavesPostingHeldWithoutAnotherSignature()throws Exception {
        queue(1);tick();ready(1);
        try(MockedStatic<LocalStore> store=mockStatic(LocalStore.class,CALLS_REAL_METHODS)) {
            store.when(()->LocalStore.writeAirdrop(eq(ctx),eq(41L),any(JSONObject.class),anyString(),eq(false))).thenReturn(false);
            accepted.accept(posted(coin(1)));assertEquals("POSTING",status());
        }
        tick();assertEquals("UNKNOWN",status());assertEquals(1,posts.size());assertFalse(AirdropEngine.hasWork(ctx));
    }
    @Test public void invalidIndexEntryDoesNotHideAnotherValidQueuedJob()throws Exception {
        ctx.getSharedPreferences("statenft_suite",0).edit().putString("draft_airdrop_index","{\"not-a-number\":1}").commit();queue(1);assertTrue(AirdropEngine.hasWork(ctx));tick();ready(1);assertEquals(1,posts.size());
    }

}
