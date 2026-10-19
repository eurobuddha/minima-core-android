"""Prepare Sweep restoration using the standalone planner and the wallet's existing whole-coin builder."""
from pathlib import Path

def adapt(root):
    base=root/'wallet/src/main/java/com/eurobuddha/wallet'
    source=Path(__file__).with_name('pandamonium-sources')/'wallet'
    for name in ('SweepPlanner.java','SweepFlow.java','SweepPending.java'):
        (base/name).write_text((source/name).read_text())
    settings=base/'SettingsView.java';text=settings.read_text()
    marker='        // --- Seed backup '
    if 'new SweepFlow(act)' not in text:
        pos=text.index(marker)
        text=text[:pos]+'''        container.addView(sectionTitle(Language.msg(l10n_sweep_title)));
        container.addView(outlineButton(Language.msg(l10n_sweep_settings),v->{if(currentSession())new SweepFlow(act).start();}));
        container.addView(hint(Language.msg(l10n_sweep_help)));

'''+text[pos:]
        settings.write_text(text)
    nft=base/'NftView.java';text=nft.read_text()
    if 'static java.util.Set<String> pendingCoinIds()' not in text:
        text=text.replace('private static final java.util.HashMap<String, Long> PENDING = new java.util.HashMap<>();',
            'private static final java.util.Map<String, Long> PENDING = new java.util.concurrent.ConcurrentHashMap<>();')
        pos=text.index('    private static boolean isPending(')
        text=text[:pos]+'''    static java.util.Set<String> pendingCoinIds(){
        java.util.Set<String> result=new java.util.HashSet<>();
        for(String id:PENDING.keySet())result.add(id.toLowerCase(java.util.Locale.ROOT));
        return result;
    }
    static void markPending(String id){PENDING.put(id,System.currentTimeMillis());}
'''+text[pos:]
        nft.write_text(text)
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
