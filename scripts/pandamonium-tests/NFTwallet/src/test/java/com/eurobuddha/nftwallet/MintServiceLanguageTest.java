package com.eurobuddha.nftwallet;

import android.app.Notification;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Refresh real presentation code with no node, wallet, collection or service startup. */
public class MintServiceLanguageTest {
    private static void set(MintService service, String name, Object value) throws Exception {
        Field field = MintService.class.getDeclaredField(name); field.setAccessible(true); field.set(service, value);
    }
    private static Object get(MintService service, String name) throws Exception {
        Field field = MintService.class.getDeclaredField(name); field.setAccessible(true); return field.get(service);
    }
    private static void refresh(MintService service) throws Exception {
        Method method = MintService.class.getDeclaredMethod("refreshLanguageNotification"); method.setAccessible(true); method.invoke(service);
    }
    @Test public void refreshPreservesPausedStatusAndDoesNotAdvanceMinting() throws Exception {
        MintService service = mock(MintService.class, CALLS_REAL_METHODS);
        NotificationManager manager = mock(NotificationManager.class);
        Notification notification = mock(Notification.class);
        doReturn(manager).when(service).getSystemService(NotificationManager.class);
        String paused = "Paused — an item still needs its image";
        set(service, "foregroundActive", true); set(service, "fgText", paused);
        try (MockedStatic<Notifier> notifier = mockStatic(Notifier.class);
             MockedStatic<MintDriver> driver = mockStatic(MintDriver.class);
             MockedStatic<MintMessages> messages = mockStatic(MintMessages.class);
             MockedConstruction<NotificationCompat.Builder> builders = mockConstruction(NotificationCompat.Builder.class,
                     withSettings().defaultAnswer(RETURNS_SELF), (builder, context) -> when(builder.build()).thenReturn(notification))) {
            messages.when(() -> MintMessages.message(anyString())).thenAnswer(call -> call.getArgument(0));
            refresh(service);
            assertEquals(1, builders.constructed().size());
            verify(builders.constructed().get(0)).setContentText(paused);
            verify(manager).notify(7501, notification);
            assertEquals(paused, get(service, "fgText"));
            driver.verify(() -> MintDriver.activeProgress(service));
            driver.verifyNoMoreInteractions();
        }
    }
    @Test public void stoppedServiceCannotRepostOrReadMintState() throws Exception {
        MintService service = mock(MintService.class, CALLS_REAL_METHODS);
        set(service, "foregroundActive", false);
        try (MockedStatic<Notifier> notifier = mockStatic(Notifier.class);
             MockedStatic<MintDriver> driver = mockStatic(MintDriver.class)) {
            refresh(service);
            driver.verifyNoInteractions();
            verify(service, never()).getSystemService(NotificationManager.class);
        }
    }
}
