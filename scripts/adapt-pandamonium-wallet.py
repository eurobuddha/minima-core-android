"""Import minimaWallet against the host node classes; preserve its classic derivation."""
from pathlib import Path
import shutil

def adapt(root):
    base = root/'wallet'
    java = base/'src/main/java/com/eurobuddha/wallet'
    def change(path, old, new):
        text = path.read_text()
        assert text.count(old) == 1, ('Wallet adaptation drift', path, old)
        path.write_text(text.replace(old, new, 1))

    # The donor's 47 node classes duplicate the host jar. Thirty-nine are identical;
    # the eight differences are standalone shims/older node helpers. Use the full
    # existing host implementation, with the classic shape explicit below.
    shutil.rmtree(base/'src/main/java/org/minima')
    change(base/'build.gradle', '    buildFeatures { buildConfig true }',
           '    buildFeatures { buildConfig true }\n    testOptions { unitTests.returnDefaultValues = true }')
    change(base/'build.gradle', 'dependencies {', """dependencies {
    compileOnly files('../../app/libs/minima.jar')
    testImplementation files('../../app/libs/minima.jar')
    testImplementation 'com.h2database:h2:2.1.214'
    testImplementation 'org.mockito:mockito-core:5.12.0'""")
    change(java/'WalletCore.java', 'return TreeKey.createDefault(derivePrivateSeed(zKeyIndex));',
           'return new TreeKey(derivePrivateSeed(zKeyIndex), 64, 3);')
    change(java/'WalletCore.java', 'if (leaf >= treekey.getMaxUses()) {',
           'if (leaf < 0 || leaf >= treekey.getMaxUses()) {')
    # Imported only; never change standalone prefs or seed data.
    for source in (Path(__file__).with_name('pandamonium-sources')/'wallet').glob('*.java'):
        shutil.copy2(source, java/source.name)
    # Same main-handler boundary already used by bundled Mail and Future Cash.
    change(java/'comms/NodeApi.java', '    public void cmd(String command, Cb cb) {', """    public void cmd(String command, Cb cb) {
        mMain.post(() -> dispatch(command, cb));
    }

    private void dispatch(String command, Cb cb) {""")
    change(java/'comms/NodeApi.java', '                    if (cb != null) cb.onResult(zResponse);', """                    if (!(zResponse.opt("status") instanceof Boolean)) {
                        if (cb != null) cb.onError(zResponse.optString("transporterror", "Node result unavailable. Outcome unknown."));
                        return;
                    }
                    if (cb != null) cb.onResult(zResponse);""")

    adapt_seed_counters(java)
    adapt_backup_validation(java)
    adapt_passphrase_verification(java)
    adapt_unlock_errors(java)
    adapt_setup_and_sessions(java)
    adapt_background_setup(java)


