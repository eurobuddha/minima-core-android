package com.eurobuddha.futurecashnext;

import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AuditCacheVersionTest {
    private Cfg fresh(String fingerprint) {
        Cfg cfg = mock(Cfg.class);
        when(cfg.is(Cfg.AUDIT_OK, false)).thenReturn(true);
        when(cfg.getLong(Cfg.AUDIT_AT)).thenReturn(System.currentTimeMillis());
        when(cfg.get(Cfg.AUDIT_USES, "")).thenReturn(fingerprint);
        when(cfg.walletAuditKeys()).thenReturn(new org.json.JSONArray());
        when(cfg.get(Cfg.AUDIT_WALLET_USES, "")).thenReturn(Audit.usesFingerprint(new org.json.JSONArray()));
        return cfg;
    }
    @Test public void oldAuditCannotAuthorizeEvenInsideFreshnessWindow() {
        Node node = mock(Node.class);
        Audit.Verdict verdict = new Audit(fresh("0XABC:10"), node).usable();
        assertFalse(verdict.ok);
        assertEquals(Audit.Mode.STALE, verdict.mode);
        verifyNoInteractions(node);
    }
    @Test public void completedDualKeyAuditRetainsNormalFreshnessWindow() {
        Node node = mock(Node.class);
        Audit.Verdict verdict = new Audit(fresh(Audit.FINGERPRINT_VERSION + "0XABC:10:64:3:262144"), node).usable();
        assertTrue(verdict.ok);
        assertEquals(Audit.Mode.FRESH, verdict.mode);
        verifyNoInteractions(node);
    }
}
