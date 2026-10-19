    private void showCreateChoices(StateNft.Meta m) {
        JSONObject row=LocalStore.findById(this,m.localId);
        JSONArray matches=row==null?null:row.optJSONArray("creatematches");
        if(matches==null || matches.length()==0 || !m.tokenid.isEmpty() || !"CREATE".equals(m.phase)) return;
        LinearLayout panel=lotCard();
        panel.addView(Design.note(this,"These tokens match this collection’s artwork and creator. Choose one to finish. The others remain in your wallet until you bury them."));
        final long generation=screenGeneration;
        for(int i=0;i<matches.length();i++) {
            final String tid=matches.optString(i);
            TextView hash=Design.hash(this,tid); hash.setTextIsSelectable(true);
            panel.addView(hash,lpm(0,10,0,4));
            TextView choose=Design.button(this,"Finish this collection",false);
            choose.setOnClickListener(v->{
                if(!collectionCurrent(m,generation) || !choose.isEnabled()) return;
                if(!LocalStore.chooseCreate(this,m.localId,tid)) { toast("Collection changed. Refresh and try again."); return; }
                choose.setEnabled(false); toast("Checking the selected token before continuing…");
                engageEngine(); MintEngine.resumeNow(this,node,msg->runOnUiThread(()->{
                    if(!collectionCurrent(m,generation)) return;
                    loadLocalCollections(); openMeta=findCollectionByLocalId(m.localId,m);lastDetailSig="";refreshDetail();
                }));
            });
            panel.addView(choose,lpm(0,0,0,8));
        }
        body.addView(panel,lpm(0,0,0,12));
    }

