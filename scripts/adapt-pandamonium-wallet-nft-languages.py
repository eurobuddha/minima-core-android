"""NFT presentation bindings reuse wallet review composition; coin/state/signing values stay literal."""
from pathlib import Path
import json
import re


def adapt(root):
    folder=root/'wallet/src/main/java/com/eurobuddha/wallet'
    catalog=json.loads((Path(__file__).resolve().parents[1]/'localization/catalog.json').read_text())
    def msg(key,args=''):
        if '.' not in key:key='nft.'+key
        assert key in catalog,key
        return 'Language.msg(l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+(', '+args if args else '')+')'
    def count(key,value):
        return 'Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_'+key+', '+value+')'
    def change(old,new,n=1):
        nonlocal s
        assert s.count(old)==n,(name,old,s.count(old))
        s=s.replace(old,new)
    def label(old,key):
        old=json.dumps(old,ensure_ascii=False);assert old in s,(name,old)
        change(old,msg(key),s.count(old))
    def block(pattern,new):
        nonlocal s
        s,n=re.subn(pattern,lambda _:new,s,flags=re.S);assert n==1,(name,pattern,n)
    name='NftView.java';s=(folder/name).read_text()
    change('package com.eurobuddha.wallet;', '''package com.eurobuddha.wallet;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;''')
    for old,key in [("No NFTs at this wallet's addresses yet — anything sent to your Receive addresses appears here",'noItems'),
        ('Pair with Minima Core to load your NFTs','nodeUnavailable'),('Back to gallery','backGallery'),('Back to collection','backCollection'),
        ('CONFIRMING','confirming'),('CONFIRMING…','confirming'),('Confirming…','confirming'),('SEND','wallet.send'),('Send','wallet.send'),
        ('Details','details'),('StateNFT (locked edition)','lockedEdition'),('Copy tokenid','copyTokenId'),('Copy coinid','copyCoinId'),
        ('Close','language.close'),('Recipient address (Mx… or 0x…)','wallet.recipient'),('Scan QR','wallet.scanQr'),('Review','review'),
        ('Cancel','common.cancel'),('StateNFT — state replayed, sent whole\n','stateReplayed'),('Sent whole (indivisible)\n','sentWhole'),
        ('Confirm — review carefully','wallet.confirmTransaction'),('Sign & broadcast all','signAll'),('Sending collection','sendingCollection'),
        ('Stop after current','stopAfterCurrent'),('Collection send','collectionSend'),('Collection send stopped','collectionStopped'),
        ('Confirming on-chain','confirmingOnChain'),('Hide (keeps confirming)','hideTracking'),('Collection sent','collectionSent'),
        ('Some items did not confirm','someUnconfirmed'),("Couldn't find the coins — refresh and retry from the gallery",'coinsNotFound')]:label(old,key)
    change('list.size() + " collection(s) · tap artwork to browse"\n                + (confirming > 0 ? " · " + confirming + " item(s) confirming on-chain" : "")',
        'Language.join('+count('collections','list.size()')+', " · ", '+msg('tapArtwork')+', confirming > 0 ? Language.join(" · ", '+count('confirming_items','confirming')+') : "")')
    change('g.meta.name + ", " + g.coins.size() + " items"','Language.join(g.meta.name, ", ", '+count('items','g.coins.size()')+')')
    change('(g.meta.stateNft ? "StateNFT · " : "NFT · ") + g.coins.size()\n                + (g.coins.size() == 1 ? " item" : " items")',
        'Language.join(g.meta.stateNft ? "StateNFT · " : "NFT · ", '+count('items','g.coins.size()')+')')
    change('(g.meta.stateNft ? "StateNFT · " : "NFT · ") + g.coins.size()\n                + (g.coins.size() == 1 ? " item held here" : " items held here")',
        'Language.join(g.meta.stateNft ? "StateNFT · " : "NFT · ", '+count('items','g.coins.size()')+')')
    change('"Send collection (" + g.coins.size() + ")"',msg('sendCollection','g.coins.size()'))
    change('final String itemName = idx >= 0 ? "#" + idx : "item " + ordinal;',
        'final CharSequence itemName = idx >= 0 ? "#" + idx : '+msg('itemNumber','ordinal')+';')
    change('g.meta.name + " " + itemName', 'Language.join(g.meta.name, " ", itemName)',2)
    change('"Send " + Language.join(g.meta.name, " ", itemName)',msg('sendNamed','Language.join(g.meta.name, " ", itemName)'))
    # Detail fields are translated; collection data, raw IDs, state port numbers and values are not.
    change('StringBuilder sb = new StringBuilder();','Language.TextBuilder sb = new Language.TextBuilder();')
    for old,key in [('artwork mode: ','artworkMode'),('\ntokenid:\n','history.token'),('\ncoinid:\n','coinId'),
        ('\namount: ','history.amount'),('created block: ','createdBlock'),('\nstate:\n','state')]:
        resource=msg(key if '.' in key else key)
        literal=json.dumps(old,ensure_ascii=False)
        prefix='"\\n", ' if old.startswith('\n') else ''
        suffix=', ":\\n"' if old.endswith('\n') else ', ": "'
        change(literal,'Language.join('+prefix+resource+suffix+')')
    change('"status: SENDING — broadcast, awaiting chain confirmation\\n"','Language.join('+msg('pendingDetail')+', "\\n")')
    change('"status: UNSTAMPED — not sendable (the creator bypass is still live)\\n"','Language.join('+msg('unstampedDetail')+', "\\n")')
    change('sb.append("\\nheld at address").append(ki != null ? " (key " + ki + ")" : "").append(":\\n")',
        'sb.append("\\n").append('+msg('heldAddress')+').append(ki != null ? Language.join(" (", '+msg('keyIndex','ki')+', ")") : "").append(":\\n")')
    change('sb.append("  port 1: [embedded artwork, ").append(v.length()).append(" chars]\\n");',
        'sb.append("  ").append('+msg('embeddedArtwork','v.length()')+').append("\\n");')
    change('sb.append("  port ").append(e[0]).append(": ").append(v).append("\\n");',
        'sb.append("  ").append('+msg('statePort','e[0]')+').append(": ").append(v).append("\\n");')
    change('msg.setText(sb.toString());','msg.setText(sb.build());')
    change('zLabel + " copied"',msg('history.copied'))
    # Immediate errors keep the String exception contract. They are never persisted.
    for old,key in [('This item is confirming on-chain — wait for it to leave the gallery (or 10 min)','waitPending'),
        ('This coin is no longer in the wallet — pull to refresh','coinGone'),('Invalid recipient address','wallet.invalidRecipient')]:
        literal=json.dumps(old,ensure_ascii=False);change(literal,msg(key).replace('Language.msg(','Language.text(act, '),s.count(literal))
    block(r'"Unstamped locked edition — the creator could reclaim it from the recipient\. ".*?Stamp it in NFT wallet first"',msg('stampFirst').replace('Language.msg(','Language.text(act, '))
    for variable in ['coin','c']:
        change('"Coin address is not in our wallet: " + Nft.str('+variable+', "address")',
            msg('wallet.foreignCoin','Nft.str('+variable+', "address")').replace('Language.msg(','Language.text(act, '))
    change('"Send " + g.meta.name + (idx >= 0 ? " #" + idx : "")',msg('sendNamed','g.meta.name + (idx >= 0 ? " #" + idx : "")'))
    change('promptRecipient(String zTitle,','promptRecipient(CharSequence zTitle,')
    change('private String selfSendNotice(', 'private CharSequence selfSendNotice(')
    change('"\\nNote: recipient is THIS wallet\'s own address (key " + ki + ")."',
        'Language.join("\\n", '+msg('selfSend','ki')+')')
    block(r'                    String review = "NFT: ".*?\+ selfSendNotice\(recipient\);',
        '''                    CharSequence review = Language.join("NFT: ", g.meta.name, idx >= 0 ? " #" + idx : "", "\\n",
                            g.meta.stateNft ? '''+msg('stateReplayed')+' : '+msg('sentWhole')+''', "\\n",
                            '''+msg('history.to')+''', ": ", recipient, "\\ncoinid: ", coinid, "\\n", selfSendNotice(recipient));''')
    change('"Could not prepare the coin: " + message',msg('prepareFailed','message'))
    change('"Nothing sendable: " + pending + " already sending, "\n                    + unstamped + " unstamped"',msg('noneSendable','pending, unstamped'))
    change('"Send ALL of " + g.meta.name + " (" + sendable.size() + " items)"',
        'Language.join('+msg('sendAllNamed','g.meta.name')+', " (", '+count('items','sendable.size()')+', ")")')
    change('StringBuilder idxs = new StringBuilder();','Language.TextBuilder idxs = new Language.TextBuilder();\n            int position = 0;')
    change('idxs.append(idxs.length() == 0 ? "" : ", ").append(idx >= 0 ? "#" + idx : "item");',
        'idxs.append(position++ == 0 ? "" : ", ").append(idx >= 0 ? "#" + idx : '+msg('item')+');')
    change('StringBuilder usage = new StringBuilder();','Language.TextBuilder usage = new Language.TextBuilder();')
    change('usage.append("\\nKey ").append(ki).append(": next signature #")\n                     .append(act.keyUses().currentUses(ki)).append(" of ").append(Util.WOTS_MAX_USES);',
        'usage.append("\\n").append('+msg('wallet.keyUsage','ki, act.keyUses().currentUses(ki), Util.WOTS_MAX_USES')+');')
    block(r'            StringBuilder skipped = new StringBuilder\(\);.*?\+ "\\n\\nThis is IRREVERSIBLE\. Once broadcast it cannot be undone\.";',
        '''            CharSequence skipped = Language.join(
                    zSkippedPending > 0 ? Language.join("\\n", '''+msg('skippingPending','zSkippedPending')+''') : "",
                    zSkippedUnstamped > 0 ? Language.join("\\n", '''+msg('skippingUnstamped','zSkippedUnstamped')+''') : "");
            CharSequence full = Language.join('''+msg('collection')+''', ": ", g.meta.name, "\\n",
                    '''+count('items','coins.size()')+''', " (", idxs.build(), ")\\n",
                    '''+msg('history.to')+''', ": ", recipient, "\\n", selfSendNotice(recipient), skipped,
                    "\\n\\n", '''+msg('separateTransactions')+''', usage.build(), "\\n\\n", '''+msg('wallet.irreversible')+''');''')
    block(r'"Item " \+ \(idx >= 0 \? "#" \+ idx : ""\).*?\+ "Signing and broadcasting…"',
        msg('sendingProgress','idx >= 0 ? "#" + idx : "", i + 1, coins.size()'))
    change('"item " + (idx >= 0 ? "#" + idx : i + 1) + ": " + e.getMessage()',msg('itemFailed','idx >= 0 ? "#" + idx : String.valueOf(i + 1), e.getMessage()'))
    change('"item " + (idx >= 0 ? "#" + idx : i + 1) + " could not be prepared: " + message',msg('itemPrepareFailed','idx >= 0 ? "#" + idx : String.valueOf(i + 1), message'))
    change('"item " + (idx >= 0 ? "#" + idx : i + 1) + " broadcast failed: " + message\n                        + " (its signature is consumed — that leaf is safely skipped)"',msg('itemBroadcastFailed','idx >= 0 ? "#" + idx : String.valueOf(i + 1), message'))
    change('String error, androidx.appcompat.app.AlertDialog pd)', 'CharSequence error, androidx.appcompat.app.AlertDialog pd)')
    block(r'        StringBuilder header = new StringBuilder\(\);.*?else if \(attempted < total\) header.append\("Stopped by you.*?;\n',
        '''        CharSequence header = Language.join('''+msg('broadcastCount','sentCoinIds.size(), total')+''', "\\n",
                error != null ? Language.join('''+msg('stoppedError','error')+''', "\\n")
                : attempted < total ? Language.join('''+msg('stoppedByYou')+''', "\\n") : "");
''')
    change('header.toString()', 'header',2)
    change('final String zHeader,', 'final CharSequence zHeader,',2)
    for confirmed in ['(totalSent - zRemaining.size())','confirmed']:
        old='zHeader + "\\nConfirmed " + '+confirmed+' + " of " + totalSent\n'+('                        ' if confirmed.startswith('(') else '                                ')+'+ "…\\n\\nKeep this screen on — a sleeping phone can lose broadcast"\n'+('                        ' if confirmed.startswith('(') else '                                ')+'+ " transactions before a block includes them."'
        change(old,'Language.join(zHeader, "\\n", '+msg('confirmedCount',confirmed+', totalSent')+', "…\\n\\n", '+msg('keepScreenOn')+')')
    change('StringBuilder done = new StringBuilder();','Language.TextBuilder done = new Language.TextBuilder();')
    change('done.append(zHeader).append("\\nAll ").append(totalSent)\n                                .append(" confirmed ON-CHAIN ✓\\n\\ntxpowids:\\n");',
        'done.append(zHeader).append("\\n").append('+msg('allConfirmed','totalSent')+').append("\\n\\ntxpowids:\\n");')
    change('done.toString()', 'done.build()')
    block(r'        String body = zHeader \+ "\\n".*?still in this wallet and safe to resend\.";',
        '        CharSequence body = Language.join(zHeader, "\\n", '+msg('confirmedCount','totalSent - zRemaining.size(), totalSent')+', "\\n", '+msg('unconfirmedBody','zRemaining.size()')+');')
    change('"Retry unconfirmed (" + zRemaining.size() + ")"',msg('retryUnconfirmed','zRemaining.size()'))
    change('showReport(String zTitle, String zBody, String zAction,', 'showReport(CharSequence zTitle, CharSequence zBody, CharSequence zAction,')
    s=s.replace('new androidx.appcompat.app.AlertDialog.Builder(act)', 'new LanguageDialog(act)')
    for view in ['status','sub','back','count','sendAll','chip','label','sendOne','details','title','counter','send','progress','msg']:
        s=re.sub(r'\b'+re.escape(view)+r'\.setText\(', 'Language.setText('+view+', ',s)
    for view in ['art','back','sendOne']:
        s=re.sub(r'\b'+view+r'\.setContentDescription\(', 'Language.description('+view+', ',s)
    change('addr.setHint(', 'Language.hint(addr, ')
    (folder/name).write_text(s)

    # MainActivity owns the common single-item signing review. Reuse SendView's live composition.
    name='MainActivity.java';s=(folder/name).read_text()
    change('confirmSignAndPublish(String zReview,', 'confirmSignAndPublish(CharSequence zReview,',2)
    change('String full = zReview\n                + "\\nKey " + zKeyIndex + ": using signature #" + mKeyUses.currentUses(zKeyIndex)\n                + " of " + Util.WOTS_MAX_USES\n                + "\\n\\nThis is IRREVERSIBLE. Once broadcast it cannot be undone.";',
        'CharSequence full = Language.join(zReview, "\\n", '+msg('wallet.keyUsage','zKeyIndex, mKeyUses.currentUses(zKeyIndex), Util.WOTS_MAX_USES')+', "\\n\\n", '+msg('wallet.irreversible')+');')
    for old,key in [('Confirm — review carefully','wallet.confirmTransaction'),('Sign & broadcast','wallet.signBroadcast'),('Broadcasting…','wallet.broadcasting')]:label(old,key)
    change('"Wallet session changed. Review again."',msg('wallet.reviewAgain').replace('Language.msg(','Language.text(this, '))
    change('"Sent. txpowid " + txpowid',msg('wallet.broadcastSent','txpowid'))
    change('"Broadcast failed: " + message\n                                        + "\\n(Signature already consumed — that leaf is safely skipped.)"',msg('wallet.broadcastFailed','message'))
    change('"Failed: " + e.getMessage()',msg('failed','e.getMessage()'))
    change('"Scan a Minima address"',msg('scanAddress').replace('Language.msg(','Language.text(this, '))
    (folder/name).write_text(s)

if __name__=='__main__':
    import sys
    adapt(Path(sys.argv[1]))
