package com.eurobuddha.statenft;

import android.widget.EditText;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class HostingSecretFieldTest {
    @Test public void encryptionFailurePreservesOldSecretAndTypedFieldThroughAllLanguages() throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        EditText input=new EditText(activity);Hosting.Profile profile=Hosting.Profile.fresh("sftp");JSONObject cfg=profile.cfg();cfg.put("password","gcm:old");
        Method bind=MainActivity.class.getDeclaredMethod("bindHostingSecret",EditText.class,JSONObject.class,String.class,boolean.class,boolean.class,boolean.class);bind.setAccessible(true);
        Method save=MainActivity.class.getDeclaredMethod("hostingSecretsReady",Hosting.Profile.class);save.setAccessible(true);
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)) {
            crypt.when(()->Crypt.decrypt("gcm:old")).thenReturn("old secret");
            crypt.when(()->Crypt.encrypt("new secret")).thenThrow(new Crypt.Unavailable());
            bind.invoke(activity,input,cfg,"password",true,false,false);
            input.setText("new secret");input.setSelection(3);
            assertEquals(false,save.invoke(activity,profile));assertEquals("gcm:old",cfg.getString("password"));
            for(String code:new String[]{"zh-Hans","ru","uk","en"}) {
                Language.select(activity,code);assertEquals("new secret",input.getText().toString());assertEquals(3,input.getSelectionStart());
                assertEquals(Language.text(activity,l10n_creative_secret_not_saved),input.getError().toString());
            }
            EditText reopened=new EditText(activity);bind.invoke(activity,reopened,cfg,"password",false,false,true);
            assertEquals("new secret",reopened.getText().toString());assertNotNull(reopened.getError());
            crypt.when(()->Crypt.encrypt("new secret")).thenReturn("gcm:new");
            assertEquals(true,save.invoke(activity,profile));assertEquals("gcm:new",cfg.getString("password"));assertNull(input.getError());assertNull(reopened.getError());
        } finally {
            
            Language.select(RuntimeEnvironment.getApplication(),"en");
        }
    }
    @Test public void unreadableExistingCredentialShowsRecoveryWithoutReplacingIt() throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        EditText input=new EditText(activity);JSONObject cfg=new JSONObject().put("password","gcm:unreadable");
        Method bind=MainActivity.class.getDeclaredMethod("bindHostingSecret",EditText.class,JSONObject.class,String.class,boolean.class,boolean.class,boolean.class);bind.setAccessible(true);
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)) {
            crypt.when(()->Crypt.decrypt("gcm:unreadable")).thenThrow(new Crypt.Unavailable());
            bind.invoke(activity,input,cfg,"password",true,false,false);
            assertEquals("gcm:unreadable",cfg.getString("password"));assertEquals("",input.getText().toString());assertNotNull(input.getError());
            crypt.verify(()->Crypt.encrypt(anyString()),never());
        } finally {  }
    }
}
