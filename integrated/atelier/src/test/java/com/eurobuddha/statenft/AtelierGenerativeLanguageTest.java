package com.eurobuddha.statenft;

import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AtelierGenerativeLanguageTest {
    private MainActivity activity;
    private LinearLayout body;
    private NodeApi node;
    private ArtStudio studio;
    private MockedStatic<ArtStudio> bridge;
    private JSONArray styles;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private Field field(String n)throws Exception {Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception {field(n).set(activity,v);}
    private Object call(String n,Class<?>[] types,Object...args)throws Exception {Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void render()throws Exception {call("renderGenerative",new Class<?>[]{});}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private JSONObject fixture(String name)throws Exception {try(java.io.InputStream in=getClass().getResourceAsStream("/"+name)){assertNotNull(in);return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}
    @SuppressWarnings("unchecked") private Map<String,JSONObject> configs()throws Exception{return (Map<String,JSONObject>)field("artCfgs").get(activity);}
    @SuppressWarnings("unchecked") private Set<String> setField(String n)throws Exception{return (Set<String>)field(n).get(activity);}
    @Before public void setup()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);set("body",body);
        for(String n:new String[]{"appbarBox","navBox"})set(n,new LinearLayout(activity));set("content",new ScrollView(activity));
        node=mock(NodeApi.class);set("node",node);HostingStore.list(activity);new EditText(activity);
        set("artDraftLoaded",true);set("artSeed","literal-中文-Русский-Українська");set("genCount","7");
        styles=fixture("art-style-fixtures.json").getJSONArray("styles");set("ART_STYLE_LIST",styles);
        for(int i=0;i<styles.length();i++){JSONObject st=styles.getJSONObject(i);configs().put(st.getString("key"),st.getJSONObject("cfg"));setField("artMigrated").add(st.getString("key"));}
        studio=mock(ArtStudio.class);doAnswer(c->{((Consumer<Long>)c.getArgument(1)).accept(1234L);return null;}).when(studio).capacity(any(JSONObject.class),any());
        bridge=mockStatic(ArtStudio.class);bridge.when(()->ArtStudio.with(eq(activity),any(ArtStudio.Ready.class))).thenAnswer(c->{((ArtStudio.Ready)c.getArgument(1)).run(studio);return null;});
    }
    @After public void cleanup(){bridge.close();Language.select(activity,"en");}
    private static List<EditText> edits(View root){List<EditText> out=new ArrayList<>();if(root instanceof EditText)out.add((EditText)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(edits(((ViewGroup)root).getChildAt(i)));return out;}
    private static TextView find(View root,String value){if(root instanceof TextView&&value.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView t=find(((ViewGroup)root).getChildAt(i),value);if(t!=null)return t;}return null;}
    private static void fits(View root){if(root instanceof TextView&&!(root instanceof EditText)){TextView t=(TextView)root;if(t.getLayout()!=null)assertTrue(t.getText()+" width="+t.getWidth()+" height="+t.getHeight(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)fits(((ViewGroup)root).getChildAt(i));}
    private void layout(){for(int w:new int[]{320,480,900}){body.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));body.layout(0,0,w,body.getMeasuredHeight());fits(body);}}
    private String upper(int id){return Language.upper(Language.msg(id)).toString();}
    @Test public void everyEngineLabelHasFourLanguageDisplayWithoutChangingItsLiteral()throws Exception {
        JSONObject labels=fixture("art-language-labels.json");Set<String> actual=new HashSet<>();
        for(int i=0;i<styles.length();i++){
            JSONObject st=styles.getJSONObject(i);actual.add(st.getString("label"));JSONArray slots=st.getJSONObject("cfg").getJSONArray("slots");
            for(int j=0;j<slots.length();j++){JSONObject slot=slots.getJSONObject(j);actual.add(slot.getString("label"));JSONArray vs=slot.getJSONArray("variants");for(int k=0;k<vs.length();k++)actual.add(vs.getJSONObject(k).getString("name"));}
        }
        assertEquals(19,styles.length());assertEquals(391,actual.size());assertEquals(labels.length(),actual.size());
        for(String label:actual){assertTrue(label,labels.has(label));CharSequence retained=ArtLabels.display(label);
            for(String code:CODES){Language.select(activity,code);if(labels.isNull(label))assertEquals(label,retained.toString());else {String resource="l10n_"+labels.getString(label).replaceAll("([a-z0-9])([A-Z])","$1_$2").replace('.','_').toLowerCase(Locale.ROOT);int id=com.eurobuddha.minimacore.i18n.R.string.class.getField(resource).getInt(null);assertEquals(Language.msg(id).toString(),retained.toString());}}
        }
        assertEquals("Custom trait Україна",ArtLabels.display("Custom trait Україна"));
    }
    @Test public void allNineteenStyleFormsRetainConfigInputsCursorsAndArtwork()throws Exception {
        for(int i=0;i<styles.length();i++){
            JSONObject st=styles.getJSONObject(i),cfg=st.getJSONObject("cfg");String key=st.getString("key");set("artStyle",key);
            JSONArray slots=cfg.getJSONArray("slots");setField("artOpenSlots").clear();for(int j=0;j<slots.length();j++)setField("artOpenSlots").add(slots.getJSONObject(j).getString("key"));
            JSONObject variant=slots.getJSONObject(0).getJSONArray("variants").getJSONObject(0);variant.put("on",false);variant.put("weight",37);
            JSONArray items=new JSONArray().put(item());set("artItems",items);String before=cfg.toString(),art=items.toString();render();
            List<EditText> inputs=edits(body);for(EditText e:inputs)e.setSelection(Math.min(2,e.length()));
            for(String code:CODES){Language.select(activity,code);layout();assertEquals(before,cfg.toString());assertSame(items,field("artItems").get(activity));assertEquals(art,items.toString());assertNotNull(find(body,upper(l10n_atelier_art_generate)));assertNotNull(find(body,upper(l10n_atelier_art_send)));assertNotNull(find(body,ArtLabels.display(st.getString("label")).toString()));
                List<EditText> after=edits(body);assertEquals(inputs.size(),after.size());for(int k=0;k<inputs.size();k++){assertSame(inputs.get(k),after.get(k));assertEquals(Math.min(2,inputs.get(k).length()),inputs.get(k).getSelectionStart());}
                assertEquals("literal-中文-Русский-Українська",field("artSeed").get(activity));assertEquals("7",field("genCount").get(activity));
            }
        }
        verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
    private JSONObject item()throws Exception{return new JSONObject().put("svg","<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\" fill=\"red\"/></svg>").put("traits",new JSONArray().put(new JSONObject().put("slot","palette").put("label","Palette").put("value","Ocean").put("pct",12.5))).put("score",3.5).put("bytes",120);}
    @Test public void plateDialogAndErrorsFollowLanguageWithoutRewritingMetadata()throws Exception {
        JSONObject it=item();String before=it.toString();call("showArtItem",new Class<?>[]{JSONObject.class,int.class},it,0);idle();AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
        CharSequence empty=ArtLabels.error("a slot has no enabled variants"),unique=ArtLabels.error("could not find a unique combo for item #8 - enable more variants or shrink the collection");
        for(String code:CODES){Language.select(activity,code);String text=((TextView)dialog.findViewById(android.R.id.message)).getText().toString();assertTrue(text.contains(ArtLabels.display("Palette")));assertTrue(text.contains(ArtLabels.display("Ocean")));assertTrue(text.contains("12.5%"));assertEquals(before,it.toString());assertEquals(Language.msg(l10n_atelier_art_no_variants).toString(),empty.toString());assertEquals(Language.msg(l10n_atelier_art_unique_failed,"8").toString(),unique.toString());assertEquals(Language.msg(l10n_atelier_art_generate_failed).toString(),ArtLabels.error("unexpected engine failure").toString());}
    }
    @Test public void generationArgumentsStayLiteralAndStaleRepliesCannotReplaceLaterWork()throws Exception {
        set("artStyle","mandala");JSONObject cfg=configs().get("mandala");String literal=cfg.toString();AtomicReference<Consumer<JSONObject>> pending=new AtomicReference<>();
        doAnswer(c->{assertEquals("literal-中文-Русский-Українська",c.getArgument(0));assertEquals(7,(int)c.getArgument(1));assertSame(cfg,c.getArgument(2));assertEquals(literal,((JSONObject)c.getArgument(2)).toString());pending.set(c.getArgument(3));return null;}).when(studio).generate(anyString(),anyInt(),any(JSONObject.class),any());
        for(String code:CODES){Language.select(activity,code);render();call("artGenerate",new Class<?>[]{ArtStudio.class,JSONObject.class},studio,cfg);pending.get().accept(new JSONObject().put("items",new JSONArray()));assertEquals(false,field("genBusy").get(activity));}
        call("artGenerate",new Class<?>[]{ArtStudio.class,JSONObject.class},studio,cfg);JSONArray newer=new JSONArray().put(item());set("artSeed","changed after request");set("artItems",newer);pending.get().accept(new JSONObject().put("items",new JSONArray()));assertSame(newer,field("artItems").get(activity));assertEquals("changed after request",field("artSeed").get(activity));assertEquals(false,field("genBusy").get(activity));
    }
    @Test public void staleDefaultConfigAndMigrationCannotOverwriteAnotherStyle()throws Exception {
        AtomicReference<Consumer<JSONObject>> pending=new AtomicReference<>();doAnswer(c->{pending.set(c.getArgument(1));return null;}).when(studio).defaultConfig(anyString(),any());
        JSONObject original=configs().remove("mandala");set("artStyle","mandala");render();Consumer<JSONObject> old=pending.get();assertNotNull(old);set("artStyle","geo");render();JSONObject geo=configs().get("geo");old.accept(original);assertSame(geo,configs().get("geo"));assertFalse(configs().containsKey("mandala"));
        configs().put("mandala",original);setField("artMigrated").remove("mandala");doAnswer(c->{pending.set(c.getArgument(1));return null;}).when(studio).migrate(any(JSONObject.class),any());set("artStyle","mandala");render();old=pending.get();set("artStyle","geo");render();old.accept(new JSONObject().put("style","bad"));assertSame(geo,configs().get("geo"));assertSame(original,configs().get("mandala"));assertFalse(setField("artMigrated").contains("mandala"));
    }
    @Test public void collectionHandoffPreservesLiteralTraitsAndRejectsLaterDraftEdits()throws Exception {
        AtomicReference<Runnable> job=new AtomicReference<>();
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class);MockedConstruction<Thread> threads=mockConstruction(Thread.class,(m,c)->job.set((Runnable)c.arguments().get(0)))){
            for(String code:CODES){Language.select(activity,code);set("artStyle","mandala");set("artItems",new JSONArray().put(item()).put(item()));render();call("sendArtToCollection",new Class<?>[]{});job.get().run();idle();assertEquals("CREATE_COLLECTION",field("screen").get(activity).toString());JSONObject traits=(JSONObject)field("collectionItemTraits").get(activity);assertEquals("Palette",traits.getJSONArray("1").getJSONObject(0).getString("trait_type"));assertEquals("Ocean",traits.getJSONArray("1").getJSONObject(0).getString("value"));String[] b64=(String[])field("createImages").get(activity);assertEquals(2,b64.length);assertEquals(item().getString("svg"),new String(Base64.getDecoder().decode(b64[0]),StandardCharsets.UTF_8));}
            render();call("sendArtToCollection",new Class<?>[]{});String[] before=(String[])field("createImages").get(activity);set("createName","later draft");job.get().run();idle();assertEquals("later draft",field("createName").get(activity));assertSame(before,field("createImages").get(activity));assertEquals("CREATE_GENERATIVE",field("screen").get(activity).toString());assertEquals(false,field("genBusy").get(activity));
        }
        verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
}
