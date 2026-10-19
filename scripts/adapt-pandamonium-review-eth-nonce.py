"""Reject unknown nonce replies before signing; preserve ABI data parsing and fee policy."""
from pathlib import Path


def adapt_app(folder):
    p = folder / 'EthRpc.java'
    s = p.read_text()
    def change(old, new, count=1):
        nonlocal s
        assert s.count(old) == count, (p, old, s.count(old))
        s = s.replace(old, new)
    change('''            return resp.opt("result");''', '''            Object result = resp.opt("result");
            // Validate inside the fallback loop too: an empty reply is not account nonce zero.
            if ("eth_getTransactionCount".equals(method)) transactionCount(result);
            return result;''')
    change('''        return hexToBig(callStr("eth_getTransactionCount", new JSONArray().put(address).put("pending")));''', '''        return transactionCount(call("eth_getTransactionCount", new JSONArray().put(address).put("pending")));''')
    marker = '    public BigInteger blockNumber() throws IOException {'
    change(marker, '''    /** RPC QUANTITY is distinct from padded ABI DATA. EIP-2681 bounds account nonces to 64 bits. */
    static BigInteger transactionCount(Object result) throws IOException {
        if (!(result instanceof String) || !((String) result).matches("0x(?:0|[1-9a-fA-F][0-9a-fA-F]{0,15})")) {
            throw new IOException("Invalid Ethereum transaction count from RPC");
        }
        return new BigInteger(((String) result).substring(2), 16);
    }

    static void requireSendableNonce(BigInteger nonce) throws IOException {
        // 2^64-1 is a valid exhausted account count, but cannot be used by a new transaction.
        if (nonce == null || nonce.signum() < 0 || nonce.bitLength() > 64
                || nonce.equals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))) {
            throw new IOException("Ethereum transaction nonce is outside the sendable range");
        }
    }

''' + marker)
    change('c = (HttpURLConnection) new URL(url).openConnection();', 'c = openConnection(url);')
    change('    private static String host(String url) {', '''    // Same injectable connection boundary used by Salon Hosting's HTTP regression tests.
    static HttpURLConnection openConnection(String url) throws IOException {
        return (HttpURLConnection) new URL(url).openConnection();
    }

    private static String host(String url) {''')
    p.write_text(s)
    p = folder / 'EthTx.java'; s = p.read_text()
    if 'long next = -1;' in s:
        change('long next = -1;      // next nonce to hand out; -1 = cold → read "pending"', 'BigInteger next;     // null = cold; never truncate an unsigned 64-bit RPC count')
        change('long pending = rpc.getTransactionCount(creds.getAddress()).longValue();', 'BigInteger pending = rpc.getTransactionCount(creds.getAddress());\n            EthRpc.requireSendableNonce(pending);')
        change('long alloc, newNext, newSync;', 'BigInteger alloc, newNext;\n            long newSync;')
        change('st.next < 0 || pending >= st.next', 'st.next == null || pending.compareTo(st.next) >= 0')
        change('pending + 1', 'pending.add(BigInteger.ONE)', 2)
        change('st.next + 1', 'st.next.add(BigInteger.ONE)')
        change('''            RawTransaction raw = RawTransaction.createTransaction(
                    BigInteger.valueOf(alloc),''', '''            EthRpc.requireSendableNonce(alloc);
            RawTransaction raw = RawTransaction.createTransaction(
                    alloc,''')
    else:
        change('BigInteger nonce = rpc.getTransactionCount(creds.getAddress());', 'BigInteger nonce = rpc.getTransactionCount(creds.getAddress());\n        EthRpc.requireSendableNonce(nonce);')
    p.write_text(s)


def adapt(root):
    for app in ('atomix', 'ethwallet'):
        adapt_app(root / app / 'src/main/java/com/eurobuddha' / app / 'eth')
