    private SalonPublication publicationCheck;
    private android.widget.ProgressBar publishSpinner;
    private TextView publishButton;
    private boolean publishPreparing;
    private SalonPublication.Stage publishStage;
    private String publishOwner = "";

    private boolean pendingPublication() {
        return SalonPublication.pending(this, SalonStore.get(this,"tokenid"));
    }
    private void showPublishState(SalonPublication.Stage stage) {
        publishStage=stage;
        CharSequence message=publishMessage(stage);
        if (screen == Screen.EDIT && profStatus != null) SET_STATUS;
        else if (stage==SalonPublication.Stage.CONFIRMED || stage==SalonPublication.Stage.UNKNOWN) toast(message.toString());
        updatePublishButton();
    }
    private void updatePublishButton() {
        if (publishButton==null) return;
        boolean busy=publishPreparing || (publicationCheck!=null && publicationCheck.active());
        boolean pending=pendingPublication();
        publishButton.setEnabled(!busy);
        publishButton.setClickable(!busy);
        if (publishSpinner!=null) publishSpinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        CharSequence label=busy ? (publishPreparing ? LABEL_PREPARING : LABEL_CONFIRMING)
                : pending ? LABEL_CHECK : publishStage==SalonPublication.Stage.CONFIRMED ? LABEL_VIEW : LABEL_SAVE;
        SET_LABEL;
    }
    private View publishAction(Runnable save) {
        FrameLayout frame=new FrameLayout(this);
        publishButton=btn(LABEL_SAVE,true,()->{
            if(publishPreparing || (publicationCheck!=null && publicationCheck.active())) return;
            if(pendingPublication()) { publishSalon(profStatus); return; }
            if(publishStage==SalonPublication.Stage.CONFIRMED) { go(Screen.HOME); return; }
            save.run();
        });
        publishButton.setPadding(dp(42),dp(12),dp(42),dp(12));
        frame.addView(publishButton,new FrameLayout.LayoutParams(-1,-2));
        publishSpinner=new android.widget.ProgressBar(this);
        publishSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Design.WHITE()));
        FrameLayout.LayoutParams indicator=new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.START|Gravity.CENTER_VERTICAL);
        indicator.leftMargin=dp(14);frame.addView(publishSpinner,indicator);
        updatePublishButton();
        return frame;
    }
    private void resumePublication() {
        if(pendingPublication() && nodeUp && (publicationCheck==null || !publicationCheck.active())) publishSalon(profStatus);
        else updatePublishButton();
    }
    private void publishSalon(TextView status) {
        if(publicationCheck!=null && publicationCheck.active()) return;
        if(!nodeUp || node==null || !SalonStore.hasIdentity(this) || SalonStore.get(this,"profileUrl").isEmpty()) {
            if(status!=null) { CharSequence message=LABEL_NODE; SET_STATUS_LOCAL; }
            return;
        }
        final String token=SalonStore.get(this,"tokenid");
        final String owner=SalonStore.get(this,"msgpk");
        final String profileUrl=SalonStore.get(this,"profileUrl");
        final String url=pendingPublication() ? SalonPublication.pendingUrl(this,token) : profileUrl;
        final int publicationEpoch=renderEpoch;
        final NodeApi requestedNode=node;
        if(publicationCheck!=null) publicationCheck.stop();
        publishOwner=token;
        publicationCheck=new SalonPublication(this,node,token,url,SalonStore.get(this,"handle"),
                ()->!isFinishing() && !isDestroyed() && node==requestedNode && nodeUp
                        && token.equals(SalonStore.get(this,"tokenid")) && owner.equals(SalonStore.get(this,"msgpk"))
                        && profileUrl.equals(SalonStore.get(this,"profileUrl")),
                stage->{
                    showPublishState(stage);
                    if(stage==SalonPublication.Stage.CONFIRMED && screen==Screen.EDIT && publicationEpoch==renderEpoch) {
                        mProfileCache.clear(); mRegistryCache=null;
                        go(Screen.DISCOVER);
                        scroll.post(()->scroll.scrollTo(0,0));
                        toast(publishMessage(stage).toString());
                    }
                    if(status!=null && status!=profStatus) { CharSequence message=publishMessage(stage); SET_STATUS_LOCAL; }
                });
        publicationCheck.start(true);
    }
