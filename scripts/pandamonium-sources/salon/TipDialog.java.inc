    private void tipDialog(String handle, String toAddr) {
        if (!nodeUp) { toast(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_connect)); return; }
        cancelTipDialog();
        final AccountRead owner=captureRead(true);if(owner==null)return;
        tipDialogRead=owner;final int epoch=renderEpoch;
        final String[] tokenid = { TipTransport.MXUSD };   // default dollar-pegged
        LinearLayout box = dialogBox();
        final TextView curBtn = Design.button(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_currency, "mxUSD"), false);
        box.addView(curBtn, lph(46, 0, 2, 0, 8));
        final EditText amount = field(box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_amount), "", false, "1");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final LinearLayout presets = row();
        for (String amt : new String[]{"1", "5", "20"}) {
            TextView pb = Design.button(this, "$" + amt, false);
            pb.setOnClickListener(v -> amount.setText(amt));
            presets.addView(pb, weight(40, 0, amt.equals("20") ? 0 : 6));
        }
        box.addView(presets, lp(0, 6, 0, 4));
        final TextView bal = Design.note(this, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_checking));
        box.addView(bal, lp(0, 0, 0, 4));
        final int[] balanceRequest = {0};
        final Runnable refreshBal = () -> {
            final int request = ++balanceRequest[0];
            final String requestedToken = tokenid[0];
            Language.setText(bal, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_checking));
            if(tipDialogRead!=owner || !readCurrent(owner) || epoch!=renderEpoch)return;
            final java.util.concurrent.atomic.AtomicBoolean received=new java.util.concurrent.atomic.AtomicBoolean();
            NodeApi.Cb cb=new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    if(!received.compareAndSet(false,true))return;
                    String value;
                    try { value=TipTransport.sendable(j,requestedToken); } catch(IllegalArgumentException unavailable){value=null;}
                    final String have=value;
                    runOnUiThread(() -> {
                        if (tipDialogRead!=owner || !readCurrent(owner) || epoch!=renderEpoch || request != balanceRequest[0] || !requestedToken.equals(tokenid[0]) || isFinishing() || isDestroyed()) return;
                        Language.setText(bal, have == null ? Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_unavailable) : Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_available, have, TipTransport.label(requestedToken)));
                    });
                }
                @Override public void onError(String ignored) {
                    if(!received.compareAndSet(false,true))return;
                    runOnUiThread(() -> {
                        if (tipDialogRead==owner && readCurrent(owner) && epoch==renderEpoch && request == balanceRequest[0] && requestedToken.equals(tokenid[0]) && !isFinishing() && !isDestroyed()) Language.setText(bal, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_unavailable));
                    });
                }
            };
            try { owner.node.cmd("balance tokenid:"+requestedToken,cb); } catch(RuntimeException unavailable){cb.onError("");}
        };
        refreshBal.run();
        final EditText note = field(box, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_mail_note), "", false, Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_note_hint));
        curBtn.setOnClickListener(v -> {
            tokenid[0] = tokenid[0].equals(TipTransport.MXUSD) ? TipTransport.MINIMA : TipTransport.MXUSD;
            Language.setText(curBtn, Language.upper(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_currency, TipTransport.label(tokenid[0]))));
            presets.setVisibility(tokenid[0].equals(TipTransport.MXUSD) ? View.VISIBLE : View.GONE);
            refreshBal.run();
        });
        tipConfirmDialog=new com.eurobuddha.minimacore.i18n.LanguageDialog(this).setTitle(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_tips_title, handle)).setView(box)
                .setPositiveButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_wallet_send), (d, w) -> {
                    if(tipDialogRead!=owner)return;tipDialogRead=null;
                    synchronized(SalonStore.class) {
                        if(!readCurrent(owner) || epoch!=renderEpoch){toast(MSG_CHANGED);return;}
                        sendTip(handle,toAddr,tokenid[0],text(amount).trim(),text(note).trim());
                    }
                })
                .setNegativeButton(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_common_cancel), null).show();
        tipConfirmDialog.setOnDismissListener(d->{if(tipDialogRead==owner)tipDialogRead=null;if(tipConfirmDialog==d)tipConfirmDialog=null;});
    }

