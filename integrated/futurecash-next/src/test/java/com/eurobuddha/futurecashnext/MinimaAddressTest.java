package com.eurobuddha.futurecashnext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Golden vectors for the default-address derivation.
 *
 * <p>Provenance: generated from {@code mds/keyuses/phrase/derive.js} — the validated JS port of
 * minima-core's derivation — and then independently confirmed against the live audit backend, which
 * derives with real minima-core Java ({@code new Address("RETURN SIGNEDBY(...)")}). Three of these
 * were checked directly:
 *
 * <pre>
 *   curl -s "https://eurobuddha.com/keyaudit?keys=0x3A985DA7…,0xFF000000…,0x00000000…"
 * </pre>
 *
 * and returned exactly the {@code address}/{@code miniaddress} pairs below.
 *
 * <p>To regenerate the whole table, see the "Regenerating address goldens" section of README.md.
 */
public class MinimaAddressTest {

    /** { publickey, expected 0x address, expected Mx address } */
    private static final String[][] GOLDENS = {
            // all-zero key: BigInteger(1, data) must not choke on the leading zeros
            { "0x0000000000000000000000000000000000000000000000000000000000000000",
              "0x3C6C5998B2CD75897F1212291FCCE6A9572E52CE77A2D13AD2BC7217CCEF4941",
              "MxG081SDHCPHCMDEM4NU4GW54FSPPY9ASN55JJNKB8JYKYSE8BSPRQ985WP0KH6" },
            // leading zero byte
            { "0x0011111111111111111111111111111111111111111111111111111111111111",
              "0x25619F02BEDE6EF76AF768BEFA0F1E286F6544F33884C4EA977FEDCC0ECCDCC3",
              "MxG0815C6FG5FMUDRRMYTR8NRT0U7H8DTWK9SPZGJ2EY5RVTN60TJ6SZF7VJ889" },
            // top bit set on the first byte: the sign-pad case in Java's BigInteger
            { "0xFF00000000000000000000000000000000000000000000000000000000000000",
              "0xBE1611D1E4C14DFAA0BE2694F853A616E320349C7FBDC0B258AFAE36C4A0A369",
              "MxG085U2Z8T3P619NTA1FH6WJS579GMSCG3973VNN0B4M5FYZRC9853D6QFNZ4H" },
            { "0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
              "0x7159569EA57B8AA753BD82AE8CF3AE8D662E1949EB203DFA39285A64E8B4B592",
              "MxG083HB5B9T9BRHAJY7FC2YQ6F7BKDCZN1WWFB40UVKE98B9WEHD5YWABB0N4C" },
            { "0x0000000000000000000000000000000000000000000000000000000000000001",
              "0xEE022AAB7CDD0C82E6308421599C2FCFDEAAF9001DD4036E6407F7147C6FD57F",
              "MxG087E08YAMV6T1W1ECC4445CPZBUFRQYFW00TQG1MSP07USA7ZRUYFSE4U2TN" },
            { "0x8000000000000000000000000000000000000000000000000000000000000000",
              "0x98735D7DD62D70D29BB260EED90720B42E0D59C8CB9ECC0F96C679D884ACCA86",
              "MxG084ZEDENRYHDE399NCJ0TRCGE85K5Z6YJW6BJR60V5M6F7C89B6AGQYVDG26" },
            // confirmed against the live backend
            { "0x3A985DA74FE225B2045C172D6BD390BD855F086E3E9D525B46BFE24511431532",
              "0x39EED202FFBE82E953D234F9CDAFC5C94AEC64875760130C8A596403906B1D8B",
              "MxG081PTR905VTUGBKY7KHKV76QVHE99BM691QNC09GP2WPCG1P0QZTHD8SWWVQ" },
            { "0xA7FFC6F8BF1ED76651C14756A061D662F580FF4DE43B49FA82D80A4B80F8434A",
              "0x9782740B0A9E901CDE3901190D4A8C2666EA23768137E5195D5A8D98F86C903A",
              "MxG084NG9Q0M2KUW0EDSE81346KY316CRY26TK16VWHWNAQHMCFGR4G79N85V60" },
            { "0x1C0FFEE0BADC0DE0DEADBEEF0123456789ABCDEFFEDCBA98765432100FACADE1",
              "0x7C4DB156CF15A876F7E7920D5CDE1D7E84D1DFECEDF48539E1B16F264090B596",
              "MxG083S9MZYDJZYY1RFFPSW1YEDS7BUGJ8TVR7DUW2JJZDHDSJ4145YWQW6S5HT" },
            { "0x0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20",
              "0xCED4685B81BED336ECF4077968CCD4E877DCFB732FA2F5D3F6B742F661999529",
              "MxG086EQHK5N0DUQCREPT07F5KCPY78EVEFMSPFKBQT7TYN8BR636CY54M5JBYU" },
    };

