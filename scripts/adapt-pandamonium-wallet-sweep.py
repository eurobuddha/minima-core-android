"""Prepare Sweep restoration using the standalone planner and the wallet's existing whole-coin builder."""
from pathlib import Path

def adapt(root):
    base=root/'wallet/src/main/java/com/eurobuddha/wallet'
    source=Path(__file__).with_name('pandamonium-sources')/'wallet'
    (base/'SweepPlanner.java').write_text((source/'SweepPlanner.java').read_text())
    p=base/'TxnFactory.java';s=p.read_text()
    if '    public BuiltTxn buildWholeTransfer(' in s:
        return
    start=s.index('    public BuiltTxn buildNftTransfer(')
    position=s.index('        MiniData recipient = parseAddress(zRecipient);',start)
    s=s[:position]+'''        return buildWholeTransfer(zCoin,zRecipient,zID);
    }

    /** Sweep can preserve state on native Minima too. Reuse the exact NFT whole-coin builder. */
    public BuiltTxn buildWholeTransfer(InputCoin zCoin, String zRecipient, String zID) {
'''+s[position:]
    p.write_text(s)
