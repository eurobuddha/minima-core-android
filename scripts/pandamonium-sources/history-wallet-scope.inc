    private boolean verifiedSelfTransfer;

    /** Reuse minimaWallet HistoryTx: outputs at this wallet minus inputs at this wallet.
     * Tracking another wallet's script is not ownership of its spending keys. */
    public boolean reconcile(Ownership own) {
        verifiedSelfTransfer = false;
        if (own == null || !own.isLoaded() || !hasCoinData()) return false;
        try {
            java.util.LinkedHashMap<String,BigDecimal> net = new java.util.LinkedHashMap<>();
            java.util.Map<String,String> names = new java.util.HashMap<>();
            java.util.Map<String,String> foreignIn = new java.util.HashMap<>(), foreignOut = new java.util.HashMap<>();
            boolean allOwned = true, touched = false;
            for (int pass=0;pass<2;pass++) {
                JSONArray rows = new JSONArray(pass==0 ? inputs : outputs);
                for (int i=0;i<rows.length();i++) {
                    JSONObject coin=rows.getJSONObject(i);
                    String address=coin.getString("addr"), tid=coin.getString("tokenid");
                    if (coin.has("name")) names.put(tid,coin.getString("name"));
                    if (own.classify(address) != Ownership.Kind.OWNED) {
                        allOwned=false;
                        (pass==0 ? foreignIn : foreignOut).putIfAbsent(tid,address);
                        continue;
                    }
                    touched=true;
                    BigDecimal value=new BigDecimal(coin.getString("amount"));
                    net.merge(tid,pass==0 ? value.negate() : value,BigDecimal::add);
                }
            }
            if (!touched) return false; // Preserve foreign activity; never call it our split.
            String primary="0x00"; BigDecimal largest=BigDecimal.ZERO;
            for (java.util.Map.Entry<String,BigDecimal> item:net.entrySet()) {
                if (item.getValue().abs().compareTo(largest.abs())>0) {primary=item.getKey();largest=item.getValue();}
            }
            JSONObject delta=new JSONObject();
            for (java.util.Map.Entry<String,BigDecimal> item:net.entrySet()) delta.put(item.getKey(),item.getValue().stripTrailingZeros().toPlainString());
            String name=Util.isMinima(primary) ? "Minima" : names.getOrDefault(primary,primary.equals(tokenid) ? tokenName : Util.shorten(primary));
            tokenid=primary;tokenName=name;amount=largest.abs().stripTrailingZeros().toPlainString();
            direction=largest.signum()>0 ? "received" : largest.signum()<0 ? "sent" : "self";
            incoming=largest.signum()>0;deltas=delta.toString();
            counterparty=(incoming ? foreignIn : foreignOut).getOrDefault(primary,"");
            verifiedSelfTransfer=allOwned && "self".equals(direction);
            return true;
        } catch (Exception malformed) { return false; }
    }

    /** Legacy native rows have exact units. Legacy token rows need a fresh scaled snapshot. */
    boolean hasCoinData() {
        try {
            for (String json:new String[]{inputs,outputs}) {
                JSONArray rows=new JSONArray(json);
                if(rows.length()==0)return false;
                for(int i=0;i<rows.length();i++) {
                    JSONObject coin=rows.getJSONObject(i);
                    if(coin.getString("addr").isEmpty())return false;
                    if(!Util.isMinima(coin.getString("tokenid")) && !coin.optBoolean("displayUnits",false))return false;
                    if(new BigDecimal(coin.getString("amount")).signum()<0)return false;
                }
            }
            return true;
        }catch(Exception malformed){return false;}
    }
