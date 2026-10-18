package com.eurobuddha.nftwallet;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.*;

/** Node-native coin selection, with an acknowledged exact intent before signing or posting. */
final class PreparedSend {
    private final MainActivity act;
    private final String recipient, amount, burn, token, tokenName;
    private final int split;
    private final TxnBuilder.Done done;
    private final String handle="nft_send_"+UUID.randomUUID().toString().replace("-","");
    private String historyId, signedFile;
    private JSONObject draft, intent;
    private int decimals;
    private boolean started, prepared, finished;

    PreparedSend(MainActivity act,String recipient,String amount,String burn,int split,String token,String tokenName,TxnBuilder.Done done) {
        this.act=act;this.recipient=recipient;this.amount=amount;this.burn=burn;this.split=split;this.token=token;this.tokenName=tokenName;this.done=done;
    }
    void run() {
        if(started)return;started=true;
        final String destinations;
        try {
            PreparedSendEvidence.request(recipient,amount,token,burn,split);
            if(split==1)destinations="address:"+recipient+" amount:"+amount;
            else {
                decimals=Util.isMinima(token)?44:act.tokenDecimals(token);
                JSONArray multi=new JSONArray();
                for(String value:PreparedSendEvidence.splitAmounts(amount,split,decimals))multi.put(recipient+":"+value);
                destinations="multi:"+multi;
            }
        }
        catch(RuntimeException invalid){finished=true;done.onFailed(TransactionEvidence.INVALID);return;}
        historyId=TxnUtil.recordPosting(act,recipient,amount,token,tokenName,null,null,null,burn);
        if(historyId==null){finished=true;done.onFailed(TransactionEvidence.SAVE_FAILED);return;}
        // Explicit outputs avoid sendnosign's split rounding loss; the node still chooses inputs/change/burn.
        String command="sendnosign "+destinations+" tokenid:"+token+" split:1"
                +" burn:"+PreparedSendEvidence.fee(burn)+" file:"+handle+".txn";
        act.node().cmd(command,new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply){if(!finished)viewUnsigned();}
            @Override public void onError(String message){fail(message);}
        });
    }
    private void viewUnsigned() {
        act.node().cmd("sendview file:"+handle+".txn",new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                if(finished)return;
                try { draft=reply;checkOwned(new ArrayList<>(PreparedSendEvidence.ownedOutputs(reply,split,false)),0,new HashSet<>()); }
                catch(RuntimeException invalid){fail(TransactionEvidence.INVALID);}
            }
            @Override public void onError(String message){fail(message);}
        });
    }
    private void checkOwned(List<String> addresses,int index,Set<String> verified) {
        if(finished)return;
        if(index>=addresses.size()) {
            try { intent=split==1?PreparedSendEvidence.quick(draft,recipient,amount,token,burn,split,verified)
                    :PreparedSendEvidence.quickSplit(draft,recipient,amount,token,burn,split,verified,decimals); }
            catch(RuntimeException invalid){fail(TransactionEvidence.INVALID);return;}
            try {
                if(!act.history().prepareAttempt(historyId,handle,intent)){fail(TransactionEvidence.RECORD_FAILED);return;}
                prepared=true;
            }catch(RuntimeException unavailable){fail(TransactionEvidence.RECORD_FAILED);return;}
            sign();return;
        }
        String address=addresses.get(index);
        act.node().cmd("scripts address:"+address,new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                if(finished)return;
                if(!PreparedSendEvidence.defaultAddress(reply,address)){fail(TransactionEvidence.INVALID);return;}
                verified.add(address);checkOwned(addresses,index+1,verified);
            }
            @Override public void onError(String message){fail(message);}
        });
    }
    private void sign() {
        act.node().cmd("sendsign file:"+handle+".txn",new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                if(finished)return;
                try {
                    Object raw=reply.optJSONObject("response").opt("txpow");
                    if(!(raw instanceof String))throw TransactionEvidence.invalid();
                    String path=(String)raw;signedFile=path.substring(path.lastIndexOf('/')+1);
                    if(!signedFile.matches("signedtransaction-[0-9]{1,20}\\.txn"))throw TransactionEvidence.invalid();
                }catch(RuntimeException invalid){fail(TransactionEvidence.NOT_SENT);return;}
                verifySigned();
            }
            @Override public void onError(String message){fail(message);}
            @Override public void onUnknown(String message){fail(TransactionEvidence.NOT_SENT);}
        });
    }
    private void verifySigned() {
        act.node().cmd("sendview file:"+signedFile,new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                if(finished)return;
                try { TransactionEvidence.receipt(reply,intent); }
                catch(RuntimeException mismatch){fail(TransactionEvidence.NOT_SENT);return;}
                post();
            }
            @Override public void onError(String message){fail(TransactionEvidence.NOT_SENT);}
            @Override public void onUnknown(String message){fail(TransactionEvidence.NOT_SENT);}
        });
    }
    private void post() {
        if(finished)return;
        if(!mark(HistoryDb.POSTING,null)){fail(TransactionEvidence.SAVE_FAILED);return;}
        act.node().cmd("sendpost file:"+signedFile,new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                if(finished)return;
                final List<TxnBuilder.OutCoin> outputs;
                try { outputs=TransactionEvidence.receipt(reply,intent); }
                catch(RuntimeException incomplete){unknown();return;}
                if(!mark(HistoryDb.POSTED,reply)){unknown();return;}
                finished=true;act.history().update(historyId,HistoryDb.STATUS_POSTED,null,"");
                done.onPosted(TransactionEvidence.txpow(reply).optString("txpowid"),outputs);
            }
            @Override public void onError(String message){fail(message);}
            @Override public void onUnknown(String message){unknown();}
        });
    }
    private boolean mark(String phase,JSONObject reply) {
        try{return prepared&&act.history().markAttempt(historyId,phase,reply);}catch(RuntimeException unavailable){return false;}
    }
    private void fail(String message) {
        if(finished)return;
        if(prepared&&!mark(HistoryDb.NOT_SUBMITTED,null)){unknown();return;}
        finished=true;act.history().update(historyId,HistoryDb.STATUS_ERROR,null,message);done.onFailed(message);
    }
    private void unknown() {
        if(finished)return;finished=true;mark(HistoryDb.UNKNOWN,null);
        act.history().update(historyId,HistoryDb.STATUS_UNKNOWN,null,TransactionEvidence.UNKNOWN);
        done.onUnknown(TransactionEvidence.UNKNOWN);
    }
}
