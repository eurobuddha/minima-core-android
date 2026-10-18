    private void fetchPubkey() {
        final AccountRead op=captureRead(true);if(op==null)return;keyRead=op;
        NodeApi.Cb cb=new NodeApi.Cb() {
            public void onResult(JSONObject reply){runOnUiThread(()->{
                if(!op.replied.compareAndSet(false,true) || keyRead!=op || !readCurrent(op))return;
                String key=firstPublicKey(reply);
                if(!key.isEmpty()){pubkey=key;return;}
                final java.util.concurrent.atomic.AtomicBoolean received=new java.util.concurrent.atomic.AtomicBoolean();
                NodeApi.Cb fallback=new NodeApi.Cb(){
                    public void onResult(JSONObject result){runOnUiThread(()->{
                        if(!received.compareAndSet(false,true) || keyRead!=op || !readCurrent(op))return;
                        String value=firstPublicKey(result);if(!value.isEmpty())pubkey=value;
                    });}
                    public void onError(String ignored){received.compareAndSet(false,true);}
                };
                try{op.node.cmd("getaddress",fallback);}catch(RuntimeException unavailable){fallback.onError("");}
            });}
            public void onError(String ignored){op.replied.compareAndSet(false,true);}
        };
        try{op.node.cmd("keys",cb);}catch(RuntimeException unavailable){cb.onError("");}
    }
    private String firstPublicKey(JSONObject reply) {
        if(reply==null || !Boolean.TRUE.equals(reply.opt("status")))return "";
        Object response=reply.opt("response");
        JSONObject row=null;
        if(response instanceof JSONArray)row=((JSONArray)response).optJSONObject(0);
        else if(response instanceof JSONObject){
            JSONObject object=(JSONObject)response;JSONArray keys=object.optJSONArray("keys");
            row=keys==null?object:keys.optJSONObject(0);
        }
        Object key=row==null?null:row.opt("publickey");
        return key instanceof String && Args.isTokenId((String)key)?(String)key:"";
    }

