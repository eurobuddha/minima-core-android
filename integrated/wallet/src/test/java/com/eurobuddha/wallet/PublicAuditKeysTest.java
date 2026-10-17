package com.eurobuddha.wallet;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Synthetic public keys and in-memory preferences; no seed derivation, node or signatures. */
public class PublicAuditKeysTest {
    private static final String PHRASE="public audit unit fixture phrase";
    private static String pk(int n) { return String.format(java.util.Locale.ROOT,"0x%064x",n+1); }
    private static Map<Integer,String> keys() {
        Map<Integer,String> out=new LinkedHashMap<>();for(int i=0;i<64;i++)out.put(i,pk(i));return out;
    }
    private static class Store extends Object {
        final PrefsKeyUsesTest.Store counters=new PrefsKeyUsesTest.Store();
        final Map<String,Object> values=new HashMap<>();
        final SharedPreferences prefs=mock(SharedPreferences.class);
        boolean fail;
        Store() {
            when(counters.context.getSharedPreferences(PublicAuditKeys.VAULT_FILE,0)).thenReturn(prefs);
            when(prefs.getAll()).thenAnswer(a->new HashMap<>(values));
            when(prefs.getString(anyString(),nullable(String.class))).thenAnswer(a->values.getOrDefault(a.getArgument(0),a.getArgument(1)));
            when(prefs.contains(anyString())).thenAnswer(a->values.containsKey(a.getArgument(0)));
            when(prefs.edit()).thenAnswer(a->{
                Map<String,Object> pending=new HashMap<>();Set<String> removed=new HashSet<>();
                SharedPreferences.Editor editor=mock(SharedPreferences.Editor.class);
                when(editor.putString(anyString(),anyString())).thenAnswer(b->{pending.put(b.getArgument(0),b.getArgument(1));return editor;});
                when(editor.remove(anyString())).thenAnswer(b->{removed.add(b.getArgument(0));return editor;});
                when(editor.commit()).thenAnswer(b->{if(fail)return false;for(String k:removed)values.remove(k);values.putAll(pending);return true;});
                return editor;
            });
        }
        void save(String phrase,boolean trusted,Map<Integer,String> publicKeys) {
            NodeKeyUses source=mock(NodeKeyUses.class);when(source.auditPublicKeys()).thenReturn(publicKeys);
            new SeedVault.PrefsBlobStore(counters.context).writeAudited("encrypted-fixture-"+phrase,
                    new VaultBlob(phrase,trusted,Collections.singletonMap(0,0)),source);
        }
        JSONArray read() {return PublicAuditKeys.snapshot(counters.context);}
    }
    @Test public void unconfiguredWalletHasNoAdditionalKeys() {
        Store s=new Store();assertEquals(0,s.read().length());verifyNoInteractions(s.counters.a.prefs,s.counters.b.prefs);
    }
    @Test public void publicSnapshotIncludesAllBaseKeysExtraIndexAndMirroredMaximum() throws Exception {
        Store s=new Store();Map<Integer,String> map=keys();map.put(70,pk(70));s.save(PHRASE,true,map);
        String ns=PublicAuditKeys.namespace(PHRASE);
        s.counters.a.values.put("uses_"+ns+"_0",7);s.counters.b.values.put("uses_"+ns+"_0",19);
        s.counters.open(ns).recordExternalUses(70,45);
        JSONArray rows=s.read();assertEquals(65,rows.length());assertEquals(19,rows.getJSONObject(0).getInt("uses"));
        assertEquals(45,rows.getJSONObject(64).getInt("uses"));assertEquals(64,rows.getJSONObject(0).getInt("size"));
        assertFalse(rows.toString().contains(PHRASE));assertFalse(rows.toString().contains(ns));
    }
    @Test public void seedReplacementAndFailedCommitCannotMixDescriptorsAndBlob() throws Exception {
        Store s=new Store();s.save(PHRASE,true,keys());Map<String,Object> before=new HashMap<>(s.values);
        s.fail=true;assertThrows(IllegalStateException.class,()->s.save("replacement fixture",true,keys()));assertEquals(before,s.values);
        s.fail=false;s.save("replacement fixture",true,keys());
        JSONObject record=new JSONObject((String)s.values.get(PublicAuditKeys.RECORD));
        assertEquals(PublicAuditKeys.namespace("replacement fixture"),record.getString("namespace"));
        assertEquals("encrypted-fixture-replacement fixture",s.values.get(PublicAuditKeys.VAULT_BLOB));
    }
    @Test public void missingMalformedPartialAndDuplicateMetadataRefuseAudit() throws Exception {
        Store s=new Store();s.values.put(PublicAuditKeys.VAULT_BLOB,"encrypted");
        assertThrows(IllegalStateException.class,s::read);
        for(String raw:new String[]{"{}","broken","[]"}) {s.values.put(PublicAuditKeys.RECORD,raw);assertThrows(IllegalStateException.class,s::read);}
        Map<Integer,String> partial=keys();partial.remove(63);s.save(PHRASE,true,partial);assertThrows(IllegalStateException.class,s::read);
        Map<Integer,String> duplicate=keys();duplicate.put(63,pk(0));s.save(PHRASE,true,duplicate);assertThrows(IllegalStateException.class,s::read);
        Map<Integer,String> malformed=keys();malformed.put(63,"0x12");s.save(PHRASE,true,malformed);assertThrows(IllegalStateException.class,s::read);
    }
    @Test public void untrustedCounterSetCannotLookLikeVerifiedZeroUses() throws Exception {
        Store s=new Store();s.save(PHRASE,false,keys());JSONArray rows=s.read();
        for(int i=0;i<rows.length();i++)assertEquals(-1,rows.getJSONObject(i).getInt("uses"));
    }
    @Test public void laterCounterAdvanceIsVisibleWithoutOpeningVaultOrWriting() throws Exception {
        Store s=new Store();s.save(PHRASE,true,keys());String ns=PublicAuditKeys.namespace(PHRASE);
        assertEquals(0,s.read().getJSONObject(0).getInt("uses"));s.counters.open(ns).reserveNextUse(0);
        clearInvocations(s.counters.a.prefs,s.counters.b.prefs,s.prefs);
        assertEquals(1,s.read().getJSONObject(0).getInt("uses"));
        verify(s.prefs,never()).edit();verify(s.counters.a.prefs,never()).edit();verify(s.counters.b.prefs,never()).edit();
    }
    @Test public void unknownExtraIndexAndCorruptCounterCannotBeSilentlyOmitted() {
        Store s=new Store();s.save(PHRASE,true,keys());String prefix="uses_"+PublicAuditKeys.namespace(PHRASE)+"_";
        s.counters.a.values.put(prefix+70,1);assertThrows(IllegalStateException.class,s::read);
        s.counters.a.values.remove(prefix+70);s.counters.a.values.put(prefix+0,"bad");assertThrows(IllegalStateException.class,s::read);
    }
    private static SeedVault vault(Store s) {
        return new SeedVault(new SeedVault.PrefsBlobStore(s.counters.context), phrase -> {
            NodeKeyUses source=mock(NodeKeyUses.class);
            when(source.auditPublicKeys()).thenReturn(keys());
            PrefsKeyUses local=s.counters.open(PublicAuditKeys.namespace(phrase));
            when(source.snapshotAllUses()).thenAnswer(call->local.snapshotAllUses());
            when(source.currentUses(anyInt())).thenAnswer(call->local.currentUses(call.getArgument(0)));
            doAnswer(call->{local.recordExternalUses(call.getArgument(0),call.getArgument(1));return null;})
                    .when(source).recordExternalUses(anyInt(),anyInt());
            return source;
        });
    }
    @Test public void actualVaultCreateUnlockAndPasswordChangePublishReadablePublicMetadata() throws Exception {
        Store s=new Store();SeedVault vault=vault(s);
        vault.createNew(PHRASE,"fixture password");assertEquals(64,s.read().length());
        vault.lock();assertFalse(vault.isOpen());assertEquals(64,s.read().length());
        s.values.remove(PublicAuditKeys.RECORD);assertThrows(IllegalStateException.class,s::read);
        assertTrue(vault.open("fixture password"));assertEquals(64,s.read().length());
        vault.changePassphrase("new fixture password");vault.lock();
        assertFalse(vault.open("fixture password"));assertTrue(vault.open("new fixture password"));
        assertEquals(64,s.read().length());
    }
    @Test public void actualBackupImportPublishesReplacementNamespaceWithUntrustedCounts() throws Exception {
        Store s=new Store();SeedVault vault=vault(s);vault.createNew(PHRASE,"fixture password");
        String replacement="different public audit fixture";
        byte[] backup=VaultCrypto.encrypt("backup fixture",new VaultBlob(replacement,true,Collections.singletonMap(0,3)).toBytes());
        vault.importVault(backup,"backup fixture","replacement password");
        JSONObject record=new JSONObject((String)s.values.get(PublicAuditKeys.RECORD));
        assertEquals(PublicAuditKeys.namespace(replacement),record.getString("namespace"));
        assertFalse(record.getBoolean("trusted"));assertEquals(-1,s.read().getJSONObject(0).getInt("uses"));
        vault.attestKeyUsesUpToDate();assertEquals(3,s.read().getJSONObject(0).getInt("uses"));
    }
    @Test public void publicKeyExportIncludesHigherIndicesWithoutQueryingNodeOrSigning() {
        KeyUses counters=mock(KeyUses.class);when(counters.snapshotAllUses()).thenReturn(Collections.singletonMap(70,3));
        EmbeddedWallet.Source unavailable=new EmbeddedWallet.Source(){public <T>T withWallet(java.util.function.Function<org.minima.database.wallet.Wallet,T> task){throw new AssertionError("node access");}};
        NodeKeyUses source=new NodeKeyUses(counters,PublicAuditKeysTest::pk,unavailable);
        assertEquals(65,source.auditPublicKeys().size());assertEquals(pk(70),source.auditPublicKeys().get(70));
        verify(counters,never()).reserveNextUse(anyInt());
    }
}
