    /** Validate before constructing or publishing credentials. Reuses ETH Wallet's exact 32-byte check. */
    private static Credentials nodeCredentials(JSONObject reply) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) throw new IllegalArgumentException();
        JSONObject response = reply.optJSONObject("response");
        Object raw = response == null ? null : response.opt("seedrandom");
        if (!(raw instanceof String)) throw new IllegalArgumentException();
        String hex = ((String) raw).trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (!hex.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException();
        BigInteger scalar = new BigInteger(hex, 16);
        if (scalar.signum() <= 0 || scalar.compareTo(org.web3j.crypto.Sign.CURVE_PARAMS.getN()) >= 0)
            throw new IllegalArgumentException();
        return Credentials.create("0x" + hex);
    }

    /** Derive without allowing a malformed response or exception to expose or replace a key. */
    public void deriveFromNode(NodeApi node, final Handler ui, final Cb cb) {
        node.cmd("seedrandom modifier:ethbridge", new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                final Credentials candidate;
                final String address;
                try { candidate = nodeCredentials(reply); address = candidate.getAddress(); }
                catch (Exception invalid) {
                    post(ui, () -> cb.err("Node returned an invalid Ethereum key. Not using it."));
                    return;
                }
                creds = candidate;
                imported = false;
                post(ui, () -> cb.ok(address));
            }
            @Override public void onError(String ignored) {
                post(ui, () -> cb.err("Ethereum key derivation failed. Retry when the node is ready."));
            }
        });
    }

    /** Read the node's current address without replacing the in-use wallet or echoing secret data. */
    public void probeNodeAddress(NodeApi node, final Handler ui, final Cb cb) {
        node.cmd("seedrandom modifier:ethbridge", new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                final String address;
                try { address = nodeCredentials(reply).getAddress(); }
                catch (Exception invalid) { post(ui, () -> cb.err("probe failed")); return; }
                post(ui, () -> cb.ok(address));
            }
            @Override public void onError(String ignored) { post(ui, () -> cb.err("probe failed")); }
        });
    }

