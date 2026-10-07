package com.eurobuddha.mail;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.Sodium;
import org.junit.Test;
import static org.junit.Assert.*;
/** Disposable emulator only; separate prepare/verify runs prove process-restart persistence. */
public class IdentityPersistenceTest {
    @Test public void malformedBackupPreservesSavedIdentityAndContacts() throws Exception {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("pandamoniumDisposable"));
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.FINGERPRINT.contains("generic"));
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        byte[] seed=new byte[32];seed[0]=42;
        CommsIdentity expected=CommsIdentity.fromSeed(Sodium.get(),seed);
        assertEquals("Only the existing synthetic review identity",expected.publicId(),new IdentityStore(ctx).load().publicId());
        seed[0]=43;
        CommsIdentity replacement=CommsIdentity.fromSeed(Sodium.get(),seed);
        org.json.JSONObject backup=new org.json.JSONObject()
                .put("identity",new org.json.JSONObject(IdentityRecord.encode(replacement)))
                .put("name","must roll back")
                .put("contacts",new org.json.JSONArray().put(new org.json.JSONObject().put("name","must roll back").put("key",replacement.publicId())))
                .put("messages",new org.json.JSONArray().put("malformed message"));
        java.io.File file=java.io.File.createTempFile("mail-restore-regression-",".json",ctx.getCacheDir());
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)) {
            out.write(com.eurobuddha.comms.BackupCrypto.encrypt("synthetic-review-password",backup.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        com.eurobuddha.comms.CommsDb db=new com.eurobuddha.comms.CommsDb(ctx);
        String priorName=db.getMeta("myname","");int priorContacts=db.contacts().size();
        MainActivity activity=(MainActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            java.lang.reflect.Method restore=MainActivity.class.getDeclaredMethod("doImport",android.net.Uri.class,String.class);restore.setAccessible(true);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { try { restore.invoke(activity,android.net.Uri.fromFile(file),"synthetic-review-password"); } catch(Exception e) { throw new RuntimeException(e); } });
            java.lang.reflect.Field worker=MainActivity.class.getDeclaredField("io");worker.setAccessible(true);
            ((java.util.concurrent.ExecutorService)worker.get(activity)).submit(() -> {}).get(30,java.util.concurrent.TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(expected.publicId(),new IdentityStore(ctx).load().publicId());
            assertEquals(priorName,db.getMeta("myname",""));
            assertEquals(priorContacts,db.contacts().size());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
            db.close();
        }
    }
    @Test public void identitySurvivesStoreAndActivityReopen() throws Exception {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("pandamoniumDisposable"));
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.FINGERPRINT.contains("generic"));
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        byte[] seed=new byte[32]; seed[0]=42;
        CommsIdentity expected=CommsIdentity.fromSeed(Sodium.get(),seed);
        IdentityStore store=new IdentityStore(ctx);
        if("prepare".equals(InstrumentationRegistry.getArguments().getString("phase"))) {
            assertNull("Never overwrite another identity",store.load());store.save(expected);
        }
        assertEquals(expected.publicId(),new IdentityStore(ctx).load().publicId());
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(ctx.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
        for(int i=0;i<2;i++) {
            MainActivity activity=(MainActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(
                    new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.lang.reflect.Method setup=MainActivity.class.getDeclaredMethod("setupIdentity");setup.setAccessible(true);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { try { setup.invoke(activity); } catch(Exception e) { throw new RuntimeException(e); } });
            java.lang.reflect.Field identity=MainActivity.class.getDeclaredField("identity");identity.setAccessible(true);
            java.lang.reflect.Field prompt=MainActivity.class.getDeclaredField("identityPrompt");prompt.setAccessible(true);
            java.util.concurrent.atomic.AtomicReference<CommsIdentity> loaded=new java.util.concurrent.atomic.AtomicReference<>();
            long end=SystemClock.elapsedRealtime()+15_000;
            while(loaded.get()==null && SystemClock.elapsedRealtime()<end) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { try { loaded.set((CommsIdentity)identity.get(activity)); } catch(Exception e) { throw new RuntimeException(e); } });
                SystemClock.sleep(100);
            }
            assertNotNull(loaded.get());assertEquals(expected.publicId(),loaded.get().publicId());
            assertNull("Saved identity does not prompt",prompt.get(activity));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
            SystemClock.sleep(300);
        }
    }
}
