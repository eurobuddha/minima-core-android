package com.eurobuddha.wallet;

import org.junit.Test;
import org.minima.objects.base.MiniData;
import org.minima.objects.keys.Signature;
import org.minima.system.params.GeneralParams;
import static org.junit.Assert.*;

/** Synthetic offline fixture only; checks the upgraded host's global mode cannot change this wallet. */
public class ClassicHostCompatibilityTest {
    @Test public void blockNodeModePreservesClassicAddressAndSignatureShape() {
        boolean before = GeneralParams.USE_BLOCK_AS_KEYUSES;
        try {
            GeneralParams.USE_BLOCK_AS_KEYUSES = false;
            String phrase = "pandamonium offline compatibility fixture only";
            WalletCore classic = new WalletCore(phrase, new WalletCoreTest.InMemoryKeyUses());
            String address = classic.getReceiveAddress().getMinimaAddress();
            GeneralParams.USE_BLOCK_AS_KEYUSES = true;
            WalletCore hosted = new WalletCore(phrase, new WalletCoreTest.InMemoryKeyUses());
            assertEquals(address, hosted.getReceiveAddress().getMinimaAddress());
            assertEquals(64, hosted.deriveTreeKey(0).getSize());
            assertEquals(3, hosted.deriveTreeKey(0).getDepth());
            MiniData txid = new MiniData("0x0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20");
            Signature sig = hosted.signTransactionID(txid, 0);
            assertTrue(classic.deriveTreeKey(0).verify(txid, sig));
        } finally { GeneralParams.USE_BLOCK_AS_KEYUSES = before; }
    }
}
