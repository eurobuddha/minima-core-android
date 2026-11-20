package com.eurobuddha.merchstudio;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/** Pandamonium: every Studio export also saves the published shop where the Inbox in this app reads its listing. */
public class ListingSaveTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void theExportedShopIsSavedForTheInboxAndReplacedEachTime() throws Exception {
        JSONObject shop = new JSONObject().put("shopName", "Art").put("shopId", "art").put("vendorPublicId", "0x" + "ab".repeat(64)).put("currency", "Minima");
        File f = new ShopExport(shop, "art", Collections.emptyList()).saveListing(tmp.getRoot());
        assertEquals(new File(new File(tmp.getRoot(), "mall_listings"), "art.shop"), f);
        assertEquals("art", new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getString("shopId"));
        shop.put("currency", "USDT");
        new ShopExport(shop, "art", Collections.emptyList()).saveListing(tmp.getRoot());
        assertEquals("USDT", new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getString("currency"));
        shop.put("shopId", "../escape");
        assertThrows(IllegalArgumentException.class, () -> new ShopExport(shop, "art", Collections.emptyList()).saveListing(tmp.getRoot()));
    }
}
