package com.eurobuddha.minimacore.main;

import org.junit.Test;
import org.minima.system.params.GeneralParams;
import org.minima.system.params.ParamConfigurer;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class StartupModeTest {
    @Test public void onlyPandamoniumAllowsAnOverrideAndDefaultsToBlockMode() {
        assertTrue(StartupMode.usesBlockKeys(true, true, false));
        assertFalse(StartupMode.usesBlockKeys(true, true, true));
        assertTrue(StartupMode.usesBlockKeys(false, true, true));
        assertTrue(StartupMode.usesBlockKeys(false, true, false));
        assertFalse(StartupMode.usesBlockKeys(false, false, true));
        assertFalse(StartupMode.usesBlockKeys(false, false, false));
    }

    @Test public void chatTabIsTheOnlyOptionalSlot() {
        assertEquals(3, StartupMode.chatPosition(true, true));
        assertEquals(4, StartupMode.chatPosition(false, true));
        assertEquals(-1, StartupMode.chatPosition(true, false));
        assertEquals(-1, StartupMode.chatPosition(false, false));
        assertEquals(4, StartupMode.homePosition(true, true));
        assertEquals(3, StartupMode.homePosition(true, false));   // Node slides into Chat's slot
        assertEquals(0, StartupMode.homePosition(false, true));
        assertEquals(0, StartupMode.homePosition(false, false));
    }

    @Test public void keetsSwitchStripsOnlyItsOwnFlagsWholeToken() {
        assertEquals("-keetsbootstrap h:49737  -keetsmaxconns 8 -port 25001",
                StartupMode.withoutKeetsSwitchFlags("-keetsbootstrap h:49737 -keets -keetsmaxconns 8 -port 25001"));
        assertEquals(" /p  -megammr", StartupMode.withoutKeetsSwitchFlags("-keetssidecar /p -keetssidecarargs -megammr"));
        assertEquals("", StartupMode.withoutKeetsSwitchFlags("-keets"));
        assertEquals("-keetsconnect x", StartupMode.withoutKeetsSwitchFlags("-keetsconnect x"));
    }

    @Test public void nodeParserAppliesBothProfilesAcrossRepeatedRestarts() {
        try {
            for (boolean block : new boolean[]{true, false, true, false}) {
                // This is the reset Minima.main performs on each Android service restart.
                GeneralParams.resetDefaults();
                ArrayList<String> args = new ArrayList<>();
                StartupMode.addArguments(args, block);
                assertEquals(block ? 2 : 0, args.size());
                assertTrue(ParamConfigurer.checkParams(args.toArray(new String[0])));
                assertEquals(block, GeneralParams.USE_BLOCK_AS_KEYUSES);
                assertEquals(block, GeneralParams.USE_SQL_COINDB);
                assertEquals(block, GeneralParams.USE_SQL_TXBLOCKDB);
            }
        } finally { GeneralParams.resetDefaults(); }
    }

    @Test public void oldOverridesAndOptionalValuesCannotDefeatEitherProfile() {
        String extra = "-lowram true -p2pnodes node.example:9001 -blockaskeyuses false "
                + "-sqlcoindb -sqltxblockdb true -limitbandwidth -lowram";
        assertEquals("-p2pnodes node.example:9001 -limitbandwidth", StartupMode.withoutManagedArguments(extra));
        assertEquals("", StartupMode.withoutManagedArguments(" \n -lowram\tfalse  "));
        assertEquals("", StartupMode.withoutManagedArguments(""));
        assertEquals("-p2pnodes lowram.example:9001", StartupMode.withoutManagedArguments("-p2pnodes lowram.example:9001"));
    }

    /** 2.2.75: the RPC switch opens the RPC to the phone's network (the node binds 127.0.0.1 unless -rpcbind),
     *  always with Basic auth; typed RPC flags are stripped so only the switch sets them. */
    @Test public void rpcSwitchBindsEveryInterfaceWithItsPassword() {
        try {
            GeneralParams.resetDefaults();
            assertEquals("127.0.0.1", GeneralParams.RPC_BIND_HOST);   // the node's default: loopback only
            ArrayList<String> args = new ArrayList<>();
            StartupMode.addRpcArguments(args, "0123abcd");
            assertEquals(java.util.Arrays.asList("-rpcenable", "-rpcpassword", "0123abcd", "-rpcbind", "all"), args);
            assertTrue(ParamConfigurer.checkParams(args.toArray(new String[0])));
            assertTrue(GeneralParams.RPC_ENABLED);
            assertEquals("0123abcd", GeneralParams.RPC_PASSWORD);
            assertEquals("", GeneralParams.RPC_BIND_HOST);              // "" = every interface
        } finally { GeneralParams.resetDefaults(); }
    }

    @Test public void typedRpcFlagsAreStrippedWithTheirValues() {
        assertEquals("-p2pnodes node.example:9001 -limitbandwidth", StartupMode.withoutRpcSwitchFlags(
                "-rpcenable -rpcpassword secret -p2pnodes node.example:9001 -rpcbind all -rpcallowhost a.example "
                + "-rpcalloworigin http://x.example -limitbandwidth -rpcenable true"));
        assertEquals("", StartupMode.withoutRpcSwitchFlags(" -rpcbind\t0.0.0.0 "));
        assertEquals("-rpcdummy x", StartupMode.withoutRpcSwitchFlags("-rpcdummy x"));
        for (String f : new String[]{"-rpcenable", "-rpcpassword", "-rpcbind", "-rpcallowhost", "-rpcalloworigin"})
            assertTrue(f, StartupMode.isRpcSwitchFlag(f));
        assertFalse(StartupMode.isRpcSwitchFlag("-rpc"));
    }
}
