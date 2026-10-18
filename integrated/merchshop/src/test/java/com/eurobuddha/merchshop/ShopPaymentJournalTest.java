package com.eurobuddha.merchshop;
import com.eurobuddha.merchshop.integratedcomms.*;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import static com.eurobuddha.merchshop.PaymentJournal.*;
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk=33,manifest=org.robolectric.annotation.Config.NONE)
public class ShopPaymentJournalTest extends ShopPaymentFixture {
    @Test public void restartCanReclaimPreparationButCannotRepeatAnUnknownPost()throws Exception {
        preparePlain();plain.close();plain=new PaymentJournal(db);preparePlain();plainPhase(POSTING);plain.close();plain=new PaymentJournal(db);
        assertThrows(RuntimeException.class,this::preparePlain);assertEquals(POSTING,plainPhase());
    }
    @Test public void twoOwnersAndTwoOrdersCannotReserveTheSameInput()throws Exception {
        preparePlain();PaymentJournal other=new PaymentJournal(db);
        try {assertThrows(RuntimeException.class,()->other.prepare(KEY,order,HANDLE,intent()));createOrder("second");MerchDb.Order second=plain.paymentOrder("second");assertThrows(RuntimeException.class,()->other.prepare("second",second,HANDLE,intent()));}
        finally{other.close();}
    }
    @Test public void orderAndAddressCommitTogetherAndDuplicateOrderCannotOverwriteAddress()throws Exception {
        MerchMessage m=new MerchMessage();m.ref="new";m.amount="3";m.tokenid=TOKEN;
        db.getWritableDatabase().execSQL("CREATE TRIGGER deny_meta BEFORE INSERT ON meta BEGIN SELECT RAISE(ABORT,'fixture'); END");
        assertThrows(RuntimeException.class,()->plain.saveOrder(m,DEST));assertNull(db.order("new"));db.getWritableDatabase().execSQL("DROP TRIGGER deny_meta");
        m.ref=KEY;assertFalse(plain.saveOrder(m,ADDRESS));assertEquals(DEST,db.getMeta("vendoraddr:"+KEY,""));
    }
    @Test public void legacyUnpaidFlagIsNotPermissionForAnotherPayment()throws Exception {
        db.setMeta("paymentready:"+KEY,"");db.setMeta("unpaid:"+KEY,"1");assertFalse(plain.available(order));assertThrows(RuntimeException.class,this::preparePlain);
    }
    @Test public void changedTermsOrRecipientAndPaidOrdersCannotAuthorizePreparation()throws Exception {
        for(String column:new String[]{"amount","tokenid","counterparty","items","currency"}) {
            String original=db.getReadableDatabase().compileStatement("SELECT "+column+" FROM orders WHERE ref='fixture'").simpleQueryForString();
            db.getWritableDatabase().execSQL("UPDATE orders SET "+column+"=?",new Object[]{"changed"});assertThrows(RuntimeException.class,this::preparePlain);
            db.getWritableDatabase().execSQL("UPDATE orders SET "+column+"=?",new Object[]{original});
        }
        db.setMeta("vendoraddr:"+KEY,ADDRESS);assertThrows(RuntimeException.class,this::preparePlain);db.setMeta("vendoraddr:"+KEY,DEST);
        db.getWritableDatabase().execSQL("UPDATE orders SET paid=1");assertThrows(RuntimeException.class,this::preparePlain);
    }
    @Test public void failedPersistenceCannotReleaseReservationOrClaimConfirmation()throws Exception {
        preparePlain();plainPhase(POSTING);db.getWritableDatabase().execSQL("CREATE TRIGGER deny_update BEFORE UPDATE ON shop_payment BEGIN SELECT RAISE(ABORT,'fixture'); END");
        assertThrows(RuntimeException.class,()->plain.receipt(KEY,order,quote(),included()));assertEquals(POSTING,plainPhase());
    }
    @Test public void exactReceiptAndDepthSurviveReopening()throws Exception {
        preparePlain();plainPhase(POSTING);JSONObject bad=quote();txn(bad).getJSONArray("outputs").getJSONObject(1).put("tokenamount","6");assertThrows(RuntimeException.class,()->plain.receipt(KEY,order,bad,included()));
        plain.receipt(KEY,order,quote(),null);assertFalse(PaymentJournal.complete(plain.load(KEY,order)));
        JSONObject shallow=included();shallow.getJSONObject("response").put("confirmations","2").put("tip","102");assertThrows(RuntimeException.class,()->plain.confirmSaved(KEY,order,TX,shallow));
        plain.confirmSaved(KEY,order,TX,included());plain.close();db.close();db=new MerchDb(context);plain=new PaymentJournal(db);assertTrue(PaymentJournal.complete(plain.load(KEY,order)));
    }
    @Test public void corruptDataOrMissingReferenceCannotBecomePaymentEvidence()throws Exception {
        preparePlain();plainPhase(POSTING);JSONObject bad=quote();txn(bad).put("state",new JSONArray());assertThrows(RuntimeException.class,()->plain.receipt(KEY,order,bad,null));
        db.getWritableDatabase().execSQL("UPDATE shop_payment SET data=data||' hidden'");assertThrows(RuntimeException.class,()->plain.load(KEY,order));
    }
    @Test public void upgradingVersionTwoPreservesOrdersAndOriginalAddresses()throws Exception {
        plain.close();db.getWritableDatabase().execSQL("DROP TABLE shop_payment");db.getWritableDatabase().setVersion(2);db.close();db=new MerchDb(context);plain=new PaymentJournal(db);
        assertEquals(DEST,db.getMeta("vendoraddr:"+KEY,""));assertEquals("3",db.order(KEY).amount);assertNull(plain.load(KEY,order));preparePlain();
    }
}
