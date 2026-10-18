"""Keep Salon node replies and tip consent with the initiating account."""
from pathlib import Path
import shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon';target=root/'salon/src/main/java/com/eurobuddha/salon'
    for name in ['TipTransport.java','MinimaMail.java']:shutil.copy2(source/name,target/name)
    p=target/'MainActivity.java';s=p.read_text()
    def replace_region(start,end,part):
        nonlocal s
        a=s.index(start);b=s.index(end,a);s=s[:a]+part+'\n'+s[b:]
    replace_region('    /** Scan the inbox','    private CharSequence describeTip(', (source/'AccountReads.java.inc').read_text())
    part=(source/'TipDialog.java.inc').read_text()+(source/'TipSend.java.inc').read_text()
    messages={
        'CHANGED':('salon_publish_changed',''), 'AMOUNT':('utxo_valid_amount',''), 'ENTER':('mail_enter_amount',''),
        'ADDRESS':('salon_tips_failed',', HoldingMessages.message("that profile\'s tip address is malformed — not sending")'),
        'TOKEN':('salon_tips_failed',', HoldingMessages.message("bad token id")'), 'BUSY':('salon_tips_busy',''),
        'CONNECT':('salon_tips_connect',''), 'CHECKING':('salon_tips_checking',''), 'UNAVAILABLE':('salon_tips_unavailable',''),
        'INSUFFICIENT':('salon_tips_insufficient',', TipTransport.label(tokenid), have.toPlainString()'),
        'SENDING':('salon_tips_sending',''), 'SUBMITTED':('salon_tips_submitted',', handle'),
        'FAILED':('salon_tips_failed',', HoldingMessages.message(message)'),
        'BALANCE_FAILED':('salon_tips_balance_failed',', HoldingMessages.message(message)')}
    for marker,(key,args) in sorted(messages.items(),key=lambda x:-len(x[0])):
        part=part.replace('MSG_'+marker,'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+key+args+')')
    replace_region('    private void tipDialog(', '    /* ================= Messages',part)
    replace_region('    private void fetchPubkey()', '    /* ================= HTTP', (source/'NodeKeyRead.java.inc').read_text())
    old='    private void onNode() {';assert s.count(old)==1
    s=s.replace(old,old+'\n        if(nodeUp!=wasNodeUp){nodeReadEpoch++;if(!nodeUp){pubkey="";reannouncedThisSession=false;}}')
    old='            ensureMailKey();\n            ensureInboxAndScan();';assert s.count(old)==1;s=s.replace(old,'            ensureMailKey();')
    old='        io.shutdownNow();';assert s.count(old)==1;s=s.replace(old,'        closeNodeWork();\n'+old)
    old='        renderEpoch++;';assert s.count(old)==1;s=s.replace(old,'        cancelTipLookup();\n'+old)
    lines=[line for line in s.splitlines() if 'l10n_salon_settings_key_kept_suffix' in line and 'render(); });' in line]
    assert len(lines)==1
    s=s.replace(lines[0],lines[0].replace('render(); });','render(); if(nodeUp)ensureMailKey(); });'))
    p.write_text(s)
