"""Reuse NFT Wallet's strict full-list validator at Inbox delivery read boundaries."""
from pathlib import Path

def adapt(root):
    folder = root/'merchinbox/src/main/java/com/eurobuddha/merchinbox'
    # Keep the tested ID/amount/list functions verbatim; no NFT UI or burial state dependencies.
    source = (root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet/BurialEvidence.java').read_text()
    a = source.index('    static IllegalArgumentException invalid()')
    b = source.index('    /** Same supported state values', a)
    methods = source[a:b].replace('new IllegalArgumentException("Incomplete burial evidence")', 'new IllegalArgumentException(INVALID)')
    (folder/'DeliveryCoinList.java').write_text('''package com.eurobuddha.merchinbox;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.HashSet;

/** Exact NFT Wallet coin-list checks; a failed read cannot establish absence or delivery. */
final class DeliveryCoinList {
    static final String INVALID = "Incomplete coin-list evidence. Delivery remains unconfirmed.";
    private DeliveryCoinList() {}
''' + methods + '}\n')
    p = folder/'MainActivity.java'; s = p.read_text()
    boundaries = [('stateNftDeliver', 'runStateTransfer'), ('collectionDeliver', 'bundleTransferNext'),
                  ('watchBundleDeparture', 'watchDeparture'), ('watchDeparture', 'replyRow')]
    for name, following in boundaries:
        a = s.index('    private void '+name+'(')
        b = s.index('    private '+('View' if following == 'replyRow' else 'void')+' '+following+'(',a)
        part = s[a:b]
        needle = '                JSONArray arr = j.optJSONArray("response");'
        assert part.count(needle) == 1, name
        poll = name.startswith('watch')
        retry = ('watchBundleDeparture(o, key, tokenid, postedIds, firstError, auto, attempt + 1);'
                 if name == 'watchBundleDeparture' else 'watchDeparture(o, key, tokenid, coinid, postedVal, auto, attempt + 1);')
        action = retry if poll else 'deliverFailed(o, key, DeliveryCoinList.INVALID, auto);'
        part = part.replace(needle, '                final JSONArray arr;\n                try { arr = DeliveryCoinList.coins(j, tokenid); }\n'
                    + '                catch (RuntimeException incomplete) { '+action+' return; }')
        # NodeApi also checks host lifetime; retain that guard at this confirmation boundary.
        part = part.replace('            @Override public void onResult(JSONObject j) {',
                            '            @Override public void onResult(JSONObject j) {\n                if (isFinishing() || isDestroyed()) return;')
        part = part.replace('            @Override public void onError(String m) {',
                            '            @Override public void onError(String m) {\n                if (isFinishing() || isDestroyed()) return;')
        if poll:
            part = part.replace('ui.postDelayed(() -> node.cmd(', 'ui.postDelayed(() -> {\n            if (isFinishing() || isDestroyed()) return;\n            node.cmd(')
            assert part.count('        }), WATCH_INTERVAL_MS);') == 1, name
            part = part.replace('        }), WATCH_INTERVAL_MS);','            });\n        }, WATCH_INTERVAL_MS);')
        if name == 'watchDeparture':
            part = part.replace('coinid.equals(c.optString("coinid", ""))','DeliveryCoinList.same(coinid, c.optString("coinid", ""))')
        if name == 'watchBundleDeparture':
            part = part.replace('live.add(c.optString("coinid", ""));','live.add(c.optString("coinid", "").toLowerCase(java.util.Locale.ROOT));')
            part = part.replace('live.contains(id)', 'live.contains(id.toLowerCase(java.util.Locale.ROOT))')
        s = s[:a]+part+s[b:]
    p.write_text(s)
    p = folder/'InboxMessages.java'; s=p.read_text()
    needle = '        if (depth >= 8) return value;\n        switch(value) {'
    assert s.count(needle)==1
    s=s.replace(needle,needle+'\n            case DeliveryCoinList.INVALID: return Language.msg(l10n_mall_inbox_coin_list_unconfirmed);')
    p.write_text(s)
