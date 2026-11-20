package com.eurobuddha.merchinbox;
import com.eurobuddha.merchinbox.integratedcomms.MerchDb;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;
import static com.eurobuddha.merchinbox.DeliveryEvidenceTest.*;

/** No delivery journal (so no transfer) without a DeliveryLedger reservation of that line, and none for an order
 *  whose due was not recomputed from the vendor's listing (review L2: never the buyer's own amount). */
public class JournalNeedsLedgerTest extends InboxDeliveryFixture {
    @Test public void anUnreservedLineGetsNoJournal() {
        db.getWritableDatabase().delete("meta","k LIKE 'ledger:%'",null);
        assertThrows(RuntimeException.class,this::prepare);
    }
    @Test public void anOrderPaidOnlyAgainstTheBuyersFigureGetsNoJournal() {
        db.getWritableDatabase().delete("meta","k=?",new String[]{"due:fixture"});
        assertThrows(RuntimeException.class,this::prepare);
        db.setMeta("due:fixture","2");   // the listing says 2; 1 was paid
        assertThrows(RuntimeException.class,this::prepare);
        db.setMeta("due:fixture","1");
        assertNotNull(prepare());
    }
}
