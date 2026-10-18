    /** Reuse SendView's literal selection comparison across confirmation and async lookup. */
    private final class Review {
        final List<Coin> coins = new ArrayList<>();
        final String[] snapshot;
        final String tokenid;
        private boolean accepted;
        private long statusOwner = -1;
        private void claimStatus() { if (statusOwner < 0) statusOwner = ++statusGeneration; }
        final Status status = (message, ok) -> {
            claimStatus();
            if (statusOwner == statusGeneration && !act.isFinishing() && !act.isDestroyed())
                WalletTools.this.status.show(message, ok);
        };
        Review() {
            List<Coin> selected = act.selectedCoins();
            snapshot = TxnUtil.selectionSnapshot(selected);
            for (Coin source : selected) {
                if (source == null) { coins.add(null); continue; }
                // Freeze the command and display fields; these tools do not replay NFT state.
                Coin c = new Coin();
                c.coinid=source.coinid;c.tokenid=source.tokenid;c.amount=source.amount;c.rawAmount=source.rawAmount;
                c.address=source.address;c.miniaddress=source.miniaddress;
                c.confirmed=source.confirmed;c.sendable=source.sendable;c.spent=source.spent;
                c.tokenName=source.tokenName;c.tokenNameProvided=source.tokenNameProvided;
                coins.add(c);
            }
            tokenid=coins.isEmpty()?Util.MINIMA_TOKENID:coins.get(0)==null?null:coins.get(0).tokenid;
        }
        boolean accept(boolean spend) {
            if (accepted) return false;
            accepted=true;
            claimStatus();
            return current(spend);
        }
        boolean current(boolean spend) {
            if (act.isFinishing() || act.isDestroyed()) return false;
            boolean valid=Util.isValidHexId(tokenid);
            java.util.Set<String> ids=new java.util.HashSet<>();
            for (Coin c:coins) {
                if (c==null || !Util.isValidHexId(c.coinid) || !BurialEvidence.same(tokenid,c.tokenid)
                        || !ids.add(c.coinid.toLowerCase(java.util.Locale.ROOT))
                        || (spend && (!c.isWellFormed() || !c.confirmed || !c.sendable || c.spent))) {valid=false;break;}
            }
            if (valid && java.util.Arrays.equals(snapshot,TxnUtil.selectionSnapshot(act.selectedCoins()))) return true;
            status.show(Language.msg(l10n_nft_tools_review_again),false);
            return false;
        }
    }

