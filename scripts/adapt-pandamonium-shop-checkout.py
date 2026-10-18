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
