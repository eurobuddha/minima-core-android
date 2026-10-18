"""Localize Salon browsing without translating public profile data or protocol keys."""
from pathlib import Path
import json,re

def adapt(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java';s=p.read_text()
    def resource(key):return 'com.eurobuddha.minimacore.i18n.R.string.l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()
    def msg(key,args=''):return 'Language.msg('+resource(key)+args+')'
    labels=json.loads((Path(__file__).parent/'pandamonium-sources/salon/social-language-labels.json').read_text());found=set()
    sections=[('    private void renderFeed()', '    /** A renderable icon'),('    private LinearLayout linkRow(', '    private String urlHost('),('    private void openUrl(', '    /** One-tap:'),('    private void sharePage(', '    private void showQr(')]
    for begin,end in sections:
        a=s.index(begin);b=s.index(end,a);part=s[a:b]
        def replace(old,new):
            nonlocal part
            assert old in part,('Salon social drift',old);part=part.replace(old,new)
        if 'renderFeed' in begin:
            replace('"Pulling posts from " + follows.length() + " salon(s)…"',msg('salonSocial.pulling',', follows.length()'))
            replace('"Couldn\'t reach " + fails + " of the salons you follow — they may be offline."',msg('salonSocial.unreachable',', fails'))
            replace('"Everyone on the Salon — read straight off the chain\'s town square (" + SalonRegistry.SALON_ADDRESS + "). No server, no directory company."',msg('salonSocial.discoverHelp',', SalonRegistry.SALON_ADDRESS'))
            replace('"Couldn\'t reach @" + viewEntry.handle + "\'s page — their host may be offline."',msg('salonSocial.pageUnreachable',', viewEntry.handle'))
            replace('"Loading @" + viewEntry.handle + "\'s page…"',msg('salonSocial.pageLoading',', viewEntry.handle'))
            replace('"▲ scroll within — showing all " + plist.size() + " posts"',msg('salonSocial.scrollPosts',', plist.size()'))
            replace('"following", Design.PILL_DONE',msg('salonSocial.following')+', Design.PILL_DONE')
            for name,old,key in [('nPosts','"posts"','postCount'),('nHold','nHold == 1 ? "holding" : "holdings"','holdingCount'),('nGal','"media"','mediaCount'),('nFol','"following"','followingCount')]:
                # Counted labels use native plurals, without modifying JSON field names.
                part,n=re.subn(r'statPill\('+name+r',\s*'+re.escape(old)+r'\)', 'statPill('+name+', Language.count('+resource('salonSocial.'+key).replace('.R.string.', '.R.plurals.')+', '+name+'))',part);assert n==1
            replace('private View statPill(int n, String label)', 'private View statPill(int n, CharSequence label)')
            replace('label.toUpperCase()', 'Language.upper(label)')
            replace('String when = Util.ago(post.optLong("ts", 0));','CharSequence when = socialTime(post.optLong("ts", 0));')
            replace('!when.isEmpty()', 'when.length() != 0');replace('when.isEmpty()', 'when.length() == 0')
            replace('''            String ago = Util.ago(upd);
            String freshness = ago.isEmpty() || ago.equalsIgnoreCase("now") ? "Updated just now" : "Updated " + ago + " ago";''','            CharSequence freshness = '+msg('salonSocial.updated',', socialTime(upd)')+';')
            # Reuse the NFT showcase binding for the header/shield too: a remote
            # profile's self-declared token must not borrow someone else's badge.
            c=part.index('    private void renderProfilePage(');d=part.index('    private int arrLen(',c)
            profile=part[c:d]
            profile=profile.replace('        LinearLayout headCard = card();', '        final String boundIdentity = (!mine && viewEntry != null) ? viewEntry.tokenid : p.optString("tokenid");\n        LinearLayout headCard = card();')
            profile=profile.replace('p.optString("tokenid"), p.optString("webvalidate")', 'boundIdentity, p.optString("webvalidate")')
            profile=profile.replace('WebValidate.status(p.optString("tokenid"))', 'WebValidate.status(boundIdentity)')
            profile=profile.replace('WebValidate.ensure(this, p.optString("tokenid"), wv', 'WebValidate.ensure(this, boundIdentity, wv')
            profile=profile.replace('String bindTid = (!mine && viewEntry != null) ? viewEntry.tokenid : p.optString("tokenid");', 'String bindTid = boundIdentity;')
            part=part[:c]+profile+part[d:]
            # Wrap translated action labels while retaining each row's weights/margins.
            part=re.sub(r'weight\((40|46|48),', 'weight(-2,',part)
            part=re.sub(r'lph\((44|46), ', 'lp(',part)
            replace('status.setText(', 'Language.setText(status, ')
            # Same explicit refresh behavior as the existing SwipeRefreshLayout handler.
            replace('toast("Refreshing…"); render();','toast("Refreshing…"); mProfileCache.clear(); mRegistryCache = null; render();')
            # Rendering a feed must not annotate cached public profiles in place.
            replace('''                        JSONObject post = ps.optJSONObject(k);
                        if (post == null) continue;
                        try { post.put("_author", author.isEmpty() ? prof.optString("handle") : author); post.put("_avatar", avatar); post.put("_tid", tid); } catch (Exception ignored) {}
                        posts.add(post);''','''                        JSONObject sourcePost = ps.optJSONObject(k);
                        if (sourcePost == null) continue;
                        try {
                            JSONObject post = new JSONObject(sourcePost.toString());
                            post.put("_author", author.isEmpty() ? prof.optString("handle") : author);
                            post.put("_avatar", avatar); post.put("_tid", tid);
                            posts.add(post);
                        } catch (org.json.JSONException malformed) { /* Skip an unreadable post. */ }''')
            replace('''                } else fails[0]++;
                runOnUiThread(() -> { if (--pending[0] == 0 && ep == renderEpoch) paintFeed(posts, status, fails[0]); });''','''                }
                final boolean failed = prof == null;
                runOnUiThread(() -> {
                    if (failed) fails[0]++;
                    if (--pending[0] == 0 && ep == renderEpoch && !isFinishing() && !isDestroyed()) paintFeed(posts, status, fails[0]);
                });''')
            replace('''            });
        }
    }

    private void paintFeed''','''            });
        }
        if (pending[0] == 0) paintFeed(posts, status, fails[0]);
    }

    private void paintFeed''')
            replace('''                    String nm = prof.optString("name", ""), av''','''                    if (ep != renderEpoch || isFinishing() || isDestroyed()) return;
                    String nm = prof.optString("name", ""), av''')
            replace('    private boolean viewFailed = false;','    private boolean viewFailed = false;\n    private int profileRequest = 0;')
            replace('''        go(Screen.VIEW);
        io.execute(() -> { JSONObject p = httpGetJson(e.url); runOnUiThread(() -> { viewProfile = p; viewFailed = (p == null); if (screen == Screen.VIEW) render(); }); });''','''        final int request = ++profileRequest;
        go(Screen.VIEW);
        io.execute(() -> { JSONObject p = httpGetJson(e.url); runOnUiThread(() -> {
            if (request != profileRequest || viewEntry != e || isFinishing() || isDestroyed()) return;
            viewProfile = p; viewFailed = (p == null); if (screen == Screen.VIEW) render();
        }); });''')
            replace('''        registryList(entries -> {
            for (SalonRegistry.Entry e : entries) if (e.tokenid.equals(tokenid)) { runOnUiThread(() -> openProfile(e)); return; }
            runOnUiThread(() -> toast("That salon isn't on the square yet."));
        });''','''        final int request = ++profileRequest, ep = renderEpoch;
        registryList(entries -> runOnUiThread(() -> {
            if (request != profileRequest || ep != renderEpoch || isFinishing() || isDestroyed()) return;
            for (SalonRegistry.Entry e : entries) if (e.tokenid.equals(tokenid)) { openProfile(e); return; }
            toast("That salon isn't on the square yet.");
        }));''')
            part='''    private CharSequence socialTime(long timestamp) {
        if (timestamp <= 0) return "";
        return Language.since(timestamp < 1_000_000_000_000L ? timestamp * 1000L : timestamp);
    }

'''+part
        elif 'linkRow' in begin:
            replace('private LinearLayout linkRow(String label, String url)', 'private LinearLayout linkRow(CharSequence label, String url)')
            replace('label.toUpperCase()','Language.upper(label)')
            replace('String label = host == null || host.isEmpty() ? "✓ WEB-VERIFIED" : "✓ VERIFIED · " + host.toUpperCase();','CharSequence label = host == null || host.isEmpty() ? '+msg('salonSocial.webVerified')+' : '+msg('salonSocial.verifiedHost',', host')+';')
        elif 'sharePage' in begin:
            replace('"@" + SalonStore.get(this, "handle") + " on The Salon — " + url',msg('salonSocial.shareText',', SalonStore.get(this, "handle"), url')+'.toString()')
        for literal,key in labels.items():
            old=json.dumps(literal,ensure_ascii=False)
            if old in part:part=part.replace(old,msg(key));found.add(literal)
        s=s[:a]+part+s[b:]
    # The lowercase pill was adapted separately; its source is not a profile data key.
    found.add('Following')
    assert found==set(labels),('Unused Salon social labels',set(labels)-found)
    old='TextView b = Design.button(this, label, filled); b.setOnClickListener'
    assert old in s;s=s.replace(old,'TextView b = Design.button(this, label, filled); b.setMinHeight(dp(48)); b.setOnClickListener')
    old='new LinearLayout.LayoutParams(0, dp(hDp), 1)';assert old in s;s=s.replace(old,'new LinearLayout.LayoutParams(0, hDp < 0 ? hDp : dp(hDp), 1)')
    p.write_text(s)
