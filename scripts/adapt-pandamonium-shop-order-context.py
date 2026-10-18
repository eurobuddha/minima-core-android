"""Bind Shop sends to the already assembled order and its persisted recipient address."""
def adapt(root):
    p=root/'merchshop/src/main/java/com/eurobuddha/merchshop/MainActivity.java';s=p.read_text()
    def change(old,new):
        nonlocal s
        assert s.count(old)==1,('Shop order context drift',old,s.count(old))
        s=s.replace(old,new)
    change('        m.shopId = catalog.shopId; m.shopName = catalog.shopName;\n',
           '        m.shopId = catalog.shopId; m.shopName = catalog.shopName;\n        final String vendorAddress = catalog.vendorAddress;\n')
    change('db.setMeta("vendoraddr:" + ref, catalog.vendorAddress);', 'db.setMeta("vendoraddr:" + ref, vendorAddress);')
    change('CommsTransport.sendMessage(node, crypto, catalog.vendorPublicId, m.toWire(),', 'CommsTransport.sendMessage(node, crypto, m.to, m.toWire(),')
    change('CommsTransport.sendPayment(node, catalog.vendorAddress, totalStr, catalog.tokenid, ref,', 'CommsTransport.sendPayment(node, vendorAddress, m.amount, m.tokenid, ref,')
    start=s.index('        // Resolve the vendor address WITHOUT the live catalog')
    end=s.index('        final AlertDialog progress',start)
    s=s[:start]+'''        // The original order owns the destination. Imported shops may have changed since purchase.
        final String ref = o.ref, amount = o.amount, tokenid = o.tokenid, currency = o.currency;
        final String vaddr = db.getMeta("vendoraddr:" + ref, "");
        if (vaddr == null || vaddr.isEmpty()) { toast(Language.msg(l10n_mall_shop_retry_unavailable)); return; }
'''+s[end:]
    start=s.index('    private void retryPayment(');end=s.index('    // ---- my orders ----',start)
    retry=s[start:end].replace('l10n_mall_shop_sending_payment, o.amount, o.currency','l10n_mall_shop_sending_payment, amount, currency')
    retry=retry.replace('sendPayment(node, vaddr, o.amount, o.tokenid, o.ref,','sendPayment(node, vaddr, amount, tokenid, ref,')
    retry=retry.replace('"unpaid:" + o.ref','"unpaid:" + ref').replace('buildOrderDetail(o.ref)','buildOrderDetail(ref)')
    s=s[:start]+retry+s[end:];p.write_text(s)
