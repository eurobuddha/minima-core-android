"""Reuse NFT raw offsets and HistorySync page validation for both wallet histories."""
from pathlib import Path

def adapt(root):
    for app,pkg in [('utxo','utxo'),('NFTwallet','nftwallet')]:
        p=root/app/'src/main/java/com/eurobuddha'/pkg/'HistoryView.java'
        s=p.read_text()
        def change(old,new,count=1):
            nonlocal s
            assert s.count(old)==count,('history page source drift',app,old,s.count(old))
            s=s.replace(old,new)
        if app=='utxo':
            change('    private boolean moreAvailable = false;', '    private boolean moreAvailable = false;\n    // Raw node rows consumed, independent of persisted/filtered transaction count.\n    private int nextOffset = 0;')
            change('fetchPage(act.history().nodeTxCount())','fetchPage(nextOffset)',2)
        change('if (!force && act.chainBlock() <= lastFetchBlock) return;',
               'if (!force && act.chainBlock() == lastFetchBlock) return;')
        change('        fetchPage(0);','        nextOffset = 0;\n        fetchPage(0);')
        change('    private void loadOlder() { if (!fetching) fetchPage(nextOffset); }',
               '    private void loadOlder() {\n        if (fetching) return;\n        if (act.chainBlock() != lastFetchBlock) fetch(true);\n        else fetchPage(nextOffset);\n    }')
        change('        fetching = true;','        final int requestedBlock = act.chainBlock();\n        fetching = true;')
        change('JSONObject resp = json.optJSONObject("response");','JSONObject resp = json == null ? null : json.optJSONObject("response");')
        start=s.index('                if (resp == null || txpows == null')
        end=s.index('                for (int i = 0; i < got; i++) {',start)
        s=s[:start]+'                if (resp == null || txpows == null || !Boolean.TRUE.equals(json.opt("status"))) {\n                    fetching = false; render(); return;\n                }\n                JSONArray details = resp.optJSONArray("details");\n                int got = txpows.length();\n                // Reuse HistorySync\'s whole-page gate. Missing economic detail must not\n                // become a permanently cached zero/self transaction or advance the cursor.\n                for (int i = 0; i < got; i++) {\n                    JSONObject tx = txpows.optJSONObject(i);\n                    Object id = tx == null ? null : tx.opt("txpowid");\n                    JSONObject detail = details == null ? null : details.optJSONObject(i);\n                    if (!(id instanceof String) || ((String) id).trim().isEmpty()\n                            || !(tx.opt("istransaction") instanceof Boolean)\n                            || (Boolean.TRUE.equals(tx.opt("istransaction"))\n                                && (detail == null || detail.optJSONObject("difference") == null))) {\n                        fetching = false; render(); return;\n                    }\n                }\n                fetching = false;\n                if (offset == 0) lastFetchBlock = requestedBlock;\n                nextOffset = offset + got;\n'+s[end:]
        change('            container.addView(empty);\n            return;', '            container.addView(empty);')
        if app=='NFTwallet':
            change('        if (shown == 0) {','        if (shown == 0 && !rows.isEmpty()) {')
            change('if (pageMax > 1) { pageMax = Math.max(1, pageMax / 2); fetchPage(offset); return; }',
                   'if (NodeApi.ERR_TOO_LONG.equals(message) && pageMax > 1) { pageMax = Math.max(1, pageMax / 2); fetchPage(offset); return; }')
        p.write_text(s)
