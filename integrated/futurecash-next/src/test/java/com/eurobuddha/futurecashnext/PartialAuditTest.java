package com.eurobuddha.futurecashnext;

import org.json.JSONArray;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PartialAuditTest {
    @Test public void partialAuditPreservesPreviouslyKnownRiskFromEitherFamily() throws Exception {
        Cfg cfg = mock(Cfg.class);
        when(cfg.get(Cfg.ATRISK_ADDRS, "[]")).thenReturn("[\"0xAABB\"]");
        new Audit(cfg, mock(Node.class)).store(Collections.singletonList("0xCCDD"),
                new JSONArray(), false, null, 0);
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(cfg).set(eq(Cfg.ATRISK_ADDRS), saved.capture());
        JSONArray values = new JSONArray(saved.getValue());
        assertEquals(2, values.length());
        assertTrue(saved.getValue().contains("0xAABB"));
        assertTrue(saved.getValue().contains("0xCCDD"));
        verify(cfg).setBool(Cfg.AUDIT_OK, false);
        verify(cfg, never()).set(eq(Cfg.SAFE_REUSED), anyString());
    }
    @Test public void completeAuditCanReplacePreviouslyKnownRisk() {
        Cfg cfg = mock(Cfg.class);
        new Audit(cfg, mock(Node.class)).store(Collections.emptyList(), new JSONArray(), true, null, 0);
        verify(cfg).set(Cfg.ATRISK_ADDRS, "[]");
        verify(cfg).setBool(Cfg.AUDIT_OK, true);
    }
}
