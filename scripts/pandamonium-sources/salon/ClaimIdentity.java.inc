    /** Setup follows the same captured-owner lifetime as profile publishing. */
    private static final class IdentityClaim {
        String snapshot, url;
        final String handle, name, bio, signer, html;
        final Hosting.Profile host;
        final JSONObject profile;
        final int epoch;
        final NodeApi node;
        final TextView status;
        volatile boolean cancelled;
        int request;
        Runnable poll;
        IdentityClaim(String snapshot, String handle, String name, String bio, String signer,
                Hosting.Profile host, JSONObject profile, String html, int epoch, NodeApi node, TextView status) {
            this.snapshot=snapshot; this.handle=handle; this.name=name; this.bio=bio; this.signer=signer;
            this.host=host; this.profile=profile; this.html=html; this.epoch=epoch; this.node=node; this.status=status;
        }
    }
    private EditText claimHandleField, claimNameField, claimBioField;
    private TextView claimRetryButton;
    private androidx.appcompat.app.AlertDialog claimRetryDialog;
    private IdentityClaim identityClaim;
    private Object identityAdoption;
    private final android.os.Handler claimTimer = new android.os.Handler(android.os.Looper.getMainLooper());

    private void cancelIdentityClaim() {
        if(claimRetryDialog!=null){androidx.appcompat.app.AlertDialog dialog=claimRetryDialog;claimRetryDialog=null;dialog.dismiss();}
        IdentityClaim op=identityClaim;
        identityClaim=null; identityAdoption=null; claiming=false;
        if(op!=null) { op.cancelled=true; if(op.poll!=null) claimTimer.removeCallbacks(op.poll); }
        setClaimEnabled(true);
    }
    private boolean claimStoredCurrent(IdentityClaim op) {
        if(op.cancelled) return false;
        try {
            Hosting.Profile host=HostingStore.getDefault(this);
            return op.snapshot.equals(SalonStore.publicationSnapshot(SalonStore.identityRecord(this)))
                    && host!=null && op.host.j.toString().equals(host.j.toString());
        } catch(Exception unavailable) { return false; }
    }
    private boolean claimCurrent(IdentityClaim op) {
        return identityClaim==op && !isFinishing() && !isDestroyed() && op.epoch==renderEpoch
                && nodeUp && node==op.node && pubkey.equals(op.signer) && claimStoredCurrent(op);
    }
    private boolean requireClaim(IdentityClaim op) {
        if(claimCurrent(op)) return true;
        if(identityClaim==op) endClaim(op, MSG_CHANGED);
        return false;
    }
    private void endClaim(IdentityClaim op, CharSequence message) {
        if(identityClaim!=op) return;
        cancelIdentityClaim();
        if(!isFinishing() && !isDestroyed() && op.epoch==renderEpoch) publicationStatus(op.status,message);
    }
    private boolean saveClaim(IdentityClaim op, JSONObject changes) {
        synchronized(SalonStore.class) {
            if(!requireClaim(op)) return false;
            if(!SalonStore.saveClaim(this,op.snapshot,changes)) { endClaim(op,MSG_STORAGE); return false; }
            op.snapshot=SalonStore.publicationSnapshot(SalonStore.identityRecord(this));
            return true;
        }
    }
    private void claimIdentity(String handle,String name,String bio,TextView status) {
        if(claiming) { publicationStatus(status,MSG_BUSY); return; }
        handle=Hosting.slug(handle);
        if(handle.isEmpty() || name.trim().isEmpty()) { publicationStatus(status,MSG_REQUIRED); return; }
        if(!Args.isSafeMeta(name) || !Args.isSafeMeta(bio)) { publicationStatus(status,MSG_META); return; }
        Hosting.Profile host=HostingStore.getDefault(this);
        if(host==null) { publicationStatus(status,MSG_HOST); return; }
        if(!nodeUp || node==null) { publicationStatus(status,MSG_NODE); return; }
        if(!Args.isTokenId(pubkey)) { publicationStatus(status,MSG_KEY); fetchPubkey(); return; }
        final IdentityClaim op;
        final JSONObject pending;
        try {
            JSONObject current=SalonStore.identityRecord(this);
            if(!current.optString("tokenid").isEmpty()) { publicationStatus(status,MSG_CHANGED); return; }
            if(current.has("claimPending") && !(current.opt("claimPending") instanceof JSONObject)) {
                publicationStatus(status,MSG_STORAGE); return;
            }
            pending=current.optJSONObject("claimPending");
            // A prior submission can still confirm after a timeout/restart. Resume reads only.
            if(pending!=null && (!handle.equals(pending.optString("handle"))
                    || !pubkey.equals(pending.optString("signer")) || pending.optString("url").isEmpty())) {
                publicationStatus(status,MSG_PENDING); return;
            }
            JSONObject profile=pending==null?buildProfileJson():new JSONObject();
            profile.put("handle",handle).put("name",name.trim()).put("bio",bio.trim());
            op=new IdentityClaim(SalonStore.publicationSnapshot(current),handle,name.trim(),bio.trim(),pubkey,
                    new Hosting.Profile(new JSONObject(host.j.toString())),profile,pending==null?salonHtml():"",renderEpoch,node,status);
        } catch(Exception unavailable) { publicationStatus(status,MSG_STORAGE); return; }
        identityAdoption=null; identityClaim=op; claiming=true; setClaimEnabled(false);
        if(pending!=null) {
            op.url=pending.optString("url"); publicationStatus(status,MSG_CONFIRMING); pollForIdentity(op,0); return;
        }
        JSONObject draft=new JSONObject();putJson(draft,"handle",op.handle);putJson(draft,"name",op.name);putJson(draft,"bio",op.bio);
        if(!saveClaim(op,draft)) return;
        publicationStatus(status,MSG_PUBLISHING);
        try {
            io.execute(()->{
                if(!claimStoredCurrent(op)) { runOnUiThread(()->endClaim(op,MSG_CHANGED)); return; }
                try {
                    String url=ProfileHosting.upload(op.host,op.handle,op.profile.toString(),op.html);
                    runOnUiThread(()->{
                        if(!requireClaim(op)) return;
                        if(url==null || url.isEmpty() || !Args.isSafeMeta(url)) { endClaim(op,MSG_STORAGE); return; }
                        JSONObject change=new JSONObject();putJson(change,"profileUrl",url);
                        if(!saveClaim(op,change)) return;
                        op.url=url;publicationStatus(op.status,MSG_CHECKING);adoptOrMint(op);
                    });
                } catch(SftpUploader.HostKeyUnverified untrusted) {
                    runOnUiThread(()->{synchronized(SalonStore.class){if(requireClaim(op)){endClaim(op,MSG_STORAGE);promptTrustHostKey(op.host,untrusted.fingerprint,op.status);}}});
                } catch(Exception unavailable) { runOnUiThread(()->endClaim(op,MSG_HOST_FAILED)); }
            });
        } catch(java.util.concurrent.RejectedExecutionException unavailable) { endClaim(op,MSG_STORAGE); }
    }
    private void adoptOrMint(IdentityClaim op) {
        if(!requireClaim(op)) return;
        final int request=++op.request;
        NodeApi.Cb result=new NodeApi.Cb() {
            public void onResult(JSONObject reply) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return; op.request++;
                try {
                    JSONObject existing=IdentityBalance.find(reply,op.handle);
                    if(existing!=null) finishClaim(op,existing.optString("tokenid"));
                    else mintIdentity(op);
                } catch(IllegalArgumentException unavailable) { endClaim(op,MSG_LOOKUP); }
            }); }
            public void onError(String ignored) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return;op.request++;endClaim(op,MSG_LOOKUP);
            }); }
        };
        try { op.node.cmd("balance",result); } catch(RuntimeException unavailable) { result.onError(""); }
    }
    private void mintIdentity(IdentityClaim op) {
        if(!requireClaim(op)) return;
        JSONObject pending=new JSONObject(),changes=new JSONObject(),meta=new JSONObject();
        try {
            pending.put("handle",op.handle).put("url",op.url).put("signer",op.signer);
            changes.put("claimPending",pending);
            meta.put("salon","1").put("handle",op.handle).put("name",op.name).put("url",op.url).put("bio",op.bio);
        } catch(Exception unavailable) { endClaim(op,MSG_STORAGE); return; }
        // Reuse Atelier's save-before-post rule. An unconfirmed attempt is never auto-reposted.
        if(!saveClaim(op,changes)) return;
        publicationStatus(op.status,MSG_MINTING);
        final int request=++op.request;
        NodeApi.Cb result=new NodeApi.Cb() {
            public void onResult(JSONObject reply) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return;op.request++;
                if(reply!=null && Boolean.FALSE.equals(reply.opt("status"))) {
                    JSONObject clear=new JSONObject();try{clear.put("claimPending",JSONObject.NULL);}catch(Exception ignored){}
                    if(saveClaim(op,clear)) endClaim(op,MSG_REJECTED);
                } else if(reply!=null && Boolean.TRUE.equals(reply.opt("status"))) {
                    publicationStatus(op.status,MSG_CONFIRMING);pollForIdentity(op,0);
                } else endClaim(op,MSG_PENDING);
            }); }
            public void onError(String ignored) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return;op.request++;endClaim(op,MSG_PENDING);
            }); }
        };
        try {
            synchronized(SalonStore.class) {
                if(!requireClaim(op)) return;
                op.node.cmd("tokencreate name:"+meta+" amount:1 decimals:0 signtoken:"+op.signer,result);
            }
        }
        catch(RuntimeException unavailable) { result.onError(""); }
    }
    private void pollForIdentity(IdentityClaim op,int tries) {
        if(!requireClaim(op)) return;
        final int request=++op.request;
        NodeApi.Cb result=new NodeApi.Cb() {
            public void onResult(JSONObject reply) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return;op.request++;
                try {
                    JSONObject row=IdentityBalance.find(reply,op.handle);
                    if(row!=null) { finishClaim(op,row.optString("tokenid")); return; }
                } catch(IllegalArgumentException unavailable) { /* Unknown balance is never permission to mint. */ }
                nextClaimPoll(op,tries);
            }); }
            public void onError(String ignored) { runOnUiThread(()->{
                if(op.request!=request || !requireClaim(op)) return;op.request++;nextClaimPoll(op,tries);
            }); }
        };
        try { op.node.cmd("balance",result); } catch(RuntimeException unavailable) { result.onError(""); }
    }
    private void nextClaimPoll(IdentityClaim op,int tries) {
        if(tries>=30) { endClaim(op,MSG_PENDING); return; }
        op.poll=()->pollForIdentity(op,tries+1);claimTimer.postDelayed(op.poll,4000);
    }
    private void finishClaim(IdentityClaim op,String tokenid) {
        if(!Args.isTokenId(tokenid) || new java.math.BigInteger(tokenid.substring(2),16).signum()==0) { endClaim(op,MSG_LOOKUP); return; }
        JSONObject change=new JSONObject();putJson(change,"tokenid",tokenid);
        try{change.put("claimPending",JSONObject.NULL);}catch(Exception ignored){}
        if(!saveClaim(op,change)) return;
        cancelIdentityClaim();publishSalon(null);toast(MSG_OPENED);go(Screen.HOME);
    }
    private void setClaimEnabled(boolean on) {
        if(claimBtn!=null){claimBtn.setEnabled(on);claimBtn.setAlpha(on?1f:0.4f);}
        for(EditText field:new EditText[]{claimHandleField,claimNameField,claimBioField}) if(field!=null) field.setEnabled(on);
    }

    /** Explicit permission for another attempt; this dialog never sends a command. */
    private void confirmClaimRetry() {
        if(claiming || claimRetryDialog!=null || !nodeUp || isFinishing() || isDestroyed()) return;
        final String snapshot;
        try {
            JSONObject current=SalonStore.identityRecord(this);
            if(!(current.opt("claimPending") instanceof JSONObject)) return;
            snapshot=SalonStore.publicationSnapshot(current);
        } catch(Exception unavailable) { toast(MSG_STORAGE); return; }
        final int epoch=renderEpoch;final NodeApi requestedNode=node;
        claimRetryDialog=new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(MSG_RETRY_TITLE).setMessage(MSG_RETRY_WARNING)
                .setNegativeButton(MSG_CANCEL,null)
                .setPositiveButton(MSG_RETRY_TITLE,(dialog,which)->{
                    if(claimRetryDialog!=dialog) return;claimRetryDialog=null;
                    if(claiming || isFinishing() || isDestroyed() || epoch!=renderEpoch || node!=requestedNode || !nodeUp) return;
                    try {
                        if(!snapshot.equals(SalonStore.publicationSnapshot(SalonStore.identityRecord(this)))) { toast(MSG_CHANGED); return; }
                        JSONObject clear=new JSONObject().put("claimPending",JSONObject.NULL);
                        if(!SalonStore.saveClaim(this,snapshot,clear)) { toast(MSG_STORAGE); return; }
                        if(claimRetryButton!=null) claimRetryButton.setVisibility(View.GONE);
                        toast(MSG_RETRY_READY);
                    } catch(Exception unavailable) { toast(MSG_STORAGE); }
                }).create();
        claimRetryDialog.setOnDismissListener(dialog->{if(claimRetryDialog==dialog)claimRetryDialog=null;});
        claimRetryDialog.show();
    }

    /** Automatic adoption may finish only for the empty account that requested the balance. */
    private void checkIdentityAdoption() {
        if(!nodeUp || node==null || adoptChecked || identityAdoption!=null || claiming || SalonStore.hasIdentity(this) || isFinishing() || isDestroyed()) return;
        final String snapshot;
        try { snapshot=SalonStore.publicationSnapshot(SalonStore.identityRecord(this)); }
        catch(Exception unavailable) { return; }
        final Object request=new Object();identityAdoption=request;
        final NodeApi requestedNode=node;final int epoch=renderEpoch;
        NodeApi.Cb result=new NodeApi.Cb() {
            public void onResult(JSONObject reply) { runOnUiThread(()->{
                if(identityAdoption!=request) return; identityAdoption=null;
                if(isFinishing() || isDestroyed() || epoch!=renderEpoch || node!=requestedNode || !nodeUp || claiming) return;
                try {
                    JSONObject current=SalonStore.identityRecord(MainActivity.this);
                    if(!snapshot.equals(SalonStore.publicationSnapshot(current))) return;
                    JSONObject pending=current.optJSONObject("claimPending");
                    JSONObject row=IdentityBalance.find(reply,pending==null?null:pending.optString("handle"));
                    if(row==null) { adoptChecked=true; return; }
                    String tokenid=row.optString("tokenid");
                    if(new java.math.BigInteger(tokenid.substring(2),16).signum()==0) return;
                    JSONObject meta=row.getJSONObject("token"),change=new JSONObject();
                    change.put("tokenid",tokenid).put("handle",meta.getString("handle")).put("profileUrl",meta.optString("url"));
                    for(String key:new String[]{"name","bio"}) if(current.optString(key).isEmpty()) change.put(key,meta.optString(key));
                    change.put("claimPending",JSONObject.NULL);
                    if(!SalonStore.saveClaim(MainActivity.this,snapshot,change)) { toast(MSG_STORAGE); return; }
                    adoptChecked=true;
                    if(screen==Screen.HOME || screen==Screen.ONBOARD || screen==Screen.FEED) go(Screen.HOME);
                    hydrateFromHosted(meta.optString("url"),false);
                } catch(Exception unavailable) { /* A failed lookup cannot adopt or mint. */ }
            }); }
            public void onError(String ignored) { runOnUiThread(()->{if(identityAdoption==request) identityAdoption=null;}); }
        };
        try { requestedNode.cmd("balance",result); } catch(RuntimeException unavailable) { result.onError(""); }
    }
