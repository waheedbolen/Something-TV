package com.tvrefresh.home;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Plain HTTPS GETs. Always called off the main thread. */
final class Net {
    private Net() {}

    private static HttpURLConnection open(String url) throws IOException {
        return open(url, null);
    }

    private static HttpURLConnection open(String url, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "TVRefreshHome/1.0 (Android TV)");
        c.setRequestProperty("Accept", "application/json, image/*");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return c;
    }

    static String text(String url) throws IOException {
        return text(url, null);
    }

    static String text(String url, String bearer) throws IOException {
        HttpURLConnection c = open(url, bearer);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** Writes to a temporary file first, so a half-downloaded picture is never shown. */
    static void download(String url, File dest) throws IOException {
        File part = new File(dest.getPath() + ".part");
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            copy(in, out);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(dest)) {
            part.delete();
            throw new IOException("could not move " + part + " into place");
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
    }
}
