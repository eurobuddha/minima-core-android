package com.eurobuddha.merchinbox;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import com.eurobuddha.merchinbox.integratedcomms.MerchDb;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
import static com.eurobuddha.merchinbox.DeliveryEvidenceTest.*;

/** Real SQLite restart, reservation and failure tests adapted from NFT Wallet TransactionJournalTest. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class InboxDeliveryJournalTest extends InboxDeliveryFixture {
    @Test public void restartReclaimsOnlyNeverPostedWorkAndNeverResendsUnknownInputs() {
        prepare();journal.close();journal=new DeliveryJournal(db);JSONObject saved=prepare();assertTrue(journal.owned(DeliveryJournal.entry(saved,coin(1).optString("coinid"))));
        phase(DeliveryJournal.POSTING);phase(DeliveryJournal.UNKNOWN);journal.close();db.close();db=new MerchDb(context);journal=new DeliveryJournal(db);
        saved=prepare();assertEquals(DeliveryJournal.UNKNOWN,phase());assertFalse(journal.owned(DeliveryJournal.entry(saved,coin(1).optString("coinid"))));
    }
    @Test public void activeOwnersAndOtherOrdersCannotClaimTheSameInput() {
        prepare();MerchDb.Order other=order("other");
        {DeliveryJournal second=new DeliveryJournal(db);
            try {assertFalse(second.owned(DeliveryJournal.entry(second.prepare(KEY,order,line,Collections.singletonList(coin(1))),coin(1).optString("coinid"))));
                assertThrows(RuntimeException.class,()->second.prepare("other-line",other,line,Collections.singletonList(coin(1))));
                assertThrows(RuntimeException.class,()->second.phase(KEY,order,line,coin(1).optString("coinid"),DeliveryJournal.POSTING));
            }finally{second.close();}
        }
        phase(DeliveryJournal.NOT_SUBMITTED);assertNotNull(journal.prepare("other-line",other,line,Collections.singletonList(coin(1))));
    }
    @Test public void sqliteFailuresRollBackIntentAndPostingChanges() {
        SQLiteDatabase sql=db.getWritableDatabase();sql.execSQL("CREATE TRIGGER deny_insert BEFORE INSERT ON nft_delivery BEGIN SELECT RAISE(ABORT,'fixture'); END");
        assertThrows(RuntimeException.class,this::prepare);assertNull(journal.load(KEY,order,line));sql.execSQL("DROP TRIGGER deny_insert");prepare();
        sql.execSQL("CREATE TRIGGER deny_update BEFORE UPDATE ON nft_delivery BEGIN SELECT RAISE(ABORT,'fixture'); END");
        assertThrows(RuntimeException.class,()->phase(DeliveryJournal.POSTING));assertEquals(DeliveryJournal.PREPARED,phase());
        sql.execSQL("DROP TRIGGER deny_update");phase(DeliveryJournal.POSTING);journal.close();journal=new DeliveryJournal(db);assertEquals(DeliveryJournal.POSTING,phase());
    }
    @Test public void legacyPostedOrSentMarkersCannotBecomeProofOrPermitAnotherSend() {
        for(String prefix:new String[]{"nftposted:","nftsent:"}) {
            db.setMeta(prefix+KEY,"old-marker");assertThrows(RuntimeException.class,this::prepare);assertThrows(RuntimeException.class,()->journal.prepare(KEY,order,line,Collections.emptyList()));db.setMeta(prefix+KEY,"");
        }
    }
    @Test public void receiptBindsInputDestinationAmountAndStateAndRequiresChainDepth()throws Exception {
        prepare();phase(DeliveryJournal.POSTING);
        for(Object[] mutation:new Object[][]{{"address",ADDRESS},{"tokenamount","2"},{"tokenid","0x99"},{"storestate",false}}) {
            JSONObject bad=reply(1);txn(bad).getJSONArray("outputs").getJSONObject(0).put((String)mutation[0],mutation[1]);
            assertThrows(RuntimeException.class,()->journal.receipt(KEY,order,line,coin(1).optString("coinid"),bad,null));
        }
        journal.receipt(KEY,order,line,coin(1).optString("coinid"),reply(1),null);journal.close();db.close();db=new MerchDb(context);journal=new DeliveryJournal(db);
        assertEquals(DeliveryJournal.POSTED,phase());String id=reply(1).getJSONObject("response").getString("txpowid");
        JSONObject shallow=included();shallow.getJSONObject("response").put("tip","102").put("confirmations","2");
        assertThrows(RuntimeException.class,()->journal.confirmSaved(KEY,order,line,coin(1).optString("coinid"),id,shallow));
        assertThrows(RuntimeException.class,()->journal.confirmSaved(KEY,order,line,coin(1).optString("coinid"),"0x99",included()));
        journal.confirmSaved(KEY,order,line,coin(1).optString("coinid"),id,included());assertTrue(DeliveryJournal.complete(journal.load(KEY,order,line)));
    }
    @Test public void changedOrderAndCorruptSavedEvidenceCannotAuthorizePosting() {
        prepare();db.getWritableDatabase().execSQL("UPDATE orders SET payaddr=?",new Object[]{ADDRESS});assertThrows(RuntimeException.class,()->phase(DeliveryJournal.POSTING));
        db.getWritableDatabase().execSQL("UPDATE orders SET payaddr=?",new Object[]{DEST});db.getWritableDatabase().execSQL("UPDATE nft_delivery SET data=data||' hidden'");
        assertThrows(RuntimeException.class,()->phase(DeliveryJournal.POSTING));assertThrows(RuntimeException.class,this::prepare);
    }
    @Test public void collectionIsFrozenAndEveryOriginalPieceMustConfirm()throws Exception {
        line[4]="1";journal.prepare(KEY,order,line,Arrays.asList(coin(1),coin(2)));
        for(int n=1;n<=2;n++){String input=coin(n).optString("coinid");journal.phase(KEY,order,line,input,DeliveryJournal.POSTING);journal.receipt(KEY,order,line,input,reply(n),n==1?included():null);}
        JSONObject resumed=journal.prepare(KEY,order,line,Collections.singletonList(coin(3)));assertEquals(2,resumed.getJSONArray("entries").length());assertFalse(DeliveryJournal.complete(resumed));
        journal.confirmSaved(KEY,order,line,coin(2).optString("coinid"),reply(2).getJSONObject("response").getString("txpowid"),included());assertTrue(DeliveryJournal.complete(journal.load(KEY,order,line)));
    }
    @Test public void upgradingVersionTwoPreservesOrdersAndLegacyEvidence() {
        db.setMeta("nftposted:"+KEY,"old");db.close();SQLiteDatabase sql=SQLiteDatabase.openDatabase(context.getDatabasePath("pandamonium_merchinbox.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);sql.execSQL("DROP TABLE nft_delivery");sql.setVersion(2);sql.close();db=new MerchDb(context);journal.close();journal=new DeliveryJournal(db);
        assertEquals(order.ref,db.order(order.ref).ref);assertEquals("old",db.getMeta("nftposted:"+KEY,""));assertNull(journal.load(KEY,order,line));assertThrows(RuntimeException.class,this::prepare);
    }
    @Test public void abandonedPreparationCanBeReleasedAcrossOrdersButUnknownCannot() {
        prepare();journal.close();journal=new DeliveryJournal(db);MerchDb.Order other=order("second");
        journal.prepare("second-line",other,line,Collections.singletonList(coin(1)));
        assertEquals(DeliveryJournal.NOT_SUBMITTED,phase());
        journal.phase("second-line",other,line,coin(1).optString("coinid"),DeliveryJournal.POSTING);
        journal.close();journal=new DeliveryJournal(db);
        assertThrows(RuntimeException.class,this::prepare);
    }
    @Test public void editedLifecycleStatusCannotBypassPaymentTokenAndAmountChecks() {
        db.getWritableDatabase().execSQL("UPDATE orders SET status=?,paidamount=?",new Object[]{MerchDb.SHIPPED,"0.5"});
        assertThrows(RuntimeException.class,this::prepare);
        db.getWritableDatabase().execSQL("UPDATE orders SET paidamount=?,paidtokenid=?",new Object[]{"1","0x99"});
        assertThrows(RuntimeException.class,this::prepare);
    }

}
