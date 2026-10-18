package com.eurobuddha.merchinbox;
import org.json.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;
import static com.eurobuddha.merchinbox.DeliveryEvidenceTest.*;
import static com.eurobuddha.merchinbox.DeliveryJournal.*;
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk=33,manifest=org.robolectric.annotation.Config.NONE)
public class InboxPlainJournalTest extends InboxPlainFixture {
    @Test public void aNewHelperCanReclaimAbandonedPreparationButNotPosting()throws Exception {
        preparePlain();plain.close();plain=new PlainDeliveryJournal(db,journal);preparePlain();plainPhase(POSTING);plain.close();plain=new PlainDeliveryJournal(db,journal);
        assertThrows(RuntimeException.class,this::preparePlain);assertEquals(POSTING,plainPhase());
    }
    @Test public void plainAndStatefulPathsCannotReserveTheSameInputInEitherOrder()throws Exception {
        preparePlain();assertThrows(RuntimeException.class,()->journal.prepare("state-line",order,line,Collections.singletonList(coin(1))));plainPhase(NOT_SUBMITTED);
        journal.prepare("state-line",order,line,Collections.singletonList(coin(1)));assertThrows(RuntimeException.class,this::preparePlain);
        journal.phase("state-line",order,line,coin(1).optString("coinid"),NOT_SUBMITTED);preparePlain();
        PlainDeliveryJournal second=new PlainDeliveryJournal(db,journal);try{assertThrows(RuntimeException.class,()->second.prepare(KEY,order,line,HANDLE,intent()));}finally{second.close();}
    }
    @Test public void legacyMarkersAndChangedOrderCannotAuthorizeAnotherTransfer()throws Exception {
        db.setMeta("nftsent:"+KEY,"sent");assertThrows(RuntimeException.class,this::preparePlain);db.setMeta("nftsent:"+KEY,"");preparePlain();
        db.getWritableDatabase().execSQL("UPDATE orders SET payaddr=?",new Object[]{ADDRESS});assertThrows(RuntimeException.class,()->plainPhase(POSTING));
    }
    @Test public void databaseFailuresCannotLoseReservationsOrPublishConfirmation()throws Exception {
        db.getWritableDatabase().execSQL("CREATE TRIGGER deny_insert BEFORE INSERT ON nft_plain_delivery BEGIN SELECT RAISE(ABORT,'fixture'); END");assertThrows(RuntimeException.class,this::preparePlain);assertNull(plain.load(KEY,order,line));db.getWritableDatabase().execSQL("DROP TRIGGER deny_insert");preparePlain();plainPhase(POSTING);
        db.getWritableDatabase().execSQL("CREATE TRIGGER deny_update BEFORE UPDATE ON nft_plain_delivery BEGIN SELECT RAISE(ABORT,'fixture'); END");assertThrows(RuntimeException.class,()->plain.receipt(KEY,order,line,quote(),included()));assertEquals(POSTING,plainPhase());
    }
    @Test public void confirmedReceiptSurvivesReopenAndAllOutputsMustMatch()throws Exception {
        preparePlain();plainPhase(POSTING);JSONObject bad=quote();txn(bad).getJSONArray("outputs").getJSONObject(1).put("tokenamount","6");assertThrows(RuntimeException.class,()->plain.receipt(KEY,order,line,bad,included()));
        plain.receipt(KEY,order,line,quote(),null);assertFalse(PlainDeliveryJournal.complete(plain.load(KEY,order,line)));
        JSONObject shallow=included();shallow.getJSONObject("response").put("confirmations","2").put("tip","102");assertThrows(RuntimeException.class,()->plain.confirmSaved(KEY,order,line,TX,shallow));
        plain.confirmSaved(KEY,order,line,TX,included());plain.close();db.close();db=new com.eurobuddha.merchinbox.integratedcomms.MerchDb(context);journal.close();journal=new DeliveryJournal(db);plain=new PlainDeliveryJournal(db,journal);
        assertTrue(PlainDeliveryJournal.complete(plain.load(KEY,order,line)));
    }
    @Test public void corruptedStoredDataAndMissingReferenceCannotBecomeEvidence()throws Exception {
        preparePlain();plainPhase(POSTING);JSONObject bad=quote();txn(bad).put("state",new JSONArray());assertThrows(RuntimeException.class,()->plain.receipt(KEY,order,line,bad,null));
        db.getWritableDatabase().execSQL("UPDATE nft_plain_delivery SET data=data||' hidden'");assertThrows(RuntimeException.class,()->plain.load(KEY,order,line));
    }
    @Test public void upgradingVersionThreeKeepsStatefulJournalOrdersAndLegacyMarkers()throws Exception {
        journal.prepare("state-kept",order,line,Collections.singletonList(coin(2)));db.setMeta("nftposted:legacy","old-marker");
        plain.close();journal.close();db.close();
        android.database.sqlite.SQLiteDatabase sql=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("pandamonium_merchinbox.db").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
        sql.execSQL("DROP TABLE nft_plain_delivery");sql.setVersion(3);sql.close();
        db=new com.eurobuddha.merchinbox.integratedcomms.MerchDb(context);journal=new DeliveryJournal(db);plain=new PlainDeliveryJournal(db,journal);
        assertEquals(order.ref,db.order(order.ref).ref);assertEquals("old-marker",db.getMeta("nftposted:legacy",""));assertNotNull(journal.load("state-kept",order,line));assertNull(plain.load(KEY,order,line));preparePlain();assertEquals(PREPARED,plainPhase());
    }
}
