    private String storedHolding(String tokenid) {
        JSONArray items = SalonStore.arr(this, "nfts");
        for (int i=0; i<items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && tokenid.equalsIgnoreCase(item.optString("tokenid"))) return item.toString();
        }
        return "";
    }

    /** Refresh once per identity/token, only while the saved holding remains unchanged. */
    private void maybeRefreshProof(final JSONObject n, final String bindHex) {
        final String tid = n.optString("tokenid"), owner = SalonStore.get(this, "tokenid"), draft = n.toString();
        if (tid.isEmpty() || owner.isEmpty() || bindHex == null || !bindHex.equalsIgnoreCase(NftProof.hexOf(owner))) return;
        final java.util.function.BooleanSupplier current = () -> !isFinishing() && !isDestroyed()
                && owner.equals(SalonStore.get(this, "tokenid")) && draft.equals(storedHolding(tid));
        final String request = owner + "|" + tid;
        if (!current.getAsBoolean() || !proofRefreshed.add(request)) return;
        NftProof.generate(node, tid, bindHex, current, new NftProof.Gen() {
            @Override public void ok(JSONObject item) {
                if (!current.getAsBoolean()) return;
                try { item.put("name", n.optString("name")); item.put("image", n.optString("image")); } catch (Exception ignored) {}
                captureEditions(tid, editions -> runOnUiThread(() -> {
                    if (!current.getAsBoolean()) return;
                    try { item.put("editions", editions); } catch (Exception ignored) {}
                    JSONArray arr = SalonStore.arr(MainActivity.this, "nfts");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null && draft.equals(o.toString())) {
                            try { arr.put(i, item); } catch (Exception ignored) { return; }
                            SalonStore.setArr(MainActivity.this, "nfts", arr); break;
                        }
                    }
                }));
            }
            @Override public void fail(String message) {}
        });
    }

