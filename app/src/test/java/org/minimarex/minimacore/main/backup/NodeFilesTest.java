package org.minimarex.minimacore.main.backup;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * These are the guardrails MinimaReceiver's FILE bridge used to provide and that running
 * in-process throws away. If they regress, the node's own UI gains the ability to write outside
 * its data folder or into the live chain DB - so they are tested rather than assumed.
 */
public class NodeFilesTest {

    private File base;

    @Before public void setUp() throws IOException {
        base = File.createTempFile("nodefiles", "");
        assertTrue(base.delete());
        assertTrue(base.mkdirs());
    }

    @Test public void aPathCannotClimbOutOfTheBaseFolder() {
        for (String escape : new String[]{"../evil", "../../evil", "sub/../../evil", "/etc/passwd"}) {
            try {
                File resolved = NodeFiles.resolveInBase(base, escape);
                // An absolute path is re-rooted rather than escaping; anything that lands
                // outside must have thrown.
                assertTrue(escape + " resolved to " + resolved,
                        resolved.getCanonicalPath().startsWith(base.getCanonicalPath()));
            } catch (IOException expected) {
                assertEquals("Path outside base folder", expected.getMessage());
            }
        }
    }

    @Test public void anOrdinaryNameResolvesInsideTheBaseFolder() throws IOException {
        File resolved = NodeFiles.resolveInBase(base, "backup.bak");
        assertEquals(new File(base, "backup.bak").getCanonicalPath(), resolved.getCanonicalPath());
    }

    @Test public void theLiveDatabasesFolderCanNeverBeWritten() throws IOException {
        try {
            NodeFiles.checkWriteAllowed(base, NodeFiles.resolveInBase(base, "databases/txpow.mv.db"));
            fail("Expected the databases folder to be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("protected"));
        }
        // A file that merely starts with the same letters is not the protected folder.
        NodeFiles.checkWriteAllowed(base, NodeFiles.resolveInBase(base, "databases-old.bak"));
        NodeFiles.checkWriteAllowed(base, NodeFiles.resolveInBase(base, "backup.bak"));
    }

    @Test public void aDisplayNameCannotCarryAFolderOrBreakTheCommandTokeniser() {
        assertEquals("passwd", NodeFiles.safeName("../../etc/passwd"));
        assertEquals("evil.bak", NodeFiles.safeName("/tmp/evil.bak"));
        assertEquals("evil.bak", NodeFiles.safeName("C:\\windows\\evil.bak"));
        // Characters the node's tokeniser would rewrite or break on are neutralised, so the
        // imported file can still be named verbatim in `restore file:<name>`.
        assertEquals("my_backup.bak", NodeFiles.safeName("my backup.bak"));
        assertEquals("a_b.bak", NodeFiles.safeName("a:b.bak"));
        assertEquals("x__phrase_STOLEN", NodeFiles.safeName("x\" phrase:STOLEN"));
        assertTrue(org.minimarex.minimacore.main.ResyncJob.validFilename(
                NodeFiles.safeName("my backup.bak")));
    }

    @Test public void aLeadingDotCannotProduceAHiddenOrEmptyName() {
        assertEquals("bashrc", NodeFiles.safeName(".bashrc"));
        assertEquals("", NodeFiles.safeName("..."));
    }

    @Test public void importNeverSilentlyOverwritesAnExistingFile() throws IOException {
        assertEquals("backup.bak", NodeFiles.uniqueName(base, "backup.bak"));
        try (FileOutputStream out = new FileOutputStream(new File(base, "backup.bak"))) {
            out.write(new byte[]{1, 2, 3});
        }
        assertEquals("backup-2.bak", NodeFiles.uniqueName(base, "backup.bak"));
        try (FileOutputStream out = new FileOutputStream(new File(base, "backup-2.bak"))) {
            out.write(new byte[]{1});
        }
        assertEquals("backup-3.bak", NodeFiles.uniqueName(base, "backup.bak"));
    }

    @Test public void byteCountsAreReportedInUnitsPeopleRead() {
        assertEquals("512 B", NodeFiles.formatBytes(512));
        assertEquals("1.0 KB", NodeFiles.formatBytes(1024));
        assertEquals("1.0 MB", NodeFiles.formatBytes(1024 * 1024));
    }
}
