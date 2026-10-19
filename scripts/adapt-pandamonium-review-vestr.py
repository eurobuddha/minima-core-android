"""Preserve the Vestr covenant; reject unsafe state before command construction."""
from pathlib import Path

def adapt(root):
    folder=root/'vestr/src/main/java/com/eurobuddha/vestr'
    donor=Path(__file__).with_name('pandamonium-sources')/'vestr/CommandSafety.java'
    (folder/'CommandSafety.java').write_text(donor.read_text())
    p=folder/'NodeApi.java';s=p.read_text();needle='        if (mReleased) return;'
    assert s.count(needle)==1
    s=s.replace(needle,needle+"\n        String unsafe = CommandSafety.failure(command);\n        if (unsafe != null) { if (cb != null) cb.onError(unsafe); return; }")
    p.write_text(s)
    p=folder/'Contract.java';s=p.read_text();needle='    public boolean isMinima()'
    assert needle in s
    s=s.replace(needle,'''    /** Same typed-field gate as PandaDEX's safeOrder; never interpolate raw unvalidated state. */
    public boolean safeForCommands() {
        if (!Util.isValidAddress(address) || !Util.isValidAddress(unlockAddr)
                || coinid == null || !coinid.matches("(?i)0x[0-9a-f]{64}")
                || tokenid == null || !tokenid.matches("(?i)0x(?:00|[0-9a-f]{64})")
                || uid == null || !uid.matches("(?i)0x[0-9a-f]{64}")) return false;
        for (int port = 1; port <= 8; port++) {
            String value = state(raw, port);
            if (!value.matches("[0-9]{1,40}(\\\\.[0-9]{1,40})?")) return false;
        }
        return amount != null && amount.signum() > 0 && total.signum() > 0;
    }

'''+needle);p.write_text(s)
    p=folder/'ContractDetailActivity.java';s=p.read_text()
    old='        render();\n        fetchBlock();'
    assert old in s
    s=s.replace(old, '        if (!c.safeForCommands()) {\n            Toast.makeText(this, Language.msg(l10n_vestr_unsafe_contract), Toast.LENGTH_LONG).show();\n            finish(); return;\n        }\n'+old)
    s=s.replace('        if (canCollect.signum() <= 0) return;', '        if (!c.safeForCommands() || canCollect.signum() <= 0) return;')
    p.write_text(s)
    p=folder/'MainActivity.java';s=p.read_text();old='if (!k.uid.isEmpty()) out.add(k);';assert old in s
    p.write_text(s.replace(old,'if (k.safeForCommands()) out.add(k);'))

    # Preserve baseline side padding, as the existing listener does for bottom padding.
    s=p.read_text()
    old='        final int navBottom = navV.getPaddingBottom();'
    assert s.count(old)==1
    s=s.replace(old,'        final int navBottom = navV.getPaddingBottom();\n        final int navLeft = navV.getPaddingLeft();\n        final int navRight = navV.getPaddingRight();')
    old='navV.setPadding(navV.getPaddingLeft() + b.left, navV.getPaddingTop(), navV.getPaddingRight() + b.right, navBottom + b.bottom);'
    assert s.count(old)==1
    p.write_text(s.replace(old,'navV.setPadding(navLeft + b.left, navV.getPaddingTop(), navRight + b.right, navBottom + b.bottom);'))
