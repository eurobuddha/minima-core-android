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
}
