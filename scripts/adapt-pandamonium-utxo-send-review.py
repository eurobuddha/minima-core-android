"""Reuse NFT Send's reviewed-draft guards and WalletTools' frozen coin fields."""
from pathlib import Path

def adapt(root):
    folder=root/'utxo/src/main/java/com/eurobuddha/utxo'
    donor=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    def change(s,old,new):
        assert s.count(old)==1, ('UTXO send source drift',old,s.count(old))
        return s.replace(old,new)
    p=folder/'TxnUtil.java';s=p.read_text();n=(donor/'TxnUtil.java').read_text()
    a=n.index('    /** Literal public selection fields:');b=n.index('    /** Native send/consolidate',a)
    s=change(s,'    private TxnUtil() {}','    private TxnUtil() {}\n\n'+n[a:b].rstrip());p.write_text(s)
    p=folder/'SendView.java';s=p.read_text();n=(donor/'SendView.java').read_text()
    a=n.index('    private String[] selectionSnapshot()');b=n.index('    // ----- preview / validate -----',a)
    helpers=n[a:b].replace('if (generation != reviewGeneration || sending) return false;',
        'if (generation != reviewGeneration || sending || act.isFinishing() || act.isDestroyed()) return false;')
    # Use the exact public-field copy already reviewed in NFT WalletTools.Review.
    w=(donor/'WalletTools.java').read_text();a=w.index('            for (Coin source : selected) {');b=w.index('            tokenid=coins.isEmpty()',a)
    copy=w[a:b].replace('                // Freeze the command and display fields; these tools do not replay NFT state.\n','')
    helpers+='    private List<Coin> freezeSelection() {\n        List<Coin> selected=act.selectedCoins(), coins=new ArrayList<>();\n'+copy+'        return coins;\n    }\n\n'
    s=change(s,'    private long changeGeneration;','    private long changeGeneration, reviewGeneration;\n    private boolean sending, reviewPending;')
    s=change(s,'    // ----- preview / validate -----',helpers+'    // ----- preview / validate -----')
    s=change(s,'        previewBtn.setEnabled(true);\n\n        String tokenName','        previewBtn.setEnabled(!sending && !reviewPending);\n\n        String tokenName')
    s=change(s,'    private void onPreview() {\n        final List<Coin> sel = act.selectedCoins();','    private void onPreview() {\n        if (sending || act.isFinishing() || act.isDestroyed()) return;\n        final List<Coin> sel = freezeSelection();')
    marker='        // Confirm the recipient with the node before showing the breakdown.\n'
    s=change(s,marker,marker+'        final long generation = ++reviewGeneration;\n        final String[] reviewedFields = fieldValues();\n        final String[] reviewedCoins = selectionSnapshot();\n        reviewPending = true;\n')
    a=s.index('    private void onPreview()');b=s.index('    // ----- confirm breakdown -----',a)
    part=s[a:b].replace('                previewBtn.setEnabled(true);','                if (!acceptReview(generation, reviewedFields, reviewedCoins)) return;')
    part=change(part,'hasChange ? changeAddr : null, changeStr, burnStr, tokenid, tokenName, total);','hasChange ? changeAddr : null, changeStr, burnStr, tokenid, tokenName, total, generation, reviewedFields, reviewedCoins);')
    s=s[:a]+part+s[b:]
    s=change(s,'final String tokenid, final String tokenName, BigDecimal total) {','final String tokenid, final String tokenName, BigDecimal total, final long generation,\n                             final String[] reviewedFields, final String[] reviewedCoins) {')
    s=change(s,'                        buildSend(sel, recipient, amountStr, changeAddr, changeStr, burnStr, tokenid, tokenName))','                        { if (acceptReview(generation, reviewedFields, reviewedCoins)) buildSend(sel, recipient, amountStr, changeAddr, changeStr, burnStr, tokenid, tokenName); })')
    marker='                           final String tokenid, final String tokenName) {\n'
    s=change(s,marker,marker+'        reviewGeneration++; reviewPending = false;\n        final String[] submittedFields = fieldValues();\n        final String[] submittedCoins = selectionSnapshot();\n        sending = true;\n')
    a=s.index('                Language.setText(addrInput, "");',s.index('    private void buildSend('));b=s.index('                act.reload();',a)
    old=s[a:b];guarded=old[:old.index('                previewBtn.setEnabled(true);')]
    new='                if (matchesDraft(submittedFields, submittedCoins)) {\n'+''.join('    '+line+'\n' for line in guarded.splitlines())+'                    act.clearSelection();\n                }\n                sending = false;\n                previewBtn.setEnabled(true);\n'
    s=s[:a]+new+s[b:]
    a=s.index('            @Override public void onFailed(',s.index('    private void buildSend('));b=s.index('        }).record(internalid)',a)
    part=s[a:b].replace('                previewBtn.setEnabled(true);','                sending = false;\n                previewBtn.setEnabled(true);').replace('                act.clearSelection();\n','')
    s=s[:a]+part+s[b:];p.write_text(s)
