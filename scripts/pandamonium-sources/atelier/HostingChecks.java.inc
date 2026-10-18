    /** Salon's frozen saved-destination checks, scoped to Atelier's screen lifetime. */
    private static final class HostingCheck {
        final Hosting.Profile profile, editor;
        final long generation;
        volatile boolean cancelled;
        HostingCheck(Hosting.Profile profile, Hosting.Profile editor, long generation) {
            this.profile=profile; this.editor=editor; this.generation=generation;
        }
    }
    private volatile HostingCheck hostingCheck;
    private boolean hostingStopped;
    private androidx.appcompat.app.AlertDialog hostingTrustDialog, hostingResultDialog;

    private void cancelHostingCheck() {
        HostingCheck op=hostingCheck; hostingCheck=null;
        if(op!=null) op.cancelled=true;
        if(hostingTrustDialog!=null) {
            androidx.appcompat.app.AlertDialog dialog=hostingTrustDialog;hostingTrustDialog=null;dialog.dismiss();
        }
        if(hostingResultDialog!=null) {
            androidx.appcompat.app.AlertDialog dialog=hostingResultDialog;hostingResultDialog=null;dialog.dismiss();
        }
    }
    private boolean hostingStoredCurrent(HostingCheck op) {
        if(op.cancelled || hostingCheck!=op) return false;
        try { return HostingStore.matches(this,op.profile); }
        catch(Exception unavailable) { return false; }
    }
    private boolean hostingCurrent(HostingCheck op) {
        if(hostingStopped || isFinishing() || isDestroyed() || op.generation!=screenGeneration) return false;
        try {
            return HostingStore.profileSnapshot(op.editor).equals(HostingStore.profileSnapshot(op.profile))
                    && hostingStoredCurrent(op);
        } catch(Exception unavailable) { return false; }
    }
    private HostingCheck beginHostingCheck(Hosting.Profile p) {
        cancelHostingCheck();
        if(hostingStopped || isFinishing() || isDestroyed()) return null;
        try {
            HostingCheck op=new HostingCheck(new Hosting.Profile(new JSONObject(p.j.toString())),p,screenGeneration);
            if(!HostingStore.matches(this,op.profile)) { toast(MSG_CHANGED);return null; }
            hostingCheck=op;return op;
        } catch(Exception unavailable) { toast(MSG_STORAGE);return null; }
    }
    private void finishHostingCheck(HostingCheck op,CharSequence message) {
        if(hostingCheck!=op) return;
        boolean current=hostingCurrent(op);cancelHostingCheck();
        if(current) {
            hostingResultDialog=new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                    .setTitle(MSG_TEST_TITLE).setMessage(message).setPositiveButton(MSG_OK,null).show();
            androidx.appcompat.app.AlertDialog dialog=hostingResultDialog;
            dialog.setOnDismissListener(d->{if(hostingResultDialog==dialog)hostingResultDialog=null;});
        }
    }
    private void testProfile(Hosting.Profile p) {
        HostingCheck op=beginHostingCheck(p);
        if(op==null) return;
        toast(MSG_TESTING_NAME);
        try {
            imageIo.execute(()->{
                if(!hostingStoredCurrent(op)) return;
                CharSequence result;
                try {
                    String nonce="atelier-"+System.currentTimeMillis();
                    String rel="atelier/probe/"+java.util.UUID.randomUUID()+".txt";
                    Hosting.Uploader uploader=Hosting.forProfile(op.profile);
                    String url;
                    try {
                        if(!hostingStoredCurrent(op)) return;
                        url=uploader.putFile(nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8),rel,"text/plain");
                    } finally {
                        if(uploader instanceof AutoCloseable) {
                            try { ((AutoCloseable)uploader).close(); } catch(Exception ignored) { }
                        }
                    }
                    if(!hostingStoredCurrent(op)) return;
                    if(Hosting.TYPE_ARWEAVE.equals(op.profile.type())) {
                        // Turbo's receipt precedes gateway indexing; retain Atelier's existing policy.
                        result=MSG_TURBO_TESTED;
                    } else {
                        Hosting.verifyUrl(url,op.profile);
                        result=MSG_TESTED;
                    }
                } catch(SftpUploader.HostKeyUnverified untrusted) {
                    main.post(()->showHostingTrust(op,untrusted.fingerprint));return;
                } catch(Exception error) {
                    result=MSG_FAILED;
                }
                final CharSequence message=result;
                main.post(()->finishHostingCheck(op,message));
            });
        } catch(java.util.concurrent.RejectedExecutionException unavailable) { finishHostingCheck(op,MSG_STORAGE); }
    }
    /** Image uploads already validate their draft before handing their frozen host here. */
    private void confirmHostKey(Hosting.Profile p,String fp) {
        HostingCheck op=beginHostingCheck(p);
        if(op!=null) showHostingTrust(op,fp);
    }
    private void showHostingTrust(HostingCheck op,String fp) {
        if(!hostingCurrent(op) || !Hosting.TYPE_SFTP.equals(op.profile.type()) || fp==null || fp.isEmpty()
                || !op.profile.cfgStr("hostKeyFp").isEmpty() || hostingTrustDialog!=null) return;
        hostingTrustDialog=new com.eurobuddha.minimacore.i18n.LanguageDialog(this)
                .setTitle(MSG_TRUST_TITLE).setMessage(MSG_TRUST_BODY)
                .setPositiveButton(MSG_PIN,(d,w)->{
                    synchronized(HostingStore.class) {
                        if(!hostingCurrent(op)) { if(hostingCheck==op)cancelHostingCheck();return; }
                        // Consume consent before persistence or retry. Never overwrite a changed destination.
                        cancelHostingCheck();
                        boolean saved;
                        try { saved=HostingStore.pinHostKey(this,op.profile,fp); }
                        catch(Exception unavailable) { saved=false; }
                        if(!saved) { toast(MSG_STORAGE);return; }
                        Hosting.put(op.editor.cfg(),"hostKeyFp",fp);
                        testProfile(op.editor);
                    }
                })
                .setNegativeButton(MSG_CANCEL,(d,w)->{if(hostingCheck==op)cancelHostingCheck();}).show();
        hostingTrustDialog.setOnDismissListener(d->{if(hostingCheck==op)cancelHostingCheck();});
    }
