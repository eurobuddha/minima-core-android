package com.eurobuddha.atomix.swap;

import com.eurobuddha.atomix.TradingContext;
import com.eurobuddha.atomix.integratedcomms.*;
import com.goterl.lazysodium.LazySodium;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Synthetic signed-coin fixtures and delayed callbacks; no live node, keys or transfers. */
public class OtcBoardContextTest {
    private TradingContext saved;
    @Before public void saveMarket() { saved = TradingContext.active(); }
    @After public void restoreMarket() { TradingContext.setActive(saved, null); }

    private static final class Scan {
        final NodeApi node = mock(NodeApi.class);
        final LazySodium sodium = mock(LazySodium.class);
        final List<String> commands = new ArrayList<>();
        final List<NodeApi.Cb> callbacks = new ArrayList<>();
        Map<String, OtcOffer> offers;
        String error;
        int deliveries;
        Scan(TradingContext market) {
            TradingContext.setActive(market, null);
            when(sodium.cryptoSignVerifyDetached(any(byte[].class), any(byte[].class), anyInt(), any(byte[].class))).thenReturn(true);
            doAnswer(i -> { commands.add(i.getArgument(0)); callbacks.add(i.getArgument(1)); return null; }).when(node).cmd(anyString(), any());
            OtcBook.scan(node, sodium, b -> { offers = b; deliveries++; }, e -> error = e);
        }
        void registered(boolean success) {
            if (success) callbacks.get(0).onResult(new JSONObject());
            else callbacks.get(0).onError("notification unavailable");
        }
        void received(JSONObject... coins) throws Exception {
            callbacks.get(1).onResult(new JSONObject().put("response", new JSONArray(Arrays.asList(coins))));
        }
        void rejected() {
            assertEquals(OtcController.MARKET_ERROR, error);
            assertNull(offers); assertEquals(0, deliveries);
        }
    }
    private static JSONObject coin(long timestamp, boolean enabled) throws Exception {
        OtcOffer offer = new OtcOffer();
        offer.enable = enabled; offer.sellSize = enabled ? 25 : 0; offer.buySize = enabled ? 30 : 0;
        offer.commsPublicId = "peer"; offer.minimaPublicKey = "0x1234"; offer.ethAddress = "0x" + "12".repeat(20); offer.ts = timestamp;
        return new JSONObject().put("coinid", "fixture-" + timestamp).put("state", new JSONObject()
            .put("99", Hex.to(offer.toJson().toString().getBytes(StandardCharsets.UTF_8)))
            .put("1", "0x" + "12".repeat(32)).put("2", "0x" + "34".repeat(64)));
    }
    private static OtcOffer scanned(TradingContext market) throws Exception {
        Scan s = new Scan(market); s.registered(true); s.received(coin(1, true));
        assertEquals(1, s.deliveries); assertNull(s.error);
        return s.offers.values().iterator().next();
    }

