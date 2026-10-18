package com.eurobuddha.salon;

import android.content.Context;
import org.json.*;
import org.junit.*;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

/** Reuses the backup memory/disk fixture; no Android keystore or remote service. */
public class HostingPinPersistenceTest {
    @Test public void failedPinCommitRollsBackMemoryAndNeverChangesSuppliedProfile()throws Exception {
        for(boolean combined:new boolean[]{false,true})for(boolean throwing:new boolean[]{false,true}) {
            Context c=mock(Context.class);SalonBackupPersistenceTest.Prefs prefs=new SalonBackupPersistenceTest.Prefs();
            SalonBackupPersistenceTest.Prefs account=new SalonBackupPersistenceTest.Prefs();
            when(c.getSharedPreferences("salon_identity",0)).thenReturn(combined?prefs.prefs:account.prefs);
            when(c.getSharedPreferences(HostingStore.PREFS,0)).thenReturn(prefs.prefs);
            Hosting.Profile p=Hosting.Profile.fresh("sftp");Hosting.put(p.cfg(),"host","example.invalid");
            prefs.seed("hosting_profiles",new JSONArray().put(p.j).toString());
            String before=prefs.disk.get("hosting_profiles");prefs.failures=1;prefs.throwAfterMemory=throwing;
            assertFalse(HostingStore.pinHostKey(c,p,"SHA256:fixture"));
            assertEquals(before,prefs.memory.get("hosting_profiles"));assertEquals(before,prefs.disk.get("hosting_profiles"));
            assertEquals("",p.cfgStr("hostKeyFp"));
            assertTrue(HostingStore.pinHostKey(c,p,"SHA256:fixture"));prefs.restart();
            assertEquals("SHA256:fixture",HostingStore.listForBackup(c).get(0).cfgStr("hostKeyFp"));
            assertFalse(HostingStore.pinHostKey(c,p,"SHA256:different"));
        }
    }
    @Test public void corruptProfileStoreIsNotReplacedWithOnlyThePinnedProfile()throws Exception {
        Context c=mock(Context.class);SalonBackupPersistenceTest.Prefs prefs=new SalonBackupPersistenceTest.Prefs();
        when(c.getSharedPreferences(anyString(),eq(0))).thenReturn(prefs.prefs);
        prefs.seed("hosting_profiles","malformed");Hosting.Profile p=Hosting.Profile.fresh("sftp");
        assertThrows(SalonStore.BackupStorageUnavailable.class,()->HostingStore.pinHostKey(c,p,"SHA256:fixture"));
        assertEquals("malformed",prefs.disk.get("hosting_profiles"));assertEquals(0,prefs.commits);
    }
}
