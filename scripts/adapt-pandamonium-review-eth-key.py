"""Reuse AtomiX scalar validation and generation-gated callback delivery."""
from pathlib import Path

def adapt(root):
    p=root/'ethwallet/src/main/java/com/eurobuddha/ethwallet/eth/EthWallet.java'
    s=p.read_text()
    def change(old,new):
        nonlocal s
        assert s.count(old)==1,('ETH key source drift',old)
        s=s.replace(old,new)
    change('    private volatile Credentials creds;', '    private volatile Credentials creds;\n    private long derivationGeneration;')
    change('public String address() { return creds == null ? null : creds.getAddress(); }',
           'public String address() { Credentials current = creds; return current == null ? null : current.getAddress(); }')
    change('        if (creds == null) return null;\n        return org.web3j.utils.Numeric.toHexStringWithPrefixZeroPadded(creds.getEcKeyPair().getPrivateKey(), 64);', '        Credentials current = creds;\n        if (current == null) return null;\n        return org.web3j.utils.Numeric.toHexStringWithPrefixZeroPadded(current.getEcKeyPair().getPrivateKey(), 64);')
    start=s.index('    public void deriveFromNode(')
    end=s.index('    // ---- balances',start)
    s=s[:start]+(Path(__file__).parent/'pandamonium-sources/ethwallet/key-derivation.inc').read_text()+s[end:]
    change('        if (creds == null) return BigInteger.ZERO;\n        return rpc.getBalance(creds.getAddress());', '        Credentials current = creds;\n        if (current == null) return BigInteger.ZERO;\n        return rpc.getBalance(current.getAddress());')
    change('        if (creds == null) return BigInteger.ZERO;\n        return erc20BalanceRaw(rpc, tokenAddress, creds.getAddress());', '        Credentials current = creds;\n        if (current == null) return BigInteger.ZERO;\n        return erc20BalanceRaw(rpc, tokenAddress, current.getAddress());')
    p.write_text(s)
