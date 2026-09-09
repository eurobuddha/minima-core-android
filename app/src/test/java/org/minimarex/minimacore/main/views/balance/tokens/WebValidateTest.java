package org.minimarex.minimacore.main.views.balance.tokens;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class WebValidateTest {
    @Test public void distinguishesNetworkFailureFromNonMatchingDocument() {
        assertEquals(WebValidate.Result.UNAVAILABLE, WebValidate.verify(null, "0xabc123"));
        assertEquals(WebValidate.Result.NOT_VERIFIED, WebValidate.verify(new byte[0], "0xabc123"));
        assertEquals(WebValidate.Result.VERIFIED, WebValidate.verify("token: 0xABC123".getBytes(StandardCharsets.UTF_8), "0xabc123"));
        assertEquals(WebValidate.Result.NOT_VERIFIED, WebValidate.verify("anything".getBytes(StandardCharsets.UTF_8), "0x"));
    }
}
