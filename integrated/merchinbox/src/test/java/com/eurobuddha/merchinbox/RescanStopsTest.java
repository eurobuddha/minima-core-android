package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import com.eurobuddha.merchinbox.integratedcomms.CommsScanner;
import com.eurobuddha.merchinbox.integratedcomms.CryptoProvider;
import com.eurobuddha.merchinbox.integratedcomms.NodeApi;

import org.junit.Test;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

/** 2026-11-22 (R5): a scan request dropped while a pass runs is tried again every 4.5 s until a pass starts, only while
 *  the Inbox is visible: onStop removes it, as it stops the block events. */
public class RescanStopsTest extends DrainTest {
    @Test public void aDroppedScanIsNotTriedAgainAfterOnStop() throws Exception {
        NodeApi silent = org.mockito.Mockito.mock(NodeApi.class);   // never answers: the first pass keeps running
        CryptoProvider crypto = org.mockito.Mockito.mock(CryptoProvider.class);
        set("crypto", crypto);
        set("orderScanner", new CommsScanner(silent, crypto, db, "0x4D494E494D45524348", (id, o, c) -> false, (ok, n) -> {}));
        set("notifyReceiver", org.mockito.Mockito.mock(com.eurobuddha.minimaapi.direct.DirectNodeEvents.Listener.class));
        call("requestScan", new Class<?>[]{});   // starts the pass
        call("requestScan", new Class<?>[]{});   // dropped: tried again in 4.5 s
        Field posted = MainActivity.class.getDeclaredField("rescanPosted"); posted.setAccessible(true);
        assertTrue(posted.getBoolean(activity));
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS);
        assertTrue("tried again while visible (the pass still runs, so posted again)", posted.getBoolean(activity));
        java.lang.reflect.Method stop = MainActivity.class.getDeclaredMethod("onStop"); stop.setAccessible(true);
        try { stop.invoke(activity); }   // the fixture never started the Activity: super.onStop() fails after the Inbox's own lines ran
        catch (java.lang.reflect.InvocationTargetException e) { assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof NullPointerException); }
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
        assertFalse("never tried again once stopped", posted.getBoolean(activity));
    }
}
