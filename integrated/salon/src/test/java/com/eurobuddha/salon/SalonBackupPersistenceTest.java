package com.eurobuddha.salon;

import android.content.Context;
import android.content.SharedPreferences;
import com.goterl.lazysodium.LazySodium;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Atelier's preference fixture extended with separate memory/disk and crash boundaries. */
public class SalonBackupPersistenceTest {
    private static final String SEED="cd".repeat(32);
    private static final String HOSTS="hosting_profiles";
    private final Context context=mock(Context.class);
    private final Prefs account=new Prefs(),legacy=new Prefs();
    private LocalEcCryptoProvider previous;

    static final class Death extends Error { }
    static final class Prefs {
        final SharedPreferences prefs=mock(SharedPreferences.class);
        final Map<String,String> memory=new ConcurrentHashMap<>(),disk=new ConcurrentHashMap<>();
        int commits,failures;
        boolean throwAfterMemory,dieBeforeCommit,dieAfterDisk;
        String failStageKey;
        Runnable beforeCommit=()->{};
        Prefs(){
            when(prefs.contains(anyString())).thenAnswer(i->memory.containsKey(i.getArgument(0)));
            when(prefs.getString(anyString(),nullable(String.class))).thenAnswer(i->memory.getOrDefault(i.getArgument(0),i.getArgument(1)));
            when(prefs.edit()).thenAnswer(i->{
                Map<String,String> changes=new LinkedHashMap<>();
                SharedPreferences.Editor edit=mock(SharedPreferences.Editor.class);
                when(edit.putString(anyString(),nullable(String.class))).thenAnswer(a->{
                    String key=a.getArgument(0);
                    if(key.equals(failStageKey)){failStageKey=null;throw new IllegalStateException("stage failure");}
                    changes.put(key,a.getArgument(1));return edit;
                });
                when(edit.remove(anyString())).thenAnswer(a->{changes.put(a.getArgument(0),null);return edit;});
                when(edit.commit()).thenAnswer(a->{
                    commits++;beforeCommit.run();
                    if(dieBeforeCommit)throw new Death();
                    merge(memory,changes);
                    if(failures>0){failures--;if(throwAfterMemory)throw new IllegalStateException("disk failure");return false;}
                    merge(disk,changes);
                    if(dieAfterDisk)throw new Death();
                    return true;
                });
                doAnswer(a->{merge(memory,changes);merge(disk,changes);return null;}).when(edit).apply();
                return edit;
            });
        }
        static void merge(Map<String,String> target,Map<String,String> changes){changes.forEach((key,value)->{if(value==null)target.remove(key);else target.put(key,value);});}
        void seed(String key,String value){memory.put(key,value);disk.put(key,value);}
        void restart(){memory.clear();memory.putAll(disk);}
    }
    private static void cache(String name,Object value)throws Exception{Field field=SalonComms.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);}
    private static Object cache(String name)throws Exception{Field field=SalonComms.class.getDeclaredField(name);field.setAccessible(true);return field.get(null);}
    @Before public void setup()throws Exception{
        when(context.getSharedPreferences("salon_identity",0)).thenReturn(account.prefs);
        when(context.getSharedPreferences(HostingStore.PREFS,0)).thenReturn(legacy.prefs);
        account.seed("me",new JSONObject().put("name","old draft").put("msgseed","gcm:old").put("msgpk","old-public").toString());
        legacy.seed(HOSTS,new JSONArray().put(host("existing","old destination")).toString());
        previous=mock(LocalEcCryptoProvider.class);cache("CRYPTO",previous);cache("MSGPK","old-public");NostrKeys.invalidate();
    }
    @After public void cleanup()throws Exception{cache("CRYPTO",null);cache("MSGPK","");NostrKeys.invalidate();}
    private JSONObject host(String id,String name)throws Exception{return new JSONObject().put("id",id).put("type","github").put("name",name).put("github",new JSONObject().put("token","gcm:credential"));}
    private JSONObject backup(boolean key)throws Exception{
        JSONObject b=new JSONObject().put("salonBackup",1).put("me",new JSONObject().put("name","Literal 中文 Русский Українська").put("handle","restored").put("tokenid","restored-token").put("msgpk","untrusted-backup-value"));
        if(key)b.put("msgseed",SEED);return b;
    }
    private JSONArray incoming()throws Exception{return new JSONArray().put(host("existing","updated destination")).put(host("new","new destination"));}
    private SalonComms.BackupResult restore(boolean key)throws Exception{return SalonComms.restoreBackup(context,backup(key),incoming(),()->true);}

    final class Keys implements AutoCloseable {
        final MockedStatic<Sodium> sodium=mockStatic(Sodium.class);
        final MockedStatic<CommsIdentity> identities=mockStatic(CommsIdentity.class);
        final MockedStatic<Crypt> crypt=mockStatic(Crypt.class);
        final MockedStatic<MailDb> mail=mockStatic(MailDb.class);
        final MockedStatic<NostrKeys> nostr=mockStatic(NostrKeys.class);
        final MailDb db=mock(MailDb.class);
        Keys(){
            LazySodium nativeApi=mock(LazySodium.class);CommsIdentity id=mock(CommsIdentity.class);when(id.publicId()).thenReturn("new-public");
            sodium.when(Sodium::get).thenReturn(nativeApi);identities.when(()->CommsIdentity.fromSeed(eq(nativeApi),any())).thenReturn(id);
            crypt.when(()->Crypt.encryptSecure(SEED)).thenReturn("gcm:new");crypt.when(()->Crypt.decryptSecure("gcm:new")).thenReturn(SEED);
            crypt.when(()->Crypt.decryptSecure("gcm:old")).thenReturn("ab".repeat(32));
            mail.when(()->MailDb.get(context)).thenReturn(db);when(db.contacts()).thenReturn(Collections.emptyList());
        }
        public void close(){nostr.close();mail.close();crypt.close();identities.close();sodium.close();}
    }
    @Test public void draftAndDestinationsCommitTogetherAndOldEncryptedStoreRemainsReadable()throws Exception{
        Map<String,String> oldLegacy=new HashMap<>(legacy.disk);SalonComms.BackupResult result=restore(false);
        assertTrue(result.draftRestored);assertFalse(result.keyRestored);assertEquals(2,result.destinations);assertEquals(1,account.commits);assertEquals(0,legacy.commits);
        assertEquals(account.disk,account.memory);assertEquals(oldLegacy,legacy.disk);assertEquals("Literal 中文 Русский Українська",SalonStore.get(context,"name"));
        assertEquals("gcm:old",SalonStore.identityRecord(context).getString("msgseed"));assertEquals("old-public",SalonStore.identityRecord(context).getString("msgpk"));
        assertEquals(2,HostingStore.listForBackup(context).size());assertEquals("updated destination",HostingStore.listForBackup(context).get(0).name());assertSame(previous,cache("CRYPTO"));
        account.restart();legacy.restart();assertEquals("updated destination",HostingStore.listForBackup(context).get(0).name());
        HostingStore.upsert(context,new Hosting.Profile(host("third","later edit")));HostingStore.setDefault(context,"third");HostingStore.remove(context,"new");
        assertEquals("third",HostingStore.getDefault(context).id());assertEquals(2,HostingStore.listForBackup(context).size());assertEquals(oldLegacy,legacy.disk);
    }
    @Test public void keyActivatesOnlyAfterTheCombinedCommitSucceeds()throws Exception{
        try(Keys keys=new Keys()){
            account.beforeCommit=()->{try{assertSame(previous,cache("CRYPTO"));}catch(Exception e){throw new AssertionError(e);}};
            SalonComms.BackupResult result=restore(true);assertTrue(result.keyRestored);assertFalse(result.keyKept);
            assertNotSame(previous,cache("CRYPTO"));assertEquals("new-public",SalonComms.publicId(context));assertEquals(SEED,SalonComms.exportSeed(context));keys.nostr.verify(NostrKeys::invalidate);
            assertEquals(1,account.commits);assertTrue(account.disk.containsKey(HOSTS));assertFalse(account.disk.toString().contains(SEED));
        }
    }
    @Test public void failedCommitOrExceptionRestoresMemoryAndKeepsCachedKey()throws Exception{
        try(Keys keys=new Keys()){
            for(boolean throwing:new boolean[]{false,true}){
                Map<String,String> before=new HashMap<>(account.disk),oldHosts=new HashMap<>(legacy.disk);
                account.failures=1;account.throwAfterMemory=throwing;
                assertThrows(SalonStore.BackupStorageUnavailable.class,()->restore(true));
                assertEquals(before,account.disk);assertEquals(before,account.memory);assertEquals(oldHosts,legacy.disk);assertSame(previous,cache("CRYPTO"));
                assertEquals("old destination",HostingStore.listForBackup(context).get(0).name());
            }
            keys.nostr.verifyNoInteractions();
        }
    }
    @Test public void failedReplacementRestoresPreviouslyPromotedHostsInsteadOfFallingBack()throws Exception{
        restore(false);Map<String,String> before=new HashMap<>(account.disk);account.failures=2;
        assertThrows(SalonStore.BackupStorageUnavailable.class,()->restore(false));
        assertEquals(before,account.disk);assertEquals(before,account.memory);assertEquals("updated destination",HostingStore.listForBackup(context).get(0).name());
    }
    @Test public void failedSecondFieldStagingCannotLeaveHalfAnAccount()throws Exception{
        try(Keys keys=new Keys()){
            Map<String,String> before=new HashMap<>(account.disk);account.failStageKey=HOSTS;
            assertThrows(SalonStore.BackupStorageUnavailable.class,()->restore(true));assertEquals(before,account.memory);assertEquals(before,account.disk);assertSame(previous,cache("CRYPTO"));keys.nostr.verifyNoInteractions();
        }
    }
    @Test public void encryptionFailureLeavesEveryStoreAndIdentityUntouched()throws Exception{
        try(Keys keys=new Keys()){
            Map<String,String> before=new HashMap<>(account.disk);keys.crypt.when(()->Crypt.encryptSecure(SEED)).thenThrow(new Crypt.Unavailable());
            assertThrows(SalonComms.IdentityUnavailable.class,()->restore(true));assertEquals(before,account.memory);assertEquals(0,account.commits);assertSame(previous,cache("CRYPTO"));keys.nostr.verifyNoInteractions();
        }
    }
    @Test public void liveInboxKeepsItsKeyAndLiveIdentityKeepsItsProfile()throws Exception{
        JSONObject current=SalonStore.identityRecord(context).put("tokenid","original-token").put("handle","original");account.seed("me",current.toString());
        try(Keys keys=new Keys()){
            when(keys.db.contacts()).thenReturn(Arrays.asList(new MailDb.Contact()));SalonComms.BackupResult result=restore(true);
            assertTrue(result.keyKept);assertFalse(result.keyRestored);assertFalse(result.draftRestored);assertEquals(current.toString(),SalonStore.identityRecord(context).toString());assertEquals(2,HostingStore.listForBackup(context).size());
            keys.crypt.verify(()->Crypt.encryptSecure(anyString()),never());keys.nostr.verifyNoInteractions();assertSame(previous,cache("CRYPTO"));
        }
    }
    @Test public void corruptLegacyOrPromotedHostsCannotBeSilentlyDiscarded()throws Exception{
        for(boolean promoted:new boolean[]{false,true})for(String raw:new String[]{"{broken","[null]","[] trailing","[]\u0000","[{\"id\":\"\"}]"}){
            Prefs target=promoted?account:legacy;target.seed(HOSTS,raw);Map<String,String> before=new HashMap<>(account.memory);
            assertThrows(SalonStore.BackupStorageUnavailable.class,()->restore(false));assertEquals(before,account.memory);assertEquals(raw,target.memory.get(HOSTS));assertEquals(0,account.commits);
        }
    }
    @Test public void simulatedStopBeforeCommitReloadsOldCompleteAccount()throws Exception{
        Map<String,String> before=new HashMap<>(account.disk);account.dieBeforeCommit=true;assertThrows(Death.class,()->restore(false));
        account.restart();legacy.restart();assertEquals(before,account.memory);assertEquals("old destination",HostingStore.listForBackup(context).get(0).name());
    }
    @Test public void simulatedStopAfterCommitReloadsBothNewStoresAndTheNewKey()throws Exception{
        try(Keys keys=new Keys()){
            account.dieAfterDisk=true;assertThrows(Death.class,()->restore(true));assertSame(previous,cache("CRYPTO"));
            account.restart();legacy.restart();cache("CRYPTO",null);cache("MSGPK","");
            assertEquals("Literal 中文 Русский Українська",SalonStore.get(context,"name"));assertEquals(2,HostingStore.listForBackup(context).size());assertEquals("new-public",SalonComms.publicId(context));assertEquals(SEED,SalonComms.exportSeed(context));
        }
    }
    @Test public void hostingEditWaitsForRestoreAndThenUsesThePromotedStore()throws Exception{
        CountDownLatch committing=new CountDownLatch(1),release=new CountDownLatch(1),editing=new CountDownLatch(1);AtomicBoolean first=new AtomicBoolean(true);
        account.beforeCommit=()->{if(first.getAndSet(false)){committing.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}};
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<?> restore=workers.submit(()->{try{restore(false);}catch(Exception e){throw new RuntimeException(e);}});assertTrue(committing.await(5,TimeUnit.SECONDS));
            Future<?> edit=workers.submit(()->{editing.countDown();try{HostingStore.upsert(context,new Hosting.Profile(host("concurrent","concurrent edit")));}catch(Exception e){throw new RuntimeException(e);}});assertTrue(editing.await(5,TimeUnit.SECONDS));
            release.countDown();restore.get(5,TimeUnit.SECONDS);edit.get(5,TimeUnit.SECONDS);assertEquals(3,HostingStore.listForBackup(context).size());assertEquals(1,new JSONArray(legacy.disk.get(HOSTS)).length());
        }finally{release.countDown();workers.shutdownNow();}
    }
    @Test public void finalOwnerCheckRunsAfterKeyPreparationAndBeforeAnyWrite()throws Exception{
        try(Keys keys=new Keys()){
            Map<String,String> before=new HashMap<>(account.memory);AtomicBoolean prepared=new AtomicBoolean();
            keys.crypt.when(()->Crypt.encryptSecure(SEED)).thenAnswer(c->{prepared.set(true);return "gcm:new";});
            assertThrows(SalonComms.BackupCancelled.class,()->SalonComms.restoreBackup(context,backup(true),incoming(),()->{assertTrue(prepared.get());return false;}));
            assertEquals(before,account.memory);assertEquals(0,account.commits);assertSame(previous,cache("CRYPTO"));keys.nostr.verifyNoInteractions();
        }
    }

}
