"""Remove standalone-node setup and whole-package reset advice from bundled screens."""
from pathlib import Path
import re

def adapt_ui(root):
    replacement='The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.'
    for p in root.glob('*/src/main/**/*'):
        if p.suffix not in ('.java','.xml'):continue
        s=p.read_text()
        if p.suffix=='.xml':
            s=re.sub(r'android:text="[^"]*(?:Minima Core → Apps|MinimaCore → Apps)[^"]*"',lambda m:'android:text="'+replacement+'"',s)
        else:
            # Whole standalone strings (no changes to command logic).
            pat=r'"(?:[^"\\]|\\.)*"'
            def literal(m):
                text=m[0]
                if ('Minima Core → Apps' in text or 'MinimaCore → Apps' in text) and not text.startswith('"Minima Core → Apps'):
                    if text.startswith('"1. '):return '"1. Start the embedded node in Minima Core.\\n"'
                    if text.startswith('"① '):return '"① The embedded node is not running.\\n"'
                    if text.endswith('"') and text.count('\\n')<=1:return '"'+replacement+'"'
                return text
            # Exclude fragmented strings handled explicitly below.
            if p.name not in ('Wallet.java','Guardian.java','AuditRunner.java','Tx.java','Copy.java','NodeApi.java','MainActivity.java'):
                s=re.sub(pat,literal,s)
            s=s.replace('"Pair with node"','"Use embedded wallet"')
            simple={
              'Minima Mail is not enabled yet':'The embedded node is not ready',
              'Open Minima Core → Apps and enable \\"Minima Mail\\", then come back.':replacement,
              'Open Minima Core → Apps and enable \\"Filez\\" with Admin (file access needs it), then come back.':replacement,
              'File access can read node backups, so it is Admin-gated.\\nOpen Minima Core → Apps → Filez and switch on Admin, then refresh.':replacement,
              'Not enabled yet — turn on “ETH Wallet” in Minima Core → Apps, then tap the banner.':replacement,
              'Enable “ETH Wallet” in Minima Core → Apps, then tap here to retry.':replacement,
              'Open MinimaCore → Apps and check that PandaDEX is enabled.':replacement,
              'Enable Zero Edge Casino in Minima Core → Apps':replacement,
              'Connect your node in Minima Core → Apps to see your balances.':replacement,
              '•  Minima Core running, with AtomiX enabled in Apps':'•  Minima Core’s embedded node running',
              '1. Enable Future Cash Next in Minima Core → Apps.':'1. Start the embedded node in Minima Core.',
              "① Future Cash Next isn't enabled in Minima Core → Apps.":'① The embedded node is not running.',
              'optimisation in Android Settings → Apps → PandaPools.':'optimisation for Minima Core in Android Settings.',
              'Enable Future Cash Next in Minima Core → Apps. Nothing can be collected or ':'Start the embedded node in Minima Core. Nothing can be collected or ',
              '⚠ Not enabled in Minima Core. The guardian must act unattended, so open ':'⚠ The embedded node is unavailable. Open ',
              'Minima Core → Apps and enable Future Cash Next. Until then nothing can be collected ':'Minima Core and check its status. Until then nothing can be collected ',
            }
            for old,new in simple.items():s=s.replace(old,new)
            s=s.replace('"Requires the Minima Core app: enable Terminal IDE in "\n                            + "Minima Core → Apps."','"Connected directly to Minima Core’s embedded Minima node."')
            s=s.replace('"Minima Core is holding this for approval — the guardian has "\n                            + "to act unattended, so enable Future Cash Next in Minima Core → Apps."','"The embedded node did not authorize the request. Check its status before retrying."')
            s=s.replace('"Minima Core is holding that for approval — enable Future Cash Next in "\n                    + "Minima Core → Apps, then retry."','"The embedded node did not authorize the request. Check its status before retrying."')
            s=s.replace('"Minima Core is holding that for approval — enable Future Cash Next "\n                    + "in Minima Core → Apps, then lock again."','"The embedded node did not authorize the request. Check its status before locking again."')
            s=s.replace('"Couldn\'t read your keys from Minima Core — enable Future Cash Next in "\n                    + "Minima Core → Apps."','"Couldn\'t read keys from the embedded node. Check Minima Core’s status."')
        p.write_text(s)
    p=next((root/'atomix/src/main/java').rglob('NodeApi.java'));s=p.read_text()
    start=s.index('    public static String offlineMessage(');end=s.index('    /** Instance form',start)
    s=s[:start]+'''    public static String offlineMessage(Offline why, boolean everReplied) {
        if (why == Offline.REFUSED) return "The embedded node is not ready. Open Minima Core and check its status.";
        if (everReplied) return "The embedded node stopped responding. It may be busy; check Minima Core before retrying.";
        return "Waiting for the embedded node. Start Minima Core from the menu.";
    }

'''+s[end:];p.write_text(s)
    p=next((root/'atomix/src/main/java').rglob('MainActivity.java'));s=p.read_text()
    start=s.index('        warn.setText("⚠  Reinstalling');end=s.index('        warn.setTextColor',start)
    s=s[:start]+'''        warn.setText("⚠  Minima Core shares one installation and wallet. Clearing app storage or uninstalling "
                + "would erase the node and every integrated app, including claim secrets for "
                + unsettled + " unsettled swap" + (unsettled == 1 ? "" : "s")
                + ". Preserve your node backup and swap recovery information. Export or sweep the stale ETH "
                + "wallet if needed. AtomiX remains halted while these identities differ.");
'''+s[end:]
    start=s.index('        // Clearing storage is offered ALONGSIDE');end=s.index('        col.addView(card, clp);',start);s=s[:start]+s[end:]
    start=s.index('    /** The reliable reset.');end=s.index('    private void buildHeader',start);s=s[:start]+s[end:];p.write_text(s)

    p=root/'futurecash-next/src/main/java/com/eurobuddha/futurecashnext/Copy.java';s=p.read_text()
    s=re.sub(r'static final String ERR_KEYS =.*?;', 'static final String ERR_KEYS = "Could not read keys from the embedded node. Check Minima Core’s status.";',s,flags=re.S)
    s=s.replace('"This app is not enabled yet"','"The embedded node is not ready"')
    s=re.sub(r'static final String ERR_PAIRING_BODY =.*?;', 'static final String ERR_PAIRING_BODY = "Open Minima Core from the menu, check its status, then run the audit again.";',s,flags=re.S);p.write_text(s)
    p=root/'atomix/src/test/java/com/eurobuddha/atomix/NodeOfflineMessageTest.java';s=p.read_text()
    s=s.replace('onlyTheNodesOwnVerdictAsksTheUserToEnableTheApp','unreadyEmbeddedNodeExplainsReadinessWithoutPairing')
    s=s.replace('assertTrue(m.contains("Enable AtomiX in Minima Core"));','assertTrue(m.contains("not ready")); assertFalse(m.contains("Apps"));')
    s=s.replace('assertTrue("a proven pairing must not be questioned", m.contains("It is enabled"));','assertTrue(m.contains("stopped responding"));')
    s=s.replace('assertTrue(m.contains("restarting"));','assertTrue(m.contains("busy"));')
    s=s.replace('assertTrue(m.contains("installed and running"));','assertTrue(m.contains("Waiting for the embedded node"));')
    s=s.replace('assertTrue(m.contains("enabled in Minima Core"));','assertTrue(m.contains("Start Minima Core")); assertFalse(m.contains("Apps"));');p.write_text(s)

    p=root/'pandadex/src/main/java/com/eurobuddha/pandadex/MainActivity.java';s=p.read_text().replace('PAIR IN MINIMA → APPS','NODE NOT READY');p.write_text(s)
    p=root/'pandadex/src/test/java/com/eurobuddha/pandadex/BalanceDisplayTest.java';s=p.read_text().replace('PAIR IN MINIMA → APPS','NODE NOT READY').replace('.contains("enabled")','.contains("embedded node")');p.write_text(s)
    p=root/'futurecash-next/src/main/java/com/eurobuddha/futurecashnext/MainActivity.java';s=p.read_text().replace('r.error.contains("Apps")','r.error.contains("embedded node")');p.write_text(s)
