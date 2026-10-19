"""Reuse Studio's tagged-view refresh to preserve the Shop checkout form."""
def adapt(root):
    p=root/'merchshop/src/main/java/com/eurobuddha/merchshop/MainActivity.java';s=p.read_text()
    old='''    private View buildWalletRow() {
        LinearLayout row = new LinearLayout(this);'''
    new='''    private View buildWalletRow() {
        LinearLayout row = new LinearLayout(this); row.setTag("checkoutWallet");'''
    assert s.count(old)==1;s=s.replace(old,new)
    old='''    private void refreshCheckoutIfTop() {
        View top = stack.peek();
        if (top != null && "checkout".equals(top.getTag())) refreshTop(buildCheckout());
    }'''
    new='''    private void refreshCheckoutIfTop() {
        if (isFinishing() || isDestroyed()) return;
        View top = stack.peek();
        if (top == null || !"checkout".equals(top.getTag())) return;
        // Studio's tagged-view update: keep the form, shipping choice and active Editable intact.
        View wallet = top.findViewWithTag("checkoutWallet");
        if (wallet == null || !(wallet.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) wallet.getParent();
        int index = parent.indexOfChild(wallet);
        View updated = buildWalletRow(); updated.setLayoutParams(wallet.getLayoutParams());
        parent.removeViewAt(index); parent.addView(updated, index);
    }'''
    assert s.count(old)==1;s=s.replace(old,new)
    old='''                if (r != null) { String a = r.optString("miniaddress", r.optString("address", "")); if (!a.isEmpty()) buyerPayaddr = a; }
'''
    assert s.count(old)==1;s=s.replace(old,old+'                refreshCheckoutIfTop();\n')
    p.write_text(s)
    p=root/'merchshop/build.gradle';s=p.read_text()
    old='testOptions { unitTests.returnDefaultValues = true }';assert old in s
    s=s.replace(old,'testOptions { unitTests.returnDefaultValues = true; unitTests.includeAndroidResources = true }')
    old="    testImplementation 'junit:junit:4.13.2'";assert old in s
    p.write_text(s.replace(old,old+"\n    testImplementation 'org.robolectric:robolectric:4.14.1'"))
    adapt_status(root)


def adapt_status(root):
    p=root/'merchshop/src/main/java/com/eurobuddha/merchshop/MainActivity.java';s=p.read_text()
    old='        if (crypto == null) {\n            TextView s = new TextView(this);\n            Language.setText(s, paired ? Language.msg(l10n_mail_connecting) : Language.msg(l10n_mall_shop_node_unavailable));\n            s.setTextColor(Design.DIM); s.setTextSize(13f); s.setPadding(dp(16), dp(8), dp(16), dp(8));\n            col.addView(s);\n        }'
    assert s.count(old)==2
    s=s.replace(old,'        col.addView(buildConnectionStatus());')
    marker='    private void onPaired(boolean enabled) {'
    helper='    /** Same tagged-view refresh as checkout/Studio: never rebuild a cart or typed form. */\n    private TextView buildConnectionStatus() {\n        TextView status = new TextView(this); status.setTag("shopConnectionStatus");\n        status.setTextColor(Design.DIM); status.setTextSize(13f); status.setPadding(dp(16),dp(8),dp(16),dp(8));\n        updateConnectionStatus(status); return status;\n    }\n    private void updateConnectionStatus(TextView status) {\n        Language.setText(status, paired ? Language.msg(l10n_mail_connecting) : Language.msg(l10n_mall_shop_node_unavailable));\n        status.setVisibility(paired && crypto != null ? View.GONE : View.VISIBLE);\n    }\n    private void refreshConnectionStatus() {\n        if (isFinishing() || isDestroyed()) return;\n        for (View page : stack) {\n            TextView status = page.findViewWithTag("shopConnectionStatus");\n            if (status != null) updateConnectionStatus(status);\n        }\n    }\n\n'
    assert marker in s;s=s.replace(marker,helper+marker)
    old='        paired = enabled;';assert s.count(old)==1;s=s.replace(old,old+'\n        refreshConnectionStatus();')
    old='        scanner = new CommsScanner(node, crypto, db, CommsTransport.MINIMERCH_ADDRESS,\n                this::routeIncoming, (ok, n) -> onScanDone(n));'
    assert s.count(old)==1;s=s.replace(old,old+'\n        refreshConnectionStatus();')
    p.write_text(s)
