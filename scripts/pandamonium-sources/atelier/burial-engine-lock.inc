    private static NodeApi mintNode;
    private static long mintGeneration;
    private static final java.util.Map<NodeApi, Integer> posting = new java.util.IdentityHashMap<>();

    private static synchronized boolean mintInFlight() {
        if (mintNode != null && mintNode.isClosed()) { mintNode = null; mintGeneration++; }
        return mintNode != null;
    }
    private static synchronized void beginPost(NodeApi node) { posting.put(node, posting.getOrDefault(node, 0) + 1); }
    private static synchronized void endPost(NodeApi node) {
        int count = posting.getOrDefault(node, 0);
        if (count <= 1) posting.remove(node); else posting.put(node, count - 1);
    }
    static synchronized boolean pauseForBurial(Context ctx, String tokenid) {
        posting.entrySet().removeIf(entry -> entry.getKey().isClosed());
        return !mintInFlight() && posting.isEmpty() && LocalStore.pauseForBurial(ctx, tokenid);
    }
