"""NFT Send: reuse UTXO live labels, full-ID rows and guarded change-address requests."""
from pathlib import Path
import json,re,importlib.util,xml.etree.ElementTree as E

def adapt(root):
 project=Path(__file__).resolve().parents[1];labels=json.loads((project/'localization/nft-send-labels.json').read_text());catalog=json.loads((project/'localization/catalog.json').read_text());folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 def resource(k):return 'l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',labels[k]).replace('.','_').lower()
 def msg(k,*args):return 'Language.msg('+resource(k)+('' if not args else ', '+', '.join(args))+')'
 def change(s,old,new):
  assert old in s,('NFT send source drift',old)
  return s.replace(old,new)
 p=folder/'SendView.java';s=p.read_text().replace('package com.eurobuddha.nftwallet;','package com.eurobuddha.nftwallet;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;')
 # Same generation check as UTXO: edits/restores and newer requests supersede delayed results.
 s=change(s,'    private boolean changeFetching = false;','    private long changeGeneration, reviewGeneration;\n    private boolean reviewPending;\n    private boolean changeFetching = false;')
 s=change(s,'if (!settingChange) changeTouched = true;','if (!settingChange) { changeTouched = true; changeGeneration++; changeFetching = false; }')
 s=change(s,'        changeFetching = true;\n        act.node().cmd("getaddress"','        final long generation = ++changeGeneration;\n        if (userInitiated) changeTouched = true;\n        changeFetching = true;\n        act.node().cmd("getaddress"')
 s=change(s,'                changeFetching = false;\n                JSONObject r','                if (generation != changeGeneration) return;\n                changeFetching = false;\n                JSONObject r')
 s=change(s,'@Override public void onError(String message) { changeFetching = false; }','@Override public void onError(String message) { if (generation == changeGeneration) changeFetching = false; }')
 s=change(s,'                settingChange = true; changeInput.setText(""); settingChange = false;','                changeGeneration++; changeFetching = false;\n                settingChange = true; changeInput.setText(""); settingChange = false;')
 # Immutable reviewed draft, token and selected-input values. Language-only updates retain it.
 hook='''    private String[] selectionSnapshot() {
        List<String> values = new ArrayList<>();
        for (Coin coin : act.selectedCoins()) {
            values.add(coin.coinid); values.add(coin.tokenid); values.add(coin.amount);
            values.add(Boolean.toString(coin.sendable));
        }
        return values.toArray(new String[0]);
    }
    private boolean matchesDraft(String[] fields, String[] coins) {
        return java.util.Arrays.equals(fields, fieldValues())
                && (coins == null || java.util.Arrays.equals(coins, selectionSnapshot()));
    }
    private boolean acceptReview(long generation, String[] fields, String[] coins) {
        if (generation != reviewGeneration || sending) return false;
        reviewPending = false;
        previewBtn.setEnabled(true);
        if (!matchesDraft(fields, coins)) {
            status(%s, false);
            return false;
        }
        return true;
    }

'''%msg('reviewAgain')
 s=change(s,'    // ----- preview / validate -----',hook+'    // ----- preview / validate -----')
 s=change(s,'    private void onPreview() {','    private void onPreview() {\n        if (sending) return;')
 s=change(s,'        final TokenBalance tok = quickToken();','        final String tokenid = quickTokenid;\n        final TokenBalance tok = quickToken();')
 # Quick-mode uses a captured token ID, as minimaWallet's reviewSend does.
 start=s.index('    private void onPreviewQuick()');end=s.index('    // ----- expert confirm breakdown -----',start)
 quick=s[start:end].replace('Util.isMinima(quickTokenid)','Util.isMinima(tokenid)').replace('act.tokenDecimals(quickTokenid)','act.tokenDecimals(tokenid)').replace('Util.isValidHexId(quickTokenid)','Util.isValidHexId(tokenid)').replace('.append(quickTokenid)','.append(tokenid)')
 quick=change(quick,'final int split, final String tokenName)','final int split, final CharSequence tokenName, final String tokenid)')
 quick=change(quick,'final String tokenName = tok == null ? "Minima" : tok.name;','final CharSequence tokenName = tok == null ? "Minima" : NftText.token(tok);')
 quick=change(quick,'showQuickConfirm(recipient, amountStr, burnStr, split, tokenName);','showQuickConfirm(recipient, amountStr, burnStr, split, tokenName, tokenid, generation, reviewedFields);')
 quick=change(quick,'runQuickSend(recipient, amountStr, burnStr, split, tokenName)','{ if (acceptReview(generation, reviewedFields, null)) runQuickSend(recipient, amountStr, burnStr, split, tokenName, tokenid); }')
 quick=change(quick,'final CharSequence tokenName, final String tokenid) {\n        LinearLayout body','final CharSequence tokenName, final String tokenid, final long generation, final String[] reviewedFields) {\n        LinearLayout body')
 s=s[:start]+quick+s[end:]
 # Two checkaddress requests have independent generations; old callbacks cannot re-enable a newer review.
 marker='        previewBtn.setEnabled(false);\n        status("Checking address…", true);'
 assert s.count(marker)==2
 s=s.replace(marker,'        final long generation = ++reviewGeneration;\n        final String[] reviewedFields = fieldValues();\n        final String[] reviewedCoins = quickMode ? null : selectionSnapshot();\n        reviewPending = true;\n'+marker)
 # Limit changes to the address-validation callbacks, before any actual send callbacks.
 start=s.index('    private void onPreview()');end=s.index('    /** Quick-mode confirm:',start)
 v=s[start:end].replace('                previewBtn.setEnabled(true);','                if (!acceptReview(generation, reviewedFields, reviewedCoins)) return;')
 v=change(v,'hasChange ? changeAddr : null, changeStr, burnStr, tokenid, tokenName, total);','hasChange ? changeAddr : null, changeStr, burnStr, tokenid, tokenName, total, generation, reviewedFields, reviewedCoins);')
 s=s[:start]+v+s[end:]
 s=change(s,'final String tokenid, final String tokenName, BigDecimal total) {','final String tokenid, final String tokenName, BigDecimal total, final long generation,\n                             final String[] reviewedFields, final String[] reviewedCoins) {')
 s=change(s,'buildSend(sel, recipient, amountStr, changeAddr, changeStr, burnStr, tokenid, tokenName))','{ if (acceptReview(generation, reviewedFields, reviewedCoins)) buildSend(sel, recipient, amountStr, changeAddr, changeStr, burnStr, tokenid, tokenName); })')
 s=change(s,'        sending = true;','        reviewGeneration++; reviewPending = false;\n        final String[] submittedFields = fieldValues();\n        final String[] submittedCoins = quickMode ? null : selectionSnapshot();\n        sending = true;')
 s=change(s,'previewBtn.setEnabled(!sending);','previewBtn.setEnabled(!sending && !reviewPending);')
 # Reuse the existing Future Cash success guard: a late success must not erase a new draft.
 s=change(s,'                    addrInput.setText("");\n                    amountInput.setText("");\n                    burnInput.setText("");\n                    splitInput.setText("");','                    if (matchesDraft(submittedFields, submittedCoins)) {\n                        addrInput.setText(""); amountInput.setText(""); burnInput.setText(""); splitInput.setText("");\n                    }')
 s=change(s,'                addrInput.setText("");\n                amountInput.setText("");\n                burnInput.setText("");\n                changeGeneration++; changeFetching = false;\n                settingChange = true; changeInput.setText(""); settingChange = false;\n                changeTouched = false;', '                if (matchesDraft(submittedFields, submittedCoins)) {\n                    addrInput.setText(""); amountInput.setText(""); burnInput.setText("");\n                    changeGeneration++; changeFetching = false;\n                    settingChange = true; changeInput.setText(""); settingChange = false;\n                    changeTouched = false;\n                    act.clearSelection();\n                }')
 s=change(s,'                act.clearSelection();\n                act.reload();','                act.reload();')
 # Display-only token provenance; raw tokenName is still passed to the transaction history.
 s=change(s,'String name = b == null ? "Minima" : displayName(b);','CharSequence name = b == null ? "Minima" : displayName(b);')
 s=change(s,'    private String displayName(TokenBalance b) {\n        String n = b.name == null || b.name.isEmpty() ? "Token" : b.name;', '    private CharSequence displayName(TokenBalance b) {\n        CharSequence n = NftText.token(b);')
 s=change(s,'!b.meta.ticker.equalsIgnoreCase(n)) n = n + "  ·  " + b.meta.ticker.toUpperCase();','!b.meta.ticker.equalsIgnoreCase(b.name)) n = Language.join(n, "  ·  ", b.meta.ticker.toUpperCase(java.util.Locale.ROOT));')
 s=change(s,'Util.tidyAmount(total.toPlainString()) + " " + tokenName','Language.join(Util.tidyAmount(total.toPlainString()), " ", NftText.token(sel.get(0)))')
 # Full identifiers are reused from UTXO confirmation. The existing NFT copy helper resolves labels at tap time.
 s=change(s,'body.addView(row("→ " + Util.shorten(recipient) + (isMine(recipient) ? "  (yours)" : ""),\n                Util.tidyAmount(amountStr) + " " + tokenName, false));', 'body.addView(idRow('+msg('to')+', recipient, isMine(recipient) ? "(yours)" : "",\n                Language.join(Util.tidyAmount(amountStr), " ", tokenName)));')
 # Expert review uses the display-only name without changing the raw History name.
 start=s.index('    private void showConfirm(');end=s.index('    /** Records a History row',start)
 expert=s[start:end].replace('Language.join(Util.tidyAmount(amountStr), " ", tokenName)','Language.join(Util.tidyAmount(amountStr), " ", NftText.token(sel.get(0)))')
 expert=change(expert,'body.addView(row("• " + Util.shorten(c.coinid), Util.tidyAmount(c.amount), false))','body.addView(idRow('+msg('coin')+', c.coinid, "", Util.tidyAmount(c.amount)))')
 expert=change(expert,'body.addView(row("↩ " + Util.shorten(changeAddr) + (isMine(changeAddr) ? "  (yours)" : "  (verify)"),\n                    Util.tidyAmount(changeStr) + " " + tokenName, false));','body.addView(idRow('+msg('change')+', changeAddr, isMine(changeAddr) ? "(yours)" : "(verify)",\n                    Language.join(Util.tidyAmount(changeStr), " ", NftText.token(sel.get(0)))));')
 s=s[:start]+expert+s[end:]
 s=change(s,'"Max sendable is " + Util.tidyAmount(maxSpendable.toPlainString()) + "."',msg('maxAmount','Util.tidyAmount(maxSpendable.toPlainString())'))
 s=change(s,'"Max sendable is " + Util.tidyAmount(tok.sendable) + "."',msg('maxAmount','Util.tidyAmount(tok.sendable)'))
 s=change(s,'"This token supports at most " + dec + " decimals."',msg('decimals','dec'))
 s=change(s,'"Amount + burn exceeds sendable (" + Util.tidyAmount(tok.sendable) + ")."',msg('amountBurnMax','Util.tidyAmount(tok.sendable)'))
 s=change(s,'split + " equal coins"',msg('equalCoins','split'))
 s=change(s,'"✓ Sent — " + Util.tidyAmount(amountStr) + " " + tokenName + " to "\n                            + Util.shorten(recipient) + "."',msg('sent','Util.tidyAmount(amountStr)','tokenName','recipient'))
 s=change(s,'"✓ Posted — " + Util.tidyAmount(amountStr) + " " + tokenName\n                        + " sent. The Explorer link appears in History once it confirms on-chain."',msg('posted','Util.tidyAmount(amountStr)','NftText.token(sel.get(0))'))
 s=change(s,'"Failed: " + message',msg('failed','NftMessages.message(message)'))
 s=change(s,'"Failed: " + err',msg('failed','NftMessages.message(err)'))
 s=change(s,').onProgress(label -> status(label, true)).run();',').onProgress(label -> status(NftMessages.message(label), true)).run();')
 # Keep error strings literal at persistence/transport boundaries; translate the UI expression only.
 s=change(s,'? "The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry." : "Could not validate address."','? Language.msg(l10n_explorer_node_retry) : '+msg('validateFailed'))
 s=change(s,'sectionLabel("SEND")','sectionLabel(Language.upper('+msg('sendCaps')+'))')
 fixed=['indivisible','chooseCoins','totalIn','burnOnly','token','noBalances','whichToken','cancel','selectFirst','recipientRequired','invalidAddress','invalidBurn','burnExceeds','validAmount','positiveAmount','changeRequired','invalidChange','checking','invalidMinima','pickToken','splitRange','burn','split','coins','nodeChosen','warningQuick','confirm','back','sendAction','sending','badTokenId','inputs','outputs','warning','signPost','recording']
 for k in fixed:s=change(s,json.dumps(catalog[labels[k]]['en'],ensure_ascii=False),msg(k))
 for old,new in [('row(String left, String right','row(CharSequence left, CharSequence right'),('sectionLabel(String text)','sectionLabel(CharSequence text)'),('setNote(TextView note, String msg','setNote(TextView note, CharSequence msg'),('status(String msg','status(CharSequence msg')]:s=change(s,old,new)
 s=change(s,'        r.addView(rt);','        rt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));\n        rt.setGravity(Gravity.END);\n        r.addView(rt);')
 s=change(s,'        root.setBackgroundColor(Design.bg());','        root.setBackgroundColor(Design.bg());\n        themeLabels(root);')
 s=change(s,'            e.setTextColor(Design.text());','            e.setTextColor(Design.text());\n            e.setHintTextColor(Design.dim());')
 s=change(s,'        previewBtn.setTextColor(Design.onAccent());','        previewBtn.setTextColor(Design.onAccent());\n        for (int id : new int[]{R.id.pm_nftwallet_scanBtn, R.id.pm_nftwallet_maxBtn, R.id.pm_nftwallet_nextBtn})\n            ((TextView)find(id)).setTextColor(Design.accentText());')
 utxo=(root/'utxo/src/main/java/com/eurobuddha/utxo/SendView.java').read_text()
 begin=utxo.index('    private void themeLabels(');finish=utxo.index('    /** Rebuilds',begin);s=change(s,'    /** Rebuilds',utxo[begin:finish]+'    /** Rebuilds')
 begin=utxo.index('    /**\n     * A row for an identifier');finish=utxo.index('    /** Builds a small dim',begin);idrow=utxo[begin:finish]
 begin=idrow.index('            ClipboardManager cm');finish=idrow.index('\n        });',begin);idrow=idrow[:begin]+'            CoinDetailDialog.copy(act, label, value);'+idrow[finish:]
 s=change(s,'    /** Builds a small dim',idrow+'    /** Builds a small dim')
 s=re.sub(r'\b([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)\.setText\(',r'Language.setText(\1, ',s).replace('Language.setText(Language, ','Language.setText(')
 p.write_text(s)
 p=folder/'Sheet.java';s=p.read_text();s=change(s,'        b.setAllCaps(false);','        b.setAllCaps(false);\n        b.setMinHeight(dp(48));');s=change(s,'        actionRow.setOrientation(LinearLayout.HORIZONTAL);','        actionRow.setOrientation(LinearLayout.HORIZONTAL);\n        actionRow.setBaselineAligned(false);')
 # Reuse the panel's vertical arrangement when three actions cannot share readable widths.
 s=change(s,'        actionRow.addView(b);', '''        actionRow.addView(b);
        if (actionRow.getChildCount() > 2) {
            actionRow.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < actionRow.getChildCount(); i++) {
                LinearLayout.LayoutParams stacked = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                stacked.topMargin = i == 0 ? 0 : dp(8);
                actionRow.getChildAt(i).setLayoutParams(stacked);
            }
        }''');p.write_text(s)
 a='{http://schemas.android.com/apk/res/android}';E.register_namespace('android',a[1:-1])
 keys=['quickMode','coinMode','token','fromSelected','recipient','addressHint','amount','max','splitLabel','changeTo','next','burnLabel','preview']
 mapping={catalog[labels[k]]['en']:resource(k) for k in keys};mapping['⌗ SCAN']=resource('scan')
 p=root/'NFTwallet/src/main/res/layout/pm_nftwallet_view_send.xml';tree=E.parse(p)
 for v in tree.iter():
  tags=[]
  for attr in ['text','hint']:
   raw=v.get(a+attr)
   if raw in mapping:v.set(a+attr,'@string/'+mapping[raw]);tags.append(attr+'='+mapping[raw])
  if tags:
   assert v.get(a+'tag') is None
   v.set(a+'tag','l10n:'+';'.join(tags))
  if v.get(a+'id','').endswith(('maxBtn','nextBtn','scanBtn','modeQuickBtn','modeCoinsBtn')):
   v.set(a+'minHeight','48dp');v.set(a+'minWidth','48dp');v.set(a+'gravity','center')
 E.indent(tree,space='    ');tree.write(p,encoding='unicode',xml_declaration=True)
 # Same explicit diagnostic generator as UTXO; never pass token names or other content through it.
 spec=json.loads((project/'localization/vestr-errors.json').read_text())
 spec['fixed'].update({'NOT_ENABLED':'explorer.nodeRetry','The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.':'explorer.nodeRetry'})
 for k in ['building','signing','rejected','tooLong','sendFailed']:spec['fixed'][catalog[labels[k]]['en']]=labels[k]
 spec['patterns']=[{'template':'Adding inputs ({0}/{1})…','key':'utxo.addingInputs','kinds':['integer','integer']},{'template':'Adding outputs ({0}/{1})…','key':'utxo.addingOutputs','kinds':['integer','integer']}]
 extra=json.loads((project/'localization/utxo-history-messages.json').read_text())
 spec['fixed'].update(extra['fixed']);spec['patterns'].extend(extra['patterns'])
 loader=importlib.util.spec_from_file_location('nft_messages',project/'scripts/adapt-pandamonium-wallet-error-languages.py');module=importlib.util.module_from_spec(loader);loader.loader.exec_module(module)
 source=module.build_source(spec,catalog).replace('com.eurobuddha.wallet','com.eurobuddha.nftwallet').replace('WalletMessages','NftMessages').replace('Explicit wallet failures only.','Explicit NFT local diagnostics only.').replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json','adapt-pandamonium-nft-send-languages.py')
 (folder/'NftMessages.java').write_text(source)
 p=folder/'MainActivity.java';s=p.read_text();s=change(s,'Toast.makeText(this, message, Toast.LENGTH_SHORT)','Toast.makeText(this, NftMessages.message(message), Toast.LENGTH_SHORT)');p.write_text(s)
 p=folder/'ReceiveView.java';s=p.read_text();s=change(s,'Toast.makeText(act, message, Toast.LENGTH_SHORT)','Toast.makeText(act, NftMessages.message(message), Toast.LENGTH_SHORT)');p.write_text(s)
