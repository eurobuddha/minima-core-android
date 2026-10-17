package com.eurobuddha.nftwallet;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A buried coin sits at the graveyard address, but the node keeps returning it from
 * {@code coins relevant:true} because it still tracks the coin. {@code balance} does not count it,
 * so before this filter existed a buried collection vanished from the token-driven Balances tab
 * while the coin-driven Gallery went on showing every buried item as though it were still held.
 */
public class BuriedCoinTest {

    private static final String TOKEN =
            "0x1111111111111111111111111111111111111111111111111111111111111111";
    private static final String MINE =
            "0x2222222222222222222222222222222222222222222222222222222222222222";

    private static Coin coinAt(String address) throws Exception {
        JSONObject j = new JSONObject();
        j.put("coinid", "0x3333333333333333333333333333333333333333333333333333333333333333");
        j.put("address", address);
        j.put("tokenid", TOKEN);
        j.put("amount", "1");
        j.put("tokenamount", "1");
        return Coin.from(j);
    }

    @Test public void aCoinInTheGraveyardIsBuried() throws Exception {
        assertTrue(StateNft.isBuried(coinAt(StateNft.GRAVEYARD)));
    }

    @Test public void anOrdinaryCoinIsNotBuried() throws Exception {
        assertFalse(StateNft.isBuried(coinAt(MINE)));
    }

    @Test public void addressMatchingIgnoresCaseAndSurroundingSpace() throws Exception {
        // The node has returned addresses in both cases across versions; a case-sensitive compare
        // would silently let buried coins back into the wallet.
        assertTrue(StateNft.isBuried(coinAt(StateNft.GRAVEYARD.toLowerCase())));
        assertTrue(StateNft.isBuried(coinAt("  " + StateNft.GRAVEYARD + "  ")));
    }

    @Test public void survivesMissingAddressAndNullCoin() throws Exception {
        assertFalse(StateNft.isBuried(null));
        JSONObject j = new JSONObject();
        j.put("coinid", "0x44");
        j.put("tokenid", TOKEN);
        j.put("amount", "1");
        assertFalse(StateNft.isBuried(Coin.from(j)));
    }

    @Test public void theGraveyardIsNotAnAddressAnyoneCouldHold() {
        // It must be a well-formed hex id, or the burial transaction itself would be malformed.
        assertTrue(Util.isValidHexId(StateNft.GRAVEYARD));
    }
}
