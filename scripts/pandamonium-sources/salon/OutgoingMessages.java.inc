    private String outgoingDelivery(MailDb.Contact contact,String preference) {
        if("chain".equals(preference)) return "chain";
        if("maxima".equals(preference)) return "maxima";
        return contact.mxaddr!=null && !contact.mxaddr.isEmpty()?"maxima":"chain";
    }

    private void sendDm(String text,String mediaRef,String mediaMime) {
        String quoted=threadPeer.equals(replyPeer)?replyBody:"";
        queueDm(text,mediaRef,mediaMime,quoted,replyFrom,"","",true);
    }

    /** Capture the authenticated sender and complete wire payload before accepting the draft. */
    private void queueDm(String text,String mediaRef,String mediaMime,String quoted,String quoteAuthor,
            String requiredDelivery,String requiredAddress,boolean consumeComposer) {
        if(threadPeer.isEmpty()) return;
        String body=text==null?"":text.trim();
        if(body.isEmpty() && (mediaRef==null || mediaRef.isEmpty())) { toast(MSG_EMPTY); return; }
        MailDb db=MailDb.get(this);MailDb.Contact contact=db.contact(threadPeer);
        if(contact==null) { toast(MSG_CONTACT); return; }
        final MailDb.Message outgoing=new MailDb.Message();
        try {
            synchronized(SalonComms.class) { synchronized(SalonStore.class) {
                String delivery=requiredDelivery.isEmpty()?outgoingDelivery(contact,SalonStore.get(this,XPORT_PREF+threadPeer)):requiredDelivery;
                if("maxima".equals(delivery) && (contact.mxaddr==null || contact.mxaddr.isEmpty())) { toast(MSG_NO_MAXIMA); return; }
                String destination=requiredAddress.isEmpty()?contact.addr:requiredAddress;
                if("chain".equals(delivery) && !Args.isAddr(destination)) { toast(MSG_NO_DELIVERY); return; }
                outgoing.sender=SalonComms.publicId(this);
                String handle="@"+SalonStore.get(this,"handle"),address=SalonStore.get(this,"tipaddr");
                outgoing.coinid="me-"+java.util.UUID.randomUUID();outgoing.peerpk=threadPeer;
                outgoing.body=body;outgoing.media=mediaRef==null?"":mediaRef;outgoing.mime=mediaMime==null?"":mediaMime;
                outgoing.ts=System.currentTimeMillis()/1000;outgoing.mine=true;outgoing.valid=true;outgoing.status=MailDb.PENDING;
                outgoing.replyBody=quoted==null?"":quoted;outgoing.replyFrom=quoteAuthor==null?"":quoteAuthor;
                outgoing.delivery=delivery;outgoing.destination=destination==null?"":destination;outgoing.phase="queued";
                JSONObject wire=MinimaMail.compose(handle,address,body,outgoing.media,outgoing.mime,outgoing.ts);
                wire.put("id",outgoing.coinid);
                String mx=MaximaLink.myAddresses(this);if(mx!=null && !mx.isEmpty())wire.put("mxaddr",mx);
                if(!outgoing.replyBody.isEmpty())wire.put("replybody",outgoing.replyBody)
                        .put("replyfrom","You".equals(outgoing.replyFrom)?handle:"You");
                outgoing.wire=wire.toString();
                if(!db.enqueue(outgoing,contact)) { toast(MSG_STORAGE); return; }
            } }
        } catch(SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); return; }
        catch(Exception unavailable) { toast(MSG_STORAGE); return; }
        if(consumeComposer) {
            replyBody="";replyFrom="";replyPeer="";
            if(outgoing.media.isEmpty())threadDraft="";
        }
        render();
        if("maxima".equals(outgoing.delivery) && !MaximaLink.isReady(this))toast(MSG_QUEUED);
        try { io.execute(()->dispatchOutgoing(outgoing)); }
        catch(java.util.concurrent.RejectedExecutionException unavailable) { toast(MSG_QUEUED); }
    }

    private boolean outgoingOwner(MailDb.Message m) {
        return m.sender!=null && !m.sender.isEmpty() && m.sender.equals(SalonStore.get(this,"msgpk"));
    }
    private void outgoingNotice(MailDb.Message m,CharSequence message) {
        runOnUiThread(()->{
            if(isFinishing() || isDestroyed() || !outgoingOwner(m))return;
            if(screen==Screen.THREAD && m.peerpk.equals(threadPeer))render();
            toast(message);
        });
    }

    private void completeOutgoing(MailDb db,MailDb.Message m,String attempt,boolean accepted,CharSequence notice) {
        try { if(db.finishAttempt(m,attempt,accepted))outgoingNotice(m,notice); }
        catch(RuntimeException unavailable) { outgoingNotice(m,MSG_STORAGE); }
    }

    /** Frozen delivery never follows a later preference change or falls back from Maxima to chain. */
    private void dispatchOutgoing(MailDb.Message m) {
        if(isFinishing() || isDestroyed())return;
        final MailDb db=MailDb.get(this);
        try {
            synchronized(SalonComms.class) {
                if(!outgoingOwner(m))return;
                JSONObject wire=new JSONObject(m.wire);
                if(!m.coinid.equals(wire.optString("id")))return;
                if("maxima".equals(m.delivery)) {
                    MailDb.Contact contact=db.contact(m.peerpk);
                    if(!MaximaLink.isReady(this) || contact==null || contact.mxaddr==null || contact.mxaddr.isEmpty())return;
                    final LocalEcCryptoProvider identity=SalonComms.crypto(this);
                    if(!m.sender.equals(identity.identity()))throw new SalonComms.IdentityUnavailable();
                    String sealed=identity.seal(m.peerpk,m.wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if(sealed==null || sealed.isEmpty()) { outgoingNotice(m,MSG_SEAL); return; }
                    if(!db.beginAttempt(m))return;
                    final String attempt=m.attempt;
                    MaximaLink.SendCb result=new MaximaLink.SendCb() {
                        public void onSent(String status,boolean held) {
                            completeOutgoing(db,m,attempt,true,held?MSG_HELD:MSG_SENT);
                        }
                        public void onFailed(String ignored) {
                            completeOutgoing(db,m,attempt,false,MSG_RETRY);
                        }
                    };
                    try { MaximaLink.sendDm(this,contact.mxaddr,sealed,result); }
                    catch(RuntimeException unavailable) { result.onFailed(""); }
                } else if("chain".equals(m.delivery) && nodeUp && Args.isAddr(m.destination)) {
                    final LocalEcCryptoProvider identity=SalonComms.crypto(this);
                    if(!m.sender.equals(identity.identity()))throw new SalonComms.IdentityUnavailable();
                    // The checked database claim becomes UNCERTAIN before any non-idempotent send.
                    if(!db.beginAttempt(m))return;
                    final String attempt=m.attempt;
                    MinimaMail.Cb result=new MinimaMail.Cb() {
                        public void onSent(String txpowid) {
                            completeOutgoing(db,m,attempt,true,MSG_MINING);
                        }
                        public void onFailed(String ignored) {
                            completeOutgoing(db,m,attempt,false,MSG_UNKNOWN);
                        }
                    };
                    try { MinimaMail.send(node,identity,m.destination,m.peerpk,wire,result); }
                    catch(RuntimeException unavailable) { result.onFailed(""); }
                }
            }
        } catch(SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); }
        catch(Exception unavailable) { outgoingNotice(m,MSG_STORAGE); }
    }

    private void retryOutbox() {
        if(isFinishing() || isDestroyed())return;
        try { io.execute(()->{
            try { for(MailDb.Message m:MailDb.get(this).pendingOutbox())dispatchOutgoing(m); }
            catch(Exception unavailable) { runOnUiThread(()->{if(!isFinishing() && !isDestroyed())toast(MSG_STORAGE);}); }
        }); } catch(java.util.concurrent.RejectedExecutionException unavailable) { /* Durable queue remains for next session. */ }
    }

    private androidx.appcompat.app.AlertDialog outboxRetryDialog;
    private void cancelOutboxDialog() {
        if(outboxRetryDialog!=null){androidx.appcompat.app.AlertDialog dialog=outboxRetryDialog;outboxRetryDialog=null;dialog.dismiss();}
    }
    private CharSequence outgoingStatus(MailDb.Message m) {
        if(!m.mine || m.status==MailDb.SENT)return socialTime(m.ts);
        if(m.status==MailDb.UNCERTAIN)return MSG_UNKNOWN;
        return outgoingOwner(m)?MSG_SENDING:MSG_OWNER;
    }
    private void reviewOutgoing(MailDb.Message m) {
        if(outboxRetryDialog!=null || !m.mine || screen!=Screen.THREAD || !m.peerpk.equals(threadPeer))return;
        MailDb.Message stored=MailDb.get(this).message(m.coinid);
        if(stored==null || stored.status==MailDb.SENT)return;
        final String owner;
        try { owner=backupOwner(); } catch(Exception unavailable) { identityUnavailable();return; }
        MailDb.Contact contact=MailDb.get(this).contact(m.peerpk);if(contact==null)return;
        final String delivery="maxima".equals(m.delivery)||"chain".equals(m.delivery)?m.delivery:outgoingDelivery(contact,SalonStore.get(this,XPORT_PREF+m.peerpk));
        final String destination="chain".equals(m.delivery)?m.destination:contact.addr;
        final int epoch=renderEpoch;
        outboxRetryDialog=new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(MSG_REVIEW).setMessage(MSG_WARNING)
                .setNegativeButton(MSG_CANCEL,null)
                .setPositiveButton(MSG_COPY,(dialog,which)->{
                    if(outboxRetryDialog!=dialog)return;outboxRetryDialog=null;
                    if(isFinishing() || isDestroyed() || screen!=Screen.THREAD || epoch!=renderEpoch || !m.peerpk.equals(threadPeer))return;
                    try { if(!owner.equals(backupOwner())) { toast(MSG_CHANGED);return; } }
                    catch(Exception unavailable) { identityUnavailable();return; }
                    MailDb.Message latest=MailDb.get(this).message(m.coinid);
                    if(latest==null || latest.status==MailDb.SENT) { toast(MSG_SENT);return; }
                    queueDm(m.body,m.media,m.mime,m.replyBody,m.replyFrom,delivery,destination==null?"":destination,false);
                }).create();
        outboxRetryDialog.setOnDismissListener(dialog->{if(outboxRetryDialog==dialog)outboxRetryDialog=null;});outboxRetryDialog.show();
    }
