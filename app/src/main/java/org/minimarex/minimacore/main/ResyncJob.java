package org.minimarex.minimacore.main;

import org.minima.system.commands.network.connect;
import org.minima.utils.messages.Message;

/**
 * One destructive node job: the command to run, and the words to describe it while it runs.
 *
 * WHY THIS TYPE EXISTS: {@link ResyncSession} used to hard-code
 * "megammrsync action:resync host:" + host. Restore, MegaMMR-from-file and archive reset are
 * equally destructive and equally restart the node, so they must share that session's lifecycle
 * (see ResyncLauncher) rather than grow their own. A job is the only thing that varies.
 *
 * WHY THE VALIDATION IS SO STRICT: the node has NO escape character. CommandRunner tokenises
 * with a bare quote-toggle, so a '"' inside a value ends the quote and everything after it
 * becomes new parameters - a password is enough to inject "phrase:" or "confirm:". Worse, the
 * fast tokeniser (splitterQuotedPattern) runs replaceAll(":", " : ") BEFORE it looks at quotes,
 * so a password of a:b arrives at the command as "a : b" - silently different from what the user
 * typed, and unusable from the terminal or any other client. Values are therefore restricted to
 * characters that survive both tokenisers byte-for-byte. This is not over-caution: it is the
 * rule the core itself documents in backup.java - "Set a password using letters and numbers only."
 */
public final class ResyncJob {

    public enum Kind { HOST_RESYNC, FILE_RESTORE, FILE_RESYNC, SEED_RESYNC }

    /** Letters and numbers only - the core's own documented password rule (backup.java help). */
    private static final String PASSWORD_RULE = "[A-Za-z0-9]{1,128}";

    /** A plain base-folder filename. No separators, no quotes, no spaces, no colons. */
    private static final String FILENAME_RULE = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    /**
     * A BIP39 seed phrase: words and single spaces, nothing else.
     *
     * Spaces are the one whitespace that CAN be carried safely, because the value is quoted and
     * both tokenisers keep a quoted run intact. A ':' or '"' cannot - see the class comment.
     */
    private static final String PHRASE_RULE = "[A-Za-z]+( [A-Za-z]+){11,23}";

    private final Kind kind;
    private final String command;
    private final String host;
    private final String label;
    private final String startLine;

    private ResyncJob(Kind kind, String command, String host, String label, String startLine) {
        this.kind = kind;
        this.command = command;
        this.host = host;
        this.label = label;
        this.startLine = startLine;
    }

    // ---- validation ----

    /** Reject command separators/extra parameters, then reuse the node's host parser. */
    public static boolean validHost(String host) {
        if (host == null || !host.matches("[A-Za-z0-9.-]+:[0-9]{1,5}")) return false;
        Message parsed = connect.createConnectMessage(host);
        return parsed != null && parsed.getInteger("port") > 0 && parsed.getInteger("port") <= 65535;
    }

    /**
     * Letters and numbers only. Anything else either breaks out of the quoting (") or is
     * silently rewritten by the tokeniser (: and whitespace) - see the class comment.
     */
    public static boolean validPassword(String password) {
        return password != null && password.matches(PASSWORD_RULE);
    }

    /**
     * A bare filename in the node's base folder. Rejects every path separator and "..", so a
     * job can never be pointed outside the folder the node actually reads from.
     */
    public static boolean validFilename(String filename) {
        return filename != null && filename.matches(FILENAME_RULE) && !filename.contains("..");
    }

    /** 12 to 24 words, letters and single spaces. Rejects anything the tokeniser would rewrite. */
    public static boolean validPhrase(String phrase) {
        return phrase != null && phrase.matches(PHRASE_RULE);
    }

    /** Collapse the whitespace a user pastes, so a tidy phrase is not rejected for formatting. */
    public static String tidyPhrase(String phrase) {
        return phrase == null ? "" : phrase.trim().replaceAll("\\s+", " ");
    }

    // ---- factories ----

    /**
     * Chain resync from a MegaMMR host. The wallet and seed are untouched.
     * Command string is byte-identical to the one ResyncSession used before this type existed.
     */
    public static ResyncJob hostResync(String host) {
        if (!validHost(host)) return null;
        return new ResyncJob(Kind.HOST_RESYNC,
                "megammrsync action:resync host:" + host,
                host,
                "Resync",
                "Starting node resync from " + host);
    }

    /** Pure local restore from a backup file already in the node's base folder. No network. */
    public static ResyncJob fileRestore(String filename, String password) {
        if (!validFilename(filename) || !validPassword(password)) return null;
        return new ResyncJob(Kind.FILE_RESTORE,
                "restore file:\"" + filename + "\" password:\"" + password + "\"",
                "",
                "Restore",
                "Restoring from " + filename);
    }

    /** Restore a backup file AND resync to the chain tip from a MegaMMR host. */
    public static ResyncJob fileResync(String host, String filename, String password) {
        if (!validHost(host) || !validFilename(filename) || !validPassword(password)) return null;
        return new ResyncJob(Kind.FILE_RESYNC,
                "megammrsync action:resync host:" + host
                        + " file:\"" + filename + "\" password:\"" + password + "\"",
                host,
                "Restore",
                "Restoring from " + filename + " and resyncing from " + host);
    }

    /**
     * Rebuild this wallet from a seed phrase and resync from a MegaMMR host.
     *
     * keyuses matters and is not cosmetic: Minima signatures are stateful, so every seed resync
     * must declare a higher used-key count than the last one or previously used keys can be
     * reused - which is how a wallet loses funds. The node defaults it to 1000, max 262144.
     */
    public static ResyncJob seedResync(String host, String phrase, int keyUses) {
        String tidy = tidyPhrase(phrase);
        if (!validHost(host) || !validPhrase(tidy) || keyUses < 0 || keyUses > 262144) return null;
        return new ResyncJob(Kind.SEED_RESYNC,
                "megammrsync action:resync host:" + host
                        + " phrase:\"" + tidy + "\" keyuses:" + keyUses,
                host,
                "Seed restore",
                "Rebuilding this wallet from your seed phrase, resyncing from " + host);
    }

    // ---- accessors ----

    public Kind kind() { return kind; }

    /** The exact string handed to the node. Never logged - it carries the backup password. */
    public String command() { return command; }

    /** "" when this job has no host, so callers can always ask without a null check. */
    public String host() { return host; }

    /** "Resync" / "Restore" - used to build progress and failure text for THIS operation. */
    public String label() { return label; }

    /** First line of the live log. Safe to show: it never contains the password. */
    public String startLine() { return startLine; }

    /** True when finishing this job should be remembered as the node's default peer. */
    public boolean setsDefaultPeer() { return !host.isEmpty(); }
}