def adapt_seed_counters(java):
    """Bind every vault generation to its seed's counters before publishing it to callers."""
    def change(path, old, new, count=1):
        text = path.read_text()
        assert text.count(old) == count, ('Wallet seed adaptation drift', path, old)
        path.write_text(text.replace(old, new))
    p = java/'SeedVault.java'
    change(p, '    private final KeyUses mKeyUses;', """    interface CounterFactory { KeyUses forPhrase(String phrase); }
    private final CounterFactory mCounterFactory;
    private KeyUses mKeyUses;
    private long mGeneration;

    public SeedVault(Context context) {
        this(new PrefsBlobStore(context), phrase -> new NodeKeyUses(phrase, new PrefsKeyUses(context,
                "classic_" + org.minima.utils.Crypto.getInstance().hashObject(
                        org.minima.utils.BIP39.convertStringToSeed(phrase)).to0xString().substring(2))));
    }""")
    change(p, '        mStore   = zStore;\n        mKeyUses = zKeyUses;', """        this(zStore, phrase -> zKeyUses);
    }

    SeedVault(BlobStore store, CounterFactory factory) {
        mStore = store;
        mCounterFactory = factory;""")
    change(p, '    public void lock() {', '    public void lock() {\n        mGeneration++;\n        mKeyUses = null;')
    for method, trusted in [('createNew', 'true'), ('importSeed', 'zBrandNew')]:
        sig = 'String zPhrase, String zPassphrase' + (', boolean zBrandNew' if method == 'importSeed' else '')
        old = '    public void ' + method + '(' + sig + ') {'
        change(p, old, old + '\n        KeyUses candidate = mCounterFactory.forPhrase(zPhrase);')
        change(p, 'new VaultBlob(zPhrase, ' + trusted + ', currentSnapshot());',
               'new VaultBlob(zPhrase, ' + trusted + ', snapshot(candidate));', 2 if method == 'createNew' else 1)
        # createNew and numeric import both have the same true openState call.
    change(p, '        // Raise the live counter for key 0 to the attested count (MAX-on-write: never lowers).\n        mKeyUses.recordExternalUses(0, zIndex0Uses);', """        KeyUses candidate = mCounterFactory.forPhrase(zPhrase);
        raiseClassicFloor(candidate, zIndex0Uses);""")
    change(p, 'openState(zPhrase, zPassphrase, true);', 'openState(zPhrase, zPassphrase, true, candidate);', 2)
    change(p, 'openState(zPhrase, zPassphrase, zBrandNew);', 'openState(zPhrase, zPassphrase, zBrandNew, candidate);')
    change(p, '            blob.reconcileInto(mKeyUses);\n            openState(blob.getPhrase(), zPassphrase, blob.isKeyUsesTrusted());', """            KeyUses candidate = mCounterFactory.forPhrase(blob.getPhrase());
            blob.reconcileInto(candidate);
            openState(blob.getPhrase(), zPassphrase, blob.isKeyUsesTrusted(), candidate);""")
    change(p, '        VaultBlob blob = reconcileRestored(restored, mKeyUses);',
           '        KeyUses candidate = mCounterFactory.forPhrase(restored.getPhrase());\n        VaultBlob blob = reconcileRestored(restored, candidate);')
    change(p, 'openState(restored.getPhrase(), zNewWalletPassphrase, false);',
           'openState(restored.getPhrase(), zNewWalletPassphrase, false, candidate);')
    change(p, '        mTrusted = true;\n        syncKeyUses();   // persist trusted=true with the current snapshot', '        trustAndPersist();')
    change(p, '        mTrusted = true;\n        syncKeyUses();\n    }', '        trustAndPersist();\n    }')
    change(p, '        mKeyUses.recordExternalUses(0, zIndex0Uses);   // MAX-safe raise\n        mTrusted = true;\n        syncKeyUses();   // persist trusted=true with the raised snapshot',
           '        raiseClassicFloor(mKeyUses, zIndex0Uses);\n        trustAndPersist();')
    change(p, '    private LinkedHashMap<Integer, Integer> currentSnapshot() {\n        LinkedHashMap<Integer, Integer> uses = new LinkedHashMap<>(mKeyUses.snapshotAllUses());\n        uses.put(0, mKeyUses.currentUses(0));', """    private LinkedHashMap<Integer, Integer> currentSnapshot() {
        requireOpen();
        return snapshot(mKeyUses);
    }

    private static LinkedHashMap<Integer, Integer> snapshot(KeyUses counter) {
        LinkedHashMap<Integer, Integer> uses = new LinkedHashMap<>(counter.snapshotAllUses());
        uses.put(0, counter.currentUses(0));""")
    change(p, '    private void openState(String zPhrase, String zPassphrase, boolean zTrusted) {', """    private void openState(String zPhrase, String zPassphrase, boolean zTrusted, KeyUses counter) {
        mGeneration++;
        mKeyUses = counter;""")
    change(p, '    private void requireOpen() {', """    private static void raiseClassicFloor(KeyUses counter, int floor) {
        if (floor < 0) throw new IllegalArgumentException("Negative key uses");
        // One creation/restoration floor applies to the whole classic base-key set.
        for (int i = 0; i < WalletCore.NUM_BASE_KEYS; i++) counter.recordExternalUses(i, floor);
    }

    private void trustAndPersist() {
        persist(new VaultBlob(mPhrase, true, currentSnapshot()), mPassphrase);
        mTrusted = true; // failed persistence must never enable signing in memory
    }

    /** A handle for this unlocked generation. Lock/reopen/seed changes revoke old signers. */
    public synchronized KeyUses keyUses() {
        requireOpen();
        final long generation = mGeneration;
        final KeyUses counter = mKeyUses;
        return new KeyUses() {
            private void check() {
                requireOpen();
                if (generation != mGeneration) throw new SigningNotAllowedException("Wallet session changed");
            }
            @Override public int currentUses(int index) {
                synchronized (SeedVault.this) { check(); return counter.currentUses(index); }
            }
            @Override public int reserveNextUse(int index) {
                synchronized (SeedVault.this) {
                    check(); assertSigningAllowed(); return counter.reserveNextUse(index);
                }
            }
            @Override public void recordExternalUses(int index, int uses) {
                synchronized (SeedVault.this) { check(); counter.recordExternalUses(index, uses); }
            }
            @Override public Map<Integer,Integer> snapshotAllUses() {
                synchronized (SeedVault.this) { check(); return counter.snapshotAllUses(); }
            }
        };
    }

    private void requireOpen() {""")
    # SharedPreferences commit(false) can already have changed its in-memory value.
    # On an uncertain blob write, revoke the old session; reopen from stored evidence.
    change(p, '            mStore.write(hex);\n        } catch (IllegalStateException e) {',
           '            mStore.write(hex);\n        } catch (IllegalStateException e) {\n            lock();')
    change(p, '        mStore.write(hex);\n\n        // Session now uses',
           '        try { mStore.write(hex); } catch (RuntimeException failure) { lock(); throw failure; }\n\n        // Session now uses')
    # The handle and all state transitions use the same vault monitor.
    import re
    text = p.read_text()
    methods = 'exists|isOpen|phrase|isKeyUsesTrusted|lock|createNew|importSeed|open|assertSigningAllowed|syncKeyUses|confirmBrandNewSeed|attestKeyUsesUpToDate|setStartingKeyUses|changePassphrase|exportBytes|importVault'
    text = re.sub(r'public (boolean|String|void|byte\[\]) (' + methods + r')\(', r'public synchronized \1 \2(', text)
    text = text.replace('index 0 (this wallet only ever signs with index 0, so a single count fully describes its signing\n     * state)', 'indices 0..63 (a shared lower bound for all classic base keys)')
    text = text.replace('Also resets the live counter reference to 0 (it is a new device+seed).',
                        'Previously recorded counters for this seed are preserved.')
    text = text.replace('the number of one-time signatures key index 0 has already made',
                        'a lower bound on signatures used by each of the 64 classic base keys')
    p.write_text(text)
    p = java/'WalletSession.java'
    change(p, '    private final PrefsKeyUses mKeyUses;\n', '')
    change(p, '        mKeyUses = new PrefsKeyUses(zApp);\n        mVault   = new SeedVault(zApp, mKeyUses);', '        mVault   = new SeedVault(zApp);')
    change(p, 'public PrefsKeyUses keyUses() { return mKeyUses; }', 'public KeyUses keyUses() { return mVault.keyUses(); }')
    p = java/'MainActivity.java'
    change(p, '    private PrefsKeyUses mKeyUses;', '    private KeyUses mKeyUses;')
    change(p, '        mKeyUses = mSession.keyUses();\n', '')
    change(p, '        mWallet  = new WalletCore(mVault.phrase(), mKeyUses);',
           '        mKeyUses = mSession.keyUses();\n        mWallet  = new WalletCore(mVault.phrase(), mKeyUses);')
    # A previous seed's derive worker must never overwrite the new seed's cache.
    p = java/'AddressBook.java'
    change(p, 'mPrefs  = zContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);',
           'mPrefs  = zContext.getSharedPreferences("pandamonium_wallet_" + PREFS + "_" + fingerprint(), Context.MODE_PRIVATE);')


