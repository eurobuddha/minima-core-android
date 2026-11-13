package com.eurobuddha.minimacore.main;

import static org.junit.Assert.*;

import org.junit.Test;
import org.minima.system.commands.CommandRunner;
import org.minima.utils.BIP39;

/** 13 Nov 2026: the Bears' guided seed boxes and the any-phrase option (owner: restore only, with a warning). */
public class SeedEntryTest {
    private static final String WORDS = "abandon ability able about above absent absorb abstract absurd abuse access accident "
            + "account accuse achieve acid acoustic acquire across act action actor actress actual";

    /** The node starts with -anyseed, so what it is handed is hashed as is: boxes (and "Minima's form") give Minima's
     *  own UPPERCASE form - what a node generated, the original wallet. */
    @Test public void minimasFormIsWhatANodeGenerated() {
        assertEquals(WORDS.toUpperCase(), SeedEntry.minimaForm(WORDS));
        assertEquals(BIP39.cleanSeedPhrase(WORDS), SeedEntry.minimaForm(WORDS));
    }

    /** Review H1/M5: list words typed as an any-phrase reach the node as typed when the user chooses so (old first-run
     *  wallets hashed raw; old 12-21 word Vault phrases). */
    @Test public void listWordsAsTypedAreAllowedForTheNode() {
        ResyncJob job = ResyncJob.seedResyncAny("example.org:9001", WORDS, 2000, false);
        assertNotNull(job);
        assertEquals(WORDS, CommandRunner.getRunner().getCommand(job.command()).getParams().get("phrase"));
        String twelve = String.join(" ", java.util.Arrays.copyOf(WORDS.split(" "), 12));
        assertNotNull(ResyncJob.seedResyncAny("example.org:9001", twelve, 2000, false));
        assertNotNull("old Vault lengths still restore through the boxes path", ResyncJob.seedResync("example.org:9001", twelve, 2000));
    }

    @Test public void anyPhraseRebuildCarriesAnyphraseAndReachesTheNodeUnchanged() {
        String p = "My own  Phrase (2019) = a/b \\ é, done!";
        ResyncJob job = ResyncJob.seedResyncAny("example.org:9001", "  " + p + " ", 2000, false);
        assertNotNull(job);
        assertTrue(job.command(), job.command().contains(" anyphrase:true "));
        org.minima.utils.json.JSONObject params = CommandRunner.getRunner().getCommand(job.command()).getParams();
        assertEquals("the node parses back exactly the phrase", p, params.get("phrase"));
        assertEquals("true", String.valueOf(params.get("anyphrase")));
        assertEquals("2000", String.valueOf(params.get("keyuses")));
        assertEquals("example.org:9001", String.valueOf(params.get("host")));
    }

    @Test public void anyPhraseRebuildRefusesWhatTheNodeWouldMangleOrMisread() {
        for (String bad : new String[]{"has a colon: in it here", "has a \"quote\" in it here", "two; commands in it here",
                "short", "-starts with a dash here", "an [unbalanced bracket here", "a {json} looking phrase",
                WORDS.replace("absurd", "absrud")})
            assertNull(bad, ResyncJob.seedResyncAny("example.org:9001", bad, 2000, false));
        assertNull("bad host", ResyncJob.seedResyncAny("not a host", "My own long enough phrase", 2000, false));
        assertNull("key uses floor", ResyncJob.seedResyncAny("example.org:9001", "My own long enough phrase", 0, false));
    }

    @Test public void boxesRebuildIsUnchangedAndNeverAnyphrase() {
        ResyncJob job = ResyncJob.seedResync("example.org:9001", WORDS, 2000);
        assertNotNull(job);
        assertFalse(job.command().contains("anyphrase"));
    }
}
