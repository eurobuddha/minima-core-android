package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import android.view.View;

import com.eurobuddha.merchinbox.integratedcomms.MerchDb;
import com.eurobuddha.merchinbox.integratedcomms.MerchMessage;
import com.eurobuddha.minimacore.i18n.Language;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Method;

/** Delta review (2026-11-20) items that only Pandamonium has: L-e Release gating, the translated summary and conflict screen. */
public class DeltaReviewTest extends DrainTest {

    static JSONObject plain(String phase) throws Exception { return new JSONObject().put("phase", phase); }
    static JSONObject state(String... phases) throws Exception {
        JSONArray e = new JSONArray(); for (String p : phases) e.put(new JSONObject().put("phase", p).put("input", "0x" + "11".repeat(32)));
        return new JSONObject().put("entries", e);
    }

    @Test public void releaseIsNeverOfferedForAPostedTransferAwaitingConfirmation() throws Exception {
        assertEquals(MainActivity.RELEASE_NEVER, MainActivity.releasable(plain(DeliveryJournal.POSTED)));
        assertEquals(MainActivity.RELEASE_NEVER, MainActivity.releasable(plain(DeliveryJournal.POSTING)));
        assertEquals(MainActivity.RELEASE_NEVER, MainActivity.releasable(state(DeliveryJournal.UNKNOWN, DeliveryJournal.POSTED)));
        assertEquals(MainActivity.RELEASE_NEVER, MainActivity.releasable(state(DeliveryJournal.CONFIRMED, DeliveryJournal.NOT_SUBMITTED)));
        assertEquals(MainActivity.RELEASE_UNKNOWN, MainActivity.releasable(plain(DeliveryJournal.UNKNOWN)));
        assertEquals(MainActivity.RELEASE_UNKNOWN, MainActivity.releasable(state(DeliveryJournal.UNKNOWN, DeliveryJournal.NOT_SUBMITTED)));
        assertEquals("no journal: a send from before it", MainActivity.RELEASE_UNKNOWN, MainActivity.releasable(null));
        assertEquals(MainActivity.RELEASE_IF_MOVED, MainActivity.releasable(plain(DeliveryJournal.PREPARED)));
        assertEquals(java.util.Collections.singletonList("0x" + "11".repeat(32)), MainActivity.journalInputs(state(DeliveryJournal.PREPARED)));
    }

    @Test public void theImportSummaryReadsInTheChosenLanguage() throws Exception {
        JSONObject l = Listing.compact(new JSONObject(db.getMeta("listing:art", "")).put("vendorPublicId", ME).toString(), ME);
        Language.select(activity, "ru");
        String s = Listing.summary(l, l, MainActivity.SUMMARY_WORDS);
        assertTrue(s, s.contains("Магазин «Art» (art)") && s.contains("«Piece» токен " + NFT + ": цена 10, в наличии 1"));
        assertTrue(s, s.contains("доставка «digital»: стоимость 0") && s.contains("Изменения по сравнению с используемым списком:\nнет"));
        Language.select(activity, "en");
        assertEquals(Listing.summary(l, l), Listing.summary(l, l, MainActivity.SUMMARY_WORDS));
    }

    @Test public void theConflictScreenShowsBothOrdersAndOnlyTheCommittedSwitch() throws Exception {
        String n = MerchMessage.newNonce(), c = MerchMessage.commitment(n), ref = "ORD-00112233AABBCCDD";
        MerchMessage s = new MerchMessage();
        s.type = MerchMessage.ORDER; s.ref = ref; s.from = "0xSTRANGER"; s.to = ME; s.currency = "Minima"; s.shopId = "art"; s.shipping = "digital";
        s.buyerPayaddr = "0x" + "66".repeat(32); s.amount = "10";
        s.items = new JSONArray().put(new JSONObject().put("product", "Piece").put("quantity", 1).put("unitPrice", "10").put("nftTokenId", NFT)).toString();
        assertTrue(db.upsertOrder(s, "seller", "0xC1"));
        MerchMessage h = MerchMessage.fromWire(s.toWire()); h.from = "0xHONEST"; h.buyerPayaddr = BUYER; h.nonce = n;
        assertFalse(db.upsertOrder(h, "seller", "0xC2"));
        db.recordPayment(ref, "0xE1", "10", "0x00", c);
        Method b = MainActivity.class.getDeclaredMethod("buildOrderDetail", String.class); b.setAccessible(true);
        String all = String.join("\n", InboxShowsIdentifiersWholeTest.texts((View) b.invoke(activity, ref)));
        assertTrue(all, all.toUpperCase(java.util.Locale.ROOT).contains("OTHER ORDERS WITH THIS REFERENCE") && all.contains("Sender 0xHONEST\nBuyer address " + BUYER));
        assertTrue(all, all.contains("The payment at your address commits to THIS order.") && all.contains("Make the order the payment commits to the order of record"));
        assertFalse(all, all.contains("by message") || all.contains("outside this app"));
        Language.select(activity, "uk");
        all = String.join("\n", InboxShowsIdentifiersWholeTest.texts((View) b.invoke(activity, ref)));
        assertTrue(all, all.toUpperCase(new java.util.Locale("uk")).contains("ІНШІ ЗАМОВЛЕННЯ З ЦИМ НОМЕРОМ") && all.contains("Відправник 0xHONEST"));
        Language.select(activity, "en");
    }
}
