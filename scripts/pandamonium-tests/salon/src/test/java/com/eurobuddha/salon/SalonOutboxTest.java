package com.eurobuddha.salon;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Real disposable SQLite plus mocked node, crypto and Maxima; never connects to a wallet. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonOutboxTest {
    private MainActivity activity;private MailDb db;private ExecutorService worker;private NodeApi node;
    private LocalEcCryptoProvider crypto;private MockedStatic<MailDb> mail;private MockedStatic<SalonComms> comms;private MockedStatic<MaximaLink> maxima;
    private final List<Runnable> jobs=new ArrayList<>();private final List<NodeApi.Cb> posts=new ArrayList<>();
    private final List<String> commands=new ArrayList<>();private final List<MaximaLink.SendCb> sends=new ArrayList<>();
    private final String owner="ab".repeat(64),peer="cd".repeat(64),addr="0x"+"12".repeat(32);
    private boolean ready;
    private Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private void set(String name,Object value)throws Exception{field(name).set(activity,value);}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private MailDb openDb()throws Exception{Constructor<MailDb> c=MailDb.class.getDeclaredConstructor(Context.class);c.setAccessible(true);return c.newInstance(activity);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void work(){int guard=0;while(!jobs.isEmpty()){assertTrue(++guard<50);jobs.remove(0).run();idle();}}
    private void send(String value)throws Exception{call("sendDm",new Class<?>[]{String.class,String.class,String.class},value,"","");}
    private void retry()throws Exception{call("retryOutbox",new Class<?>[]{});work();}
    private MailDb.Message only(){List<MailDb.Message> rows=db.messages(peer);assertEquals(1,rows.size());return rows.get(0);}
    @SuppressWarnings({"unchecked","rawtypes"})
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        ((ExecutorService)field("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);set("io",worker);doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
        set("body",new LinearLayout(activity));set("appbar",new LinearLayout(activity));set("navRow",new LinearLayout(activity));set("scroll",new ScrollView(activity));set("screen",Enum.valueOf((Class)field("screen").getType(),"THREAD"));set("threadPeer",peer);
        activity.getSharedPreferences("salon_identity",0).edit().clear().commit();SalonStore.writeIdentity(activity,"wrapped:owner",owner);SalonStore.put(activity,"handle","original");SalonStore.put(activity,"tipaddr",addr);
        activity.deleteDatabase("salon_mail");db=openDb();mail=mockStatic(MailDb.class,CALLS_REAL_METHODS);mail.when(()->MailDb.get(activity)).thenAnswer(c->db);
        db.upsertContact(peer,"Peer","",addr,"",0,false);db.setMxAddr(peer,"MAX#original");
        crypto=mock(LocalEcCryptoProvider.class);when(crypto.seal(eq(peer),any(byte[].class))).thenReturn("cafe");when(crypto.identity()).thenReturn(owner);
        comms=mockStatic(SalonComms.class);comms.when(()->SalonComms.publicId(activity)).thenReturn(owner);comms.when(()->SalonComms.crypto(activity)).thenReturn(crypto);
        maxima=mockStatic(MaximaLink.class);maxima.when(()->MaximaLink.myAddresses(activity)).thenReturn("MAX#sender");maxima.when(()->MaximaLink.isReady(activity)).thenAnswer(c->ready);
        maxima.when(()->MaximaLink.sendDm(eq(activity),anyString(),anyString(),any())).thenAnswer(c->{sends.add(c.getArgument(3));return null;});
        node=mock(NodeApi.class);set("node",node);set("nodeUp",true);doAnswer(c->{commands.add(c.getArgument(0));posts.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @After public void cleanup()throws Exception{call("cancelOutboxDialog",new Class<?>[]{});maxima.close();comms.close();mail.close();db.close();activity.deleteDatabase("salon_mail");worker.shutdownNow();Language.select(activity,"en");}

    @Test public void frozenSenderPayloadQuoteAndTransportSurvivePreferenceChanges()throws Exception{
        set("replyPeer",peer);set("replyBody","quoted 中文");set("replyFrom","You");send("literal Русский Українська");work();MailDb.Message m=only();String wire=m.wire;
        SalonStore.put(activity,"handle","new handle");SalonStore.put(activity,"tipaddr","0x3456");SalonStore.put(activity,"xport_"+peer,"chain");ready=true;retry();
        assertEquals(1,sends.size());assertTrue(commands.isEmpty());assertEquals(wire,only().wire);JSONObject payload=new JSONObject(wire);assertEquals("@original",payload.getString("from"));assertEquals("@original",payload.getString("replyfrom"));assertEquals("quoted 中文",m.replyBody);
        ArgumentCaptor<byte[]> sealed=ArgumentCaptor.forClass(byte[].class);verify(crypto).seal(eq(peer),sealed.capture());assertEquals(wire,new String(sealed.getValue(),StandardCharsets.UTF_8));
    }
    @Test public void keyReplacementCannotSealOrSendOldQueue()throws Exception{
        send("private");work();SalonStore.writeIdentity(activity,"wrapped:new","ef".repeat(64));ready=true;retry();assertTrue(sends.isEmpty());verifyNoInteractions(crypto);assertEquals(MailDb.PENDING,only().status);
        for(String code:new String[]{"en","zh-Hans","ru","uk"}){Language.select(activity,code);assertEquals(Language.msg(l10n_salon_outbox_owner).toString(),call("outgoingStatus",new Class<?>[]{MailDb.Message.class},only()).toString());}
    }
    @Test public void maximaRetryUsesSameStableIdAndDuplicateCallbacksCannotReopenIt()throws Exception{
        ready=true;send("message");work();MailDb.Message original=only();retry();assertEquals(1,sends.size());
        sends.get(0).onFailed("timeout");idle();retry();assertEquals(2,sends.size());sends.get(0).onFailed("late");idle();assertEquals("sending",only().phase);
        sends.get(1).onSent("OK",false);sends.get(1).onFailed("late");idle();assertEquals(MailDb.SENT,only().status);assertEquals(original.coinid,only().coinid);assertEquals(original.wire,only().wire);
    }
    @Test public void chainTimeoutNeverAutomaticallyPostsAgainIncludingAfterDatabaseReopen()throws Exception{
        SalonStore.put(activity,"xport_"+peer,"chain");send("chain");work();assertEquals(1,commands.size());assertEquals(MailDb.UNCERTAIN,only().status);
        posts.get(0).onError("timeout");idle();retry();db.close();db=openDb();retry();assertEquals(1,commands.size());assertEquals(MailDb.UNCERTAIN,only().status);
        assertTrue(commands.get(0).startsWith("send amount:0.001 address:"+addr+" state:"));
    }
    @Test public void malformedAcceptanceCannotMarkMessageSent()throws Exception{
        SalonStore.put(activity,"xport_"+peer,"chain");send("chain");work();posts.get(0).onResult(new JSONObject().put("status","true"));idle();assertEquals(MailDb.UNCERTAIN,only().status);
    }
    @Test public void acceptedPendingNodeResultUpdatesOnlyTheOriginalRow()throws Exception{
        SalonStore.put(activity,"xport_"+peer,"chain");send("chain");work();SalonStore.writeIdentity(activity,"new","ef".repeat(64));posts.get(0).onResult(new JSONObject().put("pending",true));idle();assertEquals(MailDb.SENT,only().status);assertEquals(owner,only().sender);
    }
    @Test public void overlappingReadersClaimOnlyOneDurableAttempt()throws Exception{
        send("queued");work();MailDb.Message a=only(),b=db.message(a.coinid);assertTrue(db.beginAttempt(a));assertFalse(db.beginAttempt(b));assertTrue(db.finishAttempt(a,a.attempt,false));assertFalse(db.finishAttempt(a,a.attempt,true));
    }
    @Test public void reopeningSameProcessDoesNotStealAnActiveMaximaAttempt()throws Exception{
        ready=true;send("active");work();db.close();db=openDb();retry();assertEquals(1,sends.size());assertEquals("sending",only().phase);
    }
    @Test public void previousProcessMaximaAttemptRecoversButStaleReceiptCannotFinishNewAttempt()throws Exception{
        send("restart");work();MailDb.Message old=only();assertTrue(db.beginAttempt(old));ContentValues v=new ContentValues();v.put("attempt","previous-process:attempt");db.getWritableDatabase().update("messages",v,"coinid=?",new String[]{old.coinid});old.attempt="previous-process:attempt";
        db.close();db=openDb();ready=true;retry();assertEquals(1,sends.size());assertFalse(db.finishAttempt(old,old.attempt,true));assertEquals("sending",only().phase);
    }
    @Test public void failedEnqueueRollsBackMessageAndPreservesDraftAndQuote()throws Exception{
        db.getWritableDatabase().execSQL("CREATE TRIGGER reject_contact BEFORE UPDATE ON contacts BEGIN SELECT RAISE(ABORT,'fixture'); END");
        set("threadDraft","keep draft");set("replyPeer",peer);set("replyBody","keep quote");set("replyFrom","You");send("keep draft");
        assertEquals(0,db.messageCount(peer));assertEquals("keep draft",field("threadDraft").get(activity));assertEquals("keep quote",field("replyBody").get(activity));assertTrue(jobs.isEmpty());
    }
    @Test public void explicitReviewSendsNewCopyOnlyAfterConsentAndKeepsComposer()throws Exception{
        SalonStore.put(activity,"xport_"+peer,"chain");send("original");work();posts.get(0).onError("timeout");idle();set("threadDraft","new unsent draft");set("replyBody","new quote");set("replyPeer",peer);set("replyFrom","You");
        call("reviewOutgoing",new Class<?>[]{MailDb.Message.class},only());idle();AlertDialog dialog=(AlertDialog)field("outboxRetryDialog").get(activity);
        for(String code:new String[]{"en","zh-Hans","ru","uk"}){Language.select(activity,code);assertSame(dialog,field("outboxRetryDialog").get(activity));assertEquals(Language.msg(l10n_salon_outbox_copy).toString(),dialog.getButton(-1).getText().toString());assertEquals(Language.msg(l10n_salon_outbox_warning,Language.msg(l10n_salon_chat_chain)).toString(),((TextView)dialog.findViewById(android.R.id.message)).getText().toString());}
        assertEquals(1,commands.size());dialog.getButton(-1).performClick();idle();assertEquals(2,db.messageCount(peer));assertEquals("new unsent draft",field("threadDraft").get(activity));assertEquals("new quote",field("replyBody").get(activity));work();assertEquals(2,commands.size());
    }
    @Test public void changedOwnerInvalidatesReviewConsent()throws Exception{
        SalonStore.put(activity,"xport_"+peer,"chain");send("original");work();posts.get(0).onError("timeout");idle();call("reviewOutgoing",new Class<?>[]{MailDb.Message.class},only());idle();AlertDialog dialog=(AlertDialog)field("outboxRetryDialog").get(activity);
        SalonStore.writeIdentity(activity,"new","ef".repeat(64));dialog.getButton(-1).performClick();idle();assertEquals(1,db.messageCount(peer));assertEquals(1,commands.size());
    }
    @Test public void migrationRetainsHistoryAndPausesLegacyPendingRows()throws Exception{
        for(int version=1;version<=4;version++) {
            db.close();activity.deleteDatabase("salon_mail");SQLiteDatabase legacy=activity.openOrCreateDatabase("salon_mail",0,null);
            legacy.execSQL("CREATE TABLE contacts(peerpk TEXT PRIMARY KEY,handle TEXT,avatar TEXT,addr TEXT,"+(version>=2?"mxaddr TEXT,":"")+"lastbody TEXT,lastts INTEGER,unread INTEGER DEFAULT 0)");
            legacy.execSQL("CREATE TABLE messages(coinid TEXT PRIMARY KEY,peerpk TEXT,mine INTEGER,body TEXT,media TEXT,mime TEXT,ts INTEGER,valid INTEGER"+(version>=3?",status INTEGER":"")+(version>=4?",replybody TEXT,replyfrom TEXT":"")+")");
            ContentValues values=new ContentValues();values.put("coinid","old");values.put("peerpk",peer);values.put("mine",1);values.put("body","literal");values.put("ts",1);values.put("valid",1);
            if(version>=3)values.put("status",MailDb.PENDING);if(version>=4){values.put("replybody","quote");values.put("replyfrom","You");}
            legacy.insertOrThrow("messages",null,values);legacy.setVersion(version);legacy.close();db=openDb();MailDb.Message row=only();
            assertEquals(version>=3?MailDb.UNCERTAIN:MailDb.SENT,row.status);assertEquals("literal",row.body);assertEquals(version>=4?"quote":null,row.replyBody);assertNull(row.sender);assertTrue(db.pendingOutbox().isEmpty());assertEquals(5,db.getReadableDatabase().getVersion());
        }
    }

    @Test public void attemptTokenRemainsIndependentOfMutableMessageObject()throws Exception{
        send("queued");work();MailDb.Message m=only();assertTrue(db.beginAttempt(m));String first=m.attempt;
        assertTrue(db.finishAttempt(m,first,false));assertTrue(db.beginAttempt(m));assertNotEquals(first,m.attempt);
        assertFalse(db.finishAttempt(m,first,true));assertEquals("sending",only().phase);assertTrue(db.finishAttempt(m,m.attempt,true));
    }
    @Test public void inconsistentCachedCryptoCannotSendUnderAnotherKey()throws Exception{
        send("private");work();when(crypto.identity()).thenReturn("different-key");ready=true;retry();assertTrue(sends.isEmpty());verify(crypto,never()).seal(anyString(),any(byte[].class));assertEquals("queued",only().phase);
    }
    @Test public void successAfterReviewOpensPreventsAnUnnecessaryNewCopy()throws Exception{
        SalonStore.put(activity,"xport_"+peer,"chain");send("original");work();call("reviewOutgoing",new Class<?>[]{MailDb.Message.class},only());idle();AlertDialog dialog=(AlertDialog)field("outboxRetryDialog").get(activity);
        posts.get(0).onResult(new JSONObject().put("status",true));idle();assertEquals(MailDb.SENT,only().status);assertNull(field("outboxRetryDialog").get(activity));dialog.getButton(-1).performClick();idle();assertEquals(1,db.messageCount(peer));assertEquals(1,commands.size());
    }

}
