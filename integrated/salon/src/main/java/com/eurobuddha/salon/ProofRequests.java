package com.eurobuddha.salon;

import com.eurobuddha.minimacore.utils.ExpiringCache;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Main-thread node requests using SharedRequests' weak subscriptions and the host cache.
 * Node callbacks are asynchronous, so no synchronous supplier or worker wait is used. */
final class ProofRequests {
    static final class Result {
        final boolean verified, live;
        final String reason;
        Result(boolean verified, String reason, boolean live) {
            this.verified=verified; this.reason=reason; this.live=live;
        }
    }
    private static final class Request {
        final List<WeakReference<Consumer<Result>>> listeners=new ArrayList<>();
    }
    private final ExpiringCache<String,Result> cache;
    private final Map<String,Request> pending=new HashMap<>();
    private boolean closed;
    ProofRequests(LongSupplier clock) { cache=new ExpiringCache<>(128,clock); }

    void request(String key, boolean force, Consumer<Consumer<Result>> start, Consumer<Result> listener) {
        if (closed) return;
        Request previous=pending.get(key);
        if (previous!=null && !force) { subscribe(previous,listener); return; }
        if (!force) {
            Result result=cache.get(key);
            if (result!=null) { listener.accept(result); return; }
        }
        if (previous==null && pending.size()>=128) {
            listener.accept(new Result(false,"coin check failed (node busy?)",false)); return;
        }
        Request request=new Request();
        if (previous!=null) request.listeners.addAll(previous.listeners);
        subscribe(request,listener);
        // A forced retry invalidates the cached verdict immediately.
        cache.put(key,null,0);
        pending.put(key,request);
        try { start.accept(result->finish(key,request,result)); }
        catch (RuntimeException failed) { finish(key,request,new Result(false,"coin check failed (node busy?)",false)); }
    }
    private static void subscribe(Request request,Consumer<Result> listener) {
        request.listeners.removeIf(ref->ref.get()==null);
        for (WeakReference<Consumer<Result>> ref:request.listeners) if(ref.get()==listener)return;
        request.listeners.add(new WeakReference<>(listener));
    }
    private void finish(String key,Request request,Result result) {
        if(closed || pending.get(key)!=request)return;
        pending.remove(key);
        if(result==null)result=new Result(false,"coin check failed (node busy?)",false);
        cache.put(key,result,30_000);
        for(WeakReference<Consumer<Result>> ref:request.listeners){
            Consumer<Result> callback=ref.get();
            if(callback!=null)try{callback.accept(result);}catch(RuntimeException ignored){}
        }
    }
    void close(){closed=true;pending.clear();}

    /** Copy only proof fields: no mutable profile data enters an in-flight check. */
    static JSONObject snapshot(JSONObject item) {
        JSONObject copy=new JSONObject();
        try {
            for(String key:FIELDS) {
                Object value=item==null?null:item.opt(key);
                copy.put(key,value instanceof String?value:"");
            }
        } catch(org.json.JSONException impossible){throw new IllegalArgumentException(impossible);}
        return copy;
    }
    static String key(JSONObject item,String binding,String owner) {
        JSONArray fields=new JSONArray().put(binding==null?"":binding).put(owner==null?"":owner);
        for(String field:FIELDS){Object value=item==null?null:item.opt(field);fields.put(value instanceof String?value:"");}
        return Hex.to(NostrEvent.sha256(fields.toString()));
    }
    private static final String[] FIELDS={"tokenid","coinid","coinproof","script","publickey","sig"};
}
