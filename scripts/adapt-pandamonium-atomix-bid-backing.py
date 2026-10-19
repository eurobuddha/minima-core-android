"""Reuse ask-prefix funding, ERC20 balanceOf and engine context guards for bids."""
from pathlib import Path

def adapt(root):
    base=root/'atomix/src/main/java/com/eurobuddha/atomix'
    (base/'eth/PendingTokenDebits.java').write_text((Path(__file__).with_name('pandamonium-sources')/'atomix/PendingTokenDebits.java').read_text())
    p=base/'swap/Order.java';s=p.read_text();marker='    // ---- (de)serialization'
    helper='''    /** Best-price prefix, as for asks; a bid spends amount * price USDT. */
    public void clampBidsToBalance(java.math.BigDecimal available) {
        java.math.BigDecimal remaining=available==null?java.math.BigDecimal.ZERO:available.max(java.math.BigDecimal.ZERO);
        for(Pair pair:pairs.values()) {
            if(pair==null||!pair.enable)continue;
            sanitize(pair);
            if(!pair.bids.isEmpty()) {
                int keep=0;
                for(Level level:pair.bids) {
                    java.math.BigDecimal cost=java.math.BigDecimal.valueOf(level.amount).multiply(java.math.BigDecimal.valueOf(level.price));
                    if(level.amount<pair.min||cost.compareTo(remaining)>0)break;
                    remaining=remaining.subtract(cost);keep++;
                }
                while(pair.bids.size()>keep)pair.bids.remove(pair.bids.size()-1);
                if(pair.bids.isEmpty())pair.sell=0;
            } else if(validPos(pair.sell)) {
                java.math.BigDecimal minimum=java.math.BigDecimal.valueOf(Math.max(0,pair.min)).multiply(java.math.BigDecimal.valueOf(pair.sell));
                if(remaining.signum()==0||remaining.compareTo(minimum)<0)pair.sell=0;
                else remaining=java.math.BigDecimal.ZERO;
            }
        }
    }

'''
    assert s.count(marker)==1;s=s.replace(marker,helper+marker)
    needle='        r.clampAsksToBalance(java.math.BigDecimal.valueOf(r.minimaAvail));'
    assert s.count(needle)==1;s=s.replace(needle,needle+'\n        r.clampBidsToBalance(java.math.BigDecimal.valueOf(r.usdtAvail));');p.write_text(s)

    p=base/'eth/EthWallet.java';s=p.read_text();marker='    /** Format a raw integer amount'
    helper='''    /** Account balance and nonce share one checked block; pending data can only lower funding. */
    public static BigInteger erc20AvailableRaw(EthRpc rpc, EthNet network, String token, String owner,
            com.eurobuddha.minimaapi.ethereum.EthereumTransactions.FundingSnapshot snapshot) throws IOException {
        if(rpc==null||network==null||owner==null||!owner.matches("0x[0-9a-fA-F]{40}")
                ||token==null||!token.matches("0x[0-9a-fA-F]{40}")||snapshot==null)throw new IOException("Invalid Ethereum balance context");
        if(!BigInteger.valueOf(network.chainId).equals(EthRpc.transactionCount(rpc.call("eth_chainId",new org.json.JSONArray()))))
            throw new IOException("Ethereum RPC is connected to a different chain");
        Object rawBlock=rpc.call("eth_getBlockByNumber",new org.json.JSONArray().put("latest").put(false));
        if(!(rawBlock instanceof JSONObject))throw new IOException("Missing Ethereum balance block");
        JSONObject block=(JSONObject)rawBlock;
        String height=block.optString("number"),hash=block.optString("hash");
        EthRpc.transactionCount(height);
        if(!hash.matches("0x[0-9a-fA-F]{64}")||new BigInteger(hash.substring(2),16).signum()==0)throw new IOException("Invalid Ethereum balance block");
        BigInteger minedNonce=EthRpc.transactionCount(rpc.call("eth_getTransactionCount",new org.json.JSONArray().put(owner).put(height)));
        BigInteger reserved=PendingTokenDebits.total(snapshot.intents,minedNonce,owner,token,network.htlc);
        Function fn=new Function("balanceOf",Collections.singletonList(new Address(owner)),Collections.singletonList(new TypeReference<Uint256>() {}));
        BigInteger available=null;
        for(String tag:new String[]{height,"pending"}) {
            Object result;
            try {
                JSONObject tx=new JSONObject().put("to",token).put("data",FunctionEncoder.encode(fn));
                result=rpc.call("eth_call",new org.json.JSONArray().put(tx).put(tag));
            }catch(org.json.JSONException invalid){throw new IOException("Invalid Ethereum balance request",invalid);}
            if(!(result instanceof String)||!((String)result).matches("0[xX][0-9a-fA-F]{64}"))throw new IOException("Invalid Ethereum balance reply");
            BigInteger balance=new BigInteger(((String)result).substring(2),16);
            // Deduct local reservations from mined state only. Pending may already include the debit.
            available=available==null?balance.subtract(reserved).max(BigInteger.ZERO):available.min(balance);
        }
        Object current=rpc.call("eth_getBlockByNumber",new org.json.JSONArray().put(height).put(false));
        if(!(current instanceof JSONObject)||!hash.equalsIgnoreCase(((JSONObject)current).optString("hash"))
                ||!height.equals(((JSONObject)current).optString("number"))||!snapshot.unchanged())
            throw new IOException("Ethereum funding changed during review");
        return available;
    }

'''
    assert s.count(marker)==1;s=s.replace(marker,helper+marker);p.write_text(s)

    p=base/'swap/SwapOrderBook.java';s=p.read_text()
    signature='    public static void publishFresh(NodeApi node, LazySodium ls, CommsIdentity id, Order base, long generation, CommsTransport.SendCb cb) {'
    assert s.count(signature)==1
    s=s.replace(signature,signature+'''
        publishFresh(node,ls,id,base,generation,()->true,cb);
    }
    static void publishFresh(NodeApi node, LazySodium ls, CommsIdentity id, Order base, long generation,
                             java.util.function.BooleanSupplier authorized, CommsTransport.SendCb cb) {''')
    a=s.index('    static void publishFresh');b=s.index('    public static final String BALANCE_ERROR',a)
    section=s[a:b].replace('!current(base, generation)','!current(base, generation) || !authorized.getAsBoolean()')
    section=section.replace('publish(node, ls, id, base, generation, cb)','publish(node, ls, id, base, generation, authorized, cb)')
    s=s[:a]+section+s[b:]
    signature='    private static void publish(NodeApi node, LazySodium ls, CommsIdentity id, Order order, long generation, CommsTransport.SendCb cb) {'
    assert s.count(signature)==1;s=s.replace(signature,signature+'''
        publish(node,ls,id,order,generation,()->true,cb);
    }
    private static void publish(NodeApi node, LazySodium ls, CommsIdentity id, Order order, long generation,
                                java.util.function.BooleanSupplier authorized, CommsTransport.SendCb cb) {''')
    a=s.index('                                java.util.function.BooleanSupplier authorized');b=s.index('    /**\n     * Bounded one-shot',a)
    section=s[a:b].replace('!current(order, generation)','!current(order, generation) || !authorized.getAsBoolean()')
    section=section.replace('() -> current(order, generation)','() -> current(order, generation) && authorized.getAsBoolean()')
    s=s[:a]+section+s[b:];p.write_text(s)

    p=base/'swap/SwapEngine.java';s=p.read_text();marker='    /** At publish: trim the ask ladder'
    helper='''    /** Fresh bid backing through the existing worker/main callback boundary, then fresh Minima backing. */
    public void publishFresh(com.goterl.lazysodium.LazySodium sodium,
                             com.eurobuddha.atomix.integratedcomms.CommsIdentity identity, Order order, long generation,
                             com.eurobuddha.atomix.integratedcomms.CommsTransport.SendCb cb) {
        final EthRpc connection=rpc;
        final EthNet network=net;
        final Credentials owner=wallet.creds();
        final String minimaKey=myMinimaPk;
        final com.eurobuddha.atomix.TradingContext market=order==null?null:order.market;
        final java.util.concurrent.atomic.AtomicReference<com.eurobuddha.minimaapi.ethereum.EthereumTransactions.FundingSnapshot> funding=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.BooleanSupplier current=()->order!=null&&market!=null&&market==com.eurobuddha.atomix.TradingContext.active()
                &&generation==PublishGate.gen()&&!io.isShutdown()&&!halted()&&connection==rpc&&network==net
                &&owner!=null&&owner==wallet.creds()&&owner.getAddress().equalsIgnoreCase(order.ethAddress)
                &&java.util.Objects.equals(minimaKey,myMinimaPk)&&java.util.Objects.equals(minimaKey,order.minimaPublicKey)
                &&(funding.get()==null||funding.get().unchanged());
        if(!current.getAsBoolean()){cb.onFailed(SwapOrderBook.MARKET_ERROR);return;}
        boolean bids=false;
        for(String symbol:order.pairs.keySet())if(!order.effectiveBids(symbol).isEmpty())bids=true;
        if(!bids) {
            order.usdtAvail=0;
            SwapOrderBook.publishFresh(node,sodium,identity,order,generation,current,cb);return;
        }
        try { io.execute(()->{
            if(!current.getAsBoolean()){ui.post(()->cb.onFailed(SwapOrderBook.MARKET_ERROR));return;}
            final BigDecimal available;
            try {
                EthNet.Token token=network==null?null:network.token("USDT");
                if(token==null)throw new IllegalStateException();
                com.eurobuddha.minimaapi.ethereum.EthereumTransactions.FundingSnapshot snapshot=
                        com.eurobuddha.minimaapi.ethereum.EthereumTransactions.shared().fundingSnapshot(network.chainId,owner.getAddress());
                funding.set(snapshot);
                available=new BigDecimal(EthWallet.erc20AvailableRaw(connection,network,token.address,owner.getAddress(),snapshot),token.decimals);
            } catch(Exception unavailable){ui.post(()->cb.onFailed(SwapOrderBook.BALANCE_ERROR));return;}
            ui.post(()->{
                if(!current.getAsBoolean()){cb.onFailed(SwapOrderBook.MARKET_ERROR);return;}
                order.usdtAvail=available.doubleValue();order.clampBidsToBalance(available);
                SwapOrderBook.publishFresh(node,sodium,identity,order,generation,current,cb);
            });
        }); }catch(java.util.concurrent.RejectedExecutionException stopped){cb.onFailed(SwapOrderBook.MARKET_ERROR);}
    }

'''
    assert s.count(marker)==1;s=s.replace(marker,helper+marker);p.write_text(s)
    for name,count in [('MainActivity.java',3),('SwapService.java',1)]:
        p=base/name;s=p.read_text();old='SwapOrderBook.publishFresh(node, ls, identity, o, gen,'
        assert s.count(old)==count,(name,s.count(old));p.write_text(s.replace(old,'engine.publishFresh(ls, identity, o, gen,'))

    p=base/'MainActivity.java';s=p.read_text();old='orderStatus = live ? "✓ Order updated" : "✓ Order withdrawn — removed from the book";'
    assert s.count(old)==1;p.write_text(s.replace(old,'orderStatus = o.hasLiquidity() ? "✓ Order updated" : "✓ Order withdrawn — removed from the book";'))
    for name in ['MainActivity.java','SwapService.java']:
        p=base/name;s=p.read_text();old='restored && peg == PriceOracle.PEG_APPLIED'
        assert s.count(old)==1;p.write_text(s.replace(old,'restored && peg == PriceOracle.PEG_APPLIED && o.hasLiquidity()'))

    # The editor loads pairs-only configuration, unlike baseOrder().
    p=base/'MainActivity.java';s=p.read_text()
    old='        if (identity == null || myMinimaPk == null || !wallet.ready()) return;\n        final boolean live = o.hasLiquidity();'
    new='        if (identity == null || myMinimaPk == null || !wallet.ready()) return;\n        // Saved configuration contains pairs only; attach identities just as baseOrder() does.\n        o.minimaPublicKey = myMinimaPk;\n        o.ethAddress = wallet.address();\n        o.usdtAvail = parseD(Util.tidyAmount(tokenBals.get("USDT")), 0);\n        final boolean live = o.hasLiquidity();'
    assert s.count(old)==1;p.write_text(s.replace(old,new))
