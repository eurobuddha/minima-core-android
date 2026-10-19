package com.eurobuddha.futurecashnext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Runs the actual activity audit callback without a node, network or signing. */
public class AuditCompletionThreadingTest {
    static void set(Object target,String name,Object value) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    static final class Fixture {
        final MainActivity activity=mock(MainActivity.class,CALLS_REAL_METHODS);
        final Guardian guardian=mock(Guardian.class);
        final AuditRunner runner=mock(AuditRunner.class);
        final ExecutorService worker=mock(ExecutorService.class);
        final List<Runnable> queue=new ArrayList<>();
        final AuditRunner.Cb callback;
        Fixture() throws Exception {
            set(activity,"guardian",guardian);set(activity,"auditRunner",runner);
            set(activity,"cfg",mock(Cfg.class));set(activity,"worker",worker);
            doNothing().when(activity).runOnUiThread(any());
            doAnswer(i->{queue.add(i.getArgument(0));return null;}).when(worker).execute(any());
            Method start=MainActivity.class.getDeclaredMethod("maybeAudit",boolean.class);
            start.setAccessible(true);start.invoke(activity,true);
            assertEquals(1,queue.size());queue.remove(0).run();
            ArgumentCaptor<AuditRunner.Cb> cb=ArgumentCaptor.forClass(AuditRunner.Cb.class);
            verify(runner).run(cb.capture());callback=cb.getValue();
        }
    }
    @Test public void completedAuditOnlyQueuesRefreshAndNeverReadsNodeOnCallbackThread() throws Exception {
        Fixture f=new Fixture();f.callback.onDone(null,100);
        verifyNoInteractions(f.guardian);assertEquals(1,f.queue.size());
    }
    @Test public void failedAuditOnlyQueuesRefresh() throws Exception {
        Fixture f=new Fixture();f.callback.onError("offline");
        verifyNoInteractions(f.guardian);assertEquals(1,f.queue.size());
    }
    @Test public void lateAuditCompletionAfterWorkerShutdownCannotReadNodeOrQueueWork() throws Exception {
        Fixture f=new Fixture();when(f.worker.isShutdown()).thenReturn(true);
        f.callback.onDone(null,100);
        verifyNoInteractions(f.guardian);assertTrue(f.queue.isEmpty());
    }
}
