package com.eurobuddha.keyreuses;
import org.junit.Test;
import org.json.*;
import java.util.*;
import com.eurobuddha.futurecashnext.KeyAudit;
import static org.junit.Assert.*;

public class AuditSelectionTest {
    static String pk(int n){return String.format(Locale.ROOT,"0x%064X",n+1);}
    static JSONObject row(int n,int size,int uses)throws Exception{return new JSONObject().put("publickey",pk(n)).put("size",size).put("depth",size==64?3:4).put("maxuses",size==64?262144:268435456).put("uses",uses);}
    static String derived()throws Exception{
        JSONArray a=new JSONArray();for(int i=0;i<96;i++)a.put(row(i,i<64?64:128,-1).put("index",i<64?i:i-64));return a.toString();
    }
    static String node()throws Exception{
        JSONArray a=new JSONArray();for(int i=64;i<96;i++)a.put(row(i,128,2370000));return a.toString();
    }
    static String wallet(boolean sameSeed,int uses)throws Exception{
        JSONArray a=new JSONArray();for(int i=0;i<64;i++)a.put(row(sameSeed?i:100+i,64,uses));return a.toString();
    }
    @Test public void blockNodeAlwaysChecks32BlockAnd64ClassicAddresses()throws Exception{
        AuditSelection a=new AuditSelection(node(),derived(),"[]",false);assertEquals(96,a.keys.size());
        for(int i=0;i<96;i++){
            assertEquals(i<64?KeyAudit.Family.CLASSIC:KeyAudit.Family.BLOCK,a.keys.get(i).family);
            assertEquals(i<64?-1:2370000,a.keys.get(i).uses);
            assertEquals(a.addresses.get(i).hex,a.hexAddresses.get(i));
        }
    }
    @Test public void differentWalletAddsAll64OnlyWhenSelected()throws Exception{
        assertEquals(96,new AuditSelection(node(),derived(),wallet(false,12),false).keys.size());
        AuditSelection a=new AuditSelection(node(),derived(),wallet(false,12),true);assertEquals(160,a.keys.size());
        for(int i=96;i<160;i++){assertEquals(AuditSelection.WALLET,a.origins.get(i).intValue());assertEquals(12,a.keys.get(i).uses);}
        assertEquals(160,new HashSet<>(a.publicKeys).size());
    }
    @Test public void sharedClassicKeysUseMaximumAndKeepBothSourceLabels()throws Exception{
        JSONArray nodes=new JSONArray(node());nodes.put(row(0,64,50));
        AuditSelection a=new AuditSelection(nodes.toString(),derived(),wallet(true,20),true);
        assertEquals(96,a.keys.size());assertEquals(50,a.keys.get(0).uses);
        assertEquals(AuditSelection.CLASSIC|AuditSelection.NODE|AuditSelection.WALLET,a.origins.get(0).intValue());
        assertEquals(20,a.keys.get(1).uses);
    }
    @Test public void untrustedWalletAndMissingResponsesCannotLookClean()throws Exception{
        JSONArray nodes=new JSONArray(node());nodes.put(row(0,64,50));
        AuditSelection a=new AuditSelection(nodes.toString(),derived(),wallet(true,-1),true);
        assertEquals(-1,a.keys.get(0).uses);
        KeyAudit.Result result=KeyAudit.join(a.keys,Collections.emptyList(),Collections.emptyList(),a.addresses);
        assertFalse(result.complete);assertEquals(KeyAudit.Status.UNKNOWN,result.rows.get(0).status());
    }
    @Test public void malformedIncompleteDuplicateAndConflictingSetsFail()throws Exception{
        JSONArray a=new JSONArray(derived());a.remove(95);
        assertThrows(IllegalStateException.class,()->new AuditSelection(node(),a.toString(),"[]",false));
        JSONArray duplicate=new JSONArray(node());duplicate.put(duplicate.get(0));
        assertThrows(IllegalStateException.class,()->new AuditSelection(duplicate.toString(),derived(),"[]",false));
        assertThrows(IllegalStateException.class,()->new AuditSelection(node(),derived(),"[]",true));
        JSONArray bad=new JSONArray(node());bad.put(row(0,128,10));
        assertThrows(IllegalStateException.class,()->new AuditSelection(bad.toString(),derived(),"[]",false));
    }
}
