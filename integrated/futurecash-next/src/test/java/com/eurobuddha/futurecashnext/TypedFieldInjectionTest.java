package com.eurobuddha.futurecashnext;

import static org.junit.Assert.*;

import org.junit.Test;

/** Future Cash has no free typed text: the only typed fields are an amount and addresses, and each is held to an
 *  exact shape at the field, so none of { } [ ] " \ ; or a control character can reach a command. (Its finished
 *  commands legitimately carry state JSON and scripts, so the brackets can not be refused at CommandSafety.) */
public class TypedFieldInjectionTest {
    static final String MX = "Mx" + "A".repeat(60), HEX = "0x" + "ab".repeat(32);
    static final String[] TAILS = {"] amount:1", "[", "{", "}", "\"", "\\", ";send amount:1", "\n", "\t", " address:" + HEX, "\u0000", "\u007f"};

    @Test public void aTypedAddressIsAnExactShape() {
        assertTrue(Node.validMx(MX));
        assertTrue(Node.validHexAddr(HEX));
        for (String tail : TAILS) {
            for (String typed : new String[]{MX + tail, tail + MX, MX.substring(0, 30) + tail + MX.substring(30),
                    HEX + tail, tail + HEX, HEX.substring(0, 30) + tail + HEX.substring(30)}) {
                assertFalse(typed, Node.validMx(typed));
                assertFalse(typed, Node.validHexAddr(typed));
                assertFalse(typed, Node.validAddr(typed));
            }
        }
    }

    @Test public void aTypedAmountIsAPlainDecimalBeforeAnythingIsRead() {
        // The amount is checked first, before the wallet or the node is touched: a null guardian state is never reached.
        Guardian g = org.mockito.Mockito.mock(Guardian.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        for (String tail : TAILS)
        {   // in the middle: createFutureCash trims the ends, and what is left is still a plain decimal or refused
            String typed = "1" + tail + "0";
            Wallet.Res r = g.createFutureCash(typed, "0x00", 10, HEX);
            assertFalse(typed, r.ok);
            assertEquals(typed, "Enter a positive amount.", r.error);
        }
    }
}
