package com.eurobuddha.review;

import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import com.eurobuddha.minimaapi.MinimaAPI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real standalone UID/process, real SDK and broadcasts. Only read commands on a disposable node. */
public class ExternalCompatibilityTest {
    @Test public void registrationAndReadPermissions() throws Exception {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("pandamoniumDisposable"));
        assertTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu"));
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(InstrumentationRegistry.getArguments().getString("companionPackage", "com.eurobuddha.mail"), context.getPackageName());
        boolean enabled = "true".equals(InstrumentationRegistry.getArguments().getString("enabled"));
        boolean admin = "true".equals(InstrumentationRegistry.getArguments().getString("admin"));
        CountDownLatch registration = new CountDownLatch(1);
        AtomicReference<JSONObject> registered = new AtomicReference<>();
        AtomicReference<MinimaAPI> client = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> client.set(new MinimaAPI(context,
                result -> { registered.set(result); registration.countDown(); })));
        try {
            assertTrue("External registration reply", registration.await(10, TimeUnit.SECONDS));
            assertTrue(registered.get().toString(), registered.get().getBoolean("status"));
            assertEquals(enabled, registered.get().getBoolean("enabled"));
            assertEquals(admin, registered.get().getBoolean("admin"));
            CountDownLatch command = new CountDownLatch(1);
            AtomicReference<JSONObject> reply = new AtomicReference<>();
            client.get().Command("status", result -> { reply.set(result); command.countDown(); });
            assertTrue(command.await(15, TimeUnit.SECONDS));
            assertEquals(reply.get().toString(), enabled, reply.get().getBoolean("status"));
            if (enabled) assertNotNull(reply.get().getJSONObject("response"));
            CountDownLatch file = new CountDownLatch(1);
            client.get().FileCommand("list", "/", null, null, result -> { reply.set(result); file.countDown(); });
            assertTrue(file.await(15, TimeUnit.SECONDS));
            assertEquals(reply.get().toString(), enabled && admin, reply.get().getBoolean("status"));
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> client.get().onDestroy()); }
    }
}