    @Test
    public void goldens() {
        for (String[] g : GOLDENS) {
            final MinimaAddress.Result r = MinimaAddress.fromPublicKey(g[0]);
            assertNotNull("failed to derive for " + g[0], r);
            assertEquals("0x address for " + g[0], g[1], r.hex);
            assertEquals("Mx address for " + g[0], g[2], r.mx);
        }
    }

    @Test
    public void scriptShapeMatchesMinimaCore() {
        final MinimaAddress.Result r = MinimaAddress.fromPublicKey(
                "0x1c0ffee0badc0de0deadbeef0123456789abcdeffedcba98765432100facade1");
        assertNotNull(r);
        // minima-core builds the script from the uppercase 0x form
        assertEquals("RETURN SIGNEDBY(0x1C0FFEE0BADC0DE0DEADBEEF0123456789ABCDEFFEDCBA98765432100FACADE1)",
                r.script);
    }

    @Test
    public void lowercaseInputGivesTheSameAddress() {
        // keys action:list returns uppercase, but never let case change the derived address
        final MinimaAddress.Result up = MinimaAddress.fromPublicKey(GOLDENS[6][0]);
        final MinimaAddress.Result lo = MinimaAddress.fromPublicKey(GOLDENS[6][0].toLowerCase());
        assertNotNull(up);
        assertNotNull(lo);
        assertEquals(up.hex, lo.hex);
        assertEquals(up.mx, lo.mx);
    }

    @Test
    public void everyGoldenIsDistinct() {
        // guards against a derivation that collapses to a constant (which would read as "0 uses")
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] g : GOLDENS) seen.add(MinimaAddress.fromPublicKey(g[0]).hex);
        assertEquals(GOLDENS.length, seen.size());
    }

    /**
     * The effective Mx alphabet: base-32 (0-9 a-v) with i->w, l->y, o->z applied, uppercased.
     * Exactly 32 characters, and deliberately free of I/L/O (which read as 1/0) — and of X, which
     * the remap can never produce.
     */
    private static final String MX_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTUVWYZ";

    @Test
    public void mxAddressesAreWellFormed() {
        assertEquals(32, MX_ALPHABET.length());
        for (String[] g : GOLDENS) {
            final String mx = MinimaAddress.fromPublicKey(g[0]).mx;
            assertTrue(mx.startsWith("Mx"));
            for (int i = 2; i < mx.length(); i++) {
                final char c = mx.charAt(i);
                assertTrue("bad Mx char '" + c + "' in " + mx, MX_ALPHABET.indexOf(c) >= 0);
            }
        }
    }

    /**
     * A default-address script hashes any string, so a malformed key would derive a plausible but
     * bogus address — which the backend reports as "0 uses", i.e. it reads as SAFE. Rejecting bad
     * shapes up front is the only safe behaviour. Mirrors KeyUsesServer.java:47,169.
     */
    @Test
    public void malformedKeysAreRejectedRatherThanDerived() {
        assertNull(MinimaAddress.fromPublicKey(null));
        assertNull(MinimaAddress.fromPublicKey(""));
        assertNull(MinimaAddress.fromPublicKey("0x"));
        assertNull(MinimaAddress.fromPublicKey("not a key"));
        assertNull("missing 0x prefix", MinimaAddress.fromPublicKey(
                "3A985DA74FE225B2045C172D6BD390BD855F086E3E9D525B46BFE24511431532"));
        assertNull("too short", MinimaAddress.fromPublicKey(
                "0x3A985DA74FE225B2045C172D6BD390BD855F086E3E9D525B46BFE245114315"));
        assertNull("too long", MinimaAddress.fromPublicKey(
                "0x3A985DA74FE225B2045C172D6BD390BD855F086E3E9D525B46BFE2451143153200"));
        assertNull("non-hex", MinimaAddress.fromPublicKey(
                "0xZZ985DA74FE225B2045C172D6BD390BD855F086E3E9D525B46BFE24511431532"));
        assertFalse(MinimaAddress.isValidPublicKey("0x 985DA74FE225B2045C172D6BD390BD855F086E3E9D525B46BFE24511431532"));
    }

    @Test
    public void serializationPrimitives() {
        // MiniData: 4-byte big-endian length prefix
        final byte[] d = MinimaAddress.serMiniData(new byte[]{0x0A, 0x0B});
        assertEquals("000000020a0b", Sha3.hex(d));
        // MiniNumber(0): scale 0, unscaled length 1, unscaled byte 0
        assertEquals("000100", Sha3.hex(MinimaAddress.serMiniNumberZero()));
    }
}
