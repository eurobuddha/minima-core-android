    /** A test owns a frozen destination and the account/screen that requested it. */
    private static final class HostingCheck {
        final String owner;
        final Hosting.Profile profile, editor;
        final int epoch;
        final TextView status;
        volatile boolean cancelled;
        HostingCheck(String owner, Hosting.Profile profile, Hosting.Profile editor, int epoch, TextView status) {
            this.owner=owner; this.profile=profile; this.editor=editor; this.epoch=epoch; this.status=status;
        }
    }
    private volatile HostingCheck hostingCheck;
    private androidx.appcompat.app.AlertDialog hostingTrustDialog;

    private void cancelHostingCheck() {
        HostingCheck op=hostingCheck; hostingCheck=null;
        if(op!=null) op.cancelled=true;
        if(hostingTrustDialog!=null) {
            androidx.appcompat.app.AlertDialog dialog=hostingTrustDialog;hostingTrustDialog=null;dialog.dismiss();
        }
    }
    private boolean hostingStoredCurrent(HostingCheck op) {
        if(op.cancelled || hostingCheck!=op) return false;
        synchronized(SalonStore.class) {
            try { return op.owner.equals(backupOwner()) && HostingStore.matches(this,op.profile); }
            catch(Exception unavailable) { return false; }
        }
    }
    private boolean hostingCurrent(HostingCheck op) {
        if(isFinishing() || isDestroyed() || op.epoch!=renderEpoch) return false;
        try {
            return HostingStore.profileSnapshot(op.editor).equals(HostingStore.profileSnapshot(op.profile))
                    && hostingStoredCurrent(op);
        } catch(Exception unavailable) { return false; }
    }
    private HostingCheck beginHostingCheck(Hosting.Profile p,TextView status) {
        cancelHostingCheck();
        if(isFinishing() || isDestroyed()) return null;
        try {
            synchronized(SalonStore.class) {
                HostingCheck op=new HostingCheck(backupOwner(),new Hosting.Profile(new JSONObject(p.j.toString())),p,renderEpoch,status);
                if(!HostingStore.matches(this,op.profile)) { publicationStatus(status,MSG_CHANGED);return null; }
                hostingCheck=op;return op;
            }
        } catch(Exception unavailable) { publicationStatus(status,MSG_STORAGE);return null; }
    }
    private void finishHostingCheck(HostingCheck op,CharSequence message) {
        if(hostingCheck!=op) return;
        boolean current=hostingCurrent(op);cancelHostingCheck();
        if(current) publicationStatus(op.status,message);
    }
    private void testProfile(Hosting.Profile p) { testProfile(p,null); }
    private void testProfile(Hosting.Profile p,TextView status) {
        HostingCheck op=beginHostingCheck(p,status);
        if(op==null) return;
        publicationStatus(status,status!=null?MSG_TESTING:MSG_TESTING_NAME);
        try {
            io.execute(()->{
                if(!hostingStoredCurrent(op)) return;
                try {
                    String rel="_test/probe-"+java.util.UUID.randomUUID()+".txt";
                    String url;
                    try(Hosting.Uploader up=Hosting.forProfile(op.profile)) {
                        if(!hostingStoredCurrent(op)) return;
                        url=up.putFile("salon-test".getBytes(java.nio.charset.StandardCharsets.UTF_8),rel,"text/plain");
                    }
                    if(!hostingStoredCurrent(op)) return;
                    Hosting.verifyUrl(url,op.profile);
                    runOnUiThread(()->finishHostingCheck(op,MSG_TESTED));
                } catch(SftpUploader.HostKeyUnverified untrusted) {
                    runOnUiThread(()->showHostingTrust(op,untrusted.fingerprint));
                } catch(Exception error) {
                    runOnUiThread(()->finishHostingCheck(op,MSG_FAILED));
                }
            });
        } catch(java.util.concurrent.RejectedExecutionException unavailable) { finishHostingCheck(op,MSG_STORAGE); }
    }
    /** Setup already checks its operation before handing its captured host here. */
    private void promptTrustHostKey(Hosting.Profile p,String fp,TextView status) {
        HostingCheck op=beginHostingCheck(p,status);
        if(op!=null) showHostingTrust(op,fp);
    }
    private void showHostingTrust(HostingCheck op,String fp) {
        if(!hostingCurrent(op) || !Hosting.TYPE_SFTP.equals(op.profile.type()) || fp==null || fp.isEmpty()
                || !op.profile.cfgStr("hostKeyFp").isEmpty() || hostingTrustDialog!=null) return;
        hostingTrustDialog=new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(MSG_TRUST_TITLE).setMessage(MSG_TRUST_BODY)
                .setPositiveButton(MSG_PIN,(d,w)->{
                    synchronized(SalonStore.class) {
                        if(!hostingCurrent(op)) { if(hostingCheck==op)cancelHostingCheck();return; }
                        // Consume consent before touching persistent state or scheduling a retry.
                        cancelHostingCheck();
                        boolean saved;
                        try { saved=HostingStore.pinHostKey(this,op.profile,fp); }
                        catch(Exception unavailable) { saved=false; }
                        if(!saved) { publicationStatus(op.status,MSG_STORAGE);return; }
                        Hosting.put(op.editor.cfg(),"hostKeyFp",fp);
                        testProfile(op.editor,op.status);
                    }
                })
                .setNegativeButton(MSG_CANCEL,(d,w)->finishHostingCheck(op,MSG_NOT_TRUSTED)).show();
        hostingTrustDialog.setOnDismissListener(d->{if(hostingCheck==op)cancelHostingCheck();});
    }
