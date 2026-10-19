package com.eurobuddha.utxo;
import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
import static com.eurobuddha.utxo.TransactionEvidenceTest.*;
import static com.eurobuddha.utxo.UncertainWriteTest.object;

/** Migration and coexistence checks for UTXO's older history resolver and the reused NFT journal. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class JournalCompatibilityTest {
    Context context;HistoryDb db;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("pandamonium_utxo_history.db");db=new HistoryDb(context);}
    @After public void close(){db.close();context.deleteDatabase("pandamonium_utxo_history.db");}
    void record(String id){HistoryRow row=new HistoryRow();row.internalid=row.txnid=id;row.tokenid="0x00";row.amount="3";row.status=HistoryDb.STATUS_UNKNOWN;row.inputs=intent("0x00").optJSONArray("inputs").toString();row.outputs=intent("0x00").optJSONArray("outputs").toString();assertTrue(db.insertPosting(row));}
    void prepare(){record("attempt");assertTrue(db.prepareAttempt("attempt","handle",intent("0x00")));assertTrue(db.markAttempt("attempt",HistoryDb.POSTING,null));}
    @Test public void legacyResolverCannotBypassAJournalReservationEvenWithMatchingEconomics()throws Exception {
        prepare();JSONObject raw=TransactionRecoveryTest.raw();assertTrue(HistoryEvidence.matches(db.list(5).get(0),raw));assertTrue(db.listRecoverable().isEmpty());
        assertFalse(db.confirmHistory("attempt",raw,object("difference",object("0x00","-3")),TransactionRecoveryTest.inclusion("3")));
        assertEquals(HistoryDb.POSTING,db.transactionAttempts().get(0).phase);assertTrue(db.hasUnresolvedAttempt("0x00"));
    }
    @Test public void dismissCannotRemoveUnresolvedEvidenceButCanRemoveAProvenRejection(){
        prepare();db.delete("attempt");assertEquals(1,db.list(5).size());assertTrue(db.hasUnresolvedAttempt("0x00"));
        assertTrue(db.markAttempt("attempt",HistoryDb.NOT_SUBMITTED,null));db.delete("attempt");assertTrue(db.list(5).isEmpty());assertEquals(1,db.transactionAttempts().size());
    }
    @Test public void confirmedJournalRowRemainsVisibleUntilItsHistoryEntryIsAvailable()throws Exception {
        prepare();assertTrue(db.confirmAttempt("attempt",receipt(intent("0x00")),TransactionRecoveryTest.inclusion("3")));
        assertEquals(HistoryDb.STATUS_CONFIRMED,db.listOpen(5).get(0).status);
        NodeTx cached=new NodeTx();cached.txpowid=id('f');assertTrue(db.upsertNodeTx(cached));assertTrue(db.listOpen(5).isEmpty());
    }
    @Test public void versionSevenUpgradeKeepsLegacyEconomicsAndNodeCache(){
        record("legacy");NodeTx cached=new NodeTx();cached.txpowid=id('e');db.upsertNodeTx(cached);
        db.getWritableDatabase().execSQL("DROP TABLE transaction_attempts");db.getWritableDatabase().setVersion(7);db.close();db=new HistoryDb(context);
        assertEquals(1,db.nodeTxCount());assertEquals("legacy",db.listRecoverable().get(0).internalid);assertEquals(intent("0x00").optJSONArray("inputs").toString(),db.listRecoverable().get(0).inputs);assertTrue(db.transactionAttempts().isEmpty());
    }
    @Test public void pendingTransportEnvelopesCannotEstablishAReceiptOrConfirmation()throws Exception {
        JSONObject pending=receipt(intent("0x00")).put("pending",true);assertThrows(RuntimeException.class,()->TransactionEvidence.receipt(pending,intent("0x00")));
        prepare();JSONObject chain=TransactionRecoveryTest.inclusion("3").put("pending",true);assertThrows(RuntimeException.class,()->db.confirmAttempt("attempt",receipt(intent("0x00")),chain));assertTrue(db.hasUnresolvedAttempt("0x00"));
    }
    @Test public void missingTokenUnitsAndSpentFlagsCannotBeFrozenIntoASend()throws Exception {
        JSONObject raw=object("coinid",id('1'),"tokenid",TOKEN,"amount","0.00000001","address",DEST);
        Coin missing=Coin.from(raw);missing.sendable=true;assertThrows(RuntimeException.class,()->TransactionEvidence.intent(Collections.singletonList(missing),outputs("3","7"),TOKEN,null));
        raw.put("tokenamount","10");Coin valid=Coin.from(raw);valid.sendable=true;assertNotNull(TransactionEvidence.intent(Collections.singletonList(valid),outputs("3","7"),TOKEN,null));
        for(Object flag:new Object[]{true,"false",1}){raw.put("spent",flag);Coin spent=Coin.from(raw);spent.sendable=true;assertThrows(RuntimeException.class,()->TransactionEvidence.intent(Collections.singletonList(spent),outputs("3","7"),TOKEN,null));}
        raw.remove("spent");raw.remove("tokenid");Coin noToken=Coin.from(raw);noToken.sendable=true;assertThrows(RuntimeException.class,()->TransactionEvidence.intent(Collections.singletonList(noToken),outputs("3","7"),"0x00",null));
    }
}
