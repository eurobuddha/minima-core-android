package com.eurobuddha.salon;

import android.content.Context;
import android.content.SharedPreferences;
import com.goterl.lazysodium.LazySodium;
import org.json.JSONObject;
import org.junit.*;
import org.mockito.MockedStatic;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class SalonIdentityTest {
    private Context context;
    private SharedPreferences prefs;
    private final AtomicReference<String> stored = new AtomicReference<>("");
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicBoolean failCommit = new AtomicBoolean();
    private static final String SEED = "ab".repeat(32);
    @Before public void setup() throws Exception {
        resetCrypto();
        context=mock(Context.class); prefs=mock(SharedPreferences.class);
        when(context.getSharedPreferences("salon_identity",Context.MODE_PRIVATE)).thenReturn(prefs);
        when(prefs.getString(eq("me"),anyString())).thenAnswer(i->stored.get());
        when(prefs.edit()).thenAnswer(i->{
            SharedPreferences.Editor editor=mock(SharedPreferences.Editor.class);
            AtomicReference<String> staged=new AtomicReference<>();
            when(editor.putString(eq("me"),anyString())).thenAnswer(a->{staged.set(a.getArgument(1));return editor;});
            when(editor.commit()).thenAnswer(a->{stored.set(staged.get());commits.incrementAndGet();return !failCommit.getAndSet(false);});
            doAnswer(a->{stored.set(staged.get());return null;}).when(editor).apply();
            return editor;
        });
    }
    @After public void clear() throws Exception { resetCrypto(); }
    private static void resetCrypto() throws Exception {
        for(String name:new String[]{"CRYPTO","MSGPK"}) {
            java.lang.reflect.Field f=SalonComms.class.getDeclaredField(name);f.setAccessible(true);f.set(null,name.equals("MSGPK")?"":null);
        }
        NostrKeys.invalidate();
    }
    private void record(String seed,String publicId) throws Exception {
        stored.set(new JSONObject().put("msgseed",seed).put("msgpk",publicId).put("bio","keep this draft").toString());
    }
    @Test public void decryptionFailureNeverReplacesIdentityAndRetryRecoversSameSeed() throws Exception {
        record("gcm:existing","public"); String original=stored.get();
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)) {
            crypt.when(()->Crypt.decryptSecure("gcm:existing")).thenThrow(new SalonComms.IdentityUnavailable()).thenReturn(SEED);
            assertThrows(SalonComms.IdentityUnavailable.class,()->SalonComms.seed(context));
            assertEquals(original,stored.get());verify(prefs,never()).edit();
            assertArrayEquals(Hex.from(SEED),SalonComms.seed(context));
            crypt.verify(()->Crypt.encryptSecure(anyString()),never());
        }
    }
    @Test public void malformedSeedOrMissingSeedWithPublishedKeyCannotCreateAReplacement() throws Exception {
        for(String bad:new String[]{"plain:","plain:"+SEED+"a","plain:"+"z".repeat(64)}) {
            record(bad,"public");String original=stored.get();
            assertThrows(SalonComms.IdentityUnavailable.class,()->SalonComms.seed(context));
            assertEquals(original,stored.get());
        }
        record("","public");String original=stored.get();
        assertThrows(SalonComms.IdentityUnavailable.class,()->SalonComms.seed(context));
        assertThrows(SalonComms.IdentityUnavailable.class,()->SalonComms.exportSeed(context));
        assertEquals(original,stored.get());verify(prefs,never()).edit();
    }
    @Test public void corruptProfileCannotBecomeAnEmptyNewIdentityOrBeOverwrittenByDrafts() throws Exception {
        stored.set("{broken");
        assertThrows(SalonComms.IdentityUnavailable.class,()->SalonComms.seed(context));
        SalonStore.put(context,"bio","replacement");
        SalonStore.save(context,new JSONObject().put("name","replacement"));
        assertEquals("{broken",stored.get());verify(prefs,never()).edit();
    }
    @Test public void failedEncryptionCannotSavePlaintextOrActivateNewSeed() {
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)) {
            crypt.when(()->Crypt.encryptSecure(anyString())).thenThrow(new SalonComms.IdentityUnavailable());
            assertThrows(SalonComms.IdentityUnavailable.class,()->SalonComms.seed(context));
            assertEquals("",stored.get());verify(prefs,never()).edit();
        }
    }
    @Test public void simultaneousCreatorsPersistOnlyOneSeed() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        AtomicInteger encrypted=new AtomicInteger();
        Callable<byte[]> work=()->{
            try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)) {
                crypt.when(()->Crypt.encryptSecure(anyString())).thenAnswer(i->{encrypted.incrementAndGet();return "gcm:"+i.getArgument(0);});
                crypt.when(()->Crypt.decryptSecure(anyString())).thenAnswer(i->((String)i.getArgument(0)).substring(4));
                ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));return SalonComms.seed(context);
            }
        };
        try {
            Future<byte[]> a=pool.submit(work),b=pool.submit(work);
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            assertArrayEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
            assertEquals(1,encrypted.get());assertEquals(1,commits.get());
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test public void staleProfileSavePreservesCurrentIdentityAndOrdinaryFields() throws Exception {
        record("gcm:old","old-public");JSONObject stale=SalonStore.me(context);
        assertTrue(SalonStore.writeIdentity(context,"gcm:new","new-public"));
        stale.put("bio","edited");SalonStore.save(context,stale);
        JSONObject actual=new JSONObject(stored.get());
        assertEquals("gcm:new",actual.getString("msgseed"));assertEquals("new-public",actual.getString("msgpk"));
        assertEquals("edited",actual.getString("bio"));
        SalonStore.put(context,"msgseed","unwrapped");assertEquals("gcm:new",new JSONObject(stored.get()).getString("msgseed"));
    }
    @Test public void failedCommitRestoresPreviousInMemoryIdentity() throws Exception {
        record("gcm:old","old-public");String original=stored.get();failCommit.set(true);
        assertFalse(SalonStore.writeIdentity(context,"gcm:new","new-public"));
        assertEquals(original,stored.get());assertEquals(2,commits.get());
    }
    @Test public void malformedImportsAndDerivationFailureDoNotChangeExistingKeys() throws Exception {
        record("plain:"+SEED,"public");String original=stored.get();
        try(MockedStatic<Sodium> sodium=mockStatic(Sodium.class)) {
            assertFalse(SalonComms.importSeed(context,SEED+"a"));
            sodium.verifyNoInteractions();
            sodium.when(Sodium::get).thenThrow(new IllegalStateException("unavailable"));
            assertFalse(SalonComms.importSeed(context,SEED));
            assertEquals(original,stored.get());verify(prefs,never()).edit();
        }
    }
    @Test public void importActivatesOnlyAfterPersistenceAndPreservesCachedIdentityOnFailure() throws Exception {
        record("plain:"+SEED,"old-public");
        LocalEcCryptoProvider previous=mock(LocalEcCryptoProvider.class);
        java.lang.reflect.Field cache=SalonComms.class.getDeclaredField("CRYPTO");cache.setAccessible(true);cache.set(null,previous);
        LazySodium sodium=mock(LazySodium.class);CommsIdentity id=mock(CommsIdentity.class);
        when(id.publicId()).thenReturn("new-public");
        try(MockedStatic<Sodium> nativeApi=mockStatic(Sodium.class);
            MockedStatic<CommsIdentity> identity=mockStatic(CommsIdentity.class);
            MockedStatic<Crypt> crypt=mockStatic(Crypt.class);
            MockedStatic<NostrKeys> nostr=mockStatic(NostrKeys.class)) {
            nativeApi.when(Sodium::get).thenReturn(sodium);
            identity.when(()->CommsIdentity.fromSeed(eq(sodium),any())).thenReturn(id);
            crypt.when(()->Crypt.encryptSecure(SEED)).thenReturn("gcm:new");
            String original=stored.get();failCommit.set(true);
            assertFalse(SalonComms.importSeed(context,SEED));assertEquals(original,stored.get());
            assertSame(previous,SalonComms.crypto(context));
            nostr.verifyNoInteractions();
            assertTrue(SalonComms.importSeed(context,"0x"+SEED));
            assertEquals("new-public",SalonComms.publicId(context));
            JSONObject record=new JSONObject(stored.get());
            assertEquals("gcm:new",record.getString("msgseed"));assertEquals("new-public",record.getString("msgpk"));
            assertEquals("keep this draft",record.getString("bio"));
            nostr.verify(NostrKeys::invalidate);
        }
    }
    @Test public void failedFirstSeedCommitDoesNotLeaveAnUnpersistedIdentityInMemory() {
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)) {
            crypt.when(()->Crypt.encryptSecure(anyString())).thenReturn("gcm:new");
            failCommit.set(true);
            assertThrows(SalonComms.IdentityUnavailable.class,()->SalonComms.seed(context));
            assertEquals("",stored.get());
        }
    }
    @Test public void derivedKeyMismatchCannotReplacePublishedIdentity() throws Exception {
        record("plain:"+SEED,"published");String original=stored.get();
        LazySodium sodium=mock(LazySodium.class);CommsIdentity id=mock(CommsIdentity.class);
        when(id.publicId()).thenReturn("different");
        try(MockedStatic<Sodium> nativeApi=mockStatic(Sodium.class);MockedStatic<CommsIdentity> identity=mockStatic(CommsIdentity.class)) {
            nativeApi.when(Sodium::get).thenReturn(sodium);
            identity.when(()->CommsIdentity.fromSeed(eq(sodium),any())).thenReturn(id);
            assertThrows(SalonComms.IdentityUnavailable.class,()->SalonComms.publicId(context));
            assertEquals(original,stored.get());verify(prefs,never()).edit();
        }
    }
    @Test public void publicationSaveIsDurableAndPreservesUnrelatedFields() throws Exception {
        record("gcm:seed","public");String expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));
        SalonStore.put(context,"unrelated","keep");
        assertTrue(SalonStore.savePublication(context,expected,"https://example.invalid/profile.json",new org.json.JSONArray()));
        JSONObject actual=new JSONObject(stored.get());assertEquals("keep",actual.getString("unrelated"));assertEquals("gcm:seed",actual.getString("msgseed"));assertEquals(1,commits.get());
    }
    @Test public void publicationSaveRejectsChangedDraftAndIdentity() throws Exception {
        record("gcm:seed","public");String expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));
        SalonStore.put(context,"bio","edited meanwhile");String latest=stored.get();
        assertFalse(SalonStore.savePublication(context,expected,"old-url",new org.json.JSONArray()));assertEquals(latest,stored.get());assertEquals(0,commits.get());
        expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));assertTrue(SalonStore.writeIdentity(context,"new-seed","new-public"));latest=stored.get();
        assertFalse(SalonStore.savePublication(context,expected,"old-url",new org.json.JSONArray()));assertEquals(latest,stored.get());
    }
    @Test public void publicationCommitFailureRestoresPreviousInMemoryRecord() throws Exception {
        record("gcm:seed","public");String previous=stored.get();String expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));failCommit.set(true);
        assertFalse(SalonStore.savePublication(context,expected,"new-url",new org.json.JSONArray()));assertEquals(previous,stored.get());assertEquals(2,commits.get());
    }
    @Test public void hostedRestoreKeepsKeysIdentityAndUnrelatedEdits() throws Exception {
        record("gcm:seed","public");SalonStore.put(context,"handle","owner");SalonStore.put(context,"tokenid","token");SalonStore.put(context,"profileUrl","url");
        String expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));SalonStore.put(context,"keepalivebg","off");
        JSONObject hosted=new JSONObject().put("name","Literal 中文 Русский Українська").put("msgseed","attacker").put("msgpk","attacker").put("profileUrl","attacker")
                .put("links",new org.json.JSONArray().put(new JSONObject().put("label","Literal link")));
        assertEquals(SalonStore.RestoreResult.APPLIED,SalonStore.restoreHosted(context,expected,hosted));
        JSONObject actual=SalonStore.identityRecord(context);
        assertEquals("gcm:seed",actual.getString("msgseed"));assertEquals("public",actual.getString("msgpk"));assertEquals("token",actual.getString("tokenid"));
        assertEquals("owner",actual.getString("handle"));assertEquals("url",actual.getString("profileUrl"));assertEquals("off",actual.getString("keepalivebg"));
        assertEquals("Literal 中文 Русский Українська",actual.getString("name"));assertEquals(1,actual.getJSONArray("links").length());assertEquals(1,commits.get());
    }
    @Test public void hostedRestoreRejectsChangedOwnerDraftOrUrlWithoutWriting() throws Exception {
        for(String key:new String[]{"tokenid","handle","msgpk","msgseed","profileUrl","name","nfts"}) {
            record("gcm:seed","public");String expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));
            JSONObject changed=SalonStore.identityRecord(context);changed.put(key,"changed");stored.set(changed.toString());String latest=stored.get();
            assertEquals(SalonStore.RestoreResult.CHANGED,SalonStore.restoreHosted(context,expected,new JSONObject().put("name","hosted")));assertEquals(latest,stored.get());
        }
        assertEquals(0,commits.get());
    }
    @Test public void hostedRestoreValidatesAllFieldsBeforeSavingAnything() throws Exception {
        record("gcm:seed","public");SalonStore.put(context,"handle","owner");SalonStore.put(context,"tokenid","token");String previous=stored.get();
        String expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));
        JSONObject[] bad={new JSONObject().put("name","valid").put("bio",new JSONObject()),new JSONObject().put("name",JSONObject.NULL),
            new JSONObject().put("name","valid").put("nfts","[]"),new JSONObject().put("handle","other"),new JSONObject().put("tokenid","other")};
        for(JSONObject hosted:bad) {assertEquals(SalonStore.RestoreResult.INVALID,SalonStore.restoreHosted(context,expected,hosted));assertEquals(previous,stored.get());}
        assertEquals(0,commits.get());
    }
    @Test public void emptyHostedFieldsKeepDraftAndPermitPreMintProfile() throws Exception {
        record("gcm:seed","public");SalonStore.put(context,"bio","keep");String previous=stored.get();String expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));
        assertEquals(SalonStore.RestoreResult.EMPTY,SalonStore.restoreHosted(context,expected,new JSONObject().put("bio","").put("nfts",new org.json.JSONArray())));
        assertEquals(previous,stored.get());assertEquals(0,commits.get());
        assertEquals(SalonStore.RestoreResult.APPLIED,SalonStore.restoreHosted(context,expected,new JSONObject().put("tokenid","").put("name","restored")));assertEquals("keep",SalonStore.get(context,"bio"));
    }
    @Test public void failedHostedCommitRestoresMemoryAndReportsFailure() throws Exception {
        record("gcm:seed","public");String previous=stored.get();String expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));failCommit.set(true);
        assertEquals(SalonStore.RestoreResult.FAILED,SalonStore.restoreHosted(context,expected,new JSONObject().put("name","hosted")));
        assertEquals(previous,stored.get());assertEquals(2,commits.get());
    }
    @Test public void corruptIdentityIsNeverReplacedByHostedContent() throws Exception {
        stored.set("{corrupt");assertEquals(SalonStore.RestoreResult.FAILED,SalonStore.restoreHosted(context,"",new JSONObject().put("name","hosted")));assertEquals("{corrupt",stored.get());
    }
    private static final String RECOVERY_URL="https://example.invalid/salon/owner/profile.json";
    private static final String TOKEN="0x"+"ab".repeat(32);
    private JSONObject recoveryPage() throws Exception {
        record("gcm:new-node-seed","new-node-public");
        SalonStore.put(context,"handle","owner");SalonStore.put(context,"tokenid",TOKEN);
        SalonStore.put(context,"profileUrl","http://old.example/profile.json");
        SalonStore.put(context,"tipaddr","new-node-address");SalonStore.put(context,"mxaddr","new-node-mx");
        return new JSONObject().put("tokenid",TOKEN).put("handle","owner").put("name","recovered")
                .put("tipaddr","old-node-address").put("msgpk","old-public").put("msgseed","old-seed")
                .put("mxaddr","old-mx").put("hosting_profiles",new org.json.JSONArray().put("untrusted"))
                .put("gallery",new org.json.JSONArray().put(new JSONObject().put("url","https://example.invalid/image.png")))
                .put("posts",new org.json.JSONArray().put(new JSONObject().put("body","original post")));
    }
    @Test public void relocatedContentKeepsNewNodeIdentityAndPaymentAddress() throws Exception {
        JSONObject page=recoveryPage();page.put("tokenid","0x"+"AB".repeat(32));
        String expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));
        assertEquals(SalonStore.RestoreResult.APPLIED,SalonStore.restoreHostedAt(context,expected,page,RECOVERY_URL));
        JSONObject actual=SalonStore.identityRecord(context);
        assertEquals("gcm:new-node-seed",actual.getString("msgseed"));assertEquals("new-node-public",actual.getString("msgpk"));
        assertEquals("new-node-address",actual.getString("tipaddr"));assertEquals("new-node-mx",actual.getString("mxaddr"));
        assertEquals(TOKEN,actual.getString("tokenid"));assertEquals(RECOVERY_URL,actual.getString("profileUrl"));
        assertEquals("recovered",actual.getString("name"));assertEquals(1,actual.getJSONArray("gallery").length());
        assertEquals(1,actual.getJSONArray("posts").length());assertFalse(actual.has("hosting_profiles"));assertEquals(1,commits.get());
    }
    @Test public void relocatedPageMustExplicitlyMatchTokenAndHandle() throws Exception {
        for(String key:new String[]{"tokenid","handle"})for(Object value:new Object[]{null,"", "other",JSONObject.NULL}) {
            JSONObject page=recoveryPage();if(value==null)page.remove(key);else page.put(key,value);
            String previous=stored.get(),expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));
            assertEquals(SalonStore.RestoreResult.INVALID,SalonStore.restoreHostedAt(context,expected,page,RECOVERY_URL));assertEquals(previous,stored.get());
        }
        assertEquals(0,commits.get());
    }
    @Test public void unsafeRecoveryUrlsNeverChangeStoredContent() throws Exception {
        JSONObject page=recoveryPage();String previous=stored.get(),expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));
        for(String url:new String[]{"http://example.invalid/profile.json","https://user:password@example.invalid/profile.json","https://example.invalid/profile.json#fragment","file:///page.json","not a url"}) {
            assertEquals(SalonStore.RestoreResult.INVALID,SalonStore.restoreHostedAt(context,expected,page,url));assertEquals(previous,stored.get());
        }
        assertEquals(0,commits.get());
    }
    @Test public void relocatedRestoreRejectsStaleDraftAndRollsBackFailedCommit() throws Exception {
        JSONObject page=recoveryPage();String expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));
        SalonStore.put(context,"bio","newer draft");String previous=stored.get();
        assertEquals(SalonStore.RestoreResult.CHANGED,SalonStore.restoreHostedAt(context,expected,page,RECOVERY_URL));assertEquals(previous,stored.get());
        expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));failCommit.set(true);
        assertEquals(SalonStore.RestoreResult.FAILED,SalonStore.restoreHostedAt(context,expected,page,RECOVERY_URL));assertEquals(previous,stored.get());
    }

    @Test public void repeatingContentRestoreCannotRevertNewNodePaymentAddress() throws Exception {
        JSONObject page=recoveryPage();String expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));
        assertEquals(SalonStore.RestoreResult.APPLIED,SalonStore.restoreHostedAt(context,expected,page,RECOVERY_URL));
        expected=SalonStore.publicationSnapshot(SalonStore.identityRecord(context));
        assertEquals(SalonStore.RestoreResult.APPLIED,SalonStore.restoreHosted(context,expected,page));
        assertEquals("new-node-address",SalonStore.get(context,"tipaddr"));
    }

}
