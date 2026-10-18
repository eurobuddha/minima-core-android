package com.eurobuddha.salon;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Retains mutable server paths and puts IPFS siblings under one directory CID. */
final class ProfileHosting {
    static String upload(Hosting.Profile host, String handle, String profile, String html) throws Exception {
        String path = Hosting.slug(handle);
        if (path.isEmpty()) throw new Hosting.HostingException("empty profile handle");
        byte[] json = profile.getBytes(StandardCharsets.UTF_8);
        byte[] page = html.getBytes(StandardCharsets.UTF_8);
        String url, pageUrl = null;
        try (Hosting.Uploader up = Hosting.forProfile(host)) {
            if (up instanceof Hosting.DirUploader) {
                String base = ((Hosting.DirUploader) up).putDirectory(Arrays.asList(
                        new Hosting.Entry(json, "profile.json", "application/json"),
                        new Hosting.Entry(page, "index.html", "text/html")), null);
                requireRef(base);
                if (!base.endsWith("/")) base += "/";
                url = base + "profile.json"; pageUrl = base + "index.html";
            } else if (Hosting.TYPE_MAXIMA.equals(host.type()) || Hosting.TYPE_BLOSSOM.equals(host.type())
                    || Hosting.TYPE_RELAY.equals(host.type())) {
                url = up.putFile(json, path + "/profile.json", "application/json");
            } else {
                // Keep the stable URL sealed into existing identity tokens. If the renderer
                // fails, do not replace the profile or report a completed publication.
                pageUrl = putProfile(up, page, path + "/index.html", "text/html");
                requireRef(pageUrl);
                Hosting.verifyUrl(pageUrl, host);
                url = putProfile(up, json, path + "/profile.json", "application/json");
                pageUrl = null; // already checked before replacing profile.json
            }
            requireRef(url);
            Hosting.verifyUrl(url, host);
            if (pageUrl != null) Hosting.verifyUrl(pageUrl, host);
        }
        return url;
    }

    private static String putProfile(Hosting.Uploader up, byte[] bytes, String path, String mime) throws Exception {
        return up instanceof Hosting.ProfileUploader
                ? ((Hosting.ProfileUploader)up).putProfileFile(bytes, path, mime)
                : up.putFile(bytes, path, mime);
    }

    private static void requireRef(String ref) throws Hosting.HostingException {
        if (ref == null || ref.trim().isEmpty()) throw new Hosting.HostingException("empty upload reference");
    }
}
