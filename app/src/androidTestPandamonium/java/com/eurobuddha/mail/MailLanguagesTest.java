package com.eurobuddha.mail;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.eurobuddha.mail.integratedcomms.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Disposable Mail database and public display fixtures; the node and crypto never execute. */
@RunWith(AndroidJUnit4.class)
public class MailLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    private static final String KEY="0x"+"12".repeat(64), MINE="0x"+"34".repeat(64), ADDRESS="0x"+"56".repeat(32);
    private static final String DRAFT="Draft 中文 Русский Українська {count}$1", NAME="Send", SUBJECT="Settings";
    private DirectNodeApi.Backend backend;
    @Before public void prepare(){
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
        assertTrue(context.getSharedPreferences("minima_mail_identity",0).getAll().isEmpty());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{backend=(DirectNodeApi.Backend)read(DirectNodeApi.class,null,"backend");DirectNodeApi.install(null);AppLock.disable(context);Language.select(context,"en");});
        try(CommsDb db=new CommsDb(context)){assertEquals(0,db.thread("owner").size());db.getWritableDatabase().delete("messages",null,null);db.getWritableDatabase().delete("contacts",null,null);db.getWritableDatabase().delete("meta",null,null);db.addContact(NAME,KEY);db.setContactPayaddr(KEY,ADDRESS);}
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
    static Object call(Object o,String n){return call(o,n,new Class<?>[]{});}
    static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(180);}
    static JSONObject object(Object... kv){JSONObject o=new JSONObject();try{for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
    static class FixtureNode extends NodeApi {
        final List<String> requests=new ArrayList<>();FixtureNode(Context c){super(c,null);}
        @Override public void cmd(String cmd,Cb cb){requests.add(cmd);if("balance".equals(cmd))cb.onResult(object("status",true,"response",new JSONArray().put(object("tokenid","0x1234","token",NAME,"confirmed","12.500"))));}
    }
    static FixtureNode freeze(MainActivity a){
        NodeApi old=(NodeApi)get(a,"node");if(old!=null)old.onDestroy();((Handler)get(a,"ui")).removeCallbacksAndMessages(null);
        FixtureNode node=new FixtureNode(a);set(a,"node",node);set(a,"myId",MINE);set(a,"myPayaddr",ADDRESS);set(a,"contactNames",null);((View)get(a,"pairingBanner")).setVisibility(View.GONE);return node;
    }
    static void push(MainActivity a,View v){call(a,"push",new Class<?>[]{View.class},v);}
    static List<TextView> texts(View root){List<TextView> out=new ArrayList<>();if(root instanceof TextView)out.add((TextView)root);if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++)out.addAll(texts(g.getChildAt(i)));}return out;}
    static TextView exact(View root,CharSequence expected){for(TextView t:texts(root))if(expected.toString().equals(t.getText().toString()))return t;throw new AssertionError("Missing label "+expected);}
    static View findDescription(View root,String description){if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View found=findDescription(group.getChildAt(i),description);if(found!=null)return found;}}return null;}
    static List<EditText> inputs(View root){List<EditText> out=new ArrayList<>();for(TextView t:texts(root))if(t instanceof EditText)out.add((EditText)t);return out;}
    static Set<AlertDialog> dialogs(MainActivity a){return (Set<AlertDialog>)get(a,"languageDialogs");}
    static AlertDialog dialog(MainActivity a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}
    void screenshot(String name)throws Exception{settle();android.graphics.Bitmap bm=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bm);java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(bm.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bm.recycle();}}
    @Test public void composeKeepsRecipientsSubjectBodyCursorAndPendingAttachment()throws Exception{
        AtomicReference<View> root=new AtomicReference<>();AtomicReference<List<EditText>> fields=new AtomicReference<>();AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(a->{node.set(freeze(a));View v=(View)call(a,"buildCompose",new Class<?>[]{String.class,String.class},KEY,SUBJECT);root.set(v);push(a,v);List<EditText> es=inputs(v);assertEquals(3,es.size());fields.set(es);es.get(2).setText(DRAFT);es.get(2).setSelection(7);set(a,"imagePickContact",KEY);set(a,"imagePickSubject",SUBJECT);});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(root.get(),((ArrayDeque<View>)get(a,"stack")).peek());assertEquals(KEY,fields.get().get(0).getText().toString());assertEquals(SUBJECT,fields.get().get(1).getText().toString());assertEquals(DRAFT,fields.get().get(2).getText().toString());assertEquals(7,fields.get().get(2).getSelectionStart());assertEquals(KEY,get(a,"imagePickContact"));assertEquals(SUBJECT,get(a,"imagePickSubject"));assertEquals(Language.msg(l10n_mail_write_hint).toString(),fields.get().get(2).getHint().toString());exact(root.get(),Language.msg(l10n_mail_new_message));exact(root.get(),Language.msg(l10n_mail_send));assertTrue(node.get().requests.isEmpty());});screenshot("mail-compose-"+code);}
        }
    }
    @Test public void contactAndBackupDialogsRetainFieldsAndCancel()throws Exception{
        AtomicReference<AlertDialog> original=new AtomicReference<>();AtomicReference<List<EditText>> fields=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(a->{freeze(a);call(a,"addContactDialog",new Class<?>[]{String.class},KEY);AlertDialog d=dialog(a);original.set(d);fields.set(inputs(d.getWindow().getDecorView()));fields.get().get(0).setText(DRAFT);fields.get().get(0).setSelection(4);});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(original.get(),dialog(a));assertEquals(DRAFT,fields.get().get(0).getText().toString());assertEquals(4,fields.get().get(0).getSelectionStart());assertEquals(KEY,fields.get().get(1).getText().toString());exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_mail_add_contact));});screenshot("mail-contact-"+code);}
            scenario.onActivity(a->{dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();});settle();
            scenario.onActivity(a->{assertTrue(dialogs(a).isEmpty());call(a,"promptBackupPass",new Class<?>[]{Uri.class,boolean.class},Uri.parse("content://public-fixture/unused"),true);original.set(dialog(a));fields.set(inputs(dialog(a).getWindow().getDecorView()));fields.get().get(0).setText("disposable-passphrase");fields.get().get(0).setSelection(5);});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(original.get(),dialog(a));assertEquals("disposable-passphrase",fields.get().get(0).getText().toString());assertEquals(5,fields.get().get(0).getSelectionStart());exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_mail_encrypt_backup));assertTrue((dialog(a).getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);});}
            scenario.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();scenario.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertNull(get(a,"identity"));});
        }
    }
    @Test public void paymentSheetKeepsRawTokenAmountAddressNoteAndDismissCleanup()throws Exception{
        AtomicReference<AlertDialog> original=new AtomicReference<>();AtomicReference<List<EditText>> fields=new AtomicReference<>();AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(a->{node.set(freeze(a));set(a,"crypto",new CryptoProvider(){public String identity(){return MINE;}public String seal(String to,byte[] data){throw new AssertionError("No signing/encryption allowed");}public Opened open(String b){throw new AssertionError("No decrypt allowed");}});call(a,"showSendFundsSheet",new Class<?>[]{String.class,String.class},KEY,SUBJECT);original.set(dialog(a));fields.set(inputs(dialog(a).getWindow().getDecorView()));assertEquals(3,fields.get().size());fields.get().get(0).setText("0.0010");fields.get().get(2).setText(DRAFT);fields.get().get(2).setSelection(6);});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertSame(original.get(),dialog(a));assertEquals("0.0010",fields.get().get(0).getText().toString());assertEquals(ADDRESS,fields.get().get(1).getText().toString());assertEquals(DRAFT,fields.get().get(2).getText().toString());assertEquals(6,fields.get().get(2).getSelectionStart());exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_mail_token_balance,NAME,"12.500"));exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_mail_send_to,NAME));assertEquals(Collections.singletonList("balance"),node.get().requests);assertEquals(true,get(a,"modalOpen"));assertSame(fields.get().get(1),get(a,"openPayAddrField"));});screenshot("mail-payment-"+code);}
            scenario.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();scenario.onActivity(a->{assertTrue(dialogs(a).isEmpty());assertEquals(false,get(a,"modalOpen"));assertNull(get(a,"openPayAddrField"));assertNull(get(a,"openPayContact"));assertEquals(Collections.singletonList("balance"),node.get().requests);});
        }
    }
    @Test public void settingsHelpIdentityAndNotificationsFollowTheSelector()throws Exception{
        AtomicReference<View> root=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(a->{freeze(a);root.set((View)call(a,"buildSettings"));push(a,root.get());});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{exact(root.get(),Language.msg(l10n_mail_settings));exact(root.get(),Language.msg(l10n_mail_display_name));exact(root.get(),Language.msg(l10n_mail_theme_hint));android.app.NotificationChannel channel=a.getSystemService(android.app.NotificationManager.class).getNotificationChannel("mail");assertEquals(Language.msg(l10n_mail_new_mail).toString(),channel.getName().toString());});screenshot("mail-settings-"+code);}
            scenario.onActivity(a->{root.set((View)call(a,"buildHelp"));push(a,root.get());});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->exact(root.get(),Language.msg(l10n_mail_help)));screenshot("mail-help-"+code);}
            scenario.onActivity(a->{root.set((View)call(a,"buildIdentity"));push(a,root.get());});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{exact(root.get(),Language.msg(l10n_mail_copy_key));exact(root.get(),Language.msg(l10n_mail_backup_identity));});}
        }
    }
    @Test public void conversationAndMenusKeepRawContentAndLocalizeDatesAndStatus()throws Exception{
        AtomicReference<View> root=new AtomicReference<>();AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(a->{node.set(freeze(a));root.set((View)call(a,"buildConversation",new Class<?>[]{String.class,String.class},KEY,SUBJECT));push(a,root.get());});settle();
            scenario.onActivity(a->{MailMessage m=new MailMessage();m.type="text";m.incoming=false;m.date=1577880123000L;m.message=DRAFT;m.status="confirmed";Object adapter=get(a,"convAdapter");call(adapter,"setData",new Class<?>[]{List.class},Collections.singletonList(m));});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{exact(root.get(),DRAFT);exact(root.get(),SUBJECT);exact(root.get(),Language.msg(l10n_mail_confirmed));exact(root.get(),Language.stamp(1577880123000L,"yMMMd"));assertEquals(Language.msg(l10n_mail_reply).toString(),inputs(root.get()).get(0).getHint().toString());assertTrue(node.get().requests.isEmpty());});screenshot("mail-conversation-"+code);}
            scenario.onActivity(a->{View anchor=findDescription(root.get(),Language.msg(l10n_mail_more).toString());assertNotNull(anchor);anchor.performClick();});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{Map<PopupMenu,?> menus=(Map<PopupMenu,?>)get(a,"languageMenus");assertEquals(1,menus.size());PopupMenu menu=menus.keySet().iterator().next();assertEquals(Language.msg(l10n_mail_view_contact_key).toString(),menu.getMenu().findItem(1).getTitle().toString());assertEquals(Language.msg(l10n_mail_delete_thread).toString(),menu.getMenu().findItem(2).getTitle().toString());assertTrue(node.get().requests.isEmpty());});screenshot("mail-menu-"+code);}
        }
    }
    @Test public void seedDialogStaysSecureAndErrorsLeaveUnknownDetailsLiteral()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(a->{freeze(a);call(a,"askForSeed");assertTrue((dialog(a).getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertTrue((dialog(a).getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);assertTrue(inputs(dialog(a).getWindow().getDecorView()).get(0).getText().toString().isEmpty());exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_mail_create_identity));assertEquals(Language.msg(l10n_vestr_transport_stopped).toString(),MailMessages.message("The embedded node is not running. No command was sent.").toString());assertEquals(DRAFT,MailMessages.message(DRAFT));});}
            scenario.onActivity(a->dialog(a).cancel());
        }
    }
    @Test public void darkStatusAndConfirmationKeepTheirAmountsAndSelectedPalette()throws Exception {
        try(CommsDb db=new CommsDb(context)){db.setMeta("theme","dark");}
        AtomicReference<View> root=new AtomicReference<>();AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();scenario.onActivity(a->{node.set(freeze(a));LinearLayout column=new LinearLayout(a);column.setOrientation(LinearLayout.VERTICAL);for(String status:new String[]{"posting","failed","confirmed","sent"}){MailMessage m=new MailMessage();m.status=status;m.sentblock=123456;column.addView((View)call(a,"statusLine",new Class<?>[]{MailMessage.class,boolean.class},m,false));}root.set(column);push(a,column);});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertFalse(Design.LIGHT);exact(root.get(),Language.msg(l10n_mail_posting));exact(root.get(),Language.msg(l10n_mail_failed_chain));exact(root.get(),Language.join(Language.msg(l10n_mail_confirmed)," · ",Language.msg(l10n_mail_block,123456L)));assertTrue(node.get().requests.isEmpty());});}
            scenario.onActivity(a->call(a,"trySendFunds",new Class<?>[]{String.class,String.class,String.class,String.class,String.class,String.class,String.class,String.class},KEY,SUBJECT,"0x1234",NAME,"12.500","0.0010",ADDRESS,DRAFT));
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertFalse(Design.LIGHT);exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_mail_confirm_send,"0.0010",NAME));exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_mail_confirm_funds,NAME,ADDRESS));assertTrue(node.get().requests.isEmpty());});screenshot("mail-confirm-dark-"+code);}
            scenario.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        }
    }

}
