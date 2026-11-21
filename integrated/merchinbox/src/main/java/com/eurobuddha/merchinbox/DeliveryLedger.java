package com.eurobuddha.merchinbox;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * What the Inbox has sent or is sending, per order line (security review 2026-11-20, "the vendor cannot be
 * drained"). One row per line key in the Inbox database's meta table: {product, token, qty, state}, state
 * reserved (being sent, or the send did not finish: it may have gone) / sent / released (the vendor checked
 * the history and it was not sent). A line is sent at most once; a product's lines together never exceed its
 * listed stock; lines in flight count against what the vendor holds. {@link #reserve} re-checks all of that
 * inside one transaction, so two sends can never both pass.
 */
final class DeliveryLedger {

    static final String P = "ledger:";
    static final String RESERVED = "reserved", SENT = "sent", RELEASED = "released";

    private DeliveryLedger() {}

    static JSONObject row(SQLiteDatabase db, String lineKey) {
        try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k=?", new String[]{P + lineKey})) {
            return c.moveToFirst() ? new JSONObject(c.getString(0)) : null;
        } catch (org.json.JSONException e) { return new JSONObject(); }   // unreadable: treated as in flight
    }

    /** "" (never), reserved, sent or released. */
    static String state(SQLiteDatabase db, String lineKey) {
        JSONObject r = row(db, lineKey);
        return r == null ? "" : r.optString("state", RESERVED);
    }

    /** Units of a product reserved or sent across all orders under one listing (point release item 2: Studio exports
     *  a product's stock as what the vendor holds then, so after a re-export the deliveries under the earlier listing
     *  are already gone from that holding and must not count again). Rows from before the listing was recorded never
     *  count here; what is in flight stays bounded by the holding. */
    static BigDecimal delivered(SQLiteDatabase db, String product, String listing) { return totals(db, listing, product, null)[0]; }

    /** Units of a token in flight (reserved, not yet known to be sent) across all orders and listings. */
    static BigDecimal inflight(SQLiteDatabase db, String tokenid) { return totals(db, null, null, tokenid)[1]; }

    /** Units of every product of one token in one shop reserved or sent under one listing, and whether one of them
     *  is the complete collection (review L1: a complete collection uses every piece's stock, and a piece uses the
     *  collection's). */
    static BigDecimal deliveredOfToken(SQLiteDatabase db, String tokenPrefix, String listing) { return totals(db, listing, tokenPrefix, null)[2]; }
    static boolean bundleTaken(SQLiteDatabase db, String tokenPrefix, String listing) { return totals(db, listing, tokenPrefix, null)[3].signum() > 0; }

    /** One pass over the ledger: {delivered of product, in flight of token, delivered of token prefix, bundles of
     *  token prefix}; {@code key} is a product (exact) for [0] and a "shop|token|" prefix for [2] and [3]. */
    static BigDecimal[] totals(SQLiteDatabase db, String listing, String key, String tokenid) {
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k LIKE 'ledger:%'", null)) {
            while (c.moveToNext()) {
                JSONObject r;
                try { r = new JSONObject(c.getString(0)); } catch (org.json.JSONException e) { continue; }
                String st = r.optString("state", RESERVED), product = r.optString("product");
                if (RELEASED.equals(st)) continue;
                BigDecimal q;
                try { q = new BigDecimal(r.optString("qty", "0")); } catch (NumberFormatException e) { continue; }
                if (tokenid != null && RESERVED.equals(st) && tokenid.equalsIgnoreCase(r.optString("token"))) t[1] = t[1].add(q);
                if (key == null || listing == null || !r.has("listing") || !listing.equals(r.optString("listing"))) continue;
                if (product.equals(key)) t[0] = t[0].add(q);
                if (product.startsWith(key)) { t[2] = t[2].add(q); if (product.endsWith("|1")) t[3] = t[3].add(q); }
            }
        }
        return t;
    }

    /** Once (m3): rows reserved before rows named their listing are tagged with the listing then in use for their shop
     *  ({@code idForShop}: "" when the shop has none, and the row is left as it is). */
    static void tagUntagged(SQLiteDatabase db, java.util.function.Function<String, String> idForShop) {
        try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k='migrated:ledger-listing'", null)) { if (c.moveToFirst()) return; }
        db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT k, v FROM meta WHERE k LIKE 'ledger:%'", null)) {
                while (c.moveToNext()) {
                    try {
                        JSONObject r = new JSONObject(c.getString(1));
                        if (r.has("listing")) continue;
                        String product = r.optString("product"), shop = product.contains("|") ? product.substring(0, product.indexOf('|')) : "";
                        String id = shop.isEmpty() ? "" : idForShop.apply(shop);
                        if (id == null || id.isEmpty()) continue;
                        ContentValues v = new ContentValues(); v.put("k", c.getString(0)); v.put("v", r.put("listing", id).toString());
                        db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                    } catch (org.json.JSONException ignored) {}
                }
            }
            ContentValues m = new ContentValues(); m.put("k", "migrated:ledger-listing"); m.put("v", "1");
            db.insertWithOnConflict("meta", null, m, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Reserve a line for sending, or the reason it may not be sent (once only, stock across orders, holding). */
    static String reserve(SQLiteDatabase db, String lineKey, String product, String tokenid, BigDecimal qty, BigDecimal stock, BigDecimal held) {
        return reserve(db, lineKey, product, tokenid, qty, stock, held, "", "");
    }

    /** Pandamonium: the reservation also names its delivery journal key, so a journal can only be written for a
     *  reserved line ({@link #reservedFor}, checked inside the journal's own transaction). */
    static String reserve(SQLiteDatabase db, String lineKey, String product, String tokenid, BigDecimal qty, BigDecimal stock, BigDecimal held, String journalKey) {
        return reserve(db, lineKey, product, tokenid, qty, stock, held, journalKey, "");
    }

    /** As above, under the listing in use (point release item 2: stock counts only rows of the same listing). */
    static String reserve(SQLiteDatabase db, String lineKey, String product, String tokenid, BigDecimal qty, BigDecimal stock, BigDecimal held, String journalKey, String listing) {
        db.beginTransaction();
        try {
            String st = state(db, lineKey);
            if (RESERVED.equals(st)) return "this line is already being sent, or its send did not finish and may have gone: check your wallet's history first";
            if (SENT.equals(st)) return "this line was already sent";
            BigDecimal done = delivered(db, product, listing);
            if (done.add(qty).compareTo(stock) > 0)
                return "sending " + qty.toPlainString() + " would make " + done.add(qty).toPlainString() + " delivered of this product, more than the " + stock.toPlainString() + " you list";
            BigDecimal moving = inflight(db, tokenid);
            if (held == null || moving.add(qty).compareTo(held) > 0)
                return "you hold " + (held == null ? "an unknown amount" : held.toPlainString()) + " of token " + tokenid + " and " + moving.toPlainString() + " are already being sent";
            put(db, lineKey, product, tokenid, qty, RESERVED, journalKey, listing);
            db.setTransactionSuccessful();
            return null;
        } catch (org.json.JSONException e) {
            return "the delivery record could not be written";
        } finally { db.endTransaction(); }
    }

    /** sent, or released (only after the vendor checked the history and the line was not sent). */
    static void mark(SQLiteDatabase db, String lineKey, String state) {
        JSONObject r = row(db, lineKey);
        if (r == null) return;
        try {
            r.put("state", state);
            ContentValues v = new ContentValues(); v.put("k", P + lineKey); v.put("v", r.toString());
            db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (org.json.JSONException ignored) {}
    }

    private static void put(SQLiteDatabase db, String lineKey, String product, String tokenid, BigDecimal qty, String state, String journalKey) throws org.json.JSONException {
        put(db, lineKey, product, tokenid, qty, state, journalKey, null);
    }

    /** {@code listing}: the listing it was reserved under (null: a row adopted from before the ledger, never counted
     *  against a listing's stock). */
    private static void put(SQLiteDatabase db, String lineKey, String product, String tokenid, BigDecimal qty, String state, String journalKey, String listing) throws org.json.JSONException {
        ContentValues v = new ContentValues();
        v.put("k", P + lineKey);
        v.put("v", new JSONObject().put("product", product).put("token", tokenid).put("qty", qty.toPlainString()).put("state", state).put("journal", journalKey).toString());
        if (listing != null) { JSONObject r = new JSONObject(v.getAsString("v")).put("listing", listing); v.put("v", r.toString()); }
        db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** True when a reserved ledger row names this delivery journal key (the journal may be written). */
    static boolean reservedFor(SQLiteDatabase db, String journalKey) {
        try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k LIKE 'ledger:%'", null)) {
            while (c.moveToNext()) {
                try {
                    JSONObject r = new JSONObject(c.getString(0));
                    if (journalKey.equals(r.optString("journal")) && RESERVED.equals(r.optString("state", RESERVED))) return true;
                } catch (org.json.JSONException ignored) {}
            }
        }
        return false;
    }

    /** A delivery started before the ledger existed (its journal or posted mark is older): recorded as reserved
     *  without the stock check, so it counts against the stock and can finish being verified. */
    static void adopt(SQLiteDatabase db, String lineKey, String product, String tokenid, BigDecimal qty, String journalKey) {
        if (row(db, lineKey) != null) return;
        try { put(db, lineKey, product, tokenid, qty, RESERVED, journalKey); } catch (org.json.JSONException ignored) {}
    }

    /** Mark every ledger row of this delivery journal key (sent once the journal confirmed the transfer). */
    static void markJournal(SQLiteDatabase db, String journalKey, String state) {
        try (Cursor c = db.rawQuery("SELECT k, v FROM meta WHERE k LIKE 'ledger:%'", null)) {
            while (c.moveToNext()) {
                try {
                    if (journalKey.equals(new JSONObject(c.getString(1)).optString("journal"))) mark(db, c.getString(0).substring(P.length()), state);
                } catch (org.json.JSONException ignored) {}
            }
        }
    }

    /** The input coins of released attempts of this line (lower case), which a re-send must reuse (L-D). */
    static java.util.Set<String> pinned(SQLiteDatabase db, String journalKey) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k=?", new String[]{"pinned:" + journalKey})) {
            if (c.moveToFirst()) { org.json.JSONArray a = new org.json.JSONArray(c.getString(0)); for (int i = 0; i < a.length(); i++) out.add(a.getString(i).toLowerCase(java.util.Locale.ROOT)); }
        } catch (org.json.JSONException ignored) {}
        return out;
    }

    static java.util.Set<String> inputsOf(String raw) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            JSONObject row = new JSONObject(raw);
            org.json.JSONArray es = row.optJSONArray("entries");
            if (es != null) { for (int i = 0; i < es.length(); i++) if (es.optJSONObject(i) != null && !es.optJSONObject(i).optString("input").isEmpty()) out.add(es.optJSONObject(i).optString("input").toLowerCase(java.util.Locale.ROOT)); }
            else for (String id : com.eurobuddha.nftwallet.OrderPaymentEvidence.inputIds(row.optJSONObject("intent"))) out.add(id.toLowerCase(java.util.Locale.ROOT));
        } catch (RuntimeException | org.json.JSONException ignored) {}
        return out;
    }

    /** The vendor checked the wallet history and the transfer of this journal key did not go (review L3: a send
     *  stuck as unknown, or whose coins were moved): its journal rows are removed and its ledger row released, so
     *  it can be sent again. Only from the vendor's confirmation; never automatically. */
    static void vendorRelease(SQLiteDatabase db, String journalKey) {
        db.beginTransaction();
        try {
            // the released attempt's input coins: a re-send must spend at least one of them, so if the first transfer
            // did go after all, the two conflict and only one can confirm (final check L-D)
            java.util.Set<String> pin = pinned(db, journalKey);
            for (String table : new String[]{"nft_plain_delivery", "nft_delivery"})
                try (Cursor c = db.rawQuery("SELECT data FROM " + table + " WHERE linekey=?", new String[]{journalKey})) {
                    while (c.moveToNext()) pin.addAll(inputsOf(c.getString(0)));
                }
            if (!pin.isEmpty()) {
                ContentValues p = new ContentValues(); p.put("k", "pinned:" + journalKey); p.put("v", new org.json.JSONArray(pin).toString());
                db.insertWithOnConflict("meta", null, p, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.delete("nft_plain_delivery", "linekey=?", new String[]{journalKey});
            db.delete("nft_delivery", "linekey=?", new String[]{journalKey});
            db.delete("meta", "k=? OR k=?", new String[]{"nftposted:" + journalKey, "notified:" + journalKey});
            markJournal(db, journalKey, RELEASED);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
