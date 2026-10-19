    /** Save every recovery record together before the first leg can leave the app. */
    public synchronized void prepareInitiator(Swap swap, String secret, String requestAmount, String requestToken) {
        if (swap == null || swap.hash == null || secret == null || secret.isEmpty())
            throw new IllegalArgumentException("Missing swap recovery data");
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            // An existing hash may only retain its original preimage.
            if (!insertSecret(swap.hash, secret) && !secret.equals(getSecret(swap.hash)))
                throw new android.database.sqlite.SQLiteException("Swap secret could not be saved");
            insertMyHtlc(swap.hash, requestAmount, requestToken);
            String[] request = getRequest(swap.hash);
            if (request == null || !java.util.Objects.equals(requestAmount, request[0])
                    || !java.util.Objects.equals(requestToken, request[1]))
                throw new android.database.sqlite.SQLiteException("Swap request could not be saved");
            upsertSwap(swap);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

