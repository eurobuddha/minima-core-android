package com.eurobuddha.salon;

import android.os.Looper;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

/** Delayed/repeated node replies with disposable preferences and mocked crypto/database. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonNodeOwnershipTest {
    MainActivity activity;NodeApi node;ExecutorService worker;MailDb db;LocalEcCryptoProvider crypto;
    MockedStatic<MailDb> mail;MockedStatic<SalonComms> comms;MockedStatic<MinimaMail> scan;
    List<String> commands=new ArrayList<>();List<NodeApi.Cb> replies=new ArrayList<>();List<Runnable> jobs=new ArrayList<>();
    List<MinimaMail.Scanned> scans=new ArrayList<>();
    static final String ADDR="0x"+"12".repeat(32),KEY="ab".repeat(64),PK="0x"+"34".repeat(32);
    Field f(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    void set(String n,Object x)throws Exception{f(n).set(activity,x);}
    Object call(String n)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n);m.setAccessible(true);return m.invoke(activity);}
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void work(){int guard=0;while(!jobs.isEmpty()){assertTrue(++guard<20);jobs.remove(0).run();idle();}}
    void replace(){assertTrue(SalonStore.writeIdentity(activity,"wrapped:new","new-public"));}
    JSONObject ok(Object response)throws Exception{return new JSONObject().put("status",true).put("response",response);}
    JSONObject coin(String id)throws Exception{return new JSONObject().put("coinid",id).put("amount","1").put("tokenid","0x00");}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        ((ExecutorService)f("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);set("io",worker);
        doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any());
        activity.getSharedPreferences("salon_identity",0).edit().clear().commit();assertTrue(SalonStore.writeIdentity(activity,"wrapped:fixture",KEY));
        node=mock(NodeApi.class);set("node",node);set("nodeUp",true);
        doAnswer(c->{commands.add(c.getArgument(0));replies.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        db=mock(MailDb.class);mail=mockStatic(MailDb.class);mail.when(()->MailDb.get(activity)).thenReturn(db);
        crypto=mock(LocalEcCryptoProvider.class);when(crypto.identity()).thenReturn(KEY);
        comms=mockStatic(SalonComms.class);comms.when(()->SalonComms.crypto(activity)).thenReturn(crypto);comms.when(()->SalonComms.publicId(activity)).thenReturn(KEY);
        scan=mockStatic(MinimaMail.class);scan.when(()->MinimaMail.scan(eq(node),eq(crypto),eq(ADDR),any())).thenAnswer(c->{scans.add(c.getArgument(3));return null;});
    }
    @After public void cleanup()throws Exception{call("closeNodeWork");scan.close();comms.close();mail.close();worker.shutdownNow();Language.select(activity,"en");}
    @Test public void staleAddressReplyCannotChangeReplacementAccount()throws Exception{
        call("ensureInboxAndScan");replace();replies.get(0).onResult(ok(new JSONObject().put("address",ADDR)));idle();
        assertEquals("",SalonStore.get(activity,"tipaddr"));assertEquals(1,commands.size());assertTrue(scans.isEmpty());
    }
    @Test public void validAddressStartsBothScansOnceAndRejectsDuplicateReply()throws Exception{
        call("ensureInboxAndScan");JSONObject result=ok(new JSONObject().put("address",ADDR));replies.get(0).onResult(result);replies.get(0).onResult(result);idle();
        assertEquals(ADDR,SalonStore.get(activity,"tipaddr"));assertEquals(3,commands.size());assertEquals(1,scans.size());
        assertEquals("coins address:"+ADDR+" order:desc depth:200",commands.get(2));
    }
    @Test public void invalidAddressResultsNeverWriteOrStartScans()throws Exception{
        for(JSONObject reply:new JSONObject[]{null,new JSONObject().put("status","true").put("response",new JSONObject().put("address",ADDR)),ok(new JSONObject().put("address","0x12 burn:1"))}){
            call("ensureInboxAndScan");replies.get(replies.size()-1).onResult(reply);idle();assertEquals("",SalonStore.get(activity,"tipaddr"));
        }
        assertEquals(3,commands.size());assertTrue(scans.isEmpty());
    }
    @Test public void changedAddressOrConnectionGenerationInvalidatesOldReply()throws Exception{
        call("ensureInboxAndScan");SalonStore.put(activity,"tipaddr","0x5678");replies.get(0).onResult(ok(new JSONObject().put("address",ADDR)));idle();assertEquals("0x5678",SalonStore.get(activity,"tipaddr"));
        SalonStore.put(activity,"tipaddr","");call("ensureInboxAndScan");set("nodeReadEpoch",1L);replies.get(1).onResult(ok(new JSONObject().put("address",ADDR)));idle();assertEquals("",SalonStore.get(activity,"tipaddr"));
    }
    @Test public void disconnectResetsCachedSignerAndSessionSetupAndInvalidatesOldRead()throws Exception{
        set("screen",Enum.valueOf((Class)f("screen").getType(),"SETTINGS"));
        call("ensureInboxAndScan");set("pubkey",PK);set("wasNodeUp",true);set("reannouncedThisSession",true);set("nodeUp",false);
        call("onNode");assertEquals("",f("pubkey").get(activity));assertFalse(f("reannouncedThisSession").getBoolean(activity));assertEquals(1L,f("nodeReadEpoch").getLong(activity));
        set("pubkey",PK);set("adoptChecked",true);set("nodeUp",true);call("onNode");assertEquals(2L,f("nodeReadEpoch").getLong(activity));
        replies.get(0).onResult(ok(new JSONObject().put("address",ADDR)));idle();assertEquals("",SalonStore.get(activity,"tipaddr"));
        ((android.os.Handler)f("pairHandler").get(activity)).removeCallbacksAndMessages(null);
    }
    @Test public void queuedKeyPreparationCannotRunForReplacedAccount()throws Exception{
        call("ensureMailKey");replace();work();comms.verify(()->SalonComms.publicId(activity),never());assertTrue(commands.isEmpty());
    }
    @Test public void keyPreparationCompletesBeforeNewOwnerInboxAndKeyReads()throws Exception{
        activity.getSharedPreferences("salon_identity",0).edit().clear().commit();
        comms.when(()->SalonComms.publicId(activity)).thenAnswer(c->{assertTrue(Thread.holdsLock(SalonStore.class));assertTrue(SalonStore.writeIdentity(activity,"wrapped:created",KEY));return KEY;});
        call("ensureMailKey");assertTrue(commands.isEmpty());work();assertEquals(Arrays.asList("getaddress","keys"),commands);
    }
    @Test public void inboundMailKeepsDedupReplyAndRouteAndWritesUnderAccountLock()throws Exception{
        SalonStore.put(activity,"tipaddr",ADDR);when(db.insert(anyString(),anyString(),eq(false),anyString(),anyString(),anyString(),anyLong(),eq(true))).thenAnswer(c->{assertTrue(Thread.holdsLock(SalonStore.class));return true;});
        MinimaMail.Msg message=new MinimaMail.Msg("0x1234","peer","author",ADDR,"body 中文","media","image/png",12,true);message.stableId="stable";message.replyBody="quoted";message.replyFrom="literal";message.peerMx="MAX#peer";
        call("scanMail");scans.get(0).onMessages(Arrays.asList(message));scans.get(0).onMessages(Arrays.asList(message));work();
        verify(db).insert("id-peer-stable","peer",false,"body 中文","media","image/png",12,true);verify(db).setReply("id-peer-stable","quoted","literal");verify(db).setMxAddr("peer","MAX#peer");
        verify(db).upsertContact("peer","author","",ADDR,"body 中文",12,true);
    }
    @Test public void mailReplyOrQueuedWriteAfterOwnerChangeDoesNotTouchDatabase()throws Exception{
        SalonStore.put(activity,"tipaddr",ADDR);call("scanMail");replace();scans.get(0).onMessages(Collections.emptyList());work();verifyNoInteractions(db);
        assertTrue(SalonStore.writeIdentity(activity,"wrapped:fixture",KEY));call("scanMail");scans.get(1).onMessages(Collections.emptyList());replace();work();verifyNoInteractions(db);
    }
    @Test public void cachedWrongCryptoDoesNotStartScan()throws Exception{
        SalonStore.put(activity,"tipaddr",ADDR);when(crypto.identity()).thenReturn("wrong-key");call("scanMail");assertTrue(scans.isEmpty());verifyNoInteractions(db);
    }
    @Test public void staleUnreadWorkerCannotReadOrReplaceNewAccountBadge()throws Exception{
        set("unreadCache",9);call("refreshUnread");replace();work();assertEquals(9,f("unreadCache").getInt(activity));verifyNoInteractions(db);
        when(db.totalUnread()).thenReturn(3);call("refreshUnread");call("refreshUnread");work();assertEquals(3,f("unreadCache").getInt(activity));verify(db,times(1)).totalUnread();
    }
    @Test public void tipBaselineRequiresCompleteSuccessfulRowsAndCurrentAccount()throws Exception{
        SalonStore.put(activity,"tipaddr",ADDR);
        for(JSONObject reply:new JSONObject[]{null,new JSONObject().put("status",false),ok(new JSONArray().put(coin("0x1234")).put("malformed"))}){
            call("scanTips");replies.get(replies.size()-1).onResult(reply);idle();assertEquals("",SalonStore.get(activity,"tipbaselined"));assertEquals(0,SalonStore.arr(activity,"tipseen").length());
        }
        call("scanTips");replace();replies.get(replies.size()-1).onResult(ok(new JSONArray().put(coin("0x1234"))));idle();assertEquals("",SalonStore.get(activity,"tipbaselined"));
    }
    @Test public void validTipScanBaselinesAndConsumesReplyOnce()throws Exception{
        SalonStore.put(activity,"tipaddr",ADDR);call("scanTips");NodeApi.Cb reply=replies.get(1);reply.onResult(ok(new JSONArray().put(coin("0x1234"))));idle();
        assertEquals("1",SalonStore.get(activity,"tipbaselined"));assertEquals(1,SalonStore.arr(activity,"tipseen").length());
        reply.onResult(ok(new JSONArray().put(coin("0x5678"))));idle();assertEquals(1,SalonStore.arr(activity,"tipseen").length());
    }
    @Test public void oldKeyReplyAndFallbackCannotChangeCurrentAccountSigner()throws Exception{
        call("fetchPubkey");replies.get(0).onResult(ok(new JSONArray()));replies.get(0).onResult(ok(new JSONArray()));idle();assertEquals(Arrays.asList("keys","getaddress"),commands);
        replace();replies.get(1).onResult(ok(new JSONObject().put("publickey",PK)));idle();assertEquals("",f("pubkey").get(activity));
        call("fetchPubkey");replies.get(2).onResult(ok(new JSONArray().put(new JSONObject().put("publickey",PK))));idle();assertEquals(PK,f("pubkey").get(activity));
    }
    @Test public void malformedKeyResultCannotPopulateSignerAndClosedActivityIgnoresReads()throws Exception{
        call("fetchPubkey");replies.get(0).onResult(new JSONObject().put("status","true").put("response",new JSONObject().put("publickey",PK)));idle();assertEquals("",f("pubkey").get(activity));
        call("closeNodeWork");replies.get(1).onResult(ok(new JSONObject().put("publickey",PK)));idle();assertEquals("",f("pubkey").get(activity));call("refreshUnread");assertTrue(jobs.isEmpty());
    }
}
