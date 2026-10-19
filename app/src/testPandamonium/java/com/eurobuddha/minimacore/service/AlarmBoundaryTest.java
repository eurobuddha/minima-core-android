package com.eurobuddha.minimacore.service;

import android.app.AlarmManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowPendingIntent;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real merged manifest and scheduler; framework service launch and node health are isolated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class)
public class AlarmBoundaryTest {
    @Test public void internalAlarmIsNotAnExternalServiceStartupEntryPoint() throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        assertFalse(app.getPackageManager().getReceiverInfo(new ComponentName(app,Alarm.class),0).exported);
    }

    @Test public void hourlyAndResyncAlarmsRemainExplicitImmutableAndIndependentlyCancellable() throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        ShadowAlarmManager.setAutoSchedule(false);
        ShadowAlarmManager manager=shadowOf((AlarmManager)app.getSystemService(Context.ALARM_SERVICE));
        Alarm receiver=new Alarm();
        receiver.setAlarm(app);receiver.setAlarm(app);receiver.setOnce(app,30_000);
        assertEquals("Repeated hourly scheduling must not duplicate work",2,manager.getScheduledAlarms().size());
        for(ShadowAlarmManager.ScheduledAlarm scheduled:manager.getScheduledAlarms()) {
            ShadowPendingIntent operation=shadowOf(scheduled.operation);
            assertTrue(operation.isBroadcast());assertTrue(operation.isImmutable());
            assertEquals(new ComponentName(app,Alarm.class),operation.getSavedIntent().getComponent());
            assertEquals(app.getPackageName(),scheduled.operation.getCreatorPackage());
            if(operation.getRequestCode()==0)assertEquals(AlarmManager.INTERVAL_HOUR,scheduled.getIntervalMs());
            else {assertEquals(1,operation.getRequestCode());assertEquals(0,scheduled.getIntervalMs());assertTrue(scheduled.isAllowWhileIdle());}
        }
        receiver.cancelAlarm(app);
        assertEquals("Cancelling periodic wakeups must retain the one-shot resync restart",1,manager.getScheduledAlarms().size());
        ShadowAlarmManager.ScheduledAlarm restart=manager.getScheduledAlarms().get(0);
        assertEquals(1,shadowOf(restart.operation).getRequestCode());
        try(MockedStatic<NodeHealthMonitor> health=mockStatic(NodeHealthMonitor.class)) {
            restart.operation.send();
            shadowOf(android.os.Looper.getMainLooper()).idle();
            Intent service=shadowOf(app).getNextStartedService();
            assertNotNull("The internal PendingIntent must still reach the private receiver",service);
            assertEquals(new ComponentName(app,MinimaService.class),service.getComponent());
            health.verify(()->NodeHealthMonitor.checkAsync(any(Context.class)));
        }
    }
}
