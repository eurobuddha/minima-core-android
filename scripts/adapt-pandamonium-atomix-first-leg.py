"""Retain the OTC first-leg identity before submission, using the existing transactional stores."""
from pathlib import Path

def adapt(root):
    folder=root/'atomix/src/main/java/com/eurobuddha/atomix/swap'
    sources=Path(__file__).resolve().parent/'pandamonium-sources/atomix'
    p=folder/'SwapDb.java';s=p.read_text()
    anchor='    public synchronized void upsertSwap(Swap s) {'
    assert s.count(anchor)==1
    p.write_text(s.replace(anchor,(sources/'initiator-store.inc').read_text()+anchor))
    p=folder/'SwapEngine.java';s=p.read_text()
    old='    public interface StartCb { void ok(String hash); void err(String msg); }'
    assert s.count(old)==1
    s=s.replace(old,'''    public interface StartCb {
        /** Persist the recovery identity before the first leg is submitted. False stops submission. */
        default boolean onPrepared(String hash) { return true; }
        void ok(String hash);
        void err(String msg);
    }''')
    anchor='''        final StartCb cb = new StartCb() {
'''
    assert s.count(anchor)==1
    s=s.replace(anchor,anchor+'            public boolean onPrepared(String hash) { return result.onPrepared(hash); }\n')
    # Only the two user-initiated paths: responder records and recovery remain unchanged.
    start=s.index('    void startMinimaToErc20(');end=s.index('    /**\n     * Is MY first-leg lock',start)
    part=s[start:end]
    for request in ['buyTokenAmount, reqToken','reqMinima, "minima"']:
        old='                        db.insertSecret(hash, secret);\n                        db.insertMyHtlc(hash, '+request+');\n'
        assert part.count(old)==1
        part=part.replace(old,'')
    old='                        db.upsertSwap(s);'
    assert part.count(old)==2
    for request in ['buyTokenAmount, reqToken','reqMinima, "minima"']:
        part=part.replace(old,'''                        try {
                            db.prepareInitiator(s, secret, '''+request+''');
                            if (!cb.onPrepared(hash)) {
                                cb.err("The swap lock was not submitted because recovery data could not be saved."); return;
                            }
                        } catch (RuntimeException storageFailure) {
                            cb.err("The swap lock was not submitted because recovery data could not be saved."); return;
                        }''',1)
    eth=part.index('    void startErc20ToMinima(')
    part=part[:eth]+part[eth:].replace('cb.err("The swap lock was not submitted because recovery data could not be saved."); return;',
                                     'ui.post(() -> cb.err("The swap lock was not submitted because recovery data could not be saved.")); return;')
    s=s[:start]+part+s[end:]
    p.write_text(s)
