package com.eurobuddha.nftwallet;
import android.widget.*;
import android.text.Editable;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.object;
/** Real private callback path with mocked Android views/node; no signing or node startup. */
public class MintDraftGuardTest {
    static final String PK="0x"+"ab".repeat(32),ADDR="0x"+"cd".repeat(32);
    static void set(Object o,Class<?> type,String name,Object value)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object get(Object o,String name)throws Exception{Field f=MintView.class.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static final class Fixture implements AutoCloseable {
        final MintView mint=mock(MintView.class,CALLS_REAL_METHODS);final MainActivity act=mock(MainActivity.class);final NodeApi node=mock(NodeApi.class);
        final Map<String,String> fields=new HashMap<>();final Map<Integer,String> images=new HashMap<>();final List<NodeApi.Cb> callbacks=new ArrayList<>();final List<JSONObject> saved=new ArrayList<>();
        final MockedStatic<Language> language=mockStatic(Language.class);final MockedStatic<Design> design=mockStatic(Design.class);final MockedStatic<LocalStore> store=mockStatic(LocalStore.class);
        final Class<?> reviewType;Object review;
        Fixture()throws Exception{
            set(mint,BaseView.class,"act",act);when(act.node()).thenReturn(node);when(act.currentTab()).thenReturn(MainActivity.TAB_MINT);doNothing().when(mint).refresh();set(mint,MintView.class,"colForm",mock(LinearLayout.class));set(mint,MintView.class,"cStatus",mock(TextView.class));set(mint,MintView.class,"colImages",images);images.put(1,"IMAGE1");images.put(2,"IMAGE2");
            set(mint,MintView.class,"colEmbedMode",true);set(mint,MintView.class,"colIconEmbed",true);
            for(String name:new String[]{"colIconB64","colIconDefault","colIconDefaultSource"})set(mint,MintView.class,name,"");
            for(String name:new String[]{"cName","cSize","cDesc","cBase","cExt","cIconUrl","cExtUrl","cWebv"}){fields.put(name,name.equals("cName")?"PUBLIC":name.equals("cSize")?"2":"");EditText edit=mock(EditText.class);Editable text=mock(Editable.class);when(edit.getText()).thenReturn(text);when(text.toString()).thenAnswer(i->fields.get(name));set(mint,MintView.class,name,edit);}
            Class<?> screen=Class.forName("com.eurobuddha.nftwallet.MintView$Screen");set(mint,MintView.class,"screen",Enum.valueOf((Class)screen,"COLLECTION"));set(mint,MintView.class,"collectionReviewGeneration",1L);
            reviewType=Class.forName("com.eurobuddha.nftwallet.MintView$CollectionReview");review=capture();
            doAnswer(i->{assertEquals("getaddress",i.getArgument(0));callbacks.add(i.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
            store.when(()->LocalStore.nextId(act)).thenReturn(12L);store.when(()->LocalStore.upsert(eq(act),any(JSONObject.class))).thenAnswer(i->{saved.add(new JSONObject(((JSONObject)i.getArgument(1)).toString()));return null;});
        }
        Object capture()throws Exception{Constructor<?> c=reviewType.getDeclaredConstructor(MintView.class);c.setAccessible(true);return c.newInstance(mint);}
        void start(long generation,Object snapshot)throws Exception{Method m=MintView.class.getDeclaredMethod("startCollection",String.class,int.class,String.class,String.class,String.class,String.class,String.class,String.class,long.class,reviewType);m.setAccessible(true);m.invoke(mint,"PUBLIC",2,"desc","https://example.com/",".png","","","",generation,snapshot);}
        void reply(int idx){callbacks.get(idx).onResult(object("status",true,"response",object("publickey",PK,"address",ADDR)));}
        public void close(){store.close();design.close();language.close();}
    }
    @Test public void changedFieldsImagesModesOrFormCannotStartLookup()throws Exception{
        for(int mode=0;mode<4;mode++)try(Fixture f=new Fixture()){if(mode==0)f.fields.put("cName","EDITED");if(mode==1)f.images.put(1,"NEW");if(mode==2)set(f.mint,MintView.class,"colEmbedMode",false);if(mode==3)set(f.mint,MintView.class,"colForm",mock(LinearLayout.class));f.start(1,f.review);assertTrue(f.callbacks.isEmpty());assertTrue(f.saved.isEmpty());verify(f.act,never()).mintEngineTick();}
    }
    @Test public void editedDraftAtCallbackIsNeitherStoredNorCleared()throws Exception{
        try(Fixture f=new Fixture()){f.start(1,f.review);f.fields.put("cName","NEW DRAFT");f.images.put(1,"NEW ART");f.reply(0);assertTrue(f.saved.isEmpty());assertEquals("NEW DRAFT",f.fields.get("cName"));assertEquals("NEW ART",f.images.get(1));assertNotNull(get(f.mint,"colForm"));verify(f.act,never()).mintEngineTick();}
    }
    @Test public void oldReplyCannotConsumeANewerPendingReview()throws Exception{
        try(Fixture f=new Fixture()){f.start(1,f.review);set(f.mint,MintView.class,"collectionReviewGeneration",2L);set(f.mint,MintView.class,"collectionStarting",false);Object next=f.capture();f.start(2,next);f.reply(0);assertTrue((Boolean)get(f.mint,"collectionStarting"));assertTrue(f.saved.isEmpty());f.reply(1);assertEquals(1,f.saved.size());verify(f.act,times(1)).mintEngineTick();}
    }
    @Test public void validSnapshotStoresExactImagesOnceAndDuplicateRepliesDoNothing()throws Exception{
        try(Fixture f=new Fixture()){f.start(1,f.review);f.start(1,f.review);assertEquals(1,f.callbacks.size());f.reply(0);f.reply(0);assertEquals(1,f.saved.size());JSONObject r=f.saved.get(0);assertEquals("embed",r.getString("mode"));assertEquals("IMAGE1",r.getJSONArray("items").getJSONObject(0).getString("image"));assertEquals("IMAGE2",r.getJSONArray("items").getJSONObject(1).getString("image"));assertEquals("CREATE",r.getString("phase"));assertEquals(PK,r.getString("creatorpk"));assertTrue(f.images.isEmpty());assertNull(get(f.mint,"colForm"));verify(f.act,times(1)).mintEngineTick();}
    }
    @Test public void navigationDeadHostAndFailedIdentityCannotCreateACollection()throws Exception{
        for(int mode=0;mode<4;mode++)try(Fixture f=new Fixture()){f.start(1,f.review);if(mode==0)when(f.act.currentTab()).thenReturn(MainActivity.TAB_SEND);if(mode==1)when(f.act.isDestroyed()).thenReturn(true);if(mode==2)f.callbacks.get(0).onResult(object("status",false,"response",object("publickey",PK,"address",ADDR)));else if(mode==3)f.callbacks.get(0).onError("unavailable");else f.reply(0);assertTrue(f.saved.isEmpty());assertEquals(2,f.images.size());verify(f.act,never()).mintEngineTick();}
    }
}
