    // Capture the same market/generation boundary used by the Minima responder before entering the IO queue.
    private java.util.function.BooleanSupplier ethCounterLegGuard(JSONObject coin, String hash, String reqTokenAddr,
            com.eurobuddha.atomix.TradingContext market, long generation) {
        final EthNet network = net;
        final EthRpc connection = rpc;
        final String ownerKey = myMinimaPk, ownerEth = myEth();
        return () -> {
            if (io.isShutdown() || halted() || network != net || connection != rpc
                    || generation != PublishGate.gen() || market != com.eurobuddha.atomix.TradingContext.active()
                    || !market.tokenId.equalsIgnoreCase(minima.activeToken())
                    || !market.tokenId.equalsIgnoreCase(coin.optString("tokenid", "0x00"))
                    || !java.util.Objects.equals(ownerKey, myMinimaPk) || !java.util.Objects.equals(ownerEth, myEth())) return false;
            if ("TRUE".equals(MinimaHtlc.stateAt(coin, 7))) {
                OtcDb.Deal deal = otcLpDeal(hash);
                return deal != null && otcVerifySell(coin, deal, reqTokenAddr);
            }
            return myOrder != null && myOrder.market() == market && acceptTakerSellMinima(coin, reqTokenAddr);
        };
    }