def adapt_backup_validation(java):
    """Keep the v1 backup format; validate all data before counter reconciliation."""
    def change(path, old, new):
        text = path.read_text()
        assert text.count(old) == 1, ('Wallet backup adaptation drift', path, old)
        path.write_text(text.replace(old, new, 1))
    p = java/'VaultBlob.java'
    change(p, '        mPhrase         = zPhrase;', """        if (zPhrase == null || zPhrase.trim().isEmpty() || zKeyUses == null)
            throw new IllegalArgumentException("Missing vault phrase or key uses");
        mPhrase         = zPhrase;""")
    change(p, """                if (e.getKey() != null && e.getValue() != null) {
                    mKeyUses.put(e.getKey(), e.getValue());
                }""", """                if (e.getKey() == null || e.getValue() == null || e.getKey() < 0 || e.getValue() < 0)
                    throw new IllegalArgumentException("Invalid vault key uses");
                mKeyUses.put(e.getKey(), e.getValue());""")
    change(p, """            String json = new String(zPlain, StandardCharsets.UTF_8);
            JSONObject root = (JSONObject) new JSONParser().parse(json);

            String phrase = String.valueOf(root.get("phrase"));
            boolean trusted = asBool(root.get("trusted"), false);""", """            if (zPlain == null || zPlain.length > VaultCrypto.MAX_PLAIN_BYTES)
                throw new IllegalArgumentException("Vault payload too large or missing");
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(zPlain)).toString();
            // Reuse the bundled parser's container hook. Never let Map.put silently
            // overwrite a duplicate counter, trust flag or recovery phrase.
            JSONObject root = (JSONObject) new JSONParser().parse(json,
                    new org.minima.utils.json.parser.ContainerFactory() {
                private int objects;
                @Override public java.util.Map createObjectContainer() {
                    if (++objects > 2) throw new IllegalArgumentException("Unexpected vault object");
                    return new JSONObject() {
                        @Override public Object put(Object key, Object value) {
                            if (containsKey(key)) throw new IllegalArgumentException("Duplicate vault field");
                            return super.put(key, value);
                        }
                    };
                }
                @Override public java.util.List creatArrayContainer() {
                    throw new IllegalArgumentException("Unexpected vault array");
                }
            });
            if (root == null || root.size() != 4 || !(root.get("v") instanceof Long)
                    || ((Long) root.get("v")) != VERSION || !(root.get("phrase") instanceof String)
                    || !(root.get("trusted") instanceof Boolean) || !(root.get("keyuses") instanceof JSONObject))
                throw new IllegalArgumentException("Unsupported or malformed vault schema");
            String phrase = (String) root.get("phrase");
            boolean trusted = (Boolean) root.get("trusted");""")
    change(p, """                    int idx = Integer.parseInt(String.valueOf(k));
                    int val = asInt(um.get(k), 0);""", """                    String index = String.valueOf(k);
                    if (!index.matches("0|[1-9][0-9]*"))
                        throw new IllegalArgumentException("Invalid vault key index");
                    int idx = Integer.parseInt(index);
                    Object count = um.get(k);
                    if (!(count instanceof Long) || (Long) count < 0 || (Long) count > Integer.MAX_VALUE)
                        throw new IllegalArgumentException("Invalid vault key count");
                    int val = ((Long) count).intValue();""")
    text = p.read_text()
    start = text.index('    private static boolean asBool(')
    p.write_text(text[:start] + '}\n')

    p = java/'VaultCrypto.java'
    change(p, '    private static final int SALT_LEN', """    // Same 4 MiB input budget used by the bundled PandaDEX transport reader.
    public static final int MAX_CONTAINER_BYTES = 4 * 1024 * 1024;
    public static final int MAX_PLAIN_BYTES = MAX_CONTAINER_BYTES - 56; // v1 header + GCM tag
    public static final int MAX_ITERATIONS = 1_000_000;

    private static final int SALT_LEN""")
    change(p, '        byte[] salt  = randomBytes(SALT_LEN);', """        if (zPlain == null || zPlain.length > MAX_PLAIN_BYTES)
            throw new IllegalArgumentException("Vault payload too large or missing");
        if (zIterations < 1 || zIterations > MAX_ITERATIONS)
            throw new IllegalArgumentException("Unsupported iteration count");
        byte[] salt  = randomBytes(SALT_LEN);""")
    change(p, 'zContainer.length < MAGIC.length + 1 + 1 + 4 + 1 + SALT_LEN + 1 + NONCE_LEN',
           'zContainer.length > MAX_CONTAINER_BYTES || zContainer.length < 56')
    change(p, 'if (iterations < 1)', 'if (iterations < 1 || iterations > MAX_ITERATIONS)')
    change(p, 'if (saltLen < 1 || p + saltLen > zContainer.length)',
           'if (saltLen != SALT_LEN || p + saltLen + 1 + NONCE_LEN + TAG_BITS / 8 > zContainer.length)')
    change(p, 'if (nonceLen < 1 || p + nonceLen > zContainer.length)',
           'if (nonceLen != NONCE_LEN || p + nonceLen + TAG_BITS / 8 > zContainer.length)')
    # Bounded input loop reused from PandaDEX MinimaAPIResponse.read.
    change(p, '    /** Encrypt {@code zPlain}', """    public static byte[] readContainer(java.io.InputStream in) throws java.io.IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n, count = 0;
            while ((n = in.read(buffer)) != -1) {
                count += n;
                if (count > MAX_CONTAINER_BYTES) throw new java.io.IOException("Vault file too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Encrypt {@code zPlain}""")
    p = java/'MainActivity.java'
    change(p, """            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();""", '            return VaultCrypto.readContainer(is);')
    p = java/'SeedVault.java'
    change(p, '            byte[] container = new MiniData(hex).getBytes();', """            if (hex.length() > VaultCrypto.MAX_CONTAINER_BYTES * 2 + 2)
                throw new IllegalArgumentException("Stored vault too large");
            byte[] container = new MiniData(hex).getBytes();""")


