package com.eurobuddha.merchstudio;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Security review M1: what Studio signs is the shop without pictures, collection icons or an earlier signature. */
public class ListingOfTest {
    @Test public void theSignedListingIsTheShopWithoutPictures() throws Exception {
        JSONObject shop = new JSONObject().put("shopId", "art").put("currency", "Minima").put("listingSealed", "0xOLD")
                .put("products", new JSONArray().put(new JSONObject().put("name", "Piece").put("price", "10").put("image", "AAAA")))
                .put("nftCollections", new JSONArray().put(new JSONObject().put("icon", "BBBB")));
        JSONObject l = new JSONObject(ShopExport.listingOf(shop));
        assertFalse(l.has("listingSealed")); assertFalse(l.has("nftCollections"));
        assertFalse(l.getJSONArray("products").getJSONObject(0).has("image"));
        assertEquals("10", l.getJSONArray("products").getJSONObject(0).getString("price"));
        assertTrue("the shop itself is unchanged", shop.has("listingSealed"));
        assertEquals("minimall-listing-v1", l.getString("kind"));
        long at = l.getLong("signedAt");
        assertTrue(at > 1_700_000_000_000L && at <= System.currentTimeMillis());
    }
}
