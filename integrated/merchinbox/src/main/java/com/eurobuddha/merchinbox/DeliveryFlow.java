package com.eurobuddha.merchinbox;

import android.os.Handler;
import com.eurobuddha.merchinbox.integratedcomms.MerchDb;
import com.eurobuddha.merchinbox.integratedcomms.NodeApi;
import org.json.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** StateNFT delivery: reserve, build, persist POSTING, post once, then recover using read-only chain evidence. */
final class DeliveryFlow {
    interface Listener { void posted(); void confirmed(); void unconfirmed(); }
    private final NodeApi node;private final DeliveryJournal journal;private final Executor io;private final Handler ui;
    private final BooleanSupplier active;private final Listener listener;
    private MerchDb.Order order;private String key;private String[] line;private JSONObject saved;
    private final Map<String,JSONObject> coins=new HashMap<>();
    private boolean released,ended;private int attempts,offset,pageSize=4,pages;
    DeliveryFlow(NodeApi node,DeliveryJournal journal,Executor io,Handler ui,BooleanSupplier active,Listener listener) {
        this.node=node;this.journal=journal;this.io=io;this.ui=ui;this.active=active;this.listener=listener;
    }
    private boolean live(){return !ended&&active.getAsBoolean();}
    private void release(){if(!released){released=true;listener.posted();}}
    private void stop(){if(!live())return;ended=true;release();listener.unconfirmed();}
    private void success(){if(!live())return;ended=true;release();listener.confirmed();}
    private interface Work {void run();}
    private void work(Work work,Runnable then) {
        if(!live())return;
        try {io.execute(()->{
            try {work.run();}catch(RuntimeException failed){ui.post(this::stop);return;}
            ui.post(()->{if(live())then.run();});
        });}catch(RuntimeException failed){stop();}
    }
    void start(MerchDb.Order o,String key,String[] line) {
        this.order=o;this.key=key;this.line=line.clone();
        work(()->saved=journal.load(key,o,this.line),()->{
            if(DeliveryJournal.complete(saved)){success();return;}
            node.cmd("coins relevant:true own:true tokenid:"+this.line[1],new NodeApi.Cb(){
                public void onResult(JSONObject reply) {
                    if(!live())return;
                    List<JSONObject> selected=new ArrayList<>();
                    try {
                        JSONArray list=DeliveryEvidence.coins(reply,DeliveryFlow.this.line[1]);
                        for(int i=0;i<list.length();i++) {
                            JSONObject coin=list.getJSONObject(i);String stamp=StateNft.stamped(coin);
                            if(StateNft.isBuried(coin)||stamp==null||!StateNft.replayableState(coin))continue;
                            if(!"1".equals(DeliveryFlow.this.line[4])&&!DeliveryFlow.this.line[3].equals(stamp))continue;
                            DeliveryEvidence.intent(coin,DeliveryFlow.this.line[1]);
                            selected.add(coin);coins.put(coin.getString("coinid").toLowerCase(Locale.ROOT),coin);
                        }
                        if(!"1".equals(DeliveryFlow.this.line[4])&&selected.size()>1)throw DeliveryEvidence.invalid();
                    }catch(Exception incomplete){stop();return;}
                    work(()->saved=journal.prepare(key,order,DeliveryFlow.this.line,selected),()->sendNext(0));
                }
                public void onError(String error){stop();}
            });
        });
    }
    private void sendNext(int index) {
        if(!live())return;
        JSONArray entries=saved.optJSONArray("entries");
        if(index>=entries.length()){release();poll();return;}
        JSONObject entry=entries.optJSONObject(index);
        if(!journal.owned(entry)){sendNext(index+1);return;}
        String input=entry.optString("input");JSONObject coin=coins.get(input.toLowerCase(Locale.ROOT));
        if(coin==null){stop();return;}
        String txn="mi"+input.substring(2).toLowerCase(Locale.ROOT);
        List<String> commands=StateNft.transferCommands(txn,line[1],coin,order.payaddr);
        String post=commands.remove(commands.size()-1);
        // The existing checked command builder/signing order is preserved. No post is in this chain.
        CmdChain.run(node,commands,"txndelete id:"+txn,new CmdChain.Done(){
            public void ok(JSONObject prepared) {
                if(!live())return;
                work(()->journal.phase(key,order,line,input,DeliveryJournal.POSTING),()->{
                    node.cmd(post,new NodeApi.Cb(){
                        public void onResult(JSONObject reply) {
                            if(!live())return;
                            final boolean[] recorded={false};
                            work(()->{
                                try {journal.receipt(key,order,line,input,reply,null);recorded[0]=true;}
                                catch(RuntimeException uncertain){journal.phase(key,order,line,input,DeliveryJournal.UNKNOWN);}
                            },()->{
                                if(recorded[0])node.cmd("txndelete id:"+txn,new NodeApi.Cb(){public void onResult(JSONObject ignored){}public void onError(String ignored){}});
                                sendNext(index+1);
                            });
                        }
                        public void onError(String error) {
                            work(()->journal.phase(key,order,line,input,DeliveryJournal.UNKNOWN),()->sendNext(index+1));
                        }
                    });
                });
            }
            public void fail(String error) {
                work(()->journal.phase(key,order,line,input,DeliveryJournal.NOT_SUBMITTED),()->sendNext(index+1));
            }
        });
    }
    private void poll() {
        if(!live())return;
        if(attempts++>=20){stop();return;}
        work(()->saved=journal.load(key,order,line),()->{
            if(DeliveryJournal.complete(saved)){success();return;}
            verifyKnown(0);
        });
    }
    private void verifyKnown(int index) {
        if(!live())return;
        JSONArray entries=saved.optJSONArray("entries");
        if(index>=entries.length()){pages=0;page();return;}
        JSONObject entry=entries.optJSONObject(index);
        if(!DeliveryJournal.POSTED.equals(entry.optString("phase"))){verifyKnown(index+1);return;}
        String input=entry.optString("input"),txid=entry.optString("txpowid");
        node.cmd("txpow onchain:"+txid,new NodeApi.Cb(){
            public void onResult(JSONObject inclusion) {
                if(!live())return;
                try{DeliveryEvidence.inclusion(inclusion);}catch(RuntimeException notMined){verifyKnown(index+1);return;}
                work(()->journal.confirmSaved(key,order,line,input,txid,inclusion),()->verifyKnown(index+1));
            }
            public void onError(String error){if(live())verifyKnown(index+1);}
        });
    }
    private boolean unknown() {
        JSONArray entries=saved.optJSONArray("entries");
        for(int i=0;i<entries.length();i++)if(Arrays.asList(DeliveryJournal.POSTING,DeliveryJournal.UNKNOWN).contains(entries.optJSONObject(i).optString("phase")))return true;
        return false;
    }
    /** Reuse NFT Wallet's bounded history scan and shrink oversized replies, never a full-history request. */
    private void page() {
        if(!live())return;
        if(!unknown()){finishPoll();return;}
        int start=offset,requested=pageSize;
        node.cmd("history relevant:true max:"+requested+" offset:"+start,new NodeApi.Cb(){
            public void onResult(JSONObject reply) {
                if(!live())return;
                JSONObject response=reply==null?null:reply.optJSONObject("response");JSONArray txpows=response==null?null:response.optJSONArray("txpows");
                if(!Boolean.TRUE.equals(reply==null?null:reply.opt("status"))||txpows==null||txpows.length()>requested){finishPoll();return;}
                pages++;inspect(txpows,0,()->{
                    if(txpows.length()<requested){offset=0;finishPoll();return;}
                    if(start>Integer.MAX_VALUE-txpows.length()){offset=0;finishPoll();return;}
                    offset=start+txpows.length();if(pages>=4)finishPoll();else page();
                });
            }
            public void onError(String error) {
                if(!live())return;
                if(!NodeApi.ERR_TOO_LONG.equals(error)){finishPoll();return;}
                if(pageSize>1){pageSize=Math.max(1,pageSize/2);page();return;}
                pages++;if(start==Integer.MAX_VALUE){offset=0;finishPoll();return;}
                offset=start+1;if(pages>=4)finishPoll();else page();
            }
        });
    }
    private void inspect(JSONArray txpows,int index,Runnable done) {
        if(!live())return;if(index>=txpows.length()){done.run();return;}
        JSONObject txpow=txpows.optJSONObject(index),reply;
        try {
            if(txpow==null||!Boolean.TRUE.equals(txpow.opt("istransaction")))throw DeliveryEvidence.invalid();
            reply=new JSONObject().put("status",true).put("response",txpow);DeliveryEvidence.txpow(reply);
        }catch(Exception invalid){inspect(txpows,index+1,done);return;}
        String matched=null;JSONArray entries=saved.optJSONArray("entries");
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.optJSONObject(i);
            if(!Arrays.asList(DeliveryJournal.POSTING,DeliveryJournal.UNKNOWN).contains(entry.optString("phase")))continue;
            try{DeliveryEvidence.receipt(reply,entry,order.payaddr);matched=entry.optString("input");break;}catch(RuntimeException mismatch){}
        }
        if(matched==null){inspect(txpows,index+1,done);return;}
        String input=matched;JSONObject exact=reply;
        node.cmd("txpow onchain:"+txpow.optString("txpowid"),new NodeApi.Cb(){
            public void onResult(JSONObject inclusion) {
                if(!live())return;
                try{DeliveryEvidence.inclusion(inclusion);}catch(RuntimeException notMined){inspect(txpows,index+1,done);return;}
                work(()->journal.receipt(key,order,line,input,exact,inclusion),()->inspect(txpows,index+1,done));
            }
            public void onError(String error){if(live())inspect(txpows,index+1,done);}
        });
    }
    private void finishPoll() {
        work(()->saved=journal.load(key,order,line),()->{
            if(DeliveryJournal.complete(saved)){success();return;}
            JSONArray entries=saved.optJSONArray("entries");boolean waiting=false;
            for(int i=0;i<entries.length();i++)if(Arrays.asList(DeliveryJournal.POSTING,DeliveryJournal.UNKNOWN,DeliveryJournal.POSTED).contains(entries.optJSONObject(i).optString("phase")))waiting=true;
            if(!waiting){stop();return;}
            ui.postDelayed(()->{if(live())poll();},20000L);
        });
    }
}