def adapt_passphrase_verification(java):
    """Reauthentication must not replace the active vault generation."""
    p = java/'SeedVault.java'
    text = p.read_text()
    start = text.index('    public synchronized boolean open(String zPassphrase) {')
    end = text.index('    // ---------------------------------------------------------------------------------------------', start)
    old = text[start:end]
    decrypt = old[old.index('        String hex = mStore.read();'):old.index('        try {\n            VaultBlob blob = VaultBlob.fromBytes(plain);')]
    decrypt = decrypt.replace('if (hex == null) return false;', 'if (hex == null) return null;').replace('            return false;', '            return null;')
    apply = old[old.index('        try {\n            VaultBlob blob = VaultBlob.fromBytes(plain);'):]
    apply = apply.replace('            VaultBlob blob = VaultBlob.fromBytes(plain);\n', '')
    new = """    public synchronized boolean open(String zPassphrase) {
        VaultBlob blob = decryptStored(zPassphrase);
        if (blob == null) return false;
""" + apply + """    /** Verify sensitive Settings actions without replacing the active signing generation. */
    public synchronized boolean verifyPassphrase(String passphrase) {
        requireOpen();
        VaultBlob blob = decryptStored(passphrase);
        return blob != null && mPhrase.equals(blob.getPhrase());
    }

    private VaultBlob decryptStored(String zPassphrase) {
""" + decrypt + """        try { return VaultBlob.fromBytes(plain); }
        catch (IllegalArgumentException corrupt) {
            throw new VaultCorruptException("Wallet vault payload is unreadable", corrupt);
        }
    }

"""
    p.write_text(text[:start] + new + text[end:])
    p = java/'SettingsView.java'
    text = p.read_text()
    assert text.count('act.vault().open(') == 3, 'Wallet reauthentication call drift'
    p.write_text(text.replace('act.vault().open(', 'act.vault().verifyPassphrase('))


def adapt_unlock_errors(java):
    """A stopped node or failed counter mirror is not evidence of a damaged seed backup."""
    p = java/'SeedVault.java'
    text = p.read_text()
    anchor = '    private final BlobStore mStore;'
    assert text.count(anchor) == 1
    text = text.replace(anchor, """    public static final class VaultUnavailableException extends IllegalStateException {
        public VaultUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

""" + anchor)
    old = 'throw new VaultCorruptException(\n                    "Wallet vault decrypted but could not be applied: " + applyErr.getMessage(), applyErr);'
    assert text.count(old) == 1
    text = text.replace(old, 'throw new VaultUnavailableException(\n                    "Wallet counters are unavailable. Start the embedded node and retry.", applyErr);')
    text = text.replace('// Decryption authenticated (passphrase was correct) but parsing or a mirror commit failed.',
                        '// The payload is valid. Counter/node availability must not be reported as corruption.')
    text = text.replace('the RIGHT passphrase but cannot be applied (corrupt payload, failed mirror commit) throws\n     * {@link VaultCorruptException}',
                        'the RIGHT passphrase but has an invalid payload throws {@link VaultCorruptException};\n     * unavailable node/counters throw {@link VaultUnavailableException}')
    p.write_text(text)
    p = java/'MainActivity.java'
    text = p.read_text()
    needle = '} catch (SeedVault.VaultCorruptException corrupt) {'
    assert text.count(needle) == 2
    text = text.replace(needle, '} catch (SeedVault.VaultUnavailableException unavailable) {\n                toast(unavailable.getMessage());\n            ' + needle)
    p.write_text(text)


