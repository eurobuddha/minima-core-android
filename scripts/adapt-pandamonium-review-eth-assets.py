"""Retain the existing ERC20 contract identity through balance and send UI."""
from pathlib import Path


def adapt(root):
    folder = root / 'ethwallet/src/main/java/com/eurobuddha/ethwallet'
    p = folder / 'MainActivity.java'
    s = p.read_text()
    def change(old, new, count=1):
        nonlocal s
        assert s.count(old) == count, ('ETH asset source drift', old, s.count(old))
        s = s.replace(old, new)

    change('// symbol -> formatted balance', '// normalized contract address -> formatted balance')
    change('bals.put(tk.symbol,', 'bals.put(tokenKey(tk),', 2)
    change('tokenBals.containsKey(tk.symbol) ? tokenBals.get(tk.symbol)', 'tokenBals.containsKey(tokenKey(tk)) ? tokenBals.get(tokenKey(tk))')
    change('sendDialog("ETH")', 'sendDialog(null)', 2)
    change('sendDialog(ftk.symbol)', 'sendDialog(ftk)')
    change('tokenIconUrl(tk.address), tk.symbol,', 'tokenIconUrl(tk.address), shortAddr(tk.address),')
    change('sendDialog(tk.symbol)', 'sendDialog(tk)')
    change('tokenBals.remove(tk.symbol)', 'tokenBals.remove(tokenKey(tk))')
    change('languageDialog().setTitle(tk.symbol)', 'languageDialog().setTitle(tokenLabel(tk))')
    change('private void sendDialog(String startSym)', 'private void sendDialog(EthNet.Token startToken)')
    change('''        final List<String> syms = new ArrayList<>();
        syms.add("ETH");
        for (EthNet.Token t : tokens.tokens()) syms.add(t.symbol);
        final String[] chosen = { syms.contains(startSym) ? startSym : "ETH" };''', '''        final List<EthNet.Token> assets = new ArrayList<>();
        assets.add(null); // Native ETH is distinct from every ERC20 symbol.
        assets.addAll(tokens.tokens());
        if (startToken != null && !assets.contains(startToken)) {
            modalOpen = false; toast(Language.msg(l10n_eth_wallet_missing_token, startToken.symbol)); return;
        }
        final EthNet.Token[] chosen = { startToken };''')
    change('l10n_eth_wallet_token_balance, chosen[0], balOf(chosen[0])', 'l10n_eth_wallet_token_balance, tokenLabel(chosen[0]), balOf(chosen[0])')
    change('''            String[] arr = syms.toArray(new String[0]);''', '''            String[] arr = new String[assets.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = tokenLabel(assets.get(i));''')
    change('chosen[0] = arr[i]', 'chosen[0] = assets.get(i)')
    change('if (!"ETH".equals(chosen[0]))', 'if (chosen[0] != null)')
    change('''            toast(Language.msg(l10n_eth_wallet_reserving));''', '''            final String typedAmount = amt.getText().toString();
            toast(Language.msg(l10n_eth_wallet_reserving));''')
    change('onUi(() -> Language.setText(amt, out));', 'onUi(() -> { if (chosen[0] == null && typedAmount.equals(amt.getText().toString())) Language.setText(amt, out); });')
    change('onUi(() -> Language.setText(amt, balOf("ETH")));', 'onUi(() -> { if (chosen[0] == null && typedAmount.equals(amt.getText().toString())) Language.setText(amt, balOf(null)); });')
    change('private void confirmSend(String sym, String to, String amount) {', '''private void confirmSend(EthNet.Token tk, String to, String amount) {
        final boolean isEth = tk == null;
        final String sym = isEth ? "ETH" : tk.symbol;
        if (!isEth && !tokens.has(tk.address)) { toast(Language.msg(l10n_eth_wallet_missing_token, sym)); return; }''')
    change('''                final boolean isEth = "ETH".equals(sym);
                final EthNet.Token tk = isEth ? null : tokenBySym(sym);
                if (!isEth && tk == null) { onUi(() -> toast(Language.msg(l10n_eth_wallet_missing_token, sym))); return; }
''', '')
    change('''        box.addView(kvLine(Language.msg(l10n_eth_wallet_send), amount + " " + sym));''', '''        box.addView(kvLine(Language.msg(l10n_eth_wallet_send), amount + " " + sym));
        if (!isEth) {
            box.addView(kvLine(Language.msg(l10n_eth_wallet_contract_hint), ""));
            box.addView(fullAddrBlock(txTo));
        }''')
    change('''    private String balOf(String sym) { return "ETH".equals(sym) ? ethBal : (tokenBals.containsKey(sym) ? tokenBals.get(sym) : "0"); }
    private EthNet.Token tokenBySym(String sym) { for (EthNet.Token t : tokens.tokens()) if (t.symbol.equals(sym)) return t; return null; }''', '''    private static String tokenKey(EthNet.Token token) { return token.address.toLowerCase(java.util.Locale.ROOT); }
    private static String tokenLabel(EthNet.Token token) { return token == null ? "ETH · Ethereum" : token.symbol + " · " + token.address; }
    private String balOf(EthNet.Token token) { return token == null ? ethBal : tokenBals.getOrDefault(tokenKey(token), "0"); }''')
    p.write_text(s)
    p = root / 'ethwallet/build.gradle'
    s = p.read_text().replace('unitTests.returnDefaultValues = true', "unitTests.returnDefaultValues = true; unitTests.includeAndroidResources = true; unitTests.all { maxHeapSize = '2g' }")
    s = s.replace("testImplementation 'org.mockito:mockito-core:5.12.0'", "testImplementation 'org.mockito:mockito-core:5.12.0'\n    testImplementation 'org.robolectric:robolectric:4.14.1'")
    p.write_text(s)
