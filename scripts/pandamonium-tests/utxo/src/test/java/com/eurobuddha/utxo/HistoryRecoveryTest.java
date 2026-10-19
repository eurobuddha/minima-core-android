package com.eurobuddha.utxo;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static com.eurobuddha.utxo.HistoryEvidenceTest.*;

/** Real SQLite and a queued read-only fake node. No signing, keys or live transactions. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class HistoryRecoveryTest {
    static final String DB="pandamonium_utxo_history.db";
    Context context;HistoryDb db;
    static class FakeNode implements HistoryRecovery.Commands {
        final List<String> calls=new ArrayList<>();final ArrayDeque<NodeApi.Cb> callbacks=new ArrayDeque<>();
        public void cmd(String command,NodeApi.Cb cb){assertTrue(command.startsWith("history ")||command.startsWith("txpow onchain:"));calls.add(command);callbacks.add(cb);}
        void reply(JSONObject j){callbacks.remove().onResult(j);}
        void error(String e){callbacks.remove().onError(e);}
    }
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(DB);db=new HistoryDb(context);}
    @After public void cleanup(){db.close();context.deleteDatabase(DB);}
    static JSONObject page(JSONObject... txs){JSONArray details=new JSONArray();for(JSONObject t:txs)details.put(detail());return obj("status",true,"response",obj("txpows",arr((Object[])txs),"details",details));}
    void record(){db.insertPosting(row("0x00"));}
    String status(){try(Cursor c=db.getReadableDatabase().rawQuery("SELECT status FROM history WHERE internalid='attempt'",null)){assertTrue(c.moveToFirst());return c.getString(0);}}
    @Test public void partialCacheDoesNotConfirmOrRejectLocalAttempt() throws Exception {
        record();JSONObject t=tx("0x00");txn(t).getJSONArray("outputs").remove(1);
        NodeTx n=NodeTx.from(t,detail());db.upsertNodeTx(n);assertFalse(db.resolve(n));assertFalse(db.resolveOpenAgainstStored());
        assertEquals(HistoryDb.STATUS_UNKNOWN,status());
    }
    @Test public void recoveryWaitsForInclusionThenSurvivesRestartAndLateCallback() {
        record();FakeNode node=new FakeNode();int[] changed={0};HistoryRecovery recovery=new HistoryRecovery(node,db,()->changed[0]++);
        recovery.poll(103);node.reply(page(tx("0x00")));assertEquals(HistoryDb.STATUS_UNKNOWN,status());
        assertEquals("txpow onchain:"+TX,node.calls.get(1));node.reply(inclusion());assertEquals(1,changed[0]);
        assertEquals(HistoryDb.STATUS_CONFIRMED,status());db.update("attempt",HistoryDb.STATUS_ERROR,null,"late failure");
        db.close();db=new HistoryDb(context);assertEquals(HistoryDb.STATUS_CONFIRMED,status());
        NodeTx n=db.loadNodeTx(1).get(0);assertEquals(100,n.block);assertEquals("sent",n.direction);assertEquals("3",n.amount);
    }
    @Test public void cacheAndStatusCommitTogetherOrRollback() {
        record();db.getWritableDatabase().execSQL("CREATE TRIGGER fail_confirmation BEFORE UPDATE ON history BEGIN SELECT RAISE(ABORT,'fixture'); END");
        try{db.confirmHistory("attempt",tx("0x00"),detail(),inclusion());fail();}catch(RuntimeException expected){}
        assertEquals(0,db.nodeTxCount());assertEquals(HistoryDb.STATUS_UNKNOWN,status());
    }
    @Test public void duplicateAttemptsCannotBothClaimOneReceipt() {
        record();HistoryRow second=row("0x00");second.internalid="second";db.insertPosting(second);
        FakeNode node=new FakeNode();new HistoryRecovery(node,db,()->{}).poll(103);node.reply(page(tx("0x00")));
        assertEquals(1,node.calls.size());assertFalse(db.confirmHistory("attempt",tx("0x00"),detail(),inclusion()));
        db.delete("second");assertTrue(db.confirmHistory("attempt",tx("0x00"),detail(),inclusion()));
        db.insertPosting(second);assertFalse(db.confirmHistory("second",tx("0x00"),detail(),inclusion()));
    }
    @Test public void staleOrClosedRecoveryDoesNotChangeHistory() {
        record();FakeNode node=new FakeNode();HistoryRecovery recovery=new HistoryRecovery(node,db,()->{});
        recovery.poll(103);node.reply(page(tx("0x00")));recovery.close();node.reply(inclusion());assertEquals(HistoryDb.STATUS_UNKNOWN,status());
    }
    @Test public void immatureOrFailedInclusionRemainsUnknownAndRetriesNextBlock() throws Exception {
        record();FakeNode node=new FakeNode();HistoryRecovery recovery=new HistoryRecovery(node,db,()->{});
        recovery.poll(101);node.reply(page(tx("0x00")));JSONObject p=inclusion();p.getJSONObject("response").put("tip","101").put("confirmations","1");
        node.reply(p);assertEquals(HistoryDb.STATUS_UNKNOWN,status());recovery.poll(101);assertEquals(2,node.calls.size());
        recovery.poll(103);node.reply(page(tx("0x00")));node.reply(inclusion());assertEquals(HistoryDb.STATUS_CONFIRMED,status());
    }
    @Test public void unreadablePagesKeepTheAttemptAndRetryAtSameOffset() {
        record();FakeNode node=new FakeNode();HistoryRecovery recovery=new HistoryRecovery(node,db,()->{});
        recovery.poll(100);node.reply(obj("status",true));recovery.poll(101);assertEquals(node.calls.get(0),node.calls.get(1));
        node.error(NodeApi.ERR_TOO_LONG);assertTrue(node.calls.get(2).contains("max:4 offset:0"));
        node.reply(page(tx("0x00")));node.error("offline");assertEquals(HistoryDb.STATUS_UNKNOWN,status());
    }
    @Test public void legacyUpgradePreservesHistoryAndRevalidatesUnsupportedOutcomes() {
        record();db.upsertNodeTx(NodeTx.from(tx("0x00"),detail()));
        db.getWritableDatabase().execSQL("UPDATE history SET status='confirmed',txnid='"+TX+"'");
        db.getWritableDatabase().setVersion(6);db.close();db=new HistoryDb(context);
        assertEquals(1,db.nodeTxCount());assertEquals(HistoryDb.STATUS_UNKNOWN,status());
        assertEquals("attempt",db.listOpen(1).get(0).txnid);
        assertTrue(db.confirmHistory("attempt",tx("0x00"),detail(),inclusion()));
    }
    @Test public void confirmedCacheGetsActualChainHeightAndHumanTokenUnits() throws Exception {
        HistoryRow r=row(TOKEN);db.insertPosting(r);JSONObject t=tx(TOKEN),d=obj("difference",obj(TOKEN,"-3"));
        NodeTx old=NodeTx.from(t,d);old.outputs="[]";db.upsertNodeTx(old);
        assertTrue(db.confirmHistory("attempt",t,d,inclusion()));NodeTx n=db.loadNodeTx(1).get(0);
        assertEquals(100,n.block);assertEquals("3",new JSONArray(n.outputs).getJSONObject(0).getString("amount"));
        db.upsertNodeTx(old);assertEquals(100,db.loadNodeTx(1).get(0).block);
    }
    @Test public void anAttemptChangedWhileInclusionWasPendingIsRechecked() {
        record();FakeNode node=new FakeNode();HistoryRecovery recovery=new HistoryRecovery(node,db,()->{});
        recovery.poll(103);node.reply(page(tx("0x00")));db.update("attempt",HistoryDb.STATUS_ERROR,null,"not sent");node.reply(inclusion());
        assertEquals(HistoryDb.STATUS_ERROR,status());assertEquals(0,db.nodeTxCount());
    }

    @Test public void manyErrorRowsCannotHideTheOlderUnresolvedAttempt() {
        record();
        for(int i=0;i<501;i++){HistoryRow bad=row("0x00");bad.internalid="error"+i;bad.status=HistoryDb.STATUS_ERROR;db.insertPosting(bad);}
        FakeNode node=new FakeNode();new HistoryRecovery(node,db,()->{}).poll(103);
        node.reply(page(tx("0x00")));node.reply(inclusion());assertEquals(HistoryDb.STATUS_CONFIRMED,status());
    }
    @Test public void aRecoveryTickIsBoundedAndContinuesWithoutLosingItsPlace() {
        record();FakeNode node=new FakeNode();HistoryRecovery recovery=new HistoryRecovery(node,db,()->{});
        recovery.poll(100);JSONObject unrelated=obj("istransaction",false);
        JSONObject full=page(unrelated,unrelated,unrelated,unrelated,unrelated,unrelated,unrelated,unrelated);
        for(int i=0;i<4;i++)node.reply(full);
        assertEquals(4,node.calls.size());assertTrue(node.callbacks.isEmpty());
        recovery.poll(101);assertTrue(node.calls.get(4).endsWith("offset:32"));node.reply(page());
        recovery.poll(102);assertTrue(node.calls.get(5).endsWith("offset:0"));
    }
    @Test public void oversizedSingleRowDoesNotAuthorizeAnythingOrHideLaterRows() {
        record();FakeNode node=new FakeNode();HistoryRecovery recovery=new HistoryRecovery(node,db,()->{});recovery.poll(100);
        for(int i=0;i<4;i++)node.error(NodeApi.ERR_TOO_LONG);
        assertEquals("history relevant:true max:1 offset:1",node.calls.get(4));
        node.reply(page(tx("0x00")));node.reply(inclusion());assertEquals(HistoryDb.STATUS_CONFIRMED,status());
    }
    @Test public void migrationPreservesRealErrorsAndReopensOldInferredFailures() {
        record();db.getWritableDatabase().execSQL("UPDATE history SET status='error',note='Not posted: these coins were spent by a different transaction (old).'");
        HistoryRow rejected=row("0x00");rejected.internalid="rejected";rejected.status=HistoryDb.STATUS_ERROR;rejected.note="Rejected before signing";db.insertPosting(rejected);
        db.getWritableDatabase().setVersion(6);db.close();db=new HistoryDb(context);
        assertEquals(HistoryDb.STATUS_UNKNOWN,status());assertEquals(1,db.listRecoverable().size());
        assertEquals(2,db.listOpen(10).size());
    }
}
