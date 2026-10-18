    /** Best-effort removal of this flow's completed scratch file. Never changes a transaction result. */
    public void discardPreparedFile(String name) {
        if(name==null||!name.matches("(?:ms_pay_[0-9a-f]{32}|signedtransaction-[0-9]{1,20})\\.txn"))return;
        mMain.post(()->{
            if((mReleased || dead()))return;
            try {
                // Filez uses the same guarded FILE bridge and serialized host mutation queue.
                final boolean[] checked={false};
                mApi.FileCommand("stat",name,null,null,reply->{
                    if(checked[0])return;checked[0]=true;
                    if((mReleased || dead())||reply==null||!Boolean.TRUE.equals(reply.opt("status"))
                            ||!"stat".equals(reply.opt("action"))||!name.equals(reply.opt("query"))
                            ||!Boolean.TRUE.equals(reply.opt("exists"))||!Boolean.FALSE.equals(reply.opt("isdir")))return;
                    Object base=reply.opt("base"),canonical=reply.opt("canonical"),size=reply.opt("size");
                    if(!(base instanceof String)||!((String)base).startsWith("/")||!(canonical instanceof String)
                            ||!(((String)base)+"/"+name).equals(canonical)||!(size instanceof Number)||((Number)size).longValue()<=0)return;
                    try {mApi.FileCommand("delete",name,null,null,ignored->{});}
                    catch(RuntimeException unavailable){/* Keep the file; no transfer retry or state change. */}
                });
            }catch(RuntimeException unavailable){/* Keep the file for manual inspection in Filez. */}
        });
    }

