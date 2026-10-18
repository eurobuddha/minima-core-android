package com.eurobuddha.nftwallet;

import android.content.Context;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.TransactionEvidenceTest.*;

/** Real persisted history and journal, including reopen and corrupted evidence. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class LocalHistoryTest {
    Context context;HistoryDb db;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("pandamonium_nft_history.db");db=new HistoryDb(context);}
    @After public void close(){db.close();context.deleteDatabase("pandamonium_nft_history.db");}
    void row(String id,String status){HistoryRow r=new HistoryRow();r.internalid=r.txnid=id;r.status=status;r.tokenid="0x00";r.amount="7";r.tokenName="熊 · Ведмідь · Медведь";r.note="old callback";assertTrue(db.insertPosting(r));}
    void prepare(){row("first",HistoryDb.STATUS_POSTING);assertTrue(db.prepareAttempt("first","public_handle",intent("0x00")));}
    List<LocalHistory.Entry> entries(){return LocalHistory.read(db,1000);}
    void confirm(){assertTrue(db.markAttempt("first",HistoryDb.POSTING,null));assertTrue(db.confirmAttempt("first",receipt(intent("0x00")),TransactionRecoveryTest.inclusion("3")));}
    NodeTx chain(String txpow){NodeTx n=new NodeTx();n.txpowid=txpow;return n;}
    @Test public void journalPhaseOverridesStaleDisplayStatusWithoutWritingIt() {
        prepare();assertTrue(db.markAttempt("first",HistoryDb.POSTING,null));assertTrue(db.markAttempt("first",HistoryDb.UNKNOWN,null));
        assertEquals(HistoryDb.UNKNOWN,entries().get(0).phase());assertEquals(TransactionEvidence.UNKNOWN,entries().get(0).note());
        assertEquals(HistoryDb.STATUS_POSTING,db.list(10).get(0).status);assertEquals("old callback",db.list(10).get(0).note);
    }
    @Test public void interruptionIsVisibleAfterDatabaseReopen() {
        prepare();db.getWritableDatabase().execSQL("UPDATE transaction_attempts SET owner=?",new Object[]{"00000000000000000000000000000000:11111111111111111111111111111111"});db.close();db=new HistoryDb(context);
        LocalHistory.Entry e=entries().get(0);assertEquals(HistoryDb.NOT_SUBMITTED,e.phase());assertEquals(TransactionEvidence.NOT_SENT,e.note());assertEquals("熊 · Ведмідь · Медведь",e.row.tokenName);assertFalse(e.confirmed());
    }
    @Test public void onlyExactConfirmedDuplicatesDisappearFromLocalSection() {
        prepare();confirm();List<LocalHistory.Entry> e=entries();assertTrue(e.get(0).confirmed());
        assertEquals(1,LocalHistory.visible(e,Collections.emptyList()).size());assertEquals(1,LocalHistory.visible(e,Arrays.asList(chain(id('e')))).size());
        assertTrue(LocalHistory.visible(e,Arrays.asList(chain(id('f').toUpperCase(Locale.ROOT)))).isEmpty());assertEquals(1,db.list(10).size());assertEquals(1,db.transactionAttempts().size());
    }
    @Test public void postedReceiptWithoutInclusionRemainsVisibleEvenIfDownloaded() {
        prepare();assertTrue(db.markAttempt("first",HistoryDb.POSTING,null));assertTrue(db.markAttempt("first",HistoryDb.POSTED,receipt(intent("0x00"))));
        List<LocalHistory.Entry> e=entries();assertEquals(HistoryDb.POSTED,e.get(0).phase());assertFalse(e.get(0).confirmed());assertEquals(1,LocalHistory.visible(e,Arrays.asList(chain(id('f')))).size());
    }
    @Test public void legacyStatusAloneCannotClaimConfirmationOrNonSubmission() {
        for(String status:new String[]{HistoryDb.STATUS_CONFIRMED,HistoryDb.STATUS_ERROR,HistoryDb.STATUS_POSTED,HistoryDb.STATUS_POSTING})row("legacy_"+status,status);
        for(LocalHistory.Entry e:entries()){assertNull(e.attempt);assertFalse(e.confirmed());assertEquals(HistoryDb.UNKNOWN,e.phase());assertEquals(TransactionEvidence.UNKNOWN,e.note());}
        assertEquals(4,LocalHistory.visible(entries(),Arrays.asList(chain("legacy_confirmed"))).size());assertTrue(db.transactionAttempts().isEmpty());
    }
    @Test public void malformedJournalKeepsRowsVisibleAndEvidenceUntouched() {
        prepare();String raw=intent("0x00").toString()+" trailing";db.getWritableDatabase().execSQL("UPDATE transaction_attempts SET intent=?",new Object[]{raw});
        LocalHistory.Entry e=entries().get(0);assertNull(e.attempt);assertEquals(TransactionEvidence.UNKNOWN,e.note());assertEquals(HistoryDb.STATUS_POSTING,e.row.status);
        try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT intent FROM transaction_attempts",null)){assertTrue(c.moveToFirst());assertEquals(raw,c.getString(0));}
    }
}