def adapt_setup_and_sessions(java):
    def change(path, old, new, count=1):
        text = path.read_text()
        assert text.count(old) == count, ('Wallet setup adaptation drift', path, old)
        path.write_text(text.replace(old, new))
    p = java/'SeedVault.java'
    change(p, '    private long mGeneration;', '    private long mGeneration;\n    private volatile Object mSessionToken = new Object();')
    change(p, '    private boolean mOpen;', '    private volatile boolean mOpen;')
    change(p, '        mGeneration++;', '        mGeneration++;\n        mSessionToken = new Object();', 2)
    change(p, '    /** Verify sensitive Settings actions', """    /** Same snapshot guard used by PandaDEX: old work never becomes current again. */
    public synchronized java.util.function.BooleanSupplier authorization() {
        requireOpen();
        final Object token = mSessionToken;
        return () -> mOpen && mSessionToken == token;
    }

    public synchronized void importNodeSeed(String passphrase, int floor) {
        importNodeSeed(passphrase, floor, EmbeddedWallet.NODE);
    }

    synchronized void importNodeSeed(String passphrase, int floor, EmbeddedWallet.Source source) {
        if (floor < 0 || floor >= Util.WOTS_MAX_USES)
            throw new IllegalArgumentException("Classic key-use floor is out of range");
        final String phrase = source.withWallet(EmbeddedWallet::phrase);
        KeyUses candidate = mCounterFactory.forPhrase(phrase);
        // Derivation happens outside the node monitor. Counter coordination takes
        // the monitor only for each short verified query/update.
        raiseClassicFloor(candidate, floor);
        VaultBlob blob = new VaultBlob(phrase, true, snapshot(candidate));
        source.withWallet(wallet -> {
            if (!phrase.equals(EmbeddedWallet.phrase(wallet)))
                throw new IllegalStateException("The node seed changed. Review setup again");
            persist(blob, passphrase);
            openState(phrase, passphrase, true, candidate);
            return null;
        });
    }

    /** Verify sensitive Settings actions""")
    p = java/'comms/NodeApi.java'
    change(p, '    private boolean mReleased = false;', """    private volatile boolean mReleased = false;
    private final java.util.function.BooleanSupplier mAuthorized;""")
    change(p, '    public NodeApi(Context ctx, PairingListener pairing) {', """    public NodeApi(Context ctx, PairingListener pairing) {
        this(ctx, pairing, () -> true);
    }
    public NodeApi(Context ctx, PairingListener pairing, java.util.function.BooleanSupplier authorized) {
        mAuthorized = authorized;""")
    change(p, '        return mContext instanceof Activity', '        return !authorized() || mContext instanceof Activity')
    change(p, '    public void cmd(String command, Cb cb) {', """    private boolean authorized() {
        try { return !mReleased && mAuthorized != null && mAuthorized.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }

    public void cmd(String command, Cb cb) {""")
    change(p, '        if (mReleased) return;', '        if (dead()) return;')
    change(p, 'mApi.Command(command, new MinimaAPIListener()', 'mApi.Command(command, this::authorized, new MinimaAPIListener()')
    p = java/'comms/NodeLink.java'
    change(p, '        mApi = new NodeApi(zContext, zPairing);', """        this(zContext, zPairing, () -> true);
    }
    public NodeLink(Context context, NodeApi.PairingListener pairing, java.util.function.BooleanSupplier authorized) {
        mApi = new NodeApi(context, pairing, authorized);""")
    p = java/'AddressBook.java'
    change(p, '    public void deriveMissing() {', """    public void deriveMissing() { deriveMissing(() -> true); }
    public void deriveMissing(java.util.function.BooleanSupplier current) {
        if (!current.getAsBoolean()) return;""")
    change(p, '            if (mPubKeys[i] != null) continue;', '            if (!current.getAsBoolean()) return;\n            if (mPubKeys[i] != null) continue;')
    change(p, '            put(i, pk);', '            if (!current.getAsBoolean()) return;\n            put(i, pk);')
    p = java/'MainActivity.java'
    change(p, '    private AddressBook mBook;', '''    private AddressBook mBook;
    private java.util.function.BooleanSupplier mUiSession = () -> false;

    private void stopWalletConnection() {
        mUiSession = () -> false;
        if (mNode != null) mNode.onDestroy();
        mNode = null;
        if (mBook != null) mBook.setDeriveListener(null);
        mBook = null; mWallet = null; mFactory = null; mKeyUses = null;
        mAddr0x = null; mAddrMx = null;
        mPaired = false; mTracked = false; mTrackOk = 0;
        mScanCallback = null; mCreateDocCallback = null; mOpenDocCallback = null;
        mBalances.clear(); mMeta.clear(); mLastCoins = new org.minima.utils.json.JSONArray();
    }

    public java.util.function.BooleanSupplier sessionAuthorization() {
        final java.util.function.BooleanSupplier current = mUiSession;
        return () -> !isFinishing() && !isDestroyed() && current.getAsBoolean();
    }

    /** Reuse the wallet's existing import/generate forms after explicit reauthentication. */
    public void changeSeed() {
        final EditText pass = password();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Change wallet seed")
                .setMessage("Export an encrypted backup first. The old seed's counters will be kept. Enter your current wallet passphrase to continue.")
                .setView(pass)
                .setPositiveButton("Continue", (d,w) -> {
                    try {
                        if (!mVault.verifyPassphrase(pass.getText().toString())) {
                            toast("Wrong passphrase"); return;
                        }
                        final java.util.function.BooleanSupplier authorized = sessionAuthorization();
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Choose wallet seed")
                                .setItems(new String[]{"Use Pandamonium node seed", "Import a different seed", "Create a new seed"}, (choice,index) -> {
                                    if (!authorized.getAsBoolean()) { toast("Wallet session changed. Unlock again."); return; }
                                    stopWalletConnection();
                                    mSession.lock();
                                    if (index == 0) onboardImport(true);
                                    else if (index == 1) onboardImport(false);
                                    else onboardGenerate();
                                }).setNegativeButton("Cancel", null).show();
                    } catch (Exception failure) { toast("Could not verify the wallet passphrase"); }
                }).setNegativeButton("Cancel", null).show();
    }''')
    change(p, 'col.addView(body("Self-custodial. Your seed and funds live on THIS device; the node only relays."));',
           'col.addView(body("Set up minimaWallet with your Pandamonium node seed and classic keys. Choose the starting key-use floor below; higher recorded counters are preserved."));')
    change(p, 'col.addView(primary("Create a new wallet", v -> onboardGenerate()));',
           'col.addView(primary("Use Pandamonium node seed", v -> onboardImport(true)));')
    change(p, '    private void onboardImport() {', '''    private void onboardImport() { onboardImport(false); }
    private void onboardImport(boolean useNodeSeed) {''')
    change(p, 'col.addView(bigTitle("Import a seed"));', 'col.addView(bigTitle(useNodeSeed ? "Use node seed" : "Import a seed"));')
    change(p, '''        col.addView(body("Paste a 24-word mnemonic OR any phrase of 16+ characters (web-wallet style). "
                + "Both derive the wallet the same way."));''', '''        col.addView(body(useNodeSeed
                ? "The seed is read directly from the embedded node when you confirm. It will be stored in this wallet's encrypted backup format."
                : "Paste a 24-word mnemonic OR any phrase of 16+ characters (web-wallet style). Both derive the wallet the same way."));''')
    change(p, '        col.addView(phrase);', '        if (!useNodeSeed) col.addView(phrase);')
    change(p, 'if (ph.length() < 16)', 'if (!useNodeSeed && ph.length() < 16)')
    change(p, '            confirmImport(ph, a, usedN);', '            confirmImport(ph, a, usedN, useNodeSeed);')
    change(p, 'private void confirmImport(String zPhrase, String zPassphrase, int zUsed)',
           'private void confirmImport(String zPhrase, String zPassphrase, int zUsed, boolean useNodeSeed)')
    change(p, '''                    mVault.importSeed(zPhrase, zPassphrase, zUsed);
                    mSession.touch();
                    startMain();''', '''                    try {
                        if (useNodeSeed) mVault.importNodeSeed(zPassphrase, zUsed);
                        else mVault.importSeed(zPhrase, zPassphrase, zUsed);
                        BiometricUnlock.disable(this);
                        mSession.touch();
                        startMain();
                    } catch (Exception failure) {
                        toast("Wallet setup failed. Check the embedded node and your inputs, then retry.");
                    }''')
    change(p, '''                .setTitle("Confirm signatures used")
                .setMessage(msg)''', '''                .setTitle("Confirm classic key-use floor")
                .setMessage((useNodeSeed ? "Use the Pandamonium node seed.\\n\\n" : "")
                        + msg + "\\n\\nThis lower bound applies to all 64 classic keys. Higher existing counters are preserved. Block-based key positions remain separate.")''')
    change(p, '    private void showUnlock() {', '    private void showUnlock() {\n        stopWalletConnection();')
    change(p, '    private void startMain() {', '    private void startMain() {\n        stopWalletConnection();\n        mUiSession = mVault.authorization();')
    change(p, 'mNode = new NodeLink(this, this::onPaired);',
           'mNode = new NodeLink(this, this::onPaired, mUiSession);')
    change(p, '''            mBook.setDeriveListener(n -> runOnUiThread(this::updateStatusBanner));
            new Thread(() -> {
                mBook.deriveMissing();
                runOnUiThread(this::onAddressBookReady);
            }, "addressbook-derive").start();''', '''            final AddressBook book = mBook;
            final java.util.function.BooleanSupplier current = sessionAuthorization();
            book.setDeriveListener(n -> runOnUiThread(() -> {
                if (current.getAsBoolean() && book == mBook) updateStatusBanner();
            }));
            new Thread(() -> {
                book.deriveMissing(current);
                runOnUiThread(() -> { if (current.getAsBoolean() && book == mBook) onAddressBookReady(); });
            }, "addressbook-derive").start();''')
    change(p, '        if (mNode != null) mNode.onDestroy();\n    }\n}',
           '        stopWalletConnection();\n        mTickHandler.removeCallbacks(mBlockTick);\n    }\n}')
    change(p, '        String full = zReview', '        final java.util.function.BooleanSupplier current = sessionAuthorization();\n        String full = zReview')
    change(p, '                        mVault.assertSigningAllowed();', '''                        if (!current.getAsBoolean()) throw new SeedVault.SigningNotAllowedException("Wallet session changed. Review again.");
                        mVault.assertSigningAllowed();''')
    # Settings key-use controls must also remain available once the wallet is trusted.
    p = java/'SettingsView.java'
    change(p, '            container.addView(outlineButton("Enter signatures already used…", v -> setUsesManually()));', '')
    change(p, '        boolean trusted = act.vault().isKeyUsesTrusted();', '''        container.addView(outlineButton("Set classic key-use floor…", v -> setUsesManually()));
        boolean trusted = act.vault().isKeyUsesTrusted();''')
    change(p, '        container.addView(outlineButton("Reveal seed phrase", v -> revealSeed()));', '''        container.addView(outlineButton("Change wallet seed…", v -> act.changeSeed()));
        container.addView(outlineButton("Reveal seed phrase", v -> revealSeed()));''')
    change(p, 'field.setHint("signatures already used (key 0)");', 'field.setHint("minimum signatures already used per classic key");')
    change(p, '''                .setMessage("How many one-time (WOTS) signatures has this seed already made on this "
                        + "wallet's key? The counter can only be RAISED (it is " + current + " now).\\n\\n"''', '''                .setMessage("Set a lower bound for all 64 classic keys. Higher stored counters are kept "
                        + "(key 0 is " + current + " now). Block key positions are separate.\\n\\n"''')
    p = java/'SendView.java'
    change(p, '        String full = review', '        final java.util.function.BooleanSupplier current = act.sessionAuthorization();\n        String full = review')
    change(p, '                        act.vault().assertSigningAllowed();', '''                        if (!current.getAsBoolean()) throw new SeedVault.SigningNotAllowedException("Wallet session changed. Review again.");
                        act.vault().assertSigningAllowed();''')
    p = java/'SettingsView.java'
    change(p, '    private final LinearLayout container;', '''    private final LinearLayout container;
    private final java.util.function.BooleanSupplier session;
    private boolean currentSession() {
        if (session.getAsBoolean()) return true;
        Toast.makeText(act, "Wallet session changed. Unlock again.", Toast.LENGTH_SHORT).show();
        return false;
    }''')
    change(p, '        super(a, makeRoot(a));', '        super(a, makeRoot(a));\n        session = a.sessionAuthorization();')
    change(p, '        container.removeAllViews();', '        if (!session.getAsBoolean()) return;\n        container.removeAllViews();')
    import re
    text = p.read_text()
    text, count = re.subn(r'(\.setPositiveButton\([^\n]*-> \{)',
                          r'\1\n                    if (!currentSession()) return;', text)
    assert count == 8, ('Settings confirmation source drift', count)
    p.write_text(text)
    change(p, '    private void doRestore(byte[] bytes, String filePass) {',
           '    private void doRestore(byte[] bytes, String filePass) {\n        if (!currentSession()) return;')
    for call in ['act.vault().setStartingKeyUses(n);', 'act.vault().confirmBrandNewSeed();', 'act.vault().attestKeyUsesUpToDate();']:
        change(p, call, '''try { ''' + call + ''' }
                    catch (RuntimeException failure) {
                        Toast.makeText(act, "Could not update classic counters. Check the node and unlock again.", Toast.LENGTH_LONG).show();
                        return;
                    }''')
    p = java/'MainActivity.java'
    change(p, '''            mVault.createNew(phrase, passphrase);
            mSession.touch();
            startMain();''', '''            try {
                mVault.createNew(phrase, passphrase);
                BiometricUnlock.disable(this);
                mSession.touch();
                startMain();
            } catch (Exception failure) {
                toast("Could not create the wallet. Check the embedded node and retry.");
            }''')
    change(p, '+ "will sign next from signature #" + zUsed', '+ "will use at least signature #" + zUsed')


