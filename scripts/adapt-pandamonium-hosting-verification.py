"""Keep configured hosting while verifying the actual redirect destination and body."""
from pathlib import Path


def adapt(root):
    for app, package in [('atelier', 'statenft'), ('salon', 'salon')]:
        p = root / app / f'src/main/java/com/eurobuddha/{package}/Hosting.java'
        s = p.read_text()
        start = s.index('    private static void verifyOnce(String url, Profile p)')
        end = s.index('    /* ==================== HTTP plumbing', start)
        s = s[:start] + '''    private static void verifyOnce(String url, Profile p) throws HostingException {
        verifyOnce(url, p, u -> (HttpURLConnection) u.openConnection());
    }

    interface ProbeConnection { HttpURLConnection open(URL url) throws Exception; }

    // NetFetch's bounded manual redirect loop, retaining the explicit hosting-profile allowance.
    // The injected transport is test-only; the same host/scheme policy always runs.
    static void verifyOnce(String url, Profile p, ProbeConnection connections) throws HostingException {
        java.util.Set<String> ownHosts = new java.util.HashSet<>();
        if (p != null) for (String host : p.ownHosts()) ownHosts.add(host.toLowerCase(Locale.ROOT));
        String current = url;
        try {
            for (int hop = 0; hop <= 3; hop++) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                URL u = new URL(current);
                if (!"http".equals(u.getProtocol()) && !"https".equals(u.getProtocol()))
                    throw new HostingException("URL must use HTTP or HTTPS");
                if (!ownHosts.contains(u.getHost().toLowerCase(Locale.ROOT))
                        && com.eurobuddha.minimacore.main.views.balance.tokens.NetFetch.isBlockedHost(u.getHost()))
                    throw new HostingException("URL host is not publicly reachable: " + u.getHost());
                HttpURLConnection con = connections.open(u);
                try {
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(15000);
                    con.setInstanceFollowRedirects(false);
                    con.setRequestProperty("Range", "bytes=0-1");
                    // A partial gzip stream cannot be decompressed by Android.
                    con.setRequestProperty("Accept-Encoding", "identity");
                    int code = con.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = con.getHeaderField("Location");
                        if (location == null || location.isEmpty())
                            throw new HostingException("URL redirect has no destination");
                        current = new URL(u, location).toString();
                        continue; // scheme and host are checked again before opening the next connection
                    }
                    if (code != 200 && code != 206)
                        throw new HostingException("URL does not serve (HTTP " + code + "): " + url);
                    try (java.io.InputStream in = con.getInputStream()) {
                        if (in == null || in.read() == -1) throw new HostingException("URL serves an empty response: " + url);
                    }
                    return;
                } finally { con.disconnect(); }
            }
            throw new HostingException("URL has too many redirects: " + url);
        } catch (HostingException he) { throw he; }
        catch (Exception e) {
            throw new HostingException("URL not reachable: " + url + " — " + e.getClass().getSimpleName());
        }
    }

''' + s[end:]
        # Interrupting verification must not silently restart a slow retry window.
        old='try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) { }' if app == 'atelier' else 'try { Thread.sleep(5000); } catch (InterruptedException ignored) { }'
        new=old.split(' catch ')[0] + ''' catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new HostingException("Verification stopped by user: " + url);
                }'''
        if app == 'salon':
            new = new.replace('\n', '\n    ')
        assert s.count(old) == 1
        s=s.replace(old,new)
        # A pre-existing interruption should not wait for a retry after a failed probe.
        old='                last = e;'
        assert s.count(old)==1
        s=s.replace(old,old+'\n                if (Thread.currentThread().isInterrupted()) throw e;')
        p.write_text(s)
