"""Share durable Ethereum nonce ownership across the integrated clients."""
from pathlib import Path

def adapt(root):
    for app in ['ethwallet','atomix']:
        folder=root/app/'src/main/java/com/eurobuddha'/app/'eth'
        p=folder/'EthTx.java';s=p.read_text()
        if app=='atomix':
            a=s.index('    // ── Per-address nonce serializer');b=s.index('    /** Matches NodeApi',a)
            s=s[:a]+s[b:]
            s=s.replace('public static void resetAll() { NONCE.clear(); }','public static void resetAll() { /* Durable nonce ownership survives endpoint and account changes. */ }')
            s=s.replace('/** Forget all cached nonce state — call on a chain/endpoint switch so we cold-start from the new node. */','/** Compatibility entry point. Connection changes cannot erase outstanding transactions. */')
            a=s.index('        requireAuthorized(authorized);\n        // Automated operations')
            b=s.index('    /** Same explicit-price boundary',a)
            s=s[:a]+'''        return sendOperation(rpc,creds,chainId,to,data,value,gasLimit,authorized,"pending");
    }

    public static String sendOperation(EthRpc rpc,Credentials creds,long chainId,String to,String data,
                                       BigInteger value,BigInteger gasLimit,java.util.function.BooleanSupplier authorized,String operation) throws Exception {
        requireAuthorized(authorized);
        BigInteger rawGp = EthRpc.hexToBig(rpc.callStr("eth_gasPrice", new JSONArray()));
        if (rawGp.signum() <= 0) rawGp = FALLBACK_GAS_PRICE;
        BigInteger gasPrice = EthSend.effectiveGasPriceWei(rawGp, rpc.baseFeePerGasOrZero());
        requireFees(gasPrice,gasLimit);
        return submit(rpc,creds,chainId,to,data,value,gasLimit,gasPrice,authorized,operation);
    }

'''+s[b:]
            a=s.index('        NonceState st =');s=s[:a]+'''        return submit(rpc,creds,chainId,to,data,value,gasLimit,gasPrice,authorized,null);
    }
}
'''
        else:
            a=s.index('        BigInteger nonce = rpc.getTransactionCount');s=s[:a]+'''        return submit(rpc,creds,chainId,to,data,value,gasLimit,gasPrice,authorized,null);
    }
}
'''
        body='''
    private static String submit(EthRpc rpc,Credentials creds,long chainId,String to,String data,BigInteger value,
                                 BigInteger gas,BigInteger price,java.util.function.BooleanSupplier authorized,String operation) throws Exception {
        requireAuthorized(authorized);
        return com.eurobuddha.minimaapi.ethereum.EthereumTransactions.shared().send(
                new com.eurobuddha.minimaapi.ethereum.EthereumTransactions.Rpc() {
                    public BigInteger chain() throws Exception { return EthRpc.transactionCount(rpc.call("eth_chainId",new JSONArray())); }
                    public BigInteger pending(String address) throws Exception { return rpc.getTransactionCount(address); }
                    public String broadcast(String raw) throws Exception { return rpc.sendRawTransaction(raw); }
                    public org.json.JSONObject receipt(String hash) throws Exception { return object(rpc.call("eth_getTransactionReceipt",new JSONArray().put(hash))); }
                    public org.json.JSONObject transaction(String hash) throws Exception { return object(rpc.call("eth_getTransactionByHash",new JSONArray().put(hash))); }
                },creds,chainId,to,data,value,gas,price,operation,()->requireAuthorized(authorized));
    }
    private static org.json.JSONObject object(Object value) throws java.io.IOException {
        if(value==null||value==org.json.JSONObject.NULL)return null;
        if(!(value instanceof org.json.JSONObject))throw new java.io.IOException("Invalid Ethereum transaction lookup");
        return(org.json.JSONObject)value;
    }
'''
        s=s[:s.rindex('}')]+body+'}\n';p.write_text(s)
    p=root/'atomix/src/main/java/com/eurobuddha/atomix/eth/EthHtlc.java';s=p.read_text()
    s=s.replace('return EthTx.send(rpc, creds, net.chainId, net.htlc, FunctionEncoder.encode(f), null, GAS_LOCK, authorized);','return EthTx.sendOperation(rpc, creds, net.chainId, net.htlc, FunctionEncoder.encode(f), null, GAS_LOCK, authorized,"lock:"+hashlock.toLowerCase(java.util.Locale.ROOT));')
    s=s.replace('return EthTx.send(rpc, creds, net.chainId, net.htlc, FunctionEncoder.encode(f), null, GAS_WITHDRAW);','return EthTx.sendOperation(rpc, creds, net.chainId, net.htlc, FunctionEncoder.encode(f), null, GAS_WITHDRAW,()->true,"withdraw:"+contractId.toLowerCase(java.util.Locale.ROOT));')
    s=s.replace('return EthTx.send(rpc, creds, net.chainId, net.htlc, FunctionEncoder.encode(f), null, GAS_REFUND);','return EthTx.sendOperation(rpc, creds, net.chainId, net.htlc, FunctionEncoder.encode(f), null, GAS_REFUND,()->true,"refund:"+contractId.toLowerCase(java.util.Locale.ROOT));')
    p.write_text(s)

    # Keep an ambiguous submission visibly distinct from a rejected, safe-to-retry send.
    for app in ['ethwallet','atomix']:
        p=root/app/'src/main/java/com/eurobuddha'/app/'MainActivity.java';s=p.read_text()
        if app=='ethwallet':
            old='onUi(() -> toast(Language.msg(l10n_eth_wallet_send_failed, EthMessages.error(e))));'
            new='if (e instanceof com.eurobuddha.minimaapi.ethereum.EthereumTransactions.Uncertain) {\n                                String hash = ((com.eurobuddha.minimaapi.ethereum.EthereumTransactions.Uncertain)e).hash;\n                                prefs.edit().putString("lastTx", hash).apply();\n                                onUi(() -> toast(Language.join(Language.msg(l10n_nft_transaction_unknown), "\\n", hash)));\n                            } else onUi(() -> toast(Language.msg(l10n_eth_wallet_send_failed, EthMessages.error(e))));'
        else:
            old='final CharSequence failure = context.current() ? AtomixMessages.error(e) : Language.msg(l10n_wallet_review_again);\n                        ui.post(() -> toast(Language.msg(l10n_atomix_ui_send_failed, failure)));'
            new='if (e instanceof com.eurobuddha.minimaapi.ethereum.EthereumTransactions.Uncertain) {\n                            String hash = ((com.eurobuddha.minimaapi.ethereum.EthereumTransactions.Uncertain)e).hash;\n                            SwapLog.w("manual send outcome unknown tx=" + hash);\n                            ui.post(() -> toast(Language.join(Language.msg(l10n_nft_transaction_unknown), "\\n", hash)));\n                        } else {\n                            final CharSequence failure = context.current() ? AtomixMessages.error(e) : Language.msg(l10n_wallet_review_again);\n                            ui.post(() -> toast(Language.msg(l10n_atomix_ui_send_failed, failure)));\n                        }'
        if old not in s: raise RuntimeError('Missing Ethereum send failure boundary: '+app)
        p.write_text(s.replace(old,new))
    # A retry must retain the timelock persisted before its first broadcast.
    p=root/'atomix/src/main/java/com/eurobuddha/atomix/swap/SwapEngine.java';s=p.read_text()
    old='final long timelock = ethChainNowStrict() + CP_SECS;'
    new='final SwapDb.Swap existing = db.getSwap(hash);\n            final long chainNow = ethChainNowStrict();\n            final long timelock = existing != null && !existing.myLegIsMinima && existing.myTimelock > 0\n                    ? existing.myTimelock : chainNow + CP_SECS;\n            if (timelock <= chainNow) throw new java.io.IOException("Saved Ethereum counter-leg expired; no new lock was sent.");'
    if old not in s: raise RuntimeError('Missing Ethereum counter-leg timelock boundary')
    p.write_text(s.replace(old,new))

    for app in ['ethwallet','atomix']:
        p=root/app/'src/main/java/com/eurobuddha'/app/'eth/EthRpc.java';s=p.read_text()
        old='JSONObject resp = new JSONObject(trimmed);'
        new='if (code < 200 || code >= 300) throw new IOException(host(url) + " HTTP " + code);\n            org.json.JSONTokener parser = new org.json.JSONTokener(trimmed);\n            Object parsed = parser.nextValue();\n            if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) throw new IOException("Invalid Ethereum RPC envelope");\n            JSONObject resp = (JSONObject)parsed;\n            if (!"2.0".equals(resp.opt("jsonrpc")) || !(resp.opt("id") instanceof Number)\n                    || !"1".equals(resp.opt("id").toString()) || resp.has("result") == resp.has("error"))\n                throw new IOException("Invalid Ethereum RPC envelope");'
        if old not in s: raise RuntimeError('Missing RPC envelope: '+app)
        s=s.replace(old,new).replace('c.setDoOutput(true);','c.setDoOutput(true);\n            c.setInstanceFollowRedirects(false);')
        s=s.replace('if ("eth_getTransactionCount".equals(method)) transactionCount(result);','if ("eth_getTransactionCount".equals(method) || "eth_chainId".equals(method)) transactionCount(result);')
        old='while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);'
        new='// Same bounded reader as Salon RelayBlob; an RPC cannot exhaust the host heap.\n            while ((n = in.read(buf)) != -1) {\n                if (n > 4 * 1024 * 1024 - bos.size()) throw new IOException("Ethereum RPC response too large");\n                bos.write(buf, 0, n);\n            }'
        if old not in s: raise RuntimeError('Missing RPC bounded read: '+app)
        s=s.replace(old,new)
        if app=='ethwallet':
            s=s.replace('set.add(url);','String primary = isHttps(url) ? url.trim() : EthNet.MAINNET.defaultRpc;\n        set.add(primary);').replace('this.url = url;', 'this.url = primary;')
            s=s.replace('    public String url() { return url; }','    private static boolean isHttps(String u) {\n        if (u == null) return false;\n        try { return "https".equalsIgnoreCase(new URL(u.trim()).getProtocol()); }\n        catch (Exception e) { return false; }\n    }\n\n    public String url() { return url; }')
        p.write_text(s)
