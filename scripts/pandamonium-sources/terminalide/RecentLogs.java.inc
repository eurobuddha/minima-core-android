    /** Read a bounded projection: even an old multi-megabyte row fits a CursorWindow.
     * The stored event is never modified. Newest rows survive both display budgets.
     * Called only on the LogsView worker, including opening/closing this helper.
     */
    public String getRecentLogPreview() {
        final int max = com.eurobuddha.terminalide.terminal.TerminalOutput.MAX_CHARS;
        StringBuilder tail = new StringBuilder();
        String sql = "SELECT substr(data,1,65536),length(data) FROM events "
                + "WHERE event=? ORDER BY _id DESC LIMIT 160";
        java.util.ArrayList<String> newest = new java.util.ArrayList<>();
        try (Cursor cursor = mDB.rawQuery(sql, new String[]{"MINIMALOG"})) {
            while (cursor.moveToNext()) {
                String data = cursor.getString(0);
                String message;
                if (cursor.getLong(1) > 65536) {
                    // A partial JSON document cannot be parsed; show its bounded literal prefix.
                    message = data + "…";
                } else {
                    try { message = new JSONObject(data).getString("message"); }
                    catch (org.json.JSONException malformed) { message = data; }
                }
                newest.add(new com.eurobuddha.terminalide.terminal.TerminalOutput(message).preview);
            }
        }
        for (int i = newest.size() - 1; i >= 0; i--) {
            tail.append(newest.get(i)).append('\n');
            if (tail.length() > max) {
                int from = tail.length() - max;
                if (Character.isLowSurrogate(tail.charAt(from))) from++;
                tail.delete(0, from);
            }
        }
        // Preserve the newest 160 lines, rather than clipping away the newest messages.
        int lines = 0;
        for (int i = tail.length() - 1; i >= 0; i--) {
            if (tail.charAt(i) == '\n' && ++lines > 160) {
                tail.delete(0, i + 1);
                break;
            }
        }
        return tail.toString();
    }
