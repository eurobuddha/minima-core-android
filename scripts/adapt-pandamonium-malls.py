"""Adapt the proven miniMall family to one host without sharing buyer/vendor state."""
from pathlib import Path


def adapt(root):
    def change(path, old, new, count=1):
        source = path.read_text()
        assert source.count(old) == count, ('Mall adaptation drift', path, old)
        path.write_text(source.replace(old, new))

    for name in ('merchshop', 'merchstudio', 'merchinbox'):
        gradle = root/name/'build.gradle'
        change(gradle, '    buildFeatures { buildConfig true }',
               '    buildFeatures { buildConfig true }\n    testOptions { unitTests.returnDefaultValues = true }')
        change(gradle, 'dependencies {', "dependencies {\n    testImplementation 'org.mockito:mockito-core:5.12.0'")
        folder = root / name / 'src/main/java'
        comms = folder / 'com/eurobuddha/comms'
        change(comms/'MerchDb.java', '"minima_merch.db"', '"pandamonium_'+name+'.db"')
        # Same handler-boundary adaptation as the bundled Future Cash NodeApi.
        change(comms/'NodeApi.java', '    public void cmd(String command, Cb cb) {', """    public void cmd(String command, Cb cb) {
        mMain.post(() -> cmdOnMain(command, cb));
    }

    private void cmdOnMain(String command, Cb cb) {""")
        change(comms/'NodeApi.java', '        if (mReleased) return;', """        if (mReleased) {
            if (cb != null) cb.onError("Node connection closed.");
            return;
        }""")
        # Validate opaque command arguments with the existing Future Cash Node gates.
        # Node commands are text; no imported catalog field may introduce another command.
        change(comms/'CommsTransport.java', '        try {\n            JSONObject extra = new JSONObject();', """        if (vendorAddress == null || !(vendorAddress.matches("^0x[0-9a-fA-F]{2,}$")
                || vendorAddress.matches("^M[xX][0-9A-Za-z]{30,100}$"))
                || tokenid == null || !tokenid.matches("^0x[0-9a-fA-F]{2,}$")
                || amount == null || !amount.matches("[0-9]+(?:\\\\.[0-9]+)?")) {
            cb.onFailed("Invalid payment address, token or amount.");
            return;
        }
        try {
            JSONObject extra = new JSONObject();""")
        main = folder/'com/eurobuddha'/name/'MainActivity.java'
        text = main.read_text()
        for phrase in ('Enable miniMall Shop in Minima Core → Apps to order.',
                       'Enable miniMall Inbox in Minima Core → Apps to receive orders.',
                       'Enable miniMall Inbox in Minima Core → Apps.',
                       'Enable miniMall Studio in Minima Core → Apps.'):
            text = text.replace(phrase, 'The embedded node is unavailable. Open Minima Core from the menu and check its status.')
        main.write_text(text)
        if name == 'merchshop':
            change(main, '.notify(3, b.build())', '.notify("pandamonium.mall.shop", 3, b.build())')
        elif name == 'merchinbox':
            change(main, '.notify(2, b.build())', '.notify("pandamonium.mall.inbox", 2, b.build())', 2)

    shop = root/'merchshop/src/main/java/com/eurobuddha/merchshop'
    change(shop/'MainActivity.java', 'final ImageView art = new ImageView(MainActivity.this) {', 'final ImageView art = new androidx.appcompat.widget.AppCompatImageView(MainActivity.this) {')
    change(shop/'ShopStore.java', '"shops"', '"pandamonium_mall_shops"')
    change(shop/'ShopStore.java', 'new File(dir, id + ".json")', 'fileFor(id)')
    change(shop/'ShopStore.java', '    public Catalog get(String shopId) { return read(new File(dir, shopId + ".json")); }', """    public Catalog get(String shopId) {
        try { return read(fileFor(shopId)); } catch (java.io.IOException invalid) { return null; }
    }""")
    change(shop/'ShopStore.java', '    public void delete(String shopId) { new File(dir, shopId + ".json").delete(); }', """    public void delete(String shopId) {
        try { fileFor(shopId).delete(); } catch (java.io.IOException invalid) { /* Refuse escaped paths. */ }
    }

    /** Canonical containment reused from the host's NodeFiles.resolveInBase. */
    private File fileFor(String id) throws java.io.IOException {
        if (id == null || id.isEmpty() || id.indexOf('/') >= 0 || id.indexOf('\\\\') >= 0)
            throw new java.io.IOException("Invalid shop identifier");
        File target = new File(dir, id + ".json").getCanonicalFile();
        if (!dir.getCanonicalFile().equals(target.getParentFile()))
            throw new java.io.IOException("Shop path outside its folder");
        return target;
    }""")
    change(shop/'ShopStore.java', '        try {\n            byte[] b = new byte[(int) f.length()];', """        try {
            if (!dir.getCanonicalFile().equals(f.getCanonicalFile().getParentFile())
                    || f.length() > 16 * 1024 * 1024) return null;
            byte[] b = new byte[(int) f.length()];""")
    change(shop/'MainActivity.java', 'byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);', """byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) {
                    if (bos.size() + n > 16 * 1024 * 1024) throw new java.io.IOException("Shop file is too large");
                    bos.write(buf, 0, n);
                }""")
