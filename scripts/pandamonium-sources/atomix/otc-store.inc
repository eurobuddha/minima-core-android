    /** Reuse the NFT history journal's transactional check/write across independent helpers. */
    public synchronized boolean createDeal(Deal deal) {
        if (deal == null || deal.ref == null || deal.ref.isEmpty() || deal.market() == null) return false;
        Deal saved = deal.copy();
        if (saved.created == 0) saved.created = System.currentTimeMillis();
        saved.updated = System.currentTimeMillis();
        saved.hash = normHash(saved.hash);
        if (helper.getWritableDatabase().insertWithOnConflict("otc_deals", null, values(saved),
                SQLiteDatabase.CONFLICT_IGNORE) < 0) return false;
        deal.created = saved.created; deal.updated = saved.updated; deal.hash = saved.hash;
        return true;
    }

    /** Only the exact reviewed snapshot may advance. Never recreate a missing or pruned deal. */
    public synchronized boolean replaceDeal(Deal expected, Deal next) {
        if (expected == null || next == null || expected == next || !sameIdentity(expected, next)) return false;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (!sameSnapshot(expected, getDeal(expected.ref))) return false;
            Deal saved = next.copy();
            // A monotonic stamp also rejects an old snapshot if terms change and then change back.
            saved.updated = Math.max(System.currentTimeMillis(), Math.addExact(expected.updated, 1));
            saved.hash = normHash(saved.hash);
            if (db.update("otc_deals", values(saved), "ref=?", new String[]{expected.ref}) != 1) return false;
            db.setTransactionSuccessful();
            next.updated = saved.updated; next.hash = saved.hash;
            return true;
        } finally { db.endTransaction(); }
    }

    private static ContentValues values(Deal d) {
        ContentValues v = new ContentValues();
        v.put("ref", d.ref); v.put("role", d.role); v.put("peercid", d.peerCommsId);
        v.put("peermpk", d.peerMinimaPk); v.put("peereth", d.peerEthAddr);
        v.put("side", d.side); v.put("amount", d.amount); v.put("price", d.price);
        v.put("status", d.status); v.put("whoseturn", d.whoseTurn); v.put("hash", normHash(d.hash));
        v.put("currency", d.currency); v.put("created", d.created); v.put("updated", d.updated);
        return v;
    }

    private static boolean sameIdentity(Deal a, Deal b) {
        return java.util.Objects.equals(a.ref, b.ref) && java.util.Objects.equals(a.role, b.role)
                && java.util.Objects.equals(a.peerCommsId, b.peerCommsId)
                && java.util.Objects.equals(a.peerMinimaPk, b.peerMinimaPk)
                && java.util.Objects.equals(a.peerEthAddr, b.peerEthAddr)
                && java.util.Objects.equals(a.side, b.side) && java.util.Objects.equals(a.currency, b.currency)
                && a.created == b.created;
    }

    static boolean sameSnapshot(Deal a, Deal b) {
        return a != null && b != null && sameIdentity(a, b) && a.updated == b.updated
                && java.util.Objects.equals(a.amount, b.amount) && java.util.Objects.equals(a.price, b.price)
                && java.util.Objects.equals(a.status, b.status) && java.util.Objects.equals(a.whoseTurn, b.whoseTurn)
                && normHash(a.hash).equals(normHash(b.hash));
    }

