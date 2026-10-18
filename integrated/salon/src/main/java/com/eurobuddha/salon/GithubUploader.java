package com.eurobuddha.salon;

import java.net.HttpURLConnection;

/** GitHub contents-API transport. Serves via raw.githubusercontent.com
 *  (~5 min cache) or a GitHub Pages prefix. Never sends a sha, so GitHub
 *  itself refuses overwrites with 422. Only putProfileFile opts in to stable profile updates. */
final class GithubUploader implements Hosting.Uploader, Hosting.ProfileUploader {

    private final Hosting.Profile profile;

    GithubUploader(Hosting.Profile p) { this.profile = p; }

    private String apiUrl(String path) {
        return "https://api.github.com/repos/" + profile.cfgStr("owner") + "/"
                + profile.cfgStr("repo") + "/contents/" + path;
    }

    private void headers(HttpURLConnection con) {
        con.setRequestProperty("Authorization", "Bearer " + profile.secret("token"));
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
    }

    @Override public String putFile(byte[] bytes, String relPath, String mime) throws Hosting.HostingException {
        try {
            HttpURLConnection con = Hosting.open(apiUrl(relPath), "PUT");
            headers(con);
            con.setDoOutput(true);
            byte[] body = Hosting.githubContentsBody(bytes, "atelier: " + relPath,
                    profile.cfgStr("branch")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            con.setRequestProperty("Content-Type", "application/json");
            con.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream out = con.getOutputStream()) { out.write(body); }
            int code = con.getResponseCode();
            con.disconnect();
            if (code == 422) {
                throw new Hosting.HostingException("GitHub refused: " + relPath
                        + " already exists (files are never overwritten)");
            }
            if (code == 401 || code == 403) {
                throw new Hosting.HostingException("GitHub auth failed (HTTP " + code
                        + ") — check the token and its repo permissions");
            }
            if (code < 200 || code >= 300) {
                throw new Hosting.HostingException("GitHub upload failed (HTTP " + code + ") at " + relPath);
            }
            return Hosting.publicUrl(profile, relPath, false);
        } catch (Hosting.HostingException he) {
            throw he;
        } catch (Exception e) {
            throw new Hosting.HostingException("GitHub not reachable — " + e.getClass().getSimpleName());
        }
    }

    enum ProfileError { CONFIGURATION, READ, INVALID, CONFLICT, WRITE, UNAVAILABLE }
    static final class ProfileFailure extends Hosting.HostingException {
        final ProfileError reason;
        final int status;
        ProfileFailure(ProfileError reason, int status) {
            super("GitHub profile " + reason + (status == 0 ? "" : " (HTTP " + status + ")"));
            this.reason = reason; this.status = status;
        }
    }

    /** Port of mds/salon/hosting.js putGithubFixed. Only the two mutable profile
     *  files opt in; ordinary media/NFT putFile still never supplies a SHA. */
    @Override public String putProfileFile(byte[] bytes, String relPath, String mime) throws Hosting.HostingException {
        String owner = profile.cfgStr("owner"), repo = profile.cfgStr("repo");
        if (!owner.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}") || !repo.matches("[A-Za-z0-9_.-]+")
                || repo.equals(".") || repo.equals("..") || relPath == null
                || !relPath.matches("[a-z0-9][a-z0-9-]{0,39}/(?:profile\\.json|index\\.html)"))
            throw new ProfileFailure(ProfileError.CONFIGURATION, 0);
        String branch = profile.cfgStr("branch");
        if (branch.isEmpty()) branch = "main";
        try {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            String sha = currentProfileSha(relPath, branch);
            org.json.JSONObject payload = new org.json.JSONObject(
                    Hosting.githubContentsBody(bytes, "salon: " + relPath, branch));
            if (sha != null) payload.put("sha", sha);
            byte[] body = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            HttpURLConnection con = Hosting.open(apiUrl(relPath), "PUT");
            try {
                con.setInstanceFollowRedirects(false);
                headers(con);
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                con.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream out = con.getOutputStream()) { out.write(body); }
                int code = con.getResponseCode();
                if (code == 409 || code == 422) throw new ProfileFailure(ProfileError.CONFLICT, code);
                if (code != 200 && code != 201) throw new ProfileFailure(ProfileError.WRITE, code);
                return Hosting.publicUrl(profile, relPath, false);
            } finally { con.disconnect(); }
        } catch (ProfileFailure failure) { throw failure; }
        catch (Exception unavailable) { throw new ProfileFailure(ProfileError.UNAVAILABLE, 0); }
    }

    private String currentProfileSha(String relPath, String branch) throws Exception {
        String query = java.net.URLEncoder.encode(branch, "UTF-8");
        HttpURLConnection con = Hosting.open(apiUrl(relPath) + "?ref=" + query, "GET");
        try {
            con.setInstanceFollowRedirects(false);
            con.setUseCaches(false);
            headers(con);
            // For >1 MiB files, object media type returns metadata without inline content.
            con.setRequestProperty("Accept", "application/vnd.github.object+json");
            int code = con.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new ProfileFailure(ProfileError.READ, code);
            org.json.JSONObject current;
            try { current = new org.json.JSONObject(profileMetadata(con)); }
            catch (org.json.JSONException invalid) { throw new ProfileFailure(ProfileError.INVALID, 0); }
            Object sha = current.opt("sha");
            if (!"file".equals(current.opt("type")) || !relPath.equals(current.opt("path"))
                    || !(sha instanceof String) || !((String)sha).matches("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})"))
                throw new ProfileFailure(ProfileError.INVALID, 0);
            return (String) sha;
        } finally { con.disconnect(); }
    }

    /** Reuse the core NetFetch bounded read and Salon readCapped interruption/zero-read handling. */
    private String profileMetadata(HttpURLConnection con) throws Exception {
        final int cap = 2 * 1024 * 1024;
        try (java.io.InputStream in = con.getInputStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int total = 0, n;
            while ((n = in.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                if (n == 0) { int one = in.read(); if (one == -1) break; buf[0] = (byte)one; n = 1; }
                if (total + n > cap) throw new ProfileFailure(ProfileError.INVALID, 0);
                out.write(buf, 0, n); total += n;
            }
            return out.toString("UTF-8");
        }
    }

    @Override public boolean exists(String relPath) throws Hosting.HostingException {
        try {
            HttpURLConnection con = Hosting.open(apiUrl(relPath)
                    + "?ref=" + (profile.cfgStr("branch").isEmpty() ? "main" : profile.cfgStr("branch")), "GET");
            headers(con);
            int code = con.getResponseCode();
            con.disconnect();
            if (code == 200) return true;
            if (code == 404) return false;
            throw new Hosting.HostingException("GitHub check failed (HTTP " + code + ") for " + relPath);
        } catch (Hosting.HostingException he) {
            throw he;
        } catch (Exception e) {
            throw new Hosting.HostingException("GitHub not reachable — " + e.getClass().getSimpleName());
        }
    }
}
