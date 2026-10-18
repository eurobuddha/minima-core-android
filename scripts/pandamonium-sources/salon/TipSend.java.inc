    private AccountRead tipDialogRead;
    private androidx.appcompat.app.AlertDialog tipConfirmDialog;
    private static final class TipSend {
        final AccountRead read;final int epoch;
        boolean lookedUp,submitted;
        TipSend(AccountRead read,int epoch){this.read=read;this.epoch=epoch;}
    }
    private TipSend tipSend;
    private void cancelTipDialog() {
        tipDialogRead=null;
        if(tipConfirmDialog!=null){androidx.appcompat.app.AlertDialog dialog=tipConfirmDialog;tipConfirmDialog=null;dialog.dismiss();}
    }
    private void cancelTipLookup() {
        cancelTipDialog();
        if(tipSend!=null && !tipSend.submitted){tipSend=null;mTipInFlight=false;}
    }
    private boolean tipCurrent(TipSend op){return tipSend==op && op.epoch==renderEpoch && readCurrent(op.read);}
    private void finishTip(TipSend op,CharSequence message) {
        if(tipSend!=op)return;
        boolean current=tipCurrent(op);tipSend=null;mTipInFlight=false;
        if(current && message!=null)toast(message);
    }
    private void sendTip(String handle,String toAddr,String tokenid,String amount,String note) {
        if(!Args.isDecimal(amount)){toast(MSG_AMOUNT);return;}
        final java.math.BigDecimal wanted=new java.math.BigDecimal(amount);
        if(wanted.signum()<=0){toast(MSG_ENTER);return;}
        if(!Args.isAddr(toAddr)){toast(MSG_ADDRESS);return;}
        if(!Args.isTokenId(tokenid)){toast(MSG_TOKEN);return;}
        if(mTipInFlight){toast(MSG_BUSY);return;}
        final AccountRead read=captureRead(true);if(read==null){toast(MSG_CONNECT);return;}
        final TipSend op=new TipSend(read,renderEpoch);
        final String from="@"+SalonStore.get(this,"handle");
        tipSend=op;mTipInFlight=true;toast(MSG_CHECKING);
        NodeApi.Cb cb=new NodeApi.Cb() {
            public void onResult(JSONObject reply){runOnUiThread(()->{
                if(tipSend!=op || op.lookedUp)return;op.lookedUp=true;
                synchronized(SalonStore.class) {
                    if(!tipCurrent(op)){finishTip(op,null);return;}
                    final java.math.BigDecimal have;
                    try{have=new java.math.BigDecimal(TipTransport.sendable(reply,tokenid));}
                    catch(IllegalArgumentException unavailable){finishTip(op,MSG_UNAVAILABLE);return;}
                    if(wanted.compareTo(have)>0){finishTip(op,MSG_INSUFFICIENT);return;}
                    op.submitted=true;toast(MSG_SENDING);
                    TipTransport.tip(read.node,toAddr,amount,tokenid,from,note,new TipTransport.Cb(){
                        public void onSent(String txpowid){runOnUiThread(()->finishTip(op,MSG_SUBMITTED));}
                        public void onFailed(String message){runOnUiThread(()->finishTip(op,MSG_FAILED));}
                    });
                }
            });}
            public void onError(String message){runOnUiThread(()->{
                if(tipSend!=op || op.lookedUp)return;op.lookedUp=true;finishTip(op,MSG_BALANCE_FAILED);
            });}
        };
        try{read.node.cmd("balance tokenid:"+tokenid,cb);}catch(RuntimeException unavailable){cb.onError("");}
    }

