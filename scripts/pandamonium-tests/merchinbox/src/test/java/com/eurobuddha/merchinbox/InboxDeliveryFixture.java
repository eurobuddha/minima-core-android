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
abstract class InboxDeliveryFixture {
    static final String DEST="0x"+"34".repeat(32), KEY="fixture-line";
    Context context;MerchDb db;DeliveryJournal journal;MerchDb.Order order;
    String[] line={"Fixture",TOKEN,"1","7","0"};
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("pandamonium_merchinbox.db");db=new MerchDb(context);journal=new DeliveryJournal(db);order=order("fixture");}
    @After public void close(){journal.close();db.close();context.deleteDatabase("pandamonium_merchinbox.db");}
    MerchDb.Order order(String ref){db.getWritableDatabase().execSQL("INSERT INTO orders(ref,status,paid,payaddr,items,tokenid,amount,paidamount,paidtokenid) VALUES(?,?,?,?,?,?,?,?,?)",new Object[]{ref,MerchDb.PAID,1,DEST,"[]","0x00","1","1","0x00"});return db.order(ref);}
    JSONObject prepare(){return journal.prepare(KEY,order,line,Collections.singletonList(coin(1)));}
    static JSONObject reply(int n)throws Exception {JSONObject r=posted(coin(n));r.getJSONObject("response").put("txpowid","0x"+String.format("%02x",n+20).repeat(32)).put("istransaction",true);txn(r).getJSONArray("outputs").getJSONObject(0).put("address",DEST);return r;}
    static JSONObject included(){return object("status",true,"response",object("found",true,"blockid","0x"+"ff".repeat(32),"block","100","tip","103","confirmations","3"));}
    String phase(){return DeliveryJournal.entry(journal.load(KEY,order,line),coin(1).optString("coinid")).optString("phase");}
    void phase(String next){journal.phase(KEY,order,line,coin(1).optString("coinid"),next);}
}
