package com.eurobuddha.wallet;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.futurecashnext.Audit;
import com.eurobuddha.futurecashnext.Cfg;
import com.eurobuddha.minimacore.service.MinimaService;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Real Android persistence and cross-module audit, isolated from every real app preference file. */
@RunWith(AndroidJUnit4.class)
public class WalletPublicAuditDeviceTest {
    private final Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Set<String> files=new HashSet<>();
    private Context fixture;
    private boolean disposable;
    private static final String PHRASE="public audit metadata fixture only";
    @Before public void prepare() {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);
        fixture=new ContextWrapper(target){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){
                String isolated="wallet_audit_device_fixture_"+name;
                if(files.add(isolated))assertTrue(target.getSharedPreferences(isolated,mode).getAll().isEmpty());
                return target.getSharedPreferences(isolated,mode);
            }
        };
        disposable=true;
    }
    @After public void cleanup(){if(disposable)for(String name:files)assertTrue(target.getSharedPreferences(name,0).edit().clear().commit());}
    private static String pk(int index){return String.format(java.util.Locale.ROOT,"0x%064x",index+1);}
    private void installPublicMetadata(String phrase,boolean trusted) {
        KeyUses local=new PrefsKeyUses(fixture,PublicAuditKeys.namespace(phrase));
        EmbeddedWallet.Source noNode=new EmbeddedWallet.Source(){
            public <T>T withWallet(java.util.function.Function<org.minima.database.wallet.Wallet,T> work){throw new AssertionError("Node access forbidden");}
        };
        NodeKeyUses publicSource=new NodeKeyUses(local,WalletPublicAuditDeviceTest::pk,noNode);
        new SeedVault.PrefsBlobStore(fixture).writeAudited("not-a-decryptable-vault",
                new VaultBlob(phrase,trusted,Collections.singletonMap(0,0)),publicSource);
    }
    @Test public void readsBothCounterMirrorsAndAllKeysWithVaultLocked() throws Exception {
        assertEquals(0,PublicAuditKeys.snapshot(fixture).length());installPublicMetadata(PHRASE,true);
        String key="uses_"+PublicAuditKeys.namespace(PHRASE)+"_0";
        assertTrue(fixture.getSharedPreferences("pandamonium_wallet_keyuses_a",0).edit().putInt(key,7).commit());
        assertTrue(fixture.getSharedPreferences("pandamonium_wallet_keyuses_b",0).edit().putInt(key,19).commit());
        SeedVault closed=new SeedVault(fixture);assertTrue(closed.exists());assertFalse(closed.isOpen());
        JSONArray rows=new Cfg(fixture).walletAuditKeys();assertEquals(64,rows.length());
        assertEquals(19,rows.getJSONObject(0).getInt("uses"));assertEquals(pk(63),rows.getJSONObject(63).getString("publickey"));
        assertFalse(rows.toString().contains(PHRASE));assertFalse(rows.toString().contains("vault"));assertFalse(closed.isOpen());
        assertNull(MinimaService.minima);
    }
    @Test public void realCounterChangesAndMissingMetadataRevokeFreshAudit() throws Exception {
        installPublicMetadata(PHRASE,true);Cfg cfg=new Cfg(fixture);Audit audit=new Audit(cfg,null);
        JSONArray rows=cfg.walletAuditKeys();audit.store(Collections.emptyList(),rows,true,null,0,rows);
        assertTrue(audit.usable().ok);
        new PrefsKeyUses(fixture,PublicAuditKeys.namespace(PHRASE)).recordExternalUses(0,1);
        assertEquals(Audit.Mode.STALE,audit.usable().mode);
        rows=cfg.walletAuditKeys();audit.store(Collections.emptyList(),rows,true,null,0,rows);assertTrue(audit.usable().ok);
        assertTrue(fixture.getSharedPreferences(PublicAuditKeys.VAULT_FILE,0).edit().remove(PublicAuditKeys.RECORD).commit());
        assertFalse(audit.usable().ok);assertFalse(new SeedVault(fixture).isOpen());assertNull(MinimaService.minima);
    }
}
