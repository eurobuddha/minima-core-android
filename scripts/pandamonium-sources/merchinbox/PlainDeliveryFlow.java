package com.eurobuddha.merchinbox;

import android.os.Handler;
import com.eurobuddha.merchinbox.integratedcomms.MerchDb;
import com.eurobuddha.merchinbox.integratedcomms.NodeApi;
import com.eurobuddha.nftwallet.OrderPaymentEvidence;
import org.json.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import static com.eurobuddha.merchinbox.DeliveryJournal.*;

/** NFT Wallet's unsigned native-send sequence, with Inbox order binding and read-only recovery. */
final class PlainDeliveryFlow {
    private final NodeApi node;private final PlainDeliveryJournal journal;private final Executor io;private final Handler ui;
    private final BooleanSupplier active;private final DeliveryFlow.Listener listener;
    private MerchDb.Order order;private String key;private String[] line;private JSONObject saved,quote;
    private final String handle="mi_send_"+UUID.randomUUID().toString().replace("-","");
    private String signedFile;private boolean released,ended;private int attempts,offset,pageSize=4,pages;
    PlainDeliveryFlow(NodeApi node,PlainDeliveryJournal journal,Executor io,Handler ui,BooleanSupplier active,DeliveryFlow.Listener listener) {
        this.node=node;this.journal=journal;this.io=io;this.ui=ui;this.active=active;this.listener=listener;
    }
    private boolean live(){return !ended&&active.getAsBoolean();}
    private void release(){if(!released){released=true;listener.posted();}}
    private void stop(){if(!live())return;ended=true;release();listener.unconfirmed();}
    private void success(){if(!live())return;ended=true;release();listener.confirmed();}
    private interface Work {void run();}
    private void work(Work work,Runnable then) {
        if(!live())return;
        try{io.execute(()->{try{work.run();}catch(RuntimeException failed){ui.post(this::stop);return;}ui.post(()->{if(live())then.run();});});}
        catch(RuntimeException failed){stop();}
    }
    void start(MerchDb.Order o,String key,String[] line) {
        this.order=o;this.key=key;this.line=line.clone();
        try{OrderPaymentEvidence.request(o.payaddr,line[2],line[1],o.ref);}catch(RuntimeException invalid){stop();return;}
        work(()->saved=journal.load(key,o,this.line),()->{
            if(PlainDeliveryJournal.complete(saved)){success();return;}
            if(!PlainDeliveryJournal.retryable(saved)){release();poll();return;}
            final String command;
            try{command="sendnosign address:"+o.payaddr+" amount:"+line[2]+" tokenid:"+line[1]+" split:1 burn:0 state:"+new JSONObject().put("1",OrderPaymentEvidence.reference(o.ref))+" file:"+handle+".txn";}
            catch(JSONException invalid){stop();return;}
            node.cmd(command,new NodeApi.Cb(){
                public void onResult(JSONObject reply){if(!live())return;if(!ok(reply)){stop();return;}viewUnsigned();}
                public void onError(String error){stop();}
            });
        });
    }
    private static boolean ok(JSONObject reply){return reply!=null&&Boolean.TRUE.equals(reply.opt("status"))&&!Boolean.TRUE.equals(reply.opt("pending"));}
    private void viewUnsigned() {
        node.cmd("sendview file:"+handle+".txn",new NodeApi.Cb(){
            public void onResult(JSONObject reply) {
                if(!live())return;
                try{quote=reply;checkOwned(new ArrayList<>(OrderPaymentEvidence.changeAddresses(reply)),0,new HashSet<>());}
                catch(RuntimeException invalid){stop();}
            }
            public void onError(String error){stop();}
        });
    }
    private void checkOwned(List<String> addresses,int index,Set<String> verified) {
        if(!live())return;
        if(index>=addresses.size()) {
            final JSONObject intent;
            try{intent=OrderPaymentEvidence.intent(quote,order.payaddr,line[2],line[1],order.ref,verified);}catch(RuntimeException invalid){stop();return;}
            work(()->saved=journal.prepare(key,order,line,handle,intent),this::sign);return;
        }
        String address=addresses.get(index);
        node.cmd("scripts address:"+address,new NodeApi.Cb(){
            public void onResult(JSONObject reply){if(!live())return;if(!OrderPaymentEvidence.defaultAddress(reply,address)){stop();return;}verified.add(address);checkOwned(addresses,index+1,verified);}
            public void onError(String error){stop();}
        });
    }
    private void failPrepared(){work(()->journal.phase(key,order,line,handle,NOT_SUBMITTED),this::stop);}
    private void sign() {
        node.cmd("sendsign file:"+handle+".txn",new NodeApi.Cb(){
            public void onResult(JSONObject reply) {
                if(!live())return;
                try {
                    Object path=ok(reply)&&reply.optJSONObject("response")!=null?reply.optJSONObject("response").opt("txpow"):null;
                    if(!(path instanceof String))throw DeliveryEvidence.invalid();
                    signedFile=((String)path).substring(((String)path).lastIndexOf('/')+1);
                    if(!signedFile.matches("signedtransaction-[0-9]{1,20}\\.txn"))throw DeliveryEvidence.invalid();
                }catch(RuntimeException invalid){failPrepared();return;}
                verifySigned();
            }
            public void onError(String error){failPrepared();}
        });
    }
    private void verifySigned() {
        node.cmd("sendview file:"+signedFile,new NodeApi.Cb(){
            public void onResult(JSONObject reply) {
                if(!live())return;
                try{OrderPaymentEvidence.referenceStamp(reply,order.ref);OrderPaymentEvidence.publicReceipt(reply,saved.optJSONObject("intent"));}catch(RuntimeException mismatch){failPrepared();return;}
                work(()->journal.phase(key,order,line,handle,POSTING),PlainDeliveryFlow.this::post);
            }
            public void onError(String error){failPrepared();}
        });
    }
    private void post() {
        node.cmd("sendpost file:"+signedFile,new NodeApi.Cb(){
            public void onResult(JSONObject reply) {
                if(!live())return;
                work(()->{
                    try{journal.receipt(key,order,line,reply,null);}
                    catch(RuntimeException uncertain){journal.phase(key,order,line,handle,UNKNOWN);}
                },()->{release();poll();});
            }
            // Inbox transport has no definite-vs-unknown error distinction. Never release a post on error.
            public void onError(String error){work(()->journal.phase(key,order,line,handle,UNKNOWN),()->{release();poll();});}
        });
    }
    private void poll() {
        if(!live())return;if(attempts++>=20){stop();return;}
        work(()->saved=journal.load(key,order,line),()->{
            if(PlainDeliveryJournal.complete(saved)){success();return;}
            if(!POSTED.equals(saved.optString("phase"))){pages=0;page();return;}
            String txid=saved.optJSONObject("receipt").optString("txpowid");
            node.cmd("txpow onchain:"+txid,new NodeApi.Cb(){
                public void onResult(JSONObject inclusion){if(!live())return;try{OrderPaymentEvidence.inclusion(inclusion);}catch(RuntimeException shallow){pages=0;page();return;}work(()->journal.confirmSaved(key,order,line,txid,inclusion),PlainDeliveryFlow.this::success);}
                public void onError(String error){if(live()){pages=0;page();}}
            });
        });
    }
    /** Same bounded history/shrink traversal as DeliveryFlow and NFT Wallet's TransactionRecovery. */
    private void page() {
        if(!live())return;
        if(!Arrays.asList(POSTING,UNKNOWN,POSTED).contains(saved.optString("phase"))){stop();return;}
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
            reply=new JSONObject().put("status",true).put("response",txpow);
            OrderPaymentEvidence.publicReceipt(reply,saved.optJSONObject("intent"));
        }catch(Exception mismatch){inspect(txpows,index+1,done);return;}
        JSONObject exact=reply;
        node.cmd("txpow onchain:"+OrderPaymentEvidence.txpowid(reply),new NodeApi.Cb(){
            public void onResult(JSONObject inclusion){if(!live())return;try{OrderPaymentEvidence.inclusion(inclusion);}catch(RuntimeException shallow){inspect(txpows,index+1,done);return;}work(()->journal.receipt(key,order,line,exact,inclusion),PlainDeliveryFlow.this::success);}
            public void onError(String error){if(live())inspect(txpows,index+1,done);}
        });
    }
    private void finishPoll(){if(live())ui.postDelayed(()->{if(live())poll();},20000L);}
}
