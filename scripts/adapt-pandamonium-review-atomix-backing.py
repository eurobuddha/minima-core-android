"""Close single-level/stale-refresh funding gaps using the existing cumulative ask clamp."""
from pathlib import Path

def adapt_app(folder):
    p=folder/'Order.java';s=p.read_text();marker='    // ---- (de)serialization'
    helper="""    /** Reuse checkLadderCoins' best-price prefix and trimAsks' scalar suppression at final publication.
     *  Exact decimal accounting: a tolerance must not fund an otherwise unbacked tranche.
     *  Only the outgoing/received order is changed; saved market configuration is left intact. */
    public void clampAsksToBalance(java.math.BigDecimal available) {
        if(available==null||available.signum()<0)available=java.math.BigDecimal.ZERO;
        java.math.BigDecimal remaining=available;
        for(Map.Entry<String,Pair> entry:pairs.entrySet()) {
            Pair pair=entry.getValue();if(pair==null||!pair.enable)continue;
            sanitize(pair);
            if(!pair.asks.isEmpty()) {
                int keep=0;
                for(Level level:pair.asks) {
                    java.math.BigDecimal size=java.math.BigDecimal.valueOf(level.amount);
                    if(level.amount<pair.min||size.compareTo(remaining)>0)break;
                    remaining=remaining.subtract(size);keep++;
                }
                trimAsks(entry.getKey(),keep);
            } else if(validPos(pair.buy)) {
                if(remaining.signum()==0||remaining.compareTo(java.math.BigDecimal.valueOf(Math.max(0,pair.min)))<0)pair.buy=0;
                else remaining=java.math.BigDecimal.ZERO;
            }
        }
    }

"""
    assert marker in s;s=s.replace(marker,helper+marker)
    needle='        return r;\n    }\n\n    // ---- local persisted config'
    assert needle in s;s=s.replace(needle,'        r.clampAsksToBalance(java.math.BigDecimal.valueOf(r.minimaAvail));\n'+needle)
    p.write_text(s)
    p=folder/'SwapOrderBook.java';s=p.read_text()
    a=s.index('                Object resp = j.opt("response");');b=s.index('                publish(',a)
    s=s[:a]+"""                try {
                    java.math.BigDecimal available=spendableBalance(j,token);
                    if("0x00".equals(token))available=available.subtract(new java.math.BigDecimal(CommsTransport.MESSAGE_AMOUNT)).max(java.math.BigDecimal.ZERO);
                    base.minimaAvail=available.doubleValue();
                    base.clampAsksToBalance(available);
                } catch(RuntimeException invalid) { cb.onFailed(BALANCE_ERROR);return; }
"""+s[b:]
    start=s.index('        node.cmd("balance tokenid:');end=s.index(', new NodeApi.Cb()',start)
    s=s[:start]+'        final String token=TradingContext.active().tokenId;\n        node.cmd("balance tokenid:" + token'+s[end:]
    # Integrated current() already reports market-generation changes. Standalone captures currency too.
    marker='            @Override public void onResult(JSONObject j) {\n'
    if 'private static boolean current' not in s:
        first=s.index(marker);s=s[:first]+s[first:].replace(marker,marker+'                if(!token.equals(TradingContext.active().tokenId)) { cb.onFailed(BALANCE_ERROR);return; }\n',1)
    a=s.index('            @Override public void onError(String m) { publish(');b=s.index('\n',a)
    integrated='generation' in s[a:b]
    guard='if (!current(base, generation)) { cb.onFailed(MARKET_ERROR); return; } ' if integrated else ''
    s=s[:a]+'            @Override public void onError(String m) { '+guard+'cb.onFailed(BALANCE_ERROR); }'+s[b:]
    a=s.index('    private static double parseD(');b=s.index('\n',a)
    s=s[:a]+"""    public static final String BALANCE_ERROR = "Could not verify spendable funds. Order was not refreshed.";
    /** Reuse MinimaHtlc.tokenBalance's strict reply shape, requested token and decimal checks. */
    static java.math.BigDecimal spendableBalance(JSONObject reply,String token) {
        try {
            if(reply==null||!Boolean.TRUE.equals(reply.opt("status")))throw new IllegalArgumentException();
            Object response=reply.opt("response");JSONObject row;
            if(response instanceof JSONArray) {
                JSONArray rows=(JSONArray)response;
                if(rows.length()==0)return java.math.BigDecimal.ZERO;
                if(rows.length()!=1)throw new IllegalArgumentException();
                row=rows.getJSONObject(0);
            } else if(response instanceof JSONObject)row=(JSONObject)response;
            else throw new IllegalArgumentException();
            if(!token.equalsIgnoreCase(row.getString("tokenid")))throw new IllegalArgumentException();
            Object raw=row.opt("sendable");
            if(!(raw instanceof String)||((String)raw).length()>128||!((String)raw).matches("[0-9]+(?:\\\\.[0-9]+)?"))throw new IllegalArgumentException();
            java.math.BigDecimal value=new java.math.BigDecimal((String)raw);
            if(!Double.isFinite(value.doubleValue()))throw new IllegalArgumentException();
            return value;
        } catch(Exception invalid) { throw new IllegalArgumentException(BALANCE_ERROR); }
    }
"""+s[b:]

    # The balance aggregate includes pending spends: reuse the bounded myFreeCoins query before signing.
    old='                try {\n                    java.math.BigDecimal available=spendableBalance(j,token);\n                    if("0x00".equals(token))available=available.subtract(new java.math.BigDecimal(CommsTransport.MESSAGE_AMOUNT)).max(java.math.BigDecimal.ZERO);\n                    base.minimaAvail=available.doubleValue();\n                    base.clampAsksToBalance(available);\n                } catch(RuntimeException invalid) { cb.onFailed(BALANCE_ERROR);return; }\n'
    new="""                final java.math.BigDecimal available;
                try { available=spendableBalance(j,token); requireBoundedCoins(j,token,available); }
                catch(RuntimeException invalid) { cb.onFailed(BALANCE_ERROR);return; }
                if(available.signum()==0) { base.minimaAvail=0; base.clampAsksToBalance(available); PUBLISH; return; }
                node.cmd("coins relevant:true sendable:true tokenid:"+token+" coinage:1 checkmempool:true",new NodeApi.Cb() {
                    @Override public void onResult(JSONObject coins) {
                        GUARD
                        try {
                            java.math.BigDecimal free=available.min(freeCoinBalance(coins,token));
                            if("0x00".equals(token))free=free.subtract(new java.math.BigDecimal(CommsTransport.MESSAGE_AMOUNT)).max(java.math.BigDecimal.ZERO);
                            base.minimaAvail=free.doubleValue();base.clampAsksToBalance(free);
                        } catch(RuntimeException invalid) { cb.onFailed(BALANCE_ERROR);return; }
                        PUBLISH;
                    }
                    @Override public void onError(String message) { GUARD cb.onFailed(BALANCE_ERROR); }
                });
"""
    publish='publish(node, ls, id, base, generation, cb)' if integrated else 'publish(node, ls, id, base, cb)'
    guard='if (!current(base, generation)) { cb.onFailed(MARKET_ERROR);return; }' if integrated else 'if(!token.equals(TradingContext.active().tokenId)) { cb.onFailed(BALANCE_ERROR);return; }'
    assert old+'                '+publish+';\n' in s
    s=s.replace(old+'                '+publish+';\n',new.replace('PUBLISH',publish).replace('GUARD',guard))
    marker='    /** Sign {@code order}'
    helpers="""    /** Same preflight limit as MinimaHtlc.myFreeCoins, before asking a node for coin bodies. */
    private static void requireBoundedCoins(JSONObject reply,String token,java.math.BigDecimal balance) {
        if(balance.signum()==0)return;
        try {
            Object response=reply.get("response");
            JSONObject row=response instanceof JSONArray?((JSONArray)response).getJSONObject(0):(JSONObject)response;
            int count=new java.math.BigDecimal(row.get("coins").toString()).intValueExact();
            if(count<1||count>MinimaHtlc.maxSafeCoinRows(token))throw new IllegalArgumentException();
        }catch(Exception invalid){throw new IllegalArgumentException(BALANCE_ERROR);}
    }
    static java.math.BigDecimal freeCoinBalance(JSONObject reply,String token) {
        try {
            if(reply==null||!Boolean.TRUE.equals(reply.opt("status")))throw new IllegalArgumentException();
            JSONArray coins=reply.getJSONArray("response");
            if(coins.length()>MinimaHtlc.maxSafeCoinRows(token))throw new IllegalArgumentException();
            java.util.Set<String> ids=new java.util.HashSet<>();java.math.BigDecimal sum=java.math.BigDecimal.ZERO;
            for(int i=0;i<coins.length();i++) {
                JSONObject coin=coins.getJSONObject(i);Object rawId=coin.opt("coinid");
                if(!(rawId instanceof String)||!((String)rawId).matches("0x[0-9a-fA-F]{64}")
                        ||new java.math.BigInteger(((String)rawId).substring(2),16).signum()==0
                        ||!ids.add(((String)rawId).toLowerCase(java.util.Locale.ROOT))
                        ||!token.equalsIgnoreCase(coin.getString("tokenid")))throw new IllegalArgumentException();
                Object raw=coin.opt("0x00".equals(token)?"amount":"tokenamount");
                if(!(raw instanceof String)||((String)raw).length()>128||!((String)raw).matches("[0-9]+(?:\\\\.[0-9]+)?"))throw new IllegalArgumentException();
                java.math.BigDecimal value=new java.math.BigDecimal((String)raw);
                if(value.signum()<=0)throw new IllegalArgumentException();sum=sum.add(value);
            }
            return sum;
        }catch(Exception invalid){throw new IllegalArgumentException(BALANCE_ERROR);}
    }

"""
    assert marker in s;s=s.replace(marker,helpers+marker)

    if not integrated:
        s=s.replace('        final String token=TradingContext.active().tokenId;', '        final String token=TradingContext.active().tokenId;\n        final long generation=PublishGate.gen();')
        s=s.replace('if(!token.equals(TradingContext.active().tokenId))', 'if(!token.equals(TradingContext.active().tokenId)||generation!=PublishGate.gen())')
    p.write_text(s)

def adapt(root):
    adapt_app(root/'atomix/src/main/java/com/eurobuddha/atomix/swap')
    p=root/'atomix/src/main/java/com/eurobuddha/atomix/MainActivity.java';s=p.read_text();needle='                orderStatus = wide\n';assert s.count(needle)==1
    p.write_text(s.replace(needle,'                orderStatus = !o.hasLiquidity() ? "✓ Order withdrawn — removed from the book" : wide\n'))
