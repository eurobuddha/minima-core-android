    /** Durable public transaction intents. No retention cap: unresolved evidence must survive restart. */
    static final String ATTEMPTS = "transaction_attempts";
    // A process nonce distinguishes a killed sender from another still-live Activity/helper.
    private static final String PREPARATION_PROCESS = java.util.UUID.randomUUID().toString().replace("-","");
    private final String preparationOwner = PREPARATION_PROCESS+":"+java.util.UUID.randomUUID().toString().replace("-","");
    private volatile boolean preparationClosed;
    static final String PREPARED = "PREPARED", POSTING = "POSTING", UNKNOWN = "UNKNOWN",
            POSTED = "POSTED", CONFIRMED = "CONFIRMED", NOT_SUBMITTED = "NOT_SUBMITTED";
    static final class Attempt {
        final String id, handle, phase, owner;
        final org.json.JSONObject intent, receipt;
        Attempt(String id,String handle,String phase,String intent,String receipt,String owner) {
            if(owner==null||!("legacy".equals(owner)||owner.matches("[0-9a-f]{32}:[0-9a-f]{32}")))throw TransactionEvidence.invalid();
            this.owner=owner;
            if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}")||handle==null||!handle.matches("[A-Za-z0-9_-]{1,100}")
                    ||!java.util.Arrays.asList(PREPARED,POSTING,UNKNOWN,POSTED,CONFIRMED,NOT_SUBMITTED).contains(phase))throw TransactionEvidence.invalid();
            this.id=id;this.handle=handle;this.phase=phase;this.intent=TransactionEvidence.storedIntent(intent);
            if(receipt==null)throw TransactionEvidence.invalid();
            this.receipt=receipt.isEmpty()?null:TransactionEvidence.storedObject(receipt);
            if((POSTED.equals(phase)||CONFIRMED.equals(phase))!=(this.receipt!=null))throw TransactionEvidence.invalid();
            if(CONFIRMED.equals(phase)) {
                try { TransactionEvidence.inclusion(new org.json.JSONObject().put("status",true).put("response",this.receipt.optJSONObject("inclusion"))); }
                catch(org.json.JSONException e) { throw TransactionEvidence.invalid(); }
            }
            if(this.receipt!=null)TransactionEvidence.storedReceipt(this.receipt,this.intent);
        }
    }
    private static void createAttempts(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS "+ATTEMPTS+" (internalid TEXT PRIMARY KEY NOT NULL, handle TEXT NOT NULL UNIQUE, phase TEXT NOT NULL, intent TEXT NOT NULL, receipt TEXT NOT NULL, owner TEXT NOT NULL DEFAULT 'legacy')");
    }
    private java.util.List<Attempt> attempts(SQLiteDatabase db) {
        java.util.List<Attempt> result=new java.util.ArrayList<>();
        try(Cursor cursor=db.rawQuery("SELECT internalid,handle,phase,intent,receipt,owner FROM "+ATTEMPTS,null)) {
            while(cursor.moveToNext())result.add(new Attempt(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getString(4),cursor.getString(5)));
        }
        return result;
    }
    java.util.List<Attempt> transactionAttempts() { return attempts(getReadableDatabase()); }
    /** Node-selected inputs must not bypass custom transaction reservations. */
    boolean hasUnresolvedAttempt(String tokenid) {
        if(!Util.isValidHexId(tokenid))throw TransactionEvidence.invalid();
        for(Attempt attempt:transactionAttempts())if(!released(attempt.phase)
                &&(BurialEvidence.same(tokenid,attempt.intent.optString("tokenid"))
                ||attempt.intent.has("companion")&&BurialEvidence.same(tokenid,attempt.intent.optJSONObject("companion").optString("tokenid"))))return true;
        return false;
    }
    /** Atomic read/check/write: two builders, even through different helpers, cannot reserve the same input. */
    boolean prepareAttempt(String id,String handle,org.json.JSONObject intent) {
        if(preparationClosed)return false;
        Attempt proposed=new Attempt(id,handle,PREPARED,intent.toString(),"",preparationOwner);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            if(preparationClosed)return false;
            java.util.Set<String> mine=TransactionEvidence.inputIds(proposed.intent);
            for(Attempt current:attempts(db)) {
                if(current.id.equals(id)||current.handle.equals(handle))return false;
                if(!released(current.phase)&&!java.util.Collections.disjoint(mine,TransactionEvidence.inputIds(current.intent)))return false;
            }
            // The display row must have been saved too. Missing rows never permit signing.
            try(Cursor c=db.rawQuery("SELECT internalid FROM "+TABLE+" WHERE internalid=?",new String[]{id})) {if(!c.moveToFirst())return false;}
            ContentValues v=new ContentValues();v.put("internalid",id);v.put("handle",handle);v.put("phase",PREPARED);
            v.put("intent",proposed.intent.toString());v.put("receipt","");v.put("owner",preparationOwner);db.insertOrThrow(ATTEMPTS,null,v);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return true;
    }
    /** Same acknowledged pre-post boundary as PandaDEX Pending; no callback can recreate a missing row. */
    boolean markAttempt(String id,String phase,org.json.JSONObject reply) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Attempt found=null;for(Attempt a:attempts(db))if(a.id.equals(id)){found=a;break;}
            if(found==null)return false;
            if(PREPARED.equals(found.phase)&&POSTING.equals(phase)
                    &&(preparationClosed||!preparationOwner.equals(found.owner)))return false;
            boolean valid=PREPARED.equals(found.phase)&&(POSTING.equals(phase)||NOT_SUBMITTED.equals(phase))
                    ||POSTING.equals(found.phase)&&(UNKNOWN.equals(phase)||POSTED.equals(phase)||NOT_SUBMITTED.equals(phase))
                    ||UNKNOWN.equals(found.phase)&&UNKNOWN.equals(phase);
            if(!valid)return false;
            String receipt=POSTED.equals(phase)?TransactionEvidence.publicReceipt(reply,found.intent).toString():"";
            new Attempt(found.id,found.handle,phase,found.intent.toString(),receipt,found.owner);
            ContentValues values=new ContentValues();values.put("phase",phase);values.put("receipt",receipt);
            if(db.update(ATTEMPTS,values,"internalid=? AND phase=?",new String[]{id,found.phase})!=1)return false;
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return true;
    }
    private static boolean released(String phase) { return NOT_SUBMITTED.equals(phase)||CONFIRMED.equals(phase); }
    /** Commit the exact public receipt, inclusion evidence and display status together. */
    boolean confirmAttempt(String id,org.json.JSONObject reply,org.json.JSONObject chain) {
        org.json.JSONObject inclusion=TransactionEvidence.inclusion(chain);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Attempt found=null;for(Attempt a:attempts(db))if(a.id.equals(id)){found=a;break;}
            if(found==null||!TransactionRecovery.recoverable(found.phase))return false;
            org.json.JSONObject receipt=TransactionEvidence.publicReceipt(reply,found.intent);
            try { receipt.put("inclusion",inclusion); }
            catch(org.json.JSONException e){throw TransactionEvidence.invalid();}
            new Attempt(found.id,found.handle,CONFIRMED,found.intent.toString(),receipt.toString(),found.owner);
            ContentValues values=new ContentValues();values.put("phase",CONFIRMED);values.put("receipt",receipt.toString());
            if(db.update(ATTEMPTS,values,"internalid=? AND phase=?",new String[]{id,found.phase})!=1)return false;
            ContentValues display=new ContentValues();display.put("status",STATUS_CONFIRMED);display.put("txnid",receipt.optString("txpowid"));display.put("note","");
            if(db.update(TABLE,display,"internalid=?",new String[]{id})!=1)return false;
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return true;
    }

    /** The owning client must stop callbacks before its never-posted work can be abandoned. */
    void abandonPreparations(NodeApi owner) {
        if(owner==null||!owner.isClosed())throw new IllegalStateException("Transaction client is still active");
        preparationClosed=true;
        releasePreparations(getWritableDatabase(),true);
    }
    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Old process callbacks cannot survive Android process death. POSTING/UNKNOWN remain held.
        try { releasePreparations(db,false); }
        catch (RuntimeException unavailable) { /* Preserve evidence; financial methods still fail closed, while history remains readable. */ }
    }
    private void releasePreparations(SQLiteDatabase db,boolean ownClosedClient) {
        db.beginTransaction();
        try {
            for(Attempt attempt:attempts(db)) {
                boolean abandoned=ownClosedClient?preparationOwner.equals(attempt.owner)
                        :!attempt.owner.startsWith(PREPARATION_PROCESS+":");
                if(!abandoned||!PREPARED.equals(attempt.phase))continue;
                ContentValues phase=new ContentValues();phase.put("phase",NOT_SUBMITTED);
                if(db.update(ATTEMPTS,phase,"internalid=? AND phase=?",new String[]{attempt.id,PREPARED})!=1)
                    throw new IllegalStateException("Preparation changed during recovery");
                ContentValues display=new ContentValues();display.put("status",STATUS_ERROR);display.put("note",TransactionEvidence.NOT_SENT);
                if(db.update(TABLE,display,"internalid=?",new String[]{attempt.id})!=1)
                    throw new IllegalStateException("Preparation history is missing");
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
