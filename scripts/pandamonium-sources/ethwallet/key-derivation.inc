    public void deriveFromNode(NodeApi node, final Handler ui, final String expectedAddr, final Cb cb) {
        final long generation;
        synchronized (this) { generation = ++derivationGeneration; }
        node.cmd("seedrandom modifier:ethbridge", new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                final Credentials candidate;
                try {
                    if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) throw new IllegalArgumentException();
                    JSONObject response = reply.optJSONObject("response");
                    Object key = response == null ? null : response.opt("seedrandom");
                    if (!(key instanceof String)) throw new IllegalArgumentException();
                    candidate = checkedCredentials((String) key);
                } catch (Exception invalid) {
                    deliver(generation, ui, () -> cb.err("Node returned an invalid Ethereum key. Not using it."));
                    return;
                }
                deliver(generation, ui, () -> {
                    String address = candidate.getAddress();
                    if (expectedAddr != null && !expectedAddr.equalsIgnoreCase(address)) {
                        cb.addressChanged(expectedAddr, address);
                        return;
                    }
                    creds = candidate;
                    imported = false;
                    cb.ok(address);
                });
            }
            @Override public void onError(String ignored) {
                deliver(generation, ui, () -> cb.err("Ethereum key derivation failed. Retry when the node is ready."));
            }
        });
    }

    /** Delivery and adoption share one generation check, including queued UI callbacks. */
    private void deliver(long generation, Handler ui, Runnable callback) {
        post(ui, () -> {
            synchronized (EthWallet.this) {
                if (generation != derivationGeneration) return;
                ++derivationGeneration; // A request can complete only once.
                callback.run();
            }
        });
    }

    /** AtomiX's exact width and secp256k1 scalar checks, shared by node and explicit import. */
    private static Credentials checkedCredentials(String raw) {
        if (raw == null) throw new IllegalArgumentException("Invalid Ethereum key");
        String hex = raw.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (!KEY64.matcher(hex).matches()) throw new IllegalArgumentException("Invalid Ethereum key");
        BigInteger scalar = new BigInteger(hex, 16);
        if (scalar.signum() <= 0 || scalar.compareTo(org.web3j.crypto.Sign.CURVE_PARAMS.getN()) >= 0)
            throw new IllegalArgumentException("Invalid Ethereum key");
        return Credentials.create("0x" + hex);
    }

    /** A valid explicit import supersedes every pending node derivation. */
    public synchronized void importKey(String hexPriv) {
        Credentials candidate = checkedCredentials(hexPriv);
        ++derivationGeneration;
        creds = candidate;
        imported = true;
    }

    public synchronized void clear() { ++derivationGeneration; creds = null; imported = false; }

