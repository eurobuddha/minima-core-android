package com.eurobuddha.utxo;
import android.content.Context;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real disposable SQLite, following NFT TransactionJournalTest; no node or live funds. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class UnknownHistoryTest {
    private Context context;private HistoryDb db;
    @Before public void setup() {context=RuntimeEnvironment.getApplication();context.deleteDatabase("pandamonium_utxo_history.db");db=new HistoryDb(context);}
    @After public void close(){db.close();context.deleteDatabase("pandamonium_utxo_history.db");}
    private void record() {
        HistoryRow row=new HistoryRow();row.internalid="uncertain";row.status=HistoryDb.STATUS_UNKNOWN;
        row.ts=1;row.tokenid="0x00";row.amount="10";row.note="Outcome not verified";
        row.inputs="[{\"coinid\":\"0x1234\"},{\"coinid\":\"0x5678\"}]";
        db.insertPosting(row);
    }
    @Test public void stillUnspentInputsDoNotTurnTimeoutIntoFailure() {
        record();db.reconcileUnknown(new HashSet<>(Arrays.asList("0x1234","0x5678")),1);
        assertEquals(HistoryDb.STATUS_UNKNOWN,db.listOpen(10).get(0).status);
        db.close();db=new HistoryDb(context);
        assertEquals(HistoryDb.STATUS_UNKNOWN,db.listOpen(10).get(0).status);
    }
    @Test public void missingInputsDoNotProveTheyWereSpentByThisSend() {
        record();db.reconcileUnknown(Collections.emptySet(),1);
        HistoryRow row=db.listOpen(10).get(0);assertEquals(HistoryDb.STATUS_UNKNOWN,row.status);
        assertEquals("Outcome not verified",row.note);assertNull(row.txnid);
    }
    @Test public void partialSnapshotCannotResolveTheOutcome() {
        record();db.reconcileUnknown(Collections.singleton("0x1234"),0);
        HistoryRow row=db.listOpen(10).get(0);assertEquals(HistoryDb.STATUS_UNKNOWN,row.status);assertEquals("Outcome not verified",row.note);
    }
}
