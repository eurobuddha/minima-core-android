    private final java.util.concurrent.ExecutorService auditWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> preparation;
    private int generation;
    private boolean includeWallet;
    private android.widget.CheckBox walletToggle;
    private AuditSelection selection;
    private KeyAudit.Result lastResult;
    private long lastTip;
    private int prepared;
    private java.util.function.Supplier<String> errorText;
    private final boolean derivationValid=Sha3.selfTest();
    com.eurobuddha.wallet.NodeAuditKeys auditKeys() { return com.eurobuddha.wallet.NodeAuditKeys.NODE; }
    private final Runnable languageChanged = () -> {
        if (container == null) return;
        container.removeAllViews(); buildUi(); setBusy(busy);
        if(errorText!=null) showError(errorText.get());
        if(lastResult!=null) renderResults(lastResult,lastTip);
    };
    String walletKeys() { return com.eurobuddha.wallet.PublicAuditKeys.snapshot(this).toString(); }
    private boolean live(int request) {
        return request==generation && !isFinishing() && !isDestroyed();
    }
    @Override protected void onStop() {
        generation++;
        if(preparation!=null) preparation.cancel(true);
        lastResult=null;selection=null;
        if(resultsBox!=null) {resultsBox.removeAllViews();resultsBox.setVisibility(View.GONE);setBusy(false);}
        super.onStop();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
    }
    private void runAudit() {
        if (busy || !derivationValid) return;
        clearError(); lastResult=null; selection=null; prepared=0;
        setBusy(true);
        resultsBox.removeAllViews(); resultsBox.setVisibility(View.GONE);
        final int request=++generation;
        final boolean walletSelected=includeWallet;
        preparation=auditWorker.submit(() -> {
            try {
                com.eurobuddha.wallet.NodeAuditKeys.Snapshot nodeSnapshot =
                        auditKeys().prepare((done,total) -> runOnUiThread(() -> {
                            if(live(request)) { prepared=done; updateProgress(); }
                        }));
                final String walletSnapshot=walletSelected
                        ? walletKeys() : "[]";
                AuditSelection chosen=new AuditSelection(nodeSnapshot.nodeKeys,nodeSnapshot.derivedKeys,walletSnapshot,walletSelected);
                runOnUiThread(() -> {
                    if(!live(request)) return;
                    selection=chosen;
                    api.audit(chosen.publicKeys,chosen.hexAddresses,new AuditApi.Cb() {
                        @Override public void onResult(List<KeyAudit.Usage> usage,List<KeyAudit.Reuse> reuse,long tip) {
                            if(!live(request)) return;
                            preparation=auditWorker.submit(() -> {
                                try {
                                    if(!auditKeys().current(nodeSnapshot)
                                            || (walletSelected && !walletSnapshot.equals(walletKeys()))) {
                                        auditError(request,Copy::changed); return;
                                    }
                                    KeyAudit.Result result=KeyAudit.join(chosen.keys,usage,reuse,chosen.addresses);
                                    runOnUiThread(() -> {if(live(request)){setBusy(false);renderResults(result,tip);}});
                                } catch(Exception unavailable) {auditError(request,Copy::unavailable);}
                            });
                        }
                        @Override public void onError(String message) {auditError(request,Copy::network);}
                    });
                });
            } catch(Exception unavailable) {auditError(request,Copy::unavailable);}
        });
    }
    private void auditError(int request,java.util.function.Supplier<String> message) {
        runOnUiThread(() -> {if(live(request)){setBusy(false);errorText=message;showError(message.get());}});
    }
    private void updateProgress() {
        if(runLabel!=null) runLabel.setText(busy
                ? (prepared>0 && prepared<96 ? Copy.progress(prepared) : Copy.auditing()) : Copy.run());
    }
    private String sourceLabel(int index) {
        if(selection==null || index<1 || index>selection.origins.size())return "";
        int flags=selection.origins.get(index-1);
        java.util.List<String> names=new ArrayList<>();
        if((flags&AuditSelection.NODE)!=0)names.add(Copy.node());
        if((flags&AuditSelection.CLASSIC)!=0)names.add(Copy.classic());
        if((flags&AuditSelection.BLOCK)!=0)names.add(Copy.block());
        if((flags&AuditSelection.WALLET)!=0)names.add("minimaWallet");
        return String.join(" · ",names);
    }

    /** Group display rows without changing their audit identity or source mapping. */
    private void renderKeySections(LinearLayout table, KeyAudit.Result result) {
        for(int group=0;group<3;group++) {
            java.util.List<KeyAudit.Row> rows=new ArrayList<>();
            for(KeyAudit.Row auditRow:result.rows) {
                int flags=selection.origins.get(auditRow.index-1);
                int rowGroup=flags==AuditSelection.WALLET ? 2
                        : auditRow.family==KeyAudit.Family.BLOCK ? 1 : 0;
                if(rowGroup==group) rows.add(auditRow);
            }
            if(rows.isEmpty())continue;
            LinearLayout section=new LinearLayout(this);
            section.setOrientation(LinearLayout.VERTICAL);
            section.setTag("keyuses.section."+group);
            section.setLayoutParams(marginParams(0,group==0?0:24,0,0));
            TextView heading=text(Copy.section(group,rows.size()),15f,Design.ACCENT,Typeface.BOLD);
            heading.setBackground(Design.card(this,Design.ACCENT_SOFT,Design.BORDER,8));
            heading.setPadding(dp(12),dp(14),dp(12),dp(14));
            heading.setLayoutParams(marginParams(0,0,0,12));
            androidx.core.view.ViewCompat.setAccessibilityHeading(heading,true);
            section.addView(heading);
            section.addView(headerRow());
            int displayIndex=0;
            for(KeyAudit.Row auditRow:rows) section.addView(row(auditRow,++displayIndex));
            table.addView(section);
        }
    }
