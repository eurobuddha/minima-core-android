    // The existing CP maps reserve coins; this identity additionally owns delayed callbacks.
    // All accesses use their existing CP_LOCKING monitor, shared by Activity and Service engines.
    private static final Map<String, CounterLeg> cpAttempts = new HashMap<>();
    private final class CounterLeg {
        final EthHtlc.Contract contract;
        final String hash;
        final com.eurobuddha.atomix.TradingContext market;
        final long generation;
        final EthNet network = net;
        final EthRpc connection = rpc;
        CounterLeg(EthHtlc.Contract contract, com.eurobuddha.atomix.TradingContext market, long generation) {
            this.contract = contract; hash = contract.hashlock; this.market = market; this.generation = generation;
        }
        boolean owned() { synchronized (CP_LOCKING) { return cpAttempts.get(hash) == this; } }
        boolean current() {
            synchronized (CP_LOCKING) {
                return owned() && CP_LOCKING.containsKey(hash) && cpInFlight.containsKey(hash)
                        && !io.isShutdown() && !halted() && network == net && connection == rpc
                        && generation == PublishGate.gen() && market == com.eurobuddha.atomix.TradingContext.active()
                        && market.tokenId.equalsIgnoreCase(minima.activeToken());
            }
        }
        boolean authorized() { return current() && terms(); }
        // Reuse the original acceptance boundaries immediately before creating a new liability.
        boolean terms() {
            if (contract.otc) {
                OtcDb.Deal deal = otcLpDeal(hash);
                return deal != null && otcVerifyBuy(contract, deal);
            }
            return myOrder != null && myOrder.market() == market && acceptTakerBuyMinima(contract);
        }
        void release() {
            synchronized (CP_LOCKING) {
                if (!owned()) return;
                releaseCpLeg(hash); inflight.remove("cpMin:" + hash);
            }
        }
    }

