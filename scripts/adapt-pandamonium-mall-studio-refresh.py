"""Keep editable Studio state intact across failed or stale background reads."""
from pathlib import Path
import shutil

def adapt(root):
 project=Path(__file__).resolve().parents[1];folder=root/'merchstudio/src/main/java/com/eurobuddha/merchstudio'
 shutil.copy2(project/'scripts/pandamonium-sources/merchstudio/StudioData.java',folder/'StudioData.java')
 p=folder/'MainActivity.java';s=p.read_text()
 def change(old,new):
  nonlocal s
  assert old in s,('Studio refresh drift',old);s=s.replace(old,new)
 change('    private boolean paired = false;','    private boolean paired = false;\n    private final java.util.Set<String> walletTokens = new java.util.HashSet<>();')
 change('        String imageB64 = "";','        long photoRequest;\n        String imageB64 = "";')
 change('            if (uri != null && pickingFor != null) attachPhoto(pickingFor, uri);','            TypedProduct target = pickingFor; pickingFor = null;\n            if (uri != null && target != null && typedProducts.contains(target)) attachPhoto(target, uri);')
 start=s.index('    private void attachPhoto(');end=s.index('    /** Everything that will land',start)
 s=s[:start]+'''    private void attachPhoto(final TypedProduct p, final android.net.Uri uri) {
        if (isFinishing() || isDestroyed() || !typedProducts.contains(p)) return;
        final long request = ++p.photoRequest;
        io.execute(() -> {
            byte[] jpeg = Images.compressToFit(MainActivity.this, uri, 90000);
            final String encoded = jpeg == null ? null : android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
            ui.post(() -> applyPhoto(p, request, encoded));
        });
    }

    /** Same view ownership rule as ImageLoader.loadOver; never replace an unrelated editor. */
    private void applyPhoto(TypedProduct p, long request, String encoded) {
        if (isFinishing() || isDestroyed() || !typedProducts.contains(p) || p.photoRequest != request) return;
        if (encoded == null) { toast(Language.msg(l10n_mail_image_unreadable)); return; }
        p.imageB64 = encoded;
        for (View page : stack) {
            ImageView photo = page.findViewWithTag(p);
            if (photo == null) continue;
            photo.setImageBitmap(decodeB64(encoded));
            TextView caption = page.findViewWithTag("photoHint:" + System.identityHashCode(p));
            if (caption != null) Language.setText(caption, Language.msg(l10n_mall_studio_ui_change_photo));
        }
        refreshIfTag("gallery");
    }

'''+s[end:]
 change('        ImageView photo = new ImageView(this);','        ImageView photo = new ImageView(this);\n        photo.setTag(p);')
 change('        TextView tap = new TextView(this);','        TextView tap = new TextView(this);\n        tap.setTag("photoHint:" + System.identityHashCode(p));')
 change('                nftsLoading = false; nftsLoaded = true;\n                mergeBalances(j.optJSONArray("response"));','''                final JSONArray rows;
                try { rows = StudioData.balances(j); }
                catch (IllegalArgumentException invalid) { onError(invalid.getMessage()); return; }
                nftsLoading = false; nftsLoaded = true;
                mergeBalances(rows);''')
 change('        java.util.HashSet<String> seen = new java.util.HashSet<>();','''        if (rows == null) return;
        walletTokens.clear();
        for (int i=0;i<rows.length();i++) walletTokens.add(rows.optJSONObject(i).optString("tokenid"));
        java.util.HashSet<String> seen = new java.util.HashSet<>();''')
 # Query only after publishing the new map so even an immediate callback cannot be overwritten.
 change('            ensureRecord(t.tokenid);                                  // memoized script fingerprint','            // Fingerprint after the new balance snapshot has been installed.')
 change('            } else {\n                loadCollectionCoins(e.getValue());\n            }','            }')
 change('        listings.putAll(fresh);','''        listings.putAll(fresh);
        for (StateCollection col : new ArrayList<>(collections.values())) loadCollectionCoins(col);
        for (String tokenid : seen) ensureRecord(tokenid);''')
 change('                fetchingRecords.remove(tokenid);\n                Object resp = j.opt("response");','''                fetchingRecords.remove(tokenid);
                if (isFinishing() || isDestroyed() || !walletTokens.contains(tokenid)
                        || j == null || !Boolean.TRUE.equals(j.opt("status"))) return;
                Object resp = j.opt("response");''')
 change('                tokenRecords.put(tokenid, tok);','''                if (!tokenid.equalsIgnoreCase(tok.optString("tokenid", ""))
                        || !(tok.opt("script") instanceof String)) return;
                tokenRecords.put(tokenid, tok);''')
 old='''                col.coinsLoading = false; col.coinsLoaded = true; col.tooLong = false;
                col.ownedCoins.clear(); col.unstampedCount = 0;
                java.util.HashSet<String> liveKeys = new java.util.HashSet<>();
                JSONArray arr = j.optJSONArray("response");'''
 new='''                if (isFinishing() || isDestroyed() || collections.get(col.tokenid) != col) return;
                final JSONArray arr;
                try { arr = StudioData.collection(j, col.tokenid, col.meta.size); }
                catch (IllegalArgumentException invalid) { onError(invalid.getMessage()); return; }
                col.coinsLoading = false; col.coinsLoaded = true; col.tooLong = false;
                col.ownedCoins.clear(); col.unstampedCount = 0;
                java.util.HashSet<String> liveKeys = new java.util.HashSet<>();'''
 change(old,new)
 change('''                    if (!idx.matches("^[0-9]+$") || !StateNft.replayableState(c)) continue;  // hostile state
                    col.ownedCoins.add(c);
                    String key = col.tokenid + "#" + idx;''','''                    int index = StudioData.pieceIndex(c, col.meta.size);
                    if (index <= 0 || !StateNft.replayableState(c)) continue;
                    col.ownedCoins.add(c);
                    String key = col.tokenid + "#" + index;''')
 change('                    l.stateIdx = Integer.parseInt(idx);','                    l.stateIdx = index;')
 change('                    l.tokenid = col.tokenid;', '                    String previousArt = artUrlOf(l);\n                    l.tokenid = col.tokenid;')
 change('                    l.snMeta = col.meta;', '                    l.snMeta = col.meta;\n                    if (!previousArt.equals(artUrlOf(l))) l.cachedB64 = "";')
 change('                // pieces that left the wallet are no longer listable','''                // A live bundle is a listing, not a coin; retain its edits while any pieces remain.
                if (!col.ownedCoins.isEmpty()) liveKeys.add(col.tokenid + "#ALL");
                // pieces that left the wallet are no longer listable''')
 change('''                col.coinsLoading = false;
                if (NodeApi.ERR_TOO_LONG.equals(m)) col.tooLong = true;''','''                if (isFinishing() || isDestroyed() || collections.get(col.tokenid) != col) return;
                col.coinsLoading = false;
                if (NodeApi.ERR_TOO_LONG.equals(m)) col.tooLong = true;
                else toast(Language.msg(l10n_mall_studio_ui_wallet_error, StudioMessages.message(m)));''')
 # Reuse immutable cached token records when a collection leaves and later returns.
 change('        if (tokenRecords.containsKey(tokenid) || fetchingRecords.contains(tokenid)) return;', '\n'.join([
 '        JSONObject cached = tokenRecords.get(tokenid);',
 '        if (cached != null) { adoptTokenRecord(tokenid, cached); return; }',
 '        if (fetchingRecords.contains(tokenid)) return;']))
 start=s.index('                String script = tok.optString("script", "");',s.index('private void ensureRecord'))
 end=s.index('            }\n            @Override public void onError',start)
 body=s[start:end]
 s=s[:start]+'                adoptTokenRecord(tokenid, tok);\n'+s[end:]
 needle='    /** Enumerate the collection'
 helper='    private void adoptTokenRecord(String tokenid, JSONObject tok) {\n        if (!walletTokens.contains(tokenid) || collections.containsKey(tokenid)) return;\n'+body.replace('                ','        ')+'    }\n\n'
 change(needle,helper+needle)
 p.write_text(s)
 # Adapt NFT Wallet's strict Boolean status gate to this read-oriented two-method callback.
 p=root/'merchstudio/src/main/java/com/eurobuddha/comms/NodeApi.java';s=p.read_text()
 change('                    if (done[0]) return;','                    if (done[0] || mReleased) return;')
 change('''                    if (!zResponse.optBoolean("enabled", true)) {''','''                    if (zResponse == null) {
                        if (cb != null) cb.onError("Node result unavailable. Outcome unknown.");
                        return;
                    }
                    if (!zResponse.optBoolean("enabled", true)) {''')
 change('''                    // Contain callback crashes:''','''                    if (!(zResponse.opt("status") instanceof Boolean)) {
                        if (cb != null) cb.onError(zResponse.optString("transporterror", "Node result unavailable. Outcome unknown."));
                        return;
                    }
                    if (!zResponse.optBoolean("status", false)) {
                        if (cb != null) cb.onError(zResponse.optString("error", zResponse.optString("message", "Invalid node result. Outcome remains unknown.")));
                        return;
                    }
                    // Contain callback crashes:''')
 p.write_text(s)
