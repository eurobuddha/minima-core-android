package com.eurobuddha.statenft;

import android.content.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.*;
import org.junit.*;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

/** Reuses the backup memory/disk fixture; no Android keystore or remote service. */
public class HostingPinPersistenceTest {
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
    @Test public void failedPinCommitRollsBackMemoryAndNeverChangesSuppliedProfile()throws Exception {
        for(boolean throwing:new boolean[]{false,true}) {
            Context c=mock(Context.class);Prefs prefs=new Prefs();
            when(c.getSharedPreferences(HostingStore.PREFS,0)).thenReturn(prefs.prefs);
            Hosting.Profile p=Hosting.Profile.fresh("sftp");Hosting.put(p.cfg(),"host","example.invalid");
            prefs.seed("hosting_profiles",new JSONArray().put(p.j).toString());
            String before=prefs.disk.get("hosting_profiles");prefs.failures=1;prefs.throwAfterMemory=throwing;
            assertFalse(HostingStore.pinHostKey(c,p,"SHA256:fixture"));
            assertEquals(before,prefs.memory.get("hosting_profiles"));assertEquals(before,prefs.disk.get("hosting_profiles"));
            assertEquals("",p.cfgStr("hostKeyFp"));
            assertTrue(HostingStore.pinHostKey(c,p,"SHA256:fixture"));prefs.restart();
            assertEquals("SHA256:fixture",HostingStore.listChecked(c).get(0).cfgStr("hostKeyFp"));
            assertFalse(HostingStore.pinHostKey(c,p,"SHA256:different"));
        }
    }
    @Test public void corruptProfileStoreIsNotReplacedWithOnlyThePinnedProfile()throws Exception {
        Context c=mock(Context.class);Prefs prefs=new Prefs();
        when(c.getSharedPreferences(anyString(),eq(0))).thenReturn(prefs.prefs);
        prefs.seed("hosting_profiles","malformed");Hosting.Profile p=Hosting.Profile.fresh("sftp");
        assertThrows(IllegalStateException.class,()->HostingStore.pinHostKey(c,p,"SHA256:fixture"));
        assertEquals("malformed",prefs.disk.get("hosting_profiles"));assertEquals(0,prefs.commits);
    }
}
