package com.eurobuddha.utxo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.*;

/** Public intent/receipt checks, adapted from BurialEvidence for multi-input wallet transactions. */
final class TransactionEvidence {
    static final String INVALID = "The selected coins or transaction outputs are invalid. Review the transaction again.";
    static final String UNKNOWN = "The node has not confirmed the transaction result. It may still have posted. Do not send it again until its outcome is verified.";
    static final String SAVE_FAILED = "Could not save the transaction state. Nothing was sent.";
    static final String DISTRIBUTION_UNKNOWN = "Distribution paused: the previous batch's outcome is unknown.";
    static final String RECORD_FAILED = "Could not reserve these coins. An earlier transaction may still be unresolved. Nothing new was sent.";
    static final String NOT_SENT = "Transaction preparation stopped. No transaction was sent. Any signing key uses already consumed are unchanged.";
    private TransactionEvidence() {}
    static IllegalArgumentException invalid() { return new IllegalArgumentException(INVALID); }
    static String amount(JSONObject coin, String tokenid) {
        Object value = coin == null ? null : coin.opt(Util.isMinima(tokenid) ? "amount" : "tokenamount");
        if (!(value instanceof String) || !Util.isValidAmount((String)value)
                || new BigDecimal((String)value).signum() <= 0) throw invalid();
        return new BigDecimal((String)value).stripTrailingZeros().toPlainString();
    }
    static BigDecimal rawAmount(JSONObject coin) {
        Object value=coin==null?null:coin.opt("amount");
        if (!(value instanceof String) || !Util.isValidAmount((String)value)
                || new BigDecimal((String)value).signum()<=0) throw invalid();
        return new BigDecimal((String)value);
    }
    static String coinId(JSONObject coin) {
        String id = TransactionFields.id(coin, "coinid");
        if (new java.math.BigInteger(id.substring(2),16).signum() == 0) throw invalid();
        return id.toLowerCase(Locale.ROOT);
    }
    /** Freeze command-bound fields before the first asynchronous build step. */
    static JSONObject intent(List<Coin> inputs, List<TxnBuilder.Out> outputs, String tokenid, String burn) {
        return intent(inputs,outputs,tokenid,burn,false);
    }
    /** The separate Minima burn transaction may consume its entire input and have no outputs. */
    static JSONObject intent(List<Coin> inputs,List<TxnBuilder.Out> outputs,String tokenid,String burn,boolean burnOnly) {
        if (!Util.isValidHexId(tokenid) || inputs == null || inputs.isEmpty() || outputs == null
                || (outputs.isEmpty() && !burnOnly) || (burnOnly && !Util.isMinima(tokenid))) throw invalid();
        try {
            JSONArray ins = new JSONArray(), outs = new JSONArray(); Set<String> ids = new HashSet<>();
            BigDecimal totalIn = BigDecimal.ZERO, totalOut = BigDecimal.ZERO, firstRaw = null, firstAmount = null;
            for (Coin coin : inputs) {
                if (coin == null || !coin.isWellFormed() || !coin.confirmed || !coin.sendable || coin.spent
                        || !TransactionFields.same(tokenid,coin.tokenid)) throw invalid();
                JSONObject input = new JSONObject().put("coinid",coin.coinid).put("tokenid",tokenid)
                        .put("amount",coin.rawAmount).put("tokenamount",coin.amount);
                if (!ids.add(coinId(input))) throw invalid();
                String value = amount(input,tokenid);
                BigDecimal raw = rawAmount(input), human = new BigDecimal(value);
                if (firstRaw == null) { firstRaw = raw; firstAmount = human; }
                if (raw.multiply(firstAmount).compareTo(human.multiply(firstRaw)) != 0
                        || human.compareTo(new BigDecimal(coin.amount)) != 0) throw invalid();
                totalIn = totalIn.add(new BigDecimal(value)); ins.put(input);
            }
            for (TxnBuilder.Out output : outputs) {
                if (output == null || !Util.isValidAddress(output.address) || !Util.isValidAmount(output.amount)
                        || new BigDecimal(output.amount).signum() <= 0) throw invalid();
                totalOut = totalOut.add(new BigDecimal(output.amount));
                JSONObject savedOutput=new JSONObject().put("address",output.address).put("amount",output.amount);
                if(output.storestate!=null)savedOutput.put("storestate",output.storestate);
                outs.put(savedOutput);
            }
            String fee = burn == null || burn.isEmpty() ? "0" : burn;
            if (!Util.isValidAmount(fee) || (!Util.isMinima(tokenid) && new BigDecimal(fee).signum() != 0)
                    || totalIn.subtract(totalOut).compareTo(new BigDecimal(fee)) != 0) throw invalid();
            return new JSONObject().put("tokenid",tokenid).put("inputs",ins).put("outputs",outs).put("burn",fee);
        } catch (org.json.JSONException e) { throw invalid(); }
    }
    /** Parse the entire stored value, reusing PandaDEX MakerConfig/MinimaAPIResponse's trailing-data guard. */
    static JSONObject storedObject(String raw) {
        if (raw == null || raw.indexOf(0) >= 0) throw invalid();
        try {
            org.json.JSONTokener input = new org.json.JSONTokener(raw); Object value = input.nextValue();
            while (input.more()) {
                char c = input.next();
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') throw invalid();
            }
            if (!(value instanceof JSONObject)) throw invalid();
            return (JSONObject)value;
        } catch (org.json.JSONException e) { throw invalid(); }
    }
    /** Validate a saved public intent with the same amount/balance/identity rules as a fresh build. */
    static JSONObject storedIntent(String raw) { return storedIntent(raw,false); }
    private static JSONObject storedIntent(String raw,boolean burnOnly) {
        JSONObject saved = storedObject(raw);
        String token = TransactionFields.id(saved, "tokenid");
        if (!(saved.opt("burn") instanceof String)) throw invalid();
        JSONArray ins = saved.optJSONArray("inputs"), outs = saved.optJSONArray("outputs");
        if (ins == null || outs == null) throw invalid();
        List<Coin> coins = new ArrayList<>(); List<TxnBuilder.Out> outputs = new ArrayList<>();
        for (int i=0;i<ins.length();i++) {
            JSONObject input = ins.optJSONObject(i); Coin coin = new Coin();
            coin.coinid = coinId(input); coin.tokenid = TransactionFields.id(input,"tokenid");
            rawAmount(input); TransactionFields.amount(input);
            coin.rawAmount = input.optString("amount"); coin.amount = input.optString("tokenamount");
            // These flags describe the saved pre-build snapshot, not a fresh chain observation.
            coin.confirmed = coin.sendable = true; coins.add(coin);
        }
        for (int i=0;i<outs.length();i++) {
            JSONObject output = outs.optJSONObject(i);
            if (output == null || !(output.opt("address") instanceof String) || !(output.opt("amount") instanceof String)) throw invalid();
            if(output.has("storestate")&&!(output.opt("storestate") instanceof Boolean))throw invalid();
            outputs.add(new TxnBuilder.Out(output.optString("address"),output.optString("amount"),
                    output.has("storestate")?(Boolean)output.opt("storestate"):null));
        }
        JSONObject result=intent(coins,outputs,token,saved.optString("burn"),burnOnly);
        try {
            if(saved.has("transactionid"))result.put("transactionid",nonzeroId(saved,"transactionid"));
            if(saved.has("companion")) {
                if(burnOnly||Util.isMinima(token)||saved.optJSONObject("companion")==null)throw invalid();
                JSONObject companion=storedIntent(saved.optJSONObject("companion").toString(),true);
                if(!Util.isMinima(companion.optString("tokenid"))||new BigDecimal(companion.optString("burn")).signum()<=0)throw invalid();
                result.put("companion",companion);inputIds(result);
            }
        }catch(org.json.JSONException e){throw invalid();}
        return result;
    }
    static java.util.Set<String> inputIds(JSONObject intent) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        JSONArray inputs=intent.optJSONArray("inputs");
        if(inputs==null||inputs.length()==0)throw invalid();
        for(int i=0;i<inputs.length();i++)if(!ids.add(coinId(inputs.optJSONObject(i))))throw invalid();
        if(intent.has("companion")) {
            JSONObject companion=intent.optJSONObject("companion");
            if(companion==null||companion.has("companion"))throw invalid();
            for(String id:inputIds(companion))if(!ids.add(id))throw invalid();
        }
        return ids;
    }
    /** Store only public receipt identifiers/economics; never retain signatures or the witness. */
    static JSONObject publicReceipt(JSONObject reply,JSONObject intent) {
        receipt(reply,intent);
        JSONObject result=publicReceiptOnly(reply,intent);
        try { if(intent.has("companion"))result.put("companion",publicReceiptOnly(companionReply(reply),intent.optJSONObject("companion"))); }
        catch(org.json.JSONException e){throw invalid();}
        return result;
    }
    private static JSONObject publicReceiptOnly(JSONObject reply,JSONObject intent) {
        List<TxnBuilder.OutCoin> matched=receiptOnly(reply,intent);
        try {
            JSONArray outs=new JSONArray();
            for(TxnBuilder.OutCoin c:matched) {
                JSONObject out=new JSONObject().put("coinid",c.coinid).put("address",c.address)
                        .put("miniaddress",c.miniaddress).put("amount",c.amount);
                if(c.storestate!=null)out.put("storestate",c.storestate);
                outs.put(out);
            }
            return new JSONObject().put("txpowid",txpow(reply).optString("txpowid")).put("outputs",outs);
        }catch(org.json.JSONException e){throw invalid();}
    }
    static String nonzeroId(JSONObject value,String field) {
        String id=TransactionFields.id(value,field);
        if(new java.math.BigInteger(id.substring(2),16).signum()==0)throw invalid();
        return id;
    }
    /** Validate journal receipts against both sets of reserved inputs and outputs. */
    static void storedReceipt(JSONObject receipt,JSONObject intent) { storedReceipt(receipt,intent,inputIds(intent)); }
    private static void storedReceipt(JSONObject receipt,JSONObject intent,Set<String> ids) {
        String txpowid=nonzeroId(receipt,"txpowid");
        JSONArray outputs=receipt.optJSONArray("outputs"),expected=intent.optJSONArray("outputs");
        if(outputs==null||outputs.length()!=expected.length())throw invalid();
        for(int i=0;i<outputs.length();i++) {
            JSONObject coin=outputs.optJSONObject(i),want=expected.optJSONObject(i);
            TransactionFields.id(coin,"address");
            if(!(coin.opt("miniaddress") instanceof String)||!ids.add(coinId(coin))
                    ||!matchesState(coin,want)||!TransactionFields.addressIs(coin,want.optString("address"))
                    ||!amount(coin,"0x00").equals(new BigDecimal(want.optString("amount")).stripTrailingZeros().toPlainString()))throw invalid();
        }
        if(receipt.has("companion")!=intent.has("companion"))throw invalid();
        if(intent.has("companion")) {
            JSONObject other=receipt.optJSONObject("companion");
            if(!TransactionFields.same(txpowid,nonzeroId(other,"txpowid")))throw invalid();
            storedReceipt(other,intent.optJSONObject("companion"),ids);
        }
    }
    private static JSONObject companionReply(JSONObject reply) {
        JSONObject main=txpow(reply),body=main.optJSONObject("body"),burn=body==null?null:body.optJSONObject("burntxn");
        if(burn==null)throw invalid();
        try { return new JSONObject().put("status",true).put("response",new JSONObject().put("txpowid",main.optString("txpowid"))
                .put("body",new JSONObject().put("txn",burn))); }
        catch(org.json.JSONException e){throw invalid();}
    }
    /** Match the node's configured default confirmation depth (GlobalParams.MINIMA_CONFIRM_DEPTH = 3). */
    static JSONObject inclusion(JSONObject reply) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status")) || Boolean.TRUE.equals(reply.opt("pending"))) throw invalid();
        JSONObject value = reply.optJSONObject("response");
        if (value == null || !Boolean.TRUE.equals(value.opt("found"))) throw invalid();
        String blockId = TransactionFields.id(value,"blockid");
        if (new java.math.BigInteger(blockId.substring(2),16).signum() == 0) throw invalid();
        java.math.BigInteger block = whole(value,"block"), tip = whole(value,"tip"), depth = whole(value,"confirmations");
        if (block.signum() <= 0 || depth.compareTo(java.math.BigInteger.valueOf(3)) < 0
                || !tip.subtract(block).equals(depth)) throw invalid();
        try { return new JSONObject().put("found",true).put("blockid",blockId)
                .put("block",block.toString()).put("tip",tip.toString()).put("confirmations",depth.toString()); }
        catch (org.json.JSONException e) { throw invalid(); }
    }
    private static java.math.BigInteger whole(JSONObject object,String key) {
        Object raw=object.opt(key);
        if (!(raw instanceof String) || !((String)raw).matches("[0-9]{1,30}")) throw invalid();
        return new java.math.BigInteger((String)raw);
    }
    static JSONObject txpow(JSONObject reply) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status")) || Boolean.TRUE.equals(reply.opt("pending"))) throw invalid();
        JSONObject response = reply.optJSONObject("response");
        if (response == null || (response.has("txnpost") && !Boolean.TRUE.equals(response.opt("txnpost")))) throw invalid();
        JSONObject txpow = response.optJSONObject("txpow");
        if (txpow == null) txpow = response;
        String id = TransactionFields.id(txpow,"txpowid");
        if (new java.math.BigInteger(id.substring(2),16).signum() == 0) throw invalid();
        return txpow;
    }
    /** Match every input and every output, including multiplicity. No local-ID fallback or partial match. */
    static List<TxnBuilder.OutCoin> receipt(JSONObject reply,JSONObject intent) {
        List<TxnBuilder.OutCoin> result=receiptOnly(reply,intent);
        JSONObject body=txpow(reply).optJSONObject("body"),burn=body==null?null:body.optJSONObject("burntxn");
        Set<String> ids=inputIds(intent);
        for(TxnBuilder.OutCoin coin:result)if(!ids.add(coin.coinid.toLowerCase(Locale.ROOT)))throw invalid();
        if(intent.has("companion")) {
            if(burn==null||!TransactionFields.same(nonzeroId(body.optJSONObject("txn"),"transactionid"),nonzeroId(burn,"linkhash")))throw invalid();
            for(TxnBuilder.OutCoin coin:receiptOnly(companionReply(reply),intent.optJSONObject("companion")))
                if(!ids.add(coin.coinid.toLowerCase(Locale.ROOT)))throw invalid();
        } else if(burn!=null) {
            JSONArray inputs=burn.optJSONArray("inputs"),outputs=burn.optJSONArray("outputs");
            if(inputs==null||outputs==null||inputs.length()!=0||outputs.length()!=0)throw invalid();
        }
        return result;
    }
    private static boolean matchesState(JSONObject actual,JSONObject expected) {
        if(actual.has("storestate")&&!(actual.opt("storestate") instanceof Boolean))return false;
        return !expected.has("storestate")||expected.opt("storestate").equals(actual.opt("storestate"));
    }
    private static List<TxnBuilder.OutCoin> receiptOnly(JSONObject reply,JSONObject intent) {
        JSONObject txpow = txpow(reply), body = txpow.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn");
        if(intent.has("transactionid")&&!TransactionFields.same(nonzeroId(intent,"transactionid"),nonzeroId(txn,"transactionid")))throw invalid();
        JSONArray ins = txn == null ? null : txn.optJSONArray("inputs"), outs = txn == null ? null : txn.optJSONArray("outputs");
        JSONArray expectedIn = intent.optJSONArray("inputs"), expectedOut = intent.optJSONArray("outputs");
        if (ins == null || outs == null || expectedIn == null || expectedOut == null
                || ins.length() != expectedIn.length() || outs.length() != expectedOut.length()) throw invalid();
        String token = intent.optString("tokenid");
        Map<String,JSONObject> actualInputs = new HashMap<>();
        for (int i=0;i<ins.length();i++) {
            JSONObject coin = ins.optJSONObject(i);
            if (actualInputs.put(coinId(coin),coin) != null) throw invalid();
        }
        for (int i=0;i<expectedIn.length();i++) {
            JSONObject expected = expectedIn.optJSONObject(i), actual = actualInputs.get(coinId(expected));
            if (actual == null || !TransactionFields.same(token,TransactionFields.id(actual,"tokenid"))
                    || !amount(expected,token).equals(amount(actual,token))
                    || rawAmount(expected).compareTo(rawAmount(actual)) != 0) throw invalid();
        }
        Set<String> ids = new HashSet<>(); boolean[] used = new boolean[outs.length()];
        for (int i=0;i<outs.length();i++) {
            JSONObject coin = outs.optJSONObject(i); String id = coinId(coin);
            if (!ids.add(id) || actualInputs.containsKey(id) || !TransactionFields.same(token,TransactionFields.id(coin,"tokenid"))) throw invalid();
            String human = amount(coin,token);
            JSONObject reference = expectedIn.optJSONObject(0);
            if (!Util.isValidAddress(TransactionFields.id(coin,"address"))
                    || rawAmount(coin).multiply(new BigDecimal(amount(reference,token)))
                    .compareTo(new BigDecimal(human).multiply(rawAmount(reference))) != 0) throw invalid();
        }
        List<TxnBuilder.OutCoin> result = new ArrayList<>();
        for (int i=0;i<expectedOut.length();i++) {
            JSONObject expected = expectedOut.optJSONObject(i); int match = -1;
            for (int j=0;j<outs.length();j++) {
                JSONObject coin = outs.optJSONObject(j);
                if (!used[j] && matchesState(coin,expected) && TransactionFields.addressIs(coin,expected.optString("address"))
                        && new BigDecimal(expected.optString("amount")).compareTo(new BigDecimal(amount(coin,token))) == 0) { match=j; break; }
            }
            if (match < 0) throw invalid(); used[match] = true;
            JSONObject coin = outs.optJSONObject(match);
            result.add(new TxnBuilder.OutCoin(coin.optString("coinid"),coin.optString("address"),coin.optString("miniaddress",""),amount(coin,token),
                    coin.has("storestate")?(Boolean)coin.opt("storestate"):null));
        }
        return result;
    }
}
