"""Bind OTC actions and callbacks to the reviewed persisted deal; reuse NFT journal transactions."""
from pathlib import Path

def adapt(root):
    sources=Path(__file__).resolve().parent/'pandamonium-sources/atomix'
    folder=root/'atomix/src/main/java/com/eurobuddha/atomix/swap'
    p=folder/'OtcDb.java';s=p.read_text()
    anchor='        public TradingContext market()'
    assert s.count(anchor)==1
    s=s.replace(anchor,'''        public Deal copy() {
            Deal d = new Deal();
            d.ref = ref; d.role = role; d.peerCommsId = peerCommsId; d.peerMinimaPk = peerMinimaPk;
            d.peerEthAddr = peerEthAddr; d.side = side; d.amount = amount; d.price = price;
            d.status = status; d.whoseTurn = whoseTurn; d.hash = hash; d.currency = currency;
            d.created = created; d.updated = updated;
            return d;
        }
'''+anchor)
    start=s.index('    public synchronized void upsertDeal(');end=s.index('    public synchronized Deal getDeal(',start)
    s=s[:start]+(sources/'otc-store.inc').read_text()+s[end:]
    start=s.index('    public synchronized void deleteDeal(');end=s.index('    public synchronized List<Deal> allDeals()',start)
    s=s[:start]+'''    public synchronized boolean deleteDeal(Deal expected) {
        if (expected == null) return false;
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try {
            if (!sameSnapshot(expected, getDeal(expected.ref))) return false;
            if (db.delete("otc_deals", "ref=?", new String[]{expected.ref}) != 1) return false;
            db.delete("otc_msgs", "ref=?", new String[]{expected.ref});
            db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }

    public synchronized void close() { helper.close(); }

'''+s[end:]
    p.write_text(s)
    p=folder/'OtcController.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert old in s,('AtomiX consent source drift',old)
        s=s.replace(old,new)
    change('    private static boolean isTerminal(String st) {','''    public static final String CHANGED_ERROR = "Deal changed. Review the latest terms.";
    private static boolean negotiating(OtcDb.Deal d) {
        return OtcDb.ST_PROPOSED.equals(d.status) || OtcDb.ST_COUNTERED.equals(d.status);
    }
    private static boolean matchingTerms(OtcMessage m, OtcDb.Deal d) {
        return validDeal(d) && java.util.Objects.equals(d.side, m.side)
                && java.util.Objects.equals(d.amount, m.amount) && java.util.Objects.equals(d.price, m.price);
    }

    private static boolean isTerminal(String st) {''')
    change('        otcDb.upsertDeal(d);\n        noteProposeRateLimited', '        if (!otcDb.createDeal(d)) return false;\n        noteProposeRateLimited')
    start=s.index('    private void apply(OtcMessage m)');end=s.index('    private void noteProposeRateLimited',start)
    part=s[start:end]
    part=part.replace('        if (OtcMessage.REJECT.equals(m.type)) {','        OtcDb.Deal expected = d.copy();\n        if (OtcMessage.REJECT.equals(m.type)) {')
    part=part.replace('if (isTerminal(d.status)) return;', 'if (isTerminal(d.status) || OtcDb.ST_EXECUTING.equals(d.status)) return;')
    part=part.replace('if (!agreed && !implicitAccept) return;', 'if ((!agreed && !implicitAccept) || !matchingTerms(m, d)) return;')
    part=part.replace('if (isTerminal(d.status) || !validTerms(m.amount, m.price)) return;', 'if (!negotiating(d) || !java.util.Objects.equals(d.side, m.side) || !validTerms(m.amount, m.price)) return;')
    part=part.replace('if (!validDeal(d)) return;', 'if (!matchingTerms(m, d)) return;')
    part=part.replace('otcDb.upsertDeal(d);','if (!otcDb.replaceDeal(expected, d)) return;')
    s=s[:start]+part+s[end:]
    change('        otcDb.upsertDeal(d);\n        sendMsg(d, OtcMessage.PROPOSE', '        if (!otcDb.createDeal(d)) { res.err(CHANGED_ERROR); return; }\n        sendMsg(d, OtcMessage.PROPOSE')
    start=s.index('    public void counter(');end=s.index('    private void sendMsg(',start)
    s=s[:start]+(sources/'otc-actions.inc').read_text()+s[end:]
    change('        OtcMessage m = new OtcMessage();','        if (!OtcDb.sameSnapshot(d, otcDb.getDeal(d.ref))) { res.err(CHANGED_ERROR); return; }\n        OtcMessage m = new OtcMessage();')
    start=s.index('    /** Instigator: on AGREED');end=s.index('    /** Expire deals',start)
    s=s[:start]+(sources/'otc-execute.inc').read_text()+s[end:]
    for status in ['COMPLETE','REJECTED','EXPIRED']:
        change('d.status = OtcDb.ST_'+status+'; otcDb.upsertDeal(d); ui.onDealsChanged();','changeStatus(d, OtcDb.ST_'+status+');')
    change('otcDb.deleteDeal(d.ref); lastExecResend.remove(d.ref);','if (otcDb.deleteDeal(d)) lastExecResend.remove(d.ref);')
    change('    /** Leg 1 is locked', '''    private void changeStatus(OtcDb.Deal expected, String status) {
        OtcDb.Deal next = expected.copy(); next.status = status;
        if (otcDb.replaceDeal(expected, next)) ui.onDealsChanged();
    }

    /** Leg 1 is locked''')
    assert 'upsertDeal' not in s
    p.write_text(s)
    p=root/'atomix/build.gradle';s=p.read_text();anchor="    testImplementation 'junit:junit:4.13.2'"
    assert s.count(anchor)==1
    p.write_text(s.replace(anchor,anchor+"\n    testImplementation 'org.robolectric:robolectric:4.14.1'"))
    p=folder.parent/'MainActivity.java';s=p.read_text()
    old='            @Override public void err(String m) { ui.post(() -> toast(Language.join("OTC: ", AtomixMessages.message(m)))); }'
    new='            @Override public void err(String m) { ui.post(() -> {\n                toast(Language.join("OTC: ", AtomixMessages.message(m)));\n                if (OtcController.CHANGED_ERROR.equals(m) && currentTab == TAB_OTC && !modalOpen) render();\n            }); }'
    assert s.count(old)==1, 'AtomiX OTC error refresh source drift'
    p.write_text(s.replace(old,new))
