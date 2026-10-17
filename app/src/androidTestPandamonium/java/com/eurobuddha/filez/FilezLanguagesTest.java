package com.eurobuddha.filez;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Public fixture files in the disposable emulator only; fake node records commands, never executes. */
@RunWith(AndroidJUnit4.class)
public class FilezLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private static final String NAME="Report 中文 Русский Українська {count}$1.txt";
    private static final String ROOT="/public-fixture-node";
    private static final String DRAFT="Draft 中文 Русский Українська {value}$1";
    private DirectNodeApi.Backend backend;
    @Before public void prepare(){
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{backend=(DirectNodeApi.Backend)read(DirectNodeApi.class,null,"backend");DirectNodeApi.install(null);AppLock.disable(context);Language.select(context,"en");});
    }
    @After public void reset(){
        if(!"ranchu".equals(Build.HARDWARE)||!"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Language.select(context,"en");DirectNodeApi.install(backend);});
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
    }
    static Object read(Class<?> c,Object o,String n){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    static Object get(Object o,String n){return read(o.getClass(),o,n);}
    static void set(Object o,String n,Object v){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new AssertionError(e);}}
    static Object call(Object o,String n,Class<?>[] types,Object... args){try{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject json(String s){try{return new JSONObject(s);}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject object(Object... kv){JSONObject o=new JSONObject();try{for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
    static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(150);}
    static class Request {final String action,path,newPath;final Uri uri;final NodeApi.Cb cb;Request(String a,String p,String n,Uri u,NodeApi.Cb c){action=a;path=p;newPath=n;uri=u;cb=c;}}
    static class FixtureNode extends NodeApi {
        final List<Request> requests=new ArrayList<>();FixtureNode(Context c){super(c,null);}
        @Override public void cmd(String cmd,Cb cb){requests.add(new Request("cmd",cmd,null,null,cb));}
        @Override public void cmd(String cmd,long timeout,Cb cb){cmd(cmd,cb);}
        @Override public void file(String action,String path,String newPath,Uri uri,Cb cb){requests.add(new Request(action,path,newPath,uri,cb));}
        Request take(String action){for(int i=0;i<requests.size();i++)if(action.equals(requests.get(i).action))return requests.remove(i);throw new AssertionError("No fixture request "+action);}
    }
    static FixtureNode freeze(MainActivity a){
        NodeApi old=(NodeApi)get(a,"node");if(old!=null)old.onDestroy();((Handler)get(a,"ui")).removeCallbacksAndMessages(null);
        FixtureNode node=new FixtureNode(a);set(a,"node",node);set(a,"loading",false);set(a,"nodeRoot",ROOT);((View)get(a,"pairingBanner")).setVisibility(View.GONE);return node;
    }
    static FileEntry entry(boolean folder){return new FileEntry(object("name",NAME,"isdir",folder,"size",123456L,"modified",1577880123000L));}
    static List<TextView> texts(View root){List<TextView> out=new ArrayList<>();if(root instanceof TextView)out.add((TextView)root);if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++)out.addAll(texts(g.getChildAt(i)));}return out;}
    static TextView exact(View root,CharSequence expected){for(TextView t:texts(root))if(expected.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing label "+expected);}
    static Set<AlertDialog> dialogs(MainActivity a){return (Set<AlertDialog>)get(a,"languageDialogs");}
    static AlertDialog dialog(MainActivity a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}
    static Map<PopupMenu,?> menus(MainActivity a){return (Map<PopupMenu,?>)get(a,"languageMenus");}
    static EditText input(AlertDialog d){for(TextView t:texts(d.getWindow().getDecorView()))if(t instanceof EditText)return (EditText)t;throw new AssertionError("No input");}
    void screenshot(String name)throws Exception{settle();android.graphics.Bitmap bm=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bm);java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(bm.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bm.recycle();}}
    @Test public void listingKeepsNamesPathsAndPendingExportAcrossLanguages()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<MainActivity> owner=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(a->{owner.set(a);node.set(freeze(a));call(a,"loadDirectory",new Class<?>[]{String.class},"/");Request q=node.get().take("list");q.cb.onResult(object("status",true,"path","/","base",ROOT,"list",new JSONArray().put(object("name",NAME,"size",123456L,"modified",1577880123000L)).put(object("name","Folder 中文","isdir",true))));set(a,"pendingExportPath","/"+NAME);});settle();
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                assertSame(owner.get(),a);assertEquals("/",get(a,"currentPath"));assertEquals("/"+NAME,get(a,"pendingExportPath"));assertTrue(node.get().requests.isEmpty());
                View root=a.findViewById(android.R.id.content);exact(root,NAME);exact(root,"Folder 中文");exact(root,Language.msg(l10n_filez_folder));
                exact(root,Language.join(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_filez_items,2),Language.msg(l10n_filez_root_hint)));
                exact(root,Language.join(Util.formatBytes(123456L),"  ·  ",Language.stamp(1577880123000L,"yMMMdHm")));
            });screenshot("filez-list-"+code);}
        }
    }
    @Test public void openMenusRetainActionsAndRefreshTitles()throws Exception{
        for(boolean entryMenu:new boolean[]{false,true})try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<PopupMenu> popup=new AtomicReference<>();
            settle();scenario.onActivity(a->{node.set(freeze(a));if(entryMenu)call(a,"showEntryMenu",new Class<?>[]{View.class,FileEntry.class},a.findViewById(R.id.pm_filez_menuBtn),entry(false));else call(a,"showMainMenu",new Class<?>[]{View.class},a.findViewById(R.id.pm_filez_menuBtn));assertEquals(1,menus(a).size());popup.set(menus(a).keySet().iterator().next());});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                assertEquals(1,menus(a).size());assertSame(popup.get(),menus(a).keySet().iterator().next());assertTrue(node.get().requests.isEmpty());
                assertEquals(Language.msg(entryMenu?l10n_filez_export_phone:l10n_filez_import_here).toString(),popup.get().getMenu().findItem(1).getTitle().toString());
                assertEquals(Language.msg(entryMenu?l10n_filez_rename:l10n_filez_new_folder).toString(),popup.get().getMenu().findItem(2).getTitle().toString());
            });
                // Read actual popup rows, not just the MenuItem model: cached widths can clip translations.
                for(int i=0;i<popup.get().getMenu().size();i++) {
                    String label=popup.get().getMenu().getItem(i).getTitle().toString();
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(label))
                        .inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).check((view,error)->{
                            if(error!=null)throw error;
                            TextView text=(TextView)view;
                            assertNotNull(label,text.getLayout());
                            for(int line=0;line<text.getLayout().getLineCount();line++)assertEquals(label,0,text.getLayout().getEllipsisCount(line));
                        });
                }
                screenshot("filez-"+(entryMenu?"entry-menu-":"menu-")+code);
            }
            scenario.onActivity(a->popup.get().dismiss());settle();scenario.onActivity(a->assertTrue(menus(a).isEmpty()));
        }
    }
    @Test public void renameDraftAndDeleteTargetStayLiteralUntilConfirmed()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<AlertDialog> retained=new AtomicReference<>();AtomicReference<EditText> edit=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(a->{node.set(freeze(a));set(a,"currentPath","/fixtures");call(a,"promptRename",new Class<?>[]{FileEntry.class},entry(false));retained.set(dialog(a));edit.set(input(dialog(a)));edit.get().setText(DRAFT);edit.get().setSelection(6);});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(retained.get(),dialog(a));assertEquals(DRAFT,edit.get().getText().toString());assertEquals(6,edit.get().getSelectionStart());assertTrue(node.get().requests.isEmpty());exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_filez_rename_title,NAME));});screenshot("filez-rename-"+code);}
            scenario.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick());settle();scenario.onActivity(a->{Request q=node.get().take("move");assertEquals("/fixtures/"+NAME,q.path);assertEquals("/fixtures/"+DRAFT,q.newPath);assertTrue(dialogs(a).isEmpty());call(a,"confirmDelete",new Class<?>[]{FileEntry.class},entry(true));retained.set(dialog(a));});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(retained.get(),dialog(a));assertTrue(node.get().requests.isEmpty());exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_filez_delete_folder,NAME));});screenshot("filez-delete-"+code);}
            scenario.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();scenario.onActivity(a->assertTrue(node.get().requests.isEmpty()));
        }
    }
    @Test public void detailPathAndNodeResultsRetainLiteralData()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<AlertDialog> retained=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(a->{node.set(freeze(a));set(a,"currentPath","/fixtures");call(a,"showDetail",new Class<?>[]{FileEntry.class},entry(false));retained.set(dialog(a));Request q=node.get().take("stat");assertEquals(ROOT+"/fixtures/"+NAME,q.path);q.cb.onResult(object("status",true,"exists",false,"parentexists",true,"parentlist",new JSONArray().put("File action failed").put(DRAFT)));});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(retained.get(),dialog(a));assertTrue(node.get().requests.isEmpty());View root=dialog(a).getWindow().getDecorView();assertTrue(exact(root,ROOT+"/fixtures/"+NAME).isTextSelectable());exact(root,"TXT");exact(root,NAME);exact(root,Language.join(Language.msg(l10n_filez_path_missing),Language.msg(l10n_filez_parent_contents),'"',"File action failed",'"',", ",'"',DRAFT,'"'));});screenshot("filez-detail-"+code);}
            scenario.onActivity(a->dialog(a).dismiss());settle();scenario.onActivity(a->assertTrue(dialogs(a).isEmpty()));
        }
    }
    @Test public void exportCopiesOnlyFixtureBytesWhileLanguageChanges()throws Exception{
        java.io.File src=java.io.File.createTempFile("filez-public-source-",".txt",context.getCacheDir()),dest=java.io.File.createTempFile("filez-public-dest-",".txt",context.getCacheDir());
        byte[] expected=(DRAFT+"\npublic transfer fixture").getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(src.toPath(),expected);
        AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<Request> request=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(a->{node.set(freeze(a));call(a,"doExport",new Class<?>[]{String.class,Uri.class},"/fixtures/"+NAME,Uri.fromFile(dest));request.set(node.get().take("get"));assertEquals("/fixtures/"+NAME,request.get().path);});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertNull(get(a,"pendingExportPath"));assertTrue(node.get().requests.isEmpty());exact(a.findViewById(android.R.id.content),Language.msg(l10n_filez_exporting));});}
            scenario.onActivity(a->request.get().cb.onResult(object("status",true,"uri",Uri.fromFile(src).toString(),"size",expected.length)));
            for(int i=0;i<40&&!Arrays.equals(expected,Files.readAllBytes(dest.toPath()));i++)android.os.SystemClock.sleep(50);
            assertArrayEquals(expected,Files.readAllBytes(dest.toPath()));
        }finally{assertTrue(src.delete());assertTrue(dest.delete());}
    }
    @Test public void localFallbacksTranslateAndNodeErrorsRemainLiteral(){
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(FilezLanguagesTest::freeze);
            for(String code:CODES){scenario.onActivity(a->{Language.select(a,code);call(a,"onFileError",new Class<?>[]{JSONObject.class},object("status",false));exact(a.findViewById(android.R.id.content),Language.msg(l10n_filez_file_failed));call(a,"onFileError",new Class<?>[]{JSONObject.class},object("status",false,"error","File action failed"));exact(a.findViewById(android.R.id.content),"File action failed");assertEquals(Language.msg(l10n_filez_stream_error).toString(),FilezMessages.error(new Exception("Could not open stream")).toString());assertEquals(DRAFT,FilezMessages.error(new Exception(DRAFT)));});settle();}
        }
    }
}
