package com.eurobuddha.merchinbox;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import com.eurobuddha.merchinbox.integratedcomms.MerchDb;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.Assert.*;
import static com.eurobuddha.merchinbox.InboxCoinEvidenceTest.*;
import static org.mockito.Mockito.*;

/** Failure injection at the actual SQLite, executor, and Activity delivery boundaries. No node writes. */
public class InboxDeliveryStorageTest {
    static final String KEY="fixture-key";
    static class Host extends InboxCoinEvidenceTest.Host {
        final List<Runnable> writes=new ArrayList<>(), updates=new ArrayList<>();
        final ArrayDeque<Runnable> transactions=new ArrayDeque<>();
        Host() throws Exception {
            InboxCoinEvidenceTest.set(activity,"txQueue",transactions);
            InboxCoinEvidenceTest.set(activity,"txBusy",true);
            sending.add(KEY);
            doAnswer(c->{writes.add(c.getArgument(0));return null;}).when(io).execute(any(Runnable.class));
            when(ui.post(any(Runnable.class))).thenAnswer(c->{updates.add(c.getArgument(0));return true;});
        }
        void invoke(String name,Class<?>[] types,Object... args) throws Exception {
            Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);
        }
        void complete() throws Exception {
            invoke("markSentAndMaybeAdvance",new Class<?>[]{MerchDb.Order.class,String.class,String.class,boolean.class},order,KEY,"tx-receipt",false);
        }
        void posted(boolean bundle) throws Exception {
            if(bundle) {
                invoke("bundleTransferNext",new Class<?>[]{MerchDb.Order.class,String.class,String.class,List.class,int.class,List.class,String[].class,boolean.class},
                        order,KEY,TOKEN,Collections.emptyList(),0,Collections.singletonList(COIN),new String[]{null},false);
            } else {
                final CmdChain.Done[] done={null};
                try(MockedStatic<CmdChain> chain=mockStatic(CmdChain.class)) {
                    chain.when(()->CmdChain.run(eq(node),anyList(),anyString(),any(CmdChain.Done.class)))
                        .thenAnswer(c->{done[0]=c.getArgument(3);return null;});
                    invoke("runStateTransfer",new Class<?>[]{MerchDb.Order.class,String.class,String.class,JSONObject.class,boolean.class},
                            order,KEY,TOKEN,coin(),false);
                    assertNotNull(done[0]);
                    done[0].ok(object("status",true,"response",object("txpowid","fixture-post")));
                }
            }
        }
        boolean busy() throws Exception {Field f=MainActivity.class.getDeclaredField("txBusy");f.setAccessible(true);return f.getBoolean(activity);}
        void write(){assertEquals(1,writes.size());writes.remove(0).run();}
        void update(){assertEquals(1,updates.size());updates.remove(0).run();}
    }
    @Test public void checkedInsertExposesFailureAndPreservesKeysAndValues() {
        MerchDb store=mock(MerchDb.class,CALLS_REAL_METHODS);
        SQLiteDatabase sqlite=mock(SQLiteDatabase.class);
        doReturn(sqlite).when(store).getWritableDatabase();
        when(sqlite.insertWithOnConflict(eq("meta"),isNull(),any(ContentValues.class),eq(SQLiteDatabase.CONFLICT_REPLACE)))
                .thenReturn(-1L,4L).thenThrow(new SQLiteException("disk full"));
        try(MockedConstruction<ContentValues> values=mockConstruction(ContentValues.class)) {
            assertFalse(store.setMetaChecked("nftposted:fixture","public-receipt"));
            assertTrue(store.setMetaChecked("nftposted:fixture","public-receipt"));
            assertThrows(SQLiteException.class,()->store.setMetaChecked("nftposted:fixture","public-receipt"));
            for(ContentValues v:values.constructed()) {verify(v).put("k","nftposted:fixture");verify(v).put("v","public-receipt");}
        }
        doThrow(new SQLiteException("unavailable")).when(store).getWritableDatabase();
        assertThrows(SQLiteException.class,()->store.setMetaChecked("nftposted:fixture","public-receipt"));
    }
    @Test public void failedSentRecordCannotAdvanceNotifySuccessOrUnlockTheLine() throws Exception {
        for(boolean throwsError:new boolean[]{false,true})try(Host h=new Host()) {
            if(throwsError)when(h.db.setMetaChecked(anyString(),anyString())).thenThrow(new SQLiteException("disk full"));
            h.complete();h.write();h.update();
            verify(h.db).setMetaChecked("nftsent:"+KEY,"tx-receipt");
            verify(h.db,never()).order(anyString());verify(h.db,never()).setStatus(anyString(),anyString());
            verify(h.db,never()).setMeta(anyString(),anyString());
            assertTrue(h.sending.contains(KEY));verifyNoInteractions(h.node);assertTrue(h.polls.isEmpty());
        }
    }
    @Test public void successfulSentRecordWaitsForStorageAndThenUnlocks() throws Exception {
        try(Host h=new Host()) {
            when(h.db.setMetaChecked(anyString(),anyString())).thenReturn(true);
            h.complete();assertTrue(h.sending.contains(KEY));verifyNoInteractions(h.db);
            h.write();assertTrue(h.sending.contains(KEY));
            verify(h.db).order(h.order.ref);h.update();assertFalse(h.sending.contains(KEY));
        }
    }
    @Test public void singleAndBundlePostingWaitForCheckedStorageBeforePollingAndReleasingQueue() throws Exception {
        for(boolean bundle:new boolean[]{false,true})try(Host h=new Host()) {
            when(h.db.setMetaChecked(anyString(),anyString())).thenReturn(true);
            final int[] next={0};h.transactions.add(()->next[0]++);
            h.posted(bundle);assertTrue(h.busy());assertTrue(h.polls.isEmpty());assertEquals(0,next[0]);
            verifyNoInteractions(h.db);h.write();assertEquals(0,next[0]);assertTrue(h.polls.isEmpty());
            h.update();assertEquals(1,next[0]);assertEquals(1,h.polls.size());assertTrue(h.sending.contains(KEY));
            verify(h.db).setMetaChecked("nftposted:"+KEY,bundle?"posted":"fixture-post");
        }
    }
    @Test public void bothPostRecordingFailuresStopConfirmationAndRetainTheLineLock() throws Exception {
        for(boolean bundle:new boolean[]{false,true})for(boolean throwsError:new boolean[]{false,true})try(Host h=new Host()) {
            if(throwsError)when(h.db.setMetaChecked(anyString(),anyString())).thenThrow(new SQLiteException("disk full"));
            h.posted(bundle);h.write();h.update();
            assertFalse(h.busy());assertTrue(h.polls.isEmpty());assertTrue(h.sending.contains(KEY));
            verify(h.db,never()).order(anyString());verify(h.db,never()).setStatus(anyString(),anyString());
        }
    }
    @Test public void rejectedExecutorDoesNotAdvanceOrUnlockEitherPostPath() throws Exception {
        for(boolean bundle:new boolean[]{false,true})try(Host h=new Host()) {
            doThrow(new RejectedExecutionException()).when(h.io).execute(any(Runnable.class));
            h.posted(bundle);assertFalse(h.busy());assertTrue(h.polls.isEmpty());assertTrue(h.sending.contains(KEY));
            assertTrue(h.updates.isEmpty());verifyNoInteractions(h.db);
        }
        try(Host h=new Host()) {
            doThrow(new RejectedExecutionException()).when(h.io).execute(any(Runnable.class));
            h.complete();assertTrue(h.sending.contains(KEY));verifyNoInteractions(h.db);
        }
    }
    @Test public void destroyedHostRecordsResultButCannotStartQueuedTransfersOrPublishSuccess() throws Exception {
        for(boolean bundle:new boolean[]{false,true})try(Host h=new Host()) {
            when(h.db.setMetaChecked(anyString(),anyString())).thenReturn(true);
            Runnable queued=mock(Runnable.class);h.transactions.add(queued);
            h.posted(bundle);doReturn(true).when(h.activity).isDestroyed();h.write();h.update();
            verifyNoInteractions(queued);assertTrue(h.transactions.isEmpty());assertFalse(h.busy());
            assertTrue(h.polls.isEmpty());assertTrue(h.sending.contains(KEY));
        }
        try(Host h=new Host()) {
            when(h.db.setMetaChecked(anyString(),anyString())).thenReturn(true);
            h.complete();h.write();doReturn(true).when(h.activity).isDestroyed();h.update();
            assertTrue(h.sending.contains(KEY));verify(h.db,never()).setStatus(anyString(),anyString());
        }
    }
}
