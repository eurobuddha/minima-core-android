"""Reuse existing contract tuple checks for event discovery; reject partial scan batches."""
from pathlib import Path


def adapt_app(folder):
    p=folder/'EthRpc.java';s=p.read_text()
    def change(old,new,count=1):
        nonlocal s
        assert s.count(old)==count,(p,old,s.count(old))
        s=s.replace(old,new)
    change('''            if ("eth_getTransactionCount".equals(method)) transactionCount(result);''','''            if ("eth_getTransactionCount".equals(method)) transactionCount(result);
            if ("eth_getLogs".equals(method)) logArray(result);''')
    change('''        return r instanceof JSONArray ? (JSONArray) r : new JSONArray();''','''        return logArray(r);''')
    change('''    public BigInteger getBalance(String address) throws IOException {''','''    private static JSONArray logArray(Object result) throws IOException {
        if (!(result instanceof JSONArray)) throw new IOException("Invalid Ethereum log response");
        JSONArray logs = (JSONArray) result;
        for (int i = 0; i < logs.length(); i++) {
            if (!(logs.opt(i) instanceof JSONObject)) throw new IOException("Incomplete Ethereum log response");
        }
        return logs;
    }

    public BigInteger getBalance(String address) throws IOException {''')
    p.write_text(s)
    p=folder/'EthHtlc.java';s=p.read_text()
    change('''        if (words == 12) for (int index : new int[]{8, 9, 11}) {''','''        for (int index : words == 12 ? new int[]{8, 9, 11} : words == 7 ? new int[]{6} : new int[0]) {''')
    change('''            if (c != null) out.add(c);''','''            // Never let a partial/mismatched batch advance SwapEngine's discovery bookmark.
            if (c == null || !net.htlc.equalsIgnoreCase(log.optString("address"))
                    || BigInteger.valueOf(c.block).compareTo(from) < 0 || BigInteger.valueOf(c.block).compareTo(to) > 0
                    || ownerTopic != null && !ownerTopic.equalsIgnoreCase(padAddr(c.owner))
                    || receiverTopic != null && !receiverTopic.equalsIgnoreCase(padAddr(c.receiver)))
                throw new Exception("Incomplete or mismatched Ethereum contract events");
            out.add(c);''')
    change('''            if (topics == null || topics.length() < 4) return null;
            Contract c = new Contract();
            c.block = EthRpc.hexToBig(log.optString("blockNumber", "0x0")).longValue();
            c.txnhash = log.optString("transactionHash", "");''','''            if (topics == null || topics.length() != 4 || !NEW_TOPIC.equalsIgnoreCase(topics.optString(0))
                    || !Boolean.FALSE.equals(log.opt("removed"))) return null;
            for (int i = 0; i < 4; i++) if (!(topics.opt(i) instanceof String)
                    || !((String) topics.opt(i)).matches("0x[0-9a-fA-F]{64}")) return null;
            for (int i : new int[]{2, 3}) if (!topics.getString(i).substring(2, 26).equals("0".repeat(24))) return null;
            if (!(log.opt("address") instanceof String) || !log.getString("address").matches("0x[0-9a-fA-F]{40}")
                    || !(log.opt("transactionHash") instanceof String)
                    || !log.getString("transactionHash").matches("0x[0-9a-fA-F]{64}")) return null;
            Contract c = new Contract();
            c.block = new java.math.BigDecimal(EthRpc.transactionCount(log.opt("blockNumber"))).longValueExact();
            c.txnhash = log.getString("transactionHash");''')
    change('''            List<Type> d = FunctionReturnDecoder.decode(log.optString("data", ""), org.web3j.abi.Utils.convert(refs));
            if (d.size() < 7) return null;''','''            if (!(log.opt("data") instanceof String)) return null;
            String data = log.getString("data");
            requireReturnWords(data, 7);
            if (!data.substring(66, 90).equals("0".repeat(24))) return null; // ABI address padding
            List<Type> d = FunctionReturnDecoder.decode(data, org.web3j.abi.Utils.convert(refs));
            if (d.size() != 7) return null;''')
    change('''            c.timelock = ((BigInteger) d.get(5).getValue()).longValue();''','''            c.timelock = new java.math.BigDecimal((BigInteger) d.get(5).getValue()).longValueExact();''')
    # String.repeat is unavailable on Android9; reuse the exact zero prefix already used by padAddr.
    s=s.replace('"0".repeat(24)', '"000000000000000000000000"')
    p.write_text(s)


def adapt(root):
    adapt_app(root/'atomix/src/main/java/com/eurobuddha/atomix/eth')