def adapt_background_setup(java):
    def change(path, old, new, count=1):
        text = path.read_text()
        assert text.count(old) == count, ('Wallet background adaptation drift', path, old)
        path.write_text(text.replace(old, new))
    p = java/'WalletSession.java'
    change(p, '    private final SeedVault mVault;', '''    private final SeedVault mVault;
    private final WalletWork mWork;
    private boolean mLockAfterWork;
    public WalletWork work() { return mWork; }''')
    change(p, '        mVault   = new SeedVault(zApp);', '''        mVault   = new SeedVault(zApp);
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        mWork = new WalletWork(java.util.concurrent.Executors.newSingleThreadExecutor(), action -> main.post(() -> {
            if (mLockAfterWork || mBackgroundedAt != 0L) {
                mVault.lock();
                mLockAfterWork = false;
                mBackgroundedAt = 0L;
            }
            action.run();
        }));''')
    change(p, '        if (!mVault.isOpen()) return false;', '        if (mWork.busy() || !mVault.isOpen()) return false;')
    change(p, '        mVault.lock();\n        mBackgroundedAt = 0L;', '''        if (mWork.busy()) mLockAfterWork = true;
        else mVault.lock();
        mBackgroundedAt = 0L;''')
    change(p, '        if (mVault.isOpen()) mBackgroundedAt = System.currentTimeMillis();',
           '        if (mWork.busy() || mVault.isOpen()) mBackgroundedAt = System.currentTimeMillis();')
    p = java/'MainActivity.java'
    change(p, '    private AddressBook mBook;', '''    private AddressBook mBook;
    private final Runnable mWorkListener = this::walletWorkFinished;
    private androidx.appcompat.app.AlertDialog mWorkDialog;
    private Runnable mAfterWork;
    private boolean mWaitingForWork, mWorkOnlyScreen;

    public void runWalletWork(Runnable operation, Runnable after) {
        if (mSession.work().busy()) { toast("A wallet operation is already running"); return; }
        mAfterWork = after;
        mWaitingForWork = true;
        if (!mSession.work().start(operation)) { mWaitingForWork = false; return; }
        showWalletWork();
    }
    private void showWalletWork() {
        if (mWorkDialog != null) return;
        mWorkDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Preparing wallet")
                .setMessage("Checking keys and saving the encrypted wallet…")
                .setCancelable(false).create();
        mWorkDialog.show();
    }
    private void walletWorkFinished() {
        if (!mWaitingForWork || isFinishing() || isDestroyed()) return;
        mWaitingForWork = false;
        if (mWorkDialog != null) { mWorkDialog.dismiss(); mWorkDialog = null; }
        Runnable after = mAfterWork; mAfterWork = null;
        Exception failure = mSession.work().failure();
        if (failure != null) {
            toast(failure instanceof IllegalArgumentException ? failure.getMessage()
                    : "Wallet operation failed. Check the embedded node and try again.");
        }
        if (mVault.exists() && !mVault.isOpen()) showUnlock();
        else if (failure == null && after != null) after.run();
        else if (mWorkOnlyScreen) {
            if (mVault.isOpen()) startMain(); else showOnboarding();
        }
        mWorkOnlyScreen = false;
        if (mForeground) {
            mTickHandler.removeCallbacks(mBlockTick);
            mTickHandler.postDelayed(mBlockTick, BLOCK_TICK_MS);
        }
    }
''')
    change(p, '        mVault   = mSession.vault();', '''        mVault   = mSession.vault();
        mSession.work().observe(mWorkListener);
        if (mSession.work().busy()) {
            mWaitingForWork = true; mWorkOnlyScreen = true;
            setContentView(new FrameLayout(this));
            showWalletWork();
            return;
        }''')
    change(p, '        super.onResume();', '''        super.onResume();
        mForeground = true;
        if (mSession != null && mSession.work().busy()) return;''')
    change(p, 'if (mSession != null && mVault != null && mVault.isOpen()) mSession.touch();',
           'if (mSession != null && !mSession.work().busy() && mVault != null && mVault.isOpen()) mSession.touch();')
    change(p, 'return () -> !isFinishing() && !isDestroyed() && current.getAsBoolean();',
           'return () -> !isFinishing() && !isDestroyed() && !mSession.work().busy() && current.getAsBoolean();')
    change(p, 'mNode = new NodeLink(this, this::onPaired, mUiSession);',
           'mNode = new NodeLink(this, this::onPaired, sessionAuthorization());')
    change(p, '''                    try {
                        if (useNodeSeed) mVault.importNodeSeed(zPassphrase, zUsed);
                        else mVault.importSeed(zPhrase, zPassphrase, zUsed);
                        BiometricUnlock.disable(this);
                        mSession.touch();
                        startMain();
                    } catch (Exception failure) {
                        toast("Wallet setup failed. Check the embedded node and your inputs, then retry.");
                    }''', '''                    final SeedVault vault = mVault;
                    final android.content.Context app = getApplicationContext();
                    runWalletWork(() -> {
                        if (useNodeSeed) vault.importNodeSeed(zPassphrase, zUsed);
                        else vault.importSeed(zPhrase, zPassphrase, zUsed);
                        BiometricUnlock.disable(app);
                    }, () -> { mSession.touch(); startMain(); });''')
    change(p, '''            try {
                mVault.createNew(phrase, passphrase);
                BiometricUnlock.disable(this);
                mSession.touch();
                startMain();
            } catch (Exception failure) {
                toast("Could not create the wallet. Check the embedded node and retry.");
            }''', '''            final SeedVault vault = mVault;
            final android.content.Context app = getApplicationContext();
            runWalletWork(() -> {
                vault.createNew(phrase, passphrase);
                BiometricUnlock.disable(app);
            }, () -> { mSession.touch(); startMain(); });''')
    # Typed and biometric unlock share the same asynchronous path.
    start = p.read_text().index('        col.addView(primary("Unlock", v -> {')
    end = p.read_text().index('        // Biometric unlock is an OPTIONAL', start)
    text = p.read_text()
    text = text[:start] + '''        col.addView(primary("Unlock", v -> unlockWallet(p.getText().toString())));

''' + text[end:]
    start = text.index('                try {\n                    if (mVault.open(passphrase))')
    end = text.index('            }\n            @Override public void onError', start)
    text = text[:start] + '                if (!mSession.work().busy()) unlockWallet(passphrase);\n' + text[end:]
    p.write_text(text)
    change(p, '    /** Shared post-unlock step:', '''    private void unlockWallet(String passphrase) {
        final SeedVault vault = mVault;
        runWalletWork(() -> {
            if (!vault.open(passphrase)) throw new IllegalArgumentException("Wrong wallet passphrase");
        }, this::onUnlocked);
    }

    /** Shared post-unlock step:''')
    change(p, '        super.onDestroy();\n        stopWalletConnection();', '''        super.onDestroy();
        if (mSession != null) mSession.work().detach(mWorkListener);
        mAfterWork = null;
        if (mWorkDialog != null) { mWorkDialog.dismiss(); mWorkDialog = null; }
        stopWalletConnection();''')
    p = java/'SettingsView.java'
    change(p, '''                    try { act.vault().setStartingKeyUses(n); }
                    catch (RuntimeException failure) {
                        Toast.makeText(act, "Could not update classic counters. Check the node and unlock again.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(act, "Signing enabled from signature #" + act.keyUses().currentUses(0),
                            Toast.LENGTH_LONG).show();
                    refresh();''', '''                    final SeedVault vault = act.vault();
                    act.runWalletWork(() -> vault.setStartingKeyUses(n), () -> {
                        Toast.makeText(act, "Classic key-use floor saved", Toast.LENGTH_LONG).show();
                        refresh();
                    });''')
    p = java/'MainActivity.java'
    change(p, '''            final java.util.function.BooleanSupplier current = sessionAuthorization();
            book.setDeriveListener''', '''            final java.util.function.BooleanSupplier identity = mUiSession;
            final java.util.function.BooleanSupplier current = () -> !isDestroyed()
                    && identity.getAsBoolean() && book == mBook;
            book.setDeriveListener''')
    change(p, '        if (isDestroyed()) return;\n        updateStatusBanner();',
           '        if (isDestroyed() || mSession.work().busy() || !mUiSession.getAsBoolean()) return;\n        updateStatusBanner();')
    change(p, '        mWorkOnlyScreen = false;\n        if (mForeground) {', '''        mWorkOnlyScreen = false;
        if (mUiSession.getAsBoolean() && mBook != null) onAddressBookReady();
        if (mForeground) {''')
    change(p, '    private void promptBiometricUnlock() {', '''    private void promptBiometricUnlock() {
        if (!mForeground || isFinishing() || isDestroyed() || mSession.work().busy()) return;''')
    # Reuse NFT Wallet's complete coin-list evidence check before confirming a collection.
    # Wallet NodeApi delivers Boolean false results too, so validate status here as well.
    p = java/'NftView.java'
    change(p, '''                        java.util.HashSet<String> live = new java.util.HashSet<>();
                        org.json.JSONArray arr = json.optJSONArray("response");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                org.json.JSONObject c = arr.optJSONObject(i);
                                if (c != null) live.add(c.optString("coinid", ""));
                            }
                        }
                        zRemaining.retainAll(live);''', '''                        // A missing or malformed list cannot prove a coin has left the chain state.
                        if (json == null || !Boolean.TRUE.equals(json.opt("status"))) {
                            schedule(); return;
                        }
                        org.json.JSONArray arr = json.optJSONArray("response");
                        if (arr == null) { schedule(); return; }
                        java.util.HashSet<String> live = new java.util.HashSet<>();
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject c = arr.optJSONObject(i);
                            String id = c == null ? "" : c.optString("coinid", "");
                            // Same plain-hex check as NFT Wallet's Util.isValidHexId.
                            if (!id.matches("^0x[0-9a-fA-F]{2,64}$")) { schedule(); return; }
                            live.add(id);
                        }
                        zRemaining.retainAll(live);''')
