package com.eurobuddha.nftwallet;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.object;
/** Exercises actual preference updates and the shared engine lock using public fixtures. */
public class MintImageGuardTest {
    @Before @After public void reset()throws Exception{new BurialLifecycleTest().resetDriver();}
    static JSONObject row(long id,String phase)throws Exception{return object("id",id,"tokenid","0xab","phase",phase,"mode","embed","size",2,"items",new JSONArray().put(item(1,"","")).put(item(2,"","")));}
    static JSONObject item(int idx,String image,String coin){return object("idx",idx,"image",image,"coinid",coin);}
    @Test public void capturedRowChangesOnlyItsEmptySlotsAndResumesAfterAllImages()throws Exception{
        BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();JSONObject other=row(2,"NEEDIMAGES");p.rows(row(1,"NEEDIMAGES"),other);
        assertTrue(MintDriver.saveProgressImage(p.ctx,1,1,"PUBLICIMAGE1"));assertEquals("NEEDIMAGES",LocalStore.findById(p.ctx,1).optString("phase"));assertEquals(other.toString(),LocalStore.findById(p.ctx,2).toString());
        assertTrue(MintDriver.saveProgressImage(p.ctx,1,2,"PUBLICIMAGE2"));assertEquals("STAMP",LocalStore.findById(p.ctx,1).optString("phase"));assertEquals("PUBLICIMAGE1",MintEngine.localItems(LocalStore.findById(p.ctx,1)).getJSONObject(0).getString("image"));assertEquals(other.toString(),LocalStore.findById(p.ctx,2).toString());
    }
    @Test public void finishedPausedUnknownOrStuckRowsCannotAcceptLateImages()throws Exception{
        for(String phase:new String[]{"DONE","BURY","BURIED","unknown"}){BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();p.rows(row(1,phase));String before=p.values.get("collections");assertFalse(MintDriver.saveProgressImage(p.ctx,1,1,"PUBLICIMAGE"));assertEquals(before,p.values.get("collections"));}
        BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();p.rows(row(1,"MOVE").put("stuck",1));assertFalse(MintDriver.saveProgressImage(p.ctx,1,1,"PUBLICIMAGE"));
    }
    @Test public void newerOrStampedArtworkCannotBeReplaced()throws Exception{
        for(JSONObject item:new JSONObject[]{item(1,"new image",""),item(1,"","0xab")}){BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();JSONObject row=row(1,"STAMP");row.getJSONArray("items").put(0,item);p.rows(row);String before=p.values.get("collections");assertFalse(MintDriver.saveProgressImage(p.ctx,1,1,"late image"));assertEquals(before,p.values.get("collections"));}
    }
    @Test public void malformedDuplicateMissingOrWrongModeRowsStayUntouched()throws Exception{
        for(String raw:new String[]{"not json","[null]",new JSONArray().put(row(1,"NEEDIMAGES")).put(row(1,"NEEDIMAGES")).toString(),new JSONArray().put(row(1,"STAMP").put("items",new JSONArray().put(item(1,"","")))).toString(),new JSONArray().put(row(1,"STAMP").put("mode","url")).toString()}){BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();p.values.put("collections",raw);assertFalse(MintDriver.saveProgressImage(p.ctx,1,1,"PUBLIC"));assertEquals(raw,p.values.get("collections"));}
        BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();p.rows(row(1,"STAMP"));assertFalse(MintDriver.saveProgressImage(p.ctx,999,1,"PUBLIC"));assertFalse(MintDriver.saveProgressImage(p.ctx,1,3,"PUBLIC"));assertFalse(MintDriver.saveProgressImage(p.ctx,1,1,""));assertFalse(MintDriver.saveProgressImage(p.ctx,1,1,"X".repeat(ImageTools.STATE_IMG_BUDGET+1)));
    }
    @Test public void failedPersistenceDoesNotReleaseThePausedPhase()throws Exception{
        BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();p.rows(row(1,"NEEDIMAGES"));p.writable=false;String before=p.values.get("collections");assertFalse(MintDriver.saveProgressImage(p.ctx,1,1,"PUBLIC"));assertEquals(before,p.values.get("collections"));
    }
    @Test public void inFlightEngineSnapshotBlocksImageWritesUntilReleased()throws Exception{
        BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();p.rows(row(1,"STAMP"));NodeApi node=mock(NodeApi.class);
        java.lang.reflect.Field running=MintDriver.class.getDeclaredField("running"),active=MintDriver.class.getDeclaredField("activeNode");running.setAccessible(true);active.setAccessible(true);running.set(null,true);active.set(null,node);
        String before=p.values.get("collections");assertFalse(MintDriver.saveProgressImage(p.ctx,1,1,"PUBLIC"));assertEquals(before,p.values.get("collections"));when(node.isClosed()).thenReturn(true);assertTrue(MintDriver.saveProgressImage(p.ctx,1,1,"PUBLIC"));
    }
    @Test public void retryClearsOnlyTheCapturedParkedCreateAndCannotRepeat()throws Exception{
        BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();JSONObject first=row(1,"CREATE").put("tokenid","").put("posted",1).put("createstuck",1),other=row(2,"CREATE").put("posted",1).put("createstuck",1);p.rows(first,other);
        assertTrue(MintDriver.retryCreate(p.ctx,1));assertEquals(0,LocalStore.findById(p.ctx,1).getInt("posted"));assertEquals(other.toString(),LocalStore.findById(p.ctx,2).toString());assertFalse(MintDriver.retryCreate(p.ctx,1));
    }
    @Test public void retryRefusesAdvancedPausedBusyCorruptAndUnwritableRecords()throws Exception{
        for(String phase:new String[]{"MOVE","STAMP","DONE","BURY","BURIED"}){BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();p.rows(row(1,phase).put("tokenid","").put("posted",1).put("createstuck",1));String before=p.values.get("collections");assertFalse(MintDriver.retryCreate(p.ctx,1));assertEquals(before,p.values.get("collections"));}
        BurialLifecycleTest.Prefs p=new BurialLifecycleTest.Prefs();p.rows(row(1,"CREATE").put("tokenid","").put("posted",1).put("createstuck",1));String before=p.values.get("collections");p.writable=false;assertFalse(MintDriver.retryCreate(p.ctx,1));assertEquals(before,p.values.get("collections"));p.writable=true;
        java.lang.reflect.Field running=MintDriver.class.getDeclaredField("running"),active=MintDriver.class.getDeclaredField("activeNode");running.setAccessible(true);active.setAccessible(true);running.set(null,true);active.set(null,mock(NodeApi.class));assertFalse(MintDriver.retryCreate(p.ctx,1));assertEquals(before,p.values.get("collections"));
    }

}
