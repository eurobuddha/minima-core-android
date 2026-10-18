    private static final class AccountRead {
        final String owner;
        final NodeApi node;
        final long epoch;
        final boolean nodeBound;
        final java.util.concurrent.atomic.AtomicBoolean replied=new java.util.concurrent.atomic.AtomicBoolean();
        AccountRead(String owner,NodeApi node,long epoch,boolean nodeBound) {
            this.owner=owner;this.node=node;this.epoch=epoch;this.nodeBound=nodeBound;
        }
    }
    private volatile long nodeReadEpoch;
    private volatile boolean nodeWorkClosed;
    private volatile AccountRead addressRead,mailRead,tipRead,unreadRead,keyRead;
    private AccountRead captureRead(boolean nodeBound) {
        if(nodeWorkClosed || isFinishing() || isDestroyed() || (nodeBound && (!nodeUp || node==null))) return null;
        try { return new AccountRead(backupOwner(),node,nodeReadEpoch,nodeBound); }
        catch(Exception unavailable) { return null; }
    }
    private boolean readCurrent(AccountRead op) {
        if(op==null || nodeWorkClosed || isFinishing() || isDestroyed()) return false;
        if(op.nodeBound && (!nodeUp || node!=op.node || nodeReadEpoch!=op.epoch)) return false;
        try { return op.owner.equals(backupOwner()); }
        catch(Exception unavailable) { return false; }
    }
    private boolean inboxCurrent(AccountRead op,String address) {
        return readCurrent(op) && address.equals(SalonStore.get(this,"tipaddr"));
    }
    private void closeNodeWork() {
        nodeWorkClosed=true;nodeReadEpoch++;
        addressRead=null;mailRead=null;tipRead=null;unreadRead=null;keyRead=null;
        cancelTipDialog();tipSend=null;mTipInFlight=false;
    }

    /** Scan the inbox (tips + DMs) — but only once the tip address is captured. On a fresh
     *  identity getaddress is async, so we scan INSIDE its callback (fixes the race where a
     *  new device never scanned because tipaddr was still empty). */
    private void ensureInboxAndScan() {
        final AccountRead op=captureRead(true);if(op==null)return;
        final String addr=SalonStore.get(this,"tipaddr");
        if(Args.isAddr(addr)) { scanTips();scanMail();return; }
        // Preserve malformed existing storage instead of overwriting it with another account's address.
        if(!addr.isEmpty())return;
        addressRead=op;
        NodeApi.Cb cb=new NodeApi.Cb() {
            public void onResult(JSONObject reply) { runOnUiThread(()->{
                if(!op.replied.compareAndSet(false,true) || addressRead!=op)return;
                synchronized(SalonStore.class) {
                    if(!inboxCurrent(op,addr) || reply==null || !Boolean.TRUE.equals(reply.opt("status")))return;
                    JSONObject response=reply.optJSONObject("response");
                    Object value=response==null?null:response.opt("address");
                    if(!(value instanceof String) || !Args.isAddr((String)value))return;
                    SalonStore.put(MainActivity.this,"tipaddr",(String)value);
                }
                if(readCurrent(op)){scanTips();scanMail();}
            }); }
            public void onError(String ignored){op.replied.compareAndSet(false,true);}
        };
        try { op.node.cmd("getaddress",cb); } catch(RuntimeException unavailable){cb.onError("");}
    }

    /** Derive our app-side messaging identity once (libsodium, no node/Maxima) and publish
     *  its public id as msgpk so others can seal DMs to us. */
    private void ensureMailKey() {
        final AccountRead op=captureRead(true);if(op==null)return;
        try { io.execute(()->{
            final AccountRead completed;
            final boolean available;
            synchronized(SalonComms.class) { synchronized(SalonStore.class) {
                if(!readCurrent(op))return;
                boolean ready;
                try { ready=SalonComms.publicId(this).equals(SalonStore.get(this,"msgpk")); }
                catch(SalonComms.IdentityUnavailable unavailable){ready=false;}
                available=ready;completed=captureRead(true);
            } }
            runOnUiThread(()->{
                if(!readCurrent(completed))return;
                if(!available){identityUnavailable();return;}
                // Key creation can change the owner snapshot; start reads only after it finishes.
                ensureInboxAndScan();if(pubkey.isEmpty())fetchPubkey();
            });
        }); } catch(java.util.concurrent.RejectedExecutionException unavailable) { }
    }

    /** Scan on-chain mail at our address; store new DMs + refresh the badge/thread. */
    private void scanMail() {
        final String addr = SalonStore.get(this, "tipaddr");
        if (!Args.isAddr(addr) || captureRead(true)==null) return;
        final AccountRead op;
        final LocalEcCryptoProvider identity;
        try { synchronized(SalonComms.class) { synchronized(SalonStore.class) {
            identity=SalonComms.crypto(this);
            if(!identity.identity().equals(SalonStore.get(this,"msgpk")))throw new SalonComms.IdentityUnavailable();
            op=captureRead(true);if(op==null)return;mailRead=op;
        } } }
        catch (SalonComms.IdentityUnavailable unavailable) { identityUnavailable(); return; }
        MinimaMail.scan(op.node, identity, addr, msgs -> {
            if(!op.replied.compareAndSet(false,true))return;
            try { io.execute(() -> {   // DB writes off the UI thread
            synchronized(SalonStore.class) {
            if(mailRead!=op || !inboxCurrent(op,addr))return;
            MailDb db = MailDb.get(MainActivity.this);
            int newCount = 0; String lastFrom = "", lastBody = "";
            for (MinimaMail.Msg m : msgs) {
                if (m==null || m.coinid.isEmpty()) continue;
                if (!m.valid) continue;   // anonymous seal — drop messages whose sender signature doesn't verify (anti-impersonation)
                String preview = !m.body.isEmpty() ? m.body : (!m.mediaRef.isEmpty() ? "📎 media" : "");
                // Dedup on the sender's stable app id when present (agrees with the
                // Maxima path + the NOTIFY intake), so one logical message is one
                // bubble regardless of which transport(s) carried it.
                String dedupId = m.stableId == null || m.stableId.isEmpty()
                        ? m.coinid : "id-" + m.fromPublicId + "-" + m.stableId;
                boolean isNew = db.insert(dedupId, m.fromPublicId, false, m.body, m.mediaRef, m.mediaMime, m.ts, m.valid);
                if (m.replyBody != null && !m.replyBody.isEmpty()) db.setReply(dedupId, m.replyBody, m.replyFrom);
                db.setMxAddr(m.fromPublicId, m.peerMx);   // learn their Maxima address from an on-chain DM too (sticky, no-op if absent)
                if (isNew) {
                    db.upsertContact(m.fromPublicId, m.fromHandle, "", m.fromAddr, preview, m.ts, true);
                    newCount++; lastFrom = m.fromHandle; lastBody = preview;
                }
            }
            final int fc = newCount; final String lf = lastFrom, lb = lastBody; final int unread = db.totalUnread();
            runOnUiThread(() -> {
                if(mailRead!=op || !inboxCurrent(op,addr))return;
                unreadCache = unread;
                if(navRow==null)return;
                if (fc > 0) {
                    if (screen == Screen.MESSAGES || screen == Screen.THREAD) render();
                    else { buildNav(); toast(Language.join("💬 ", fc == 1 ? "@" + lf.replaceFirst("^@", "") + ": " + lb : Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_chat_new_messages, fc))); }
                } else buildNav();
            });
            }
        }); } catch(java.util.concurrent.RejectedExecutionException unavailable) { }
        });
    }

    private int unreadCache = 0;
    /** Refresh the cached unread count off the UI thread and repaint the nav badge. */
    private void refreshUnread() {
        final AccountRead op=captureRead(false);if(op==null)return;unreadRead=op;
        try { io.execute(()->{
            final int count;
            synchronized(SalonStore.class) {
                if(unreadRead!=op || !readCurrent(op))return;
                count=MailDb.get(this).totalUnread();
            }
            runOnUiThread(()->{if(unreadRead==op && readCurrent(op)){unreadCache=count;if(navRow!=null)buildNav();}});
        }); } catch(java.util.concurrent.RejectedExecutionException unavailable) { }
    }

    /** Detect inbound tips at our address and announce new ones. Baselines silently
     *  on first run so old coins don't spam; thereafter new coinids → "X tipped you". */
    private void scanTips() {
        final String addr = SalonStore.get(this, "tipaddr");
        final AccountRead op=captureRead(true);
        if (!Args.isAddr(addr) || op==null) return;tipRead=op;
        try { op.node.cmd("coinnotify action:add address:" + addr, new NodeApi.Cb() { public void onResult(JSONObject j) {} public void onError(String m) {} }); } catch(RuntimeException unavailable){return;}
        NodeApi.Cb cb=new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if(!op.replied.compareAndSet(false,true))return;
                if(j==null || !Boolean.TRUE.equals(j.opt("status")))return;
                final JSONArray arr=j.optJSONArray("response");if(arr==null)return;
                // Validate the complete lookup before changing the seen/baseline state.
                for(int i=0;i<arr.length();i++) {
                    JSONObject coin=arr.optJSONObject(i);
                    if(coin==null || !(coin.opt("coinid") instanceof String) || !Args.isTokenId(coin.optString("coinid")))return;
                }
                runOnUiThread(() -> { synchronized(SalonStore.class) {
                    if(tipRead!=op || !inboxCurrent(op,addr))return;
                    JSONArray seen = SalonStore.arr(MainActivity.this, "tipseen");
                    java.util.HashSet<String> seenSet = new java.util.HashSet<>();
                    for (int i = 0; i < seen.length(); i++) seenSet.add(seen.optString(i));
                    boolean baselined = "1".equals(SalonStore.get(MainActivity.this, "tipbaselined"));
                    java.util.List<CharSequence> fresh = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i); if (c == null) continue;
                        String cid = c.optString("coinid", ""); if (cid.isEmpty() || seenSet.contains(cid)) continue;
                        seenSet.add(cid); seen.put(cid);
                        if (baselined) fresh.add(describeTip(c));
                    }
                    SalonStore.setArr(MainActivity.this, "tipseen", capArr(seen, 500));
                    if (!baselined) SalonStore.put(MainActivity.this, "tipbaselined", "1");
                    for (CharSequence d : fresh) toast(d);
                } });
            }
            @Override public void onError(String m) {op.replied.compareAndSet(false,true);}
        };
        try { op.node.cmd("coins address:"+addr+" order:desc depth:200",cb); }
        catch(RuntimeException unavailable){cb.onError("");}
    }