    @Test public void bothMarketsCaptureBothCommandsAndStampVerifiedOffers() throws Exception {
        for (TradingContext market : TradingContext.values()) for (boolean notification : new boolean[]{true, false}) {
            Scan s = new Scan(market); s.registered(notification); s.received(coin(1, true));
            assertEquals(Arrays.asList("coinnotify action:add address:" + market.otcBoardAddr,
                "coins simplestate:true order:desc depth:" + OtcBook.SCAN_DEPTH + " address:" + market.otcBoardAddr), s.commands);
            assertEquals(1, s.deliveries); assertNull(s.error);
            assertSame(market, s.offers.values().iterator().next().market);
        }
    }
    @Test public void switchBeforeNotificationReplyCannotStartAnotherBoardRead() {
        for (TradingContext market : TradingContext.values()) for (boolean notification : new boolean[]{true, false}) {
            Scan s = new Scan(market); TradingContext.setActive(market.other(), null); s.registered(notification);
            assertEquals(1, s.commands.size()); s.rejected(); verifyNoInteractions(s.sodium);
        }
    }
    @Test public void switchWhileCoinReadIsPendingCannotDeliverOffers() throws Exception {
        for (TradingContext market : TradingContext.values()) {
            Scan s = new Scan(market); s.registered(true); TradingContext.setActive(market.other(), null); s.received(coin(1, true));
            s.rejected(); verifyNoInteractions(s.sodium);
        }
    }
    @Test public void switchDuringVerificationCannotDeliverOffers() throws Exception {
        for (TradingContext market : TradingContext.values()) {
            Scan s = new Scan(market); s.registered(true);
            when(s.sodium.cryptoSignVerifyDetached(any(byte[].class), any(byte[].class), anyInt(), any(byte[].class)))
                .thenAnswer(i -> { TradingContext.setActive(market.other(), null); return true; });
            s.received(coin(1, true)); s.rejected();
        }
    }
    @Test public void signedWireAndPeerParsingCannotSupplyLocalProvenance() throws Exception {
        for (TradingContext market : TradingContext.values()) {
            OtcOffer offer = scanned(market); JSONObject wire = offer.toJson();
            Set<String> keys = new HashSet<>(); wire.keys().forEachRemaining(keys::add);
            assertEquals(new HashSet<>(Arrays.asList("sellsz", "buysz", "en", "mpk", "eth", "cid", "ts")), keys);
            wire.put("market", market.key); assertNull(OtcOffer.fromJson(wire).market);
            assertNull(OtcBook.verifyCoin(new Scan(market).sodium, coin(1, true)).market);
        }
    }
    @Test public void signatureFilteringFreshnessAndWithdrawalRemainIntact() throws Exception {
        Scan s = new Scan(TradingContext.MINIMA); s.registered(true);
        JSONObject malformed = coin(100, true); malformed.getJSONObject("state").remove("2");
        s.received(coin(2, false), coin(1, true), malformed);
        assertEquals(1, s.offers.size()); OtcOffer tombstone = s.offers.values().iterator().next();
        assertEquals(2, tombstone.ts); assertFalse(tombstone.hasLiquidity()); assertSame(TradingContext.MINIMA, tombstone.market);
        s = new Scan(TradingContext.MXUSDT); s.registered(true);
        when(s.sodium.cryptoSignVerifyDetached(any(byte[].class), any(byte[].class), anyInt(), any(byte[].class))).thenReturn(false);
        s.received(coin(1, true)); assertTrue(s.offers.isEmpty());
    }
    @Test public void readFailureIsReportedWithoutDeliveringOffers() {
        Scan s = new Scan(TradingContext.MINIMA); s.registered(true); s.callbacks.get(1).onError("read failed");
        assertEquals("read failed", s.error); assertNull(s.offers); assertEquals(0, s.deliveries);
    }
    private static OtcController controller(NodeApi node, OtcDb db, SwapEngine engine, CryptoProvider crypto) {
        CommsIdentity identity = mock(CommsIdentity.class); when(identity.publicId()).thenReturn("me");
        OtcController c = new OtcController(node, db, engine, mock(OtcController.Ui.class));
        c.setSelf(crypto, identity, "0x1234", "0x" + "56".repeat(20)); return c;
    }
    @Test public void wrongOrUnknownOfferMarketCannotPersistOrSend() throws Exception {
        for (TradingContext market : TradingContext.values()) {
            OtcOffer wrong = scanned(market.other());
            TradingContext.setActive(market, null);
            NodeApi node = mock(NodeApi.class); OtcDb db = mock(OtcDb.class); SwapEngine engine = mock(SwapEngine.class); CryptoProvider crypto = mock(CryptoProvider.class);
            OtcController c = controller(node, db, engine, crypto);
            OtcOffer unknown = OtcOffer.fromJson(wrong.toJson().put("market", market.key));
            for (OtcOffer offer : new OtcOffer[]{wrong, unknown}) {
                OtcController.SendResult result = mock(OtcController.SendResult.class);
                c.propose(offer, "SELL", "2", "3", result); verify(result).err(OtcController.MARKET_ERROR);
            }
            OtcOffer matching = scanned(market); TradingContext.setActive(market.other(), null);
            OtcController.SendResult result = mock(OtcController.SendResult.class);
            c.propose(matching, "SELL", "2", "3", result); verify(result).err(OtcController.MARKET_ERROR);
            verifyNoInteractions(node, db, engine, crypto);
        }
    }
    @Test public void verifiedMatchingOffersKeepTheExistingProposalPath() throws Exception {
        for (TradingContext market : TradingContext.values()) {
            OtcOffer offer = scanned(market);
            NodeApi node = mock(NodeApi.class); OtcDb db = mock(OtcDb.class); CryptoProvider crypto = mock(CryptoProvider.class);
            when(crypto.seal(eq("peer"), any())).thenReturn("abcd");
            when(db.createDeal(any())).thenAnswer(i -> {
                OtcDb.Deal stored = i.getArgument(0); when(db.getDeal(stored.ref)).thenReturn(stored.copy()); return true;
            });
            OtcController c = controller(node, db, mock(SwapEngine.class), crypto);
            try (MockedStatic<CommsTransport> transport = mockStatic(CommsTransport.class)) {
                c.propose(offer, "SELL", "2", "3", mock(OtcController.SendResult.class));
                ArgumentCaptor<OtcDb.Deal> captured = ArgumentCaptor.forClass(OtcDb.Deal.class);
                verify(db).createDeal(captured.capture()); OtcDb.Deal deal = captured.getValue();
                assertEquals(market.key, deal.currency); assertEquals("2", deal.amount); assertEquals("3", deal.price); assertEquals("peer", deal.peerCommsId);
                transport.verify(() -> CommsTransport.postBlob(eq(node), eq(market.otcChatAddr), eq(CommsTransport.MESSAGE_AMOUNT), eq(CommsTransport.NATIVE), eq("abcd"), isNull(), any()));
            }
        }
    }
}
