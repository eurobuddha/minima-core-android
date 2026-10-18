package com.eurobuddha.statenft;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.NotificationLabels;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Real service presentation paths with node, storage and Android boundaries isolated. */
public class MintServiceLanguageTest {
    private static void set(MintService service, String name, Object value) throws Exception {
        Field field=MintService.class.getDeclaredField(name); field.setAccessible(true); field.set(service,value);
    }
    private static Object call(MintService service,String name) throws Exception {
        Method method=MintService.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(service);
    }
    @Test public void refreshRetainsProgressAndUserNameWithoutTickingEitherEngine() throws Exception {
        MintService service=mock(MintService.class,CALLS_REAL_METHODS);
        NotificationManager manager=mock(NotificationManager.class);
        doReturn(manager).when(service).getSystemService(NotificationManager.class);
        set(service,"foregroundActive",true);
        NodeApi node=mock(NodeApi.class); set(service,"node",node);
        JSONObject row=new JSONObject().put("phase","STAMP").put("name","Mint engine 中文 {phase}")
                .put("minted",7).put("size",12);
        String before=row.toString();
        Notification notification=mock(Notification.class);
        try (MockedStatic<Language> language=mockStatic(Language.class);
             MockedStatic<NotificationLabels> labels=mockStatic(NotificationLabels.class);
             MockedStatic<LocalStore> store=mockStatic(LocalStore.class);
             MockedStatic<MintEngine> mint=mockStatic(MintEngine.class);
             MockedStatic<AirdropEngine> airdrop=mockStatic(AirdropEngine.class);
             MockedStatic<PendingIntent> pending=mockStatic(PendingIntent.class);
             MockedConstruction<Intent> intents=mockConstruction(Intent.class);
             MockedConstruction<Notification.Builder> builders=mockConstruction(Notification.Builder.class,
                     withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.build()).thenReturn(notification))) {
            store.when(()->LocalStore.load(service)).thenReturn(new JSONArray().put(row));
            for (String code:new String[]{"en","zh-Hans","ru","uk"}) {
                language.when(()->Language.text(service,l10n_creative_phase_stamp)).thenReturn(code+" phase");
                language.when(()->Language.text(service,l10n_nft_collection)).thenReturn(code+" collection");
                language.when(()->Language.text(service,l10n_creative_mint_title)).thenReturn(code+" title");
                language.when(()->Language.text(service,l10n_creative_sealing,"Mint engine 中文 {phase}",7,12,code+" phase"))
                        .thenReturn(code+" Mint engine 中文 {phase} 7/12");
                call(service,"refreshLanguageNotification");
                Notification.Builder builder=builders.constructed().get(builders.constructed().size()-1);
                verify(builder).setContentTitle(code+" title");
                verify(builder).setContentText(code+" Mint engine 中文 {phase} 7/12");
                verify(builder).setOnlyAlertOnce(true);
            }
            verify(manager,times(4)).notify(7601,notification);
            labels.verify(()->NotificationLabels.update(service),times(4));
            mint.verifyNoInteractions(); airdrop.verifyNoInteractions(); verifyNoInteractions(node);
            verify(service,never()).startForeground(anyInt(),any());
            assertEquals(before,row.toString());
        }
    }
    @Test public void destroyedServiceRemovesListenerAndIgnoresLateNotificationCallbacks() throws Exception {
        MintService service=mock(MintService.class,CALLS_REAL_METHODS);
        Runnable callback=mock(Runnable.class); Handler handler=mock(Handler.class); NodeApi node=mock(NodeApi.class);
        set(service,"foregroundActive",true);set(service,"languageChanged",callback);set(service,"handler",handler);set(service,"node",node);
        try (MockedStatic<Language> language=mockStatic(Language.class);
             MockedStatic<NotificationLabels> labels=mockStatic(NotificationLabels.class);
             MockedStatic<LocalStore> store=mockStatic(LocalStore.class)) {
            service.onDestroy();
            language.verify(()->Language.removeListener(callback));
            verify(node).onDestroy();
            call(service,"refreshLanguageNotification"); call(service,"updateNotification");
            labels.verifyNoInteractions();store.verifyNoInteractions();
            verify(service,never()).getSystemService(NotificationManager.class);
        }
    }
}
