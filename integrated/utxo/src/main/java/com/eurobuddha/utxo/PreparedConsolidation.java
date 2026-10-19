package com.eurobuddha.utxo;

import org.json.*;
import java.util.*;

/** Reuse native Minima coin selection without executing its one-shot signing/posting path. */
final class PreparedConsolidation {
    private final MainActivity act;
    private final String token, historyId;
    private final TxnBuilder.Done done;
    private boolean started, finished;
    private JSONObject quote;
    PreparedConsolidation(MainActivity act,String token,String historyId,TxnBuilder.Done done) {
        this.act=act;this.token=token;this.historyId=historyId;this.done=done;
    }
    void run() {
        if(started)return;started=true;
        if(!Util.isMinima(token)||historyId==null){fail(TransactionEvidence.INVALID);return;}
        act.node().cmd("consolidate tokenid:0x00 burn:0 maxcoins:20 maxsigs:5 dryrun:true",new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                if(finished)return;
                try {
                    quote=TransactionEvidence.storedObject(reply.toString());
                    checkOwned(new ArrayList<>(PreparedSendEvidence.ownedOutputs(quote,2,true)),0,new HashSet<>());
                }catch(RuntimeException invalid){fail(TransactionEvidence.INVALID);}
            }
            @Override public void onError(String message){fail(message);}
            @Override public void onUnknown(String message){fail(TransactionEvidence.NOT_SENT);}
        });
    }
    private void checkOwned(List<String> addresses,int index,Set<String> verified) {
        if(finished)return;
        if(index>=addresses.size()) {
            final JSONObject intent;
            try { intent=PreparedSendEvidence.consolidation(quote,Util.MINIMA_TOKENID,verified); }
            catch(RuntimeException invalid){fail(TransactionEvidence.INVALID);return;}
            List<Coin> inputs=new ArrayList<>();List<TxnBuilder.Out> outputs=new ArrayList<>();
            JSONArray in=intent.optJSONArray("inputs"),out=intent.optJSONArray("outputs");
            for(int i=0;i<in.length();i++){Coin coin=Coin.from(in.optJSONObject(i));coin.sendable=true;inputs.add(coin);}
            for(int i=0;i<out.length();i++){
                JSONObject item=out.optJSONObject(i);outputs.add(new TxnBuilder.Out(item.optString("address"),item.optString("amount"),(Boolean)item.opt("storestate")));
            }
            // The builder revalidates and freezes these exact inputs/outputs, then reserves them
            // atomically before txncreate. Its receipt owns the new transaction/output identifiers.
            new TxnBuilder(act,inputs,outputs,Util.MINIMA_TOKENID,new TxnBuilder.Done() {
                @Override public void onPosted(String id,List<TxnBuilder.OutCoin> coins){if(finished)return;finished=true;done.onPosted(id,coins);}
                @Override public void onFailed(String message){fail(message);}
                @Override public void onUnknown(String message){if(finished)return;finished=true;done.onUnknown(message);}
            }).record(historyId).run();
            return;
        }
        String address=addresses.get(index);
        act.node().cmd("scripts address:"+address,new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply){
                if(finished)return;
                if(!PreparedSendEvidence.defaultAddress(reply,address)){fail(TransactionEvidence.INVALID);return;}
                verified.add(address);checkOwned(addresses,index+1,verified);
            }
            @Override public void onError(String message){fail(message);}
            @Override public void onUnknown(String message){fail(TransactionEvidence.NOT_SENT);}
        });
    }
    private void fail(String message){if(finished)return;finished=true;done.onFailed(message);}
}
