package com.wifishare.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * A small HTTP/1.1 server. No dependencies, no Android classes.
 *
 * Routes:
 *   GET  /                      the PC web page (no code needed)
 *   GET  /api/ping?key=         200 if the code is right
 *   GET  /api/files?key=        JSON list of files shared from the phone
 *   GET  /d/{id}?key=           download (supports Range)
 *   POST /api/upload?key=&name= raw request body is saved on the phone
 */
public final class ShareServer {

    public interface Listener {
        void onLog(String line);
    }

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_FAILS = 8;
    private static final long LOCK_MS = 60_000L;

    private final Storage storage;
    private final byte[] codeBytes;
    private final Supplier<byte[]> page;
    private final Listener listener;
    private final Map<String, long[]> fails = new HashMap<String, long[]>(); // ip -> {count, lockedUntil}

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;

    public ShareServer(Storage storage, String code, Supplier<byte[]> page, Listener listener) {
        this.storage = storage;
        this.codeBytes = code.getBytes(StandardCharsets.UTF_8);
        this.page = page;
        this.listener = listener;
    }

    /** Binds the first free port in the range and returns it. */
    public synchronized int start(int firstPort, int lastPort) throws IOException {
        IOException last = null;
        for (int p = firstPort; p <= lastPort && serverSocket == null; p++) {
            ServerSocket ss = new ServerSocket();
            try {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p));
                serverSocket = ss;
            } catch (IOException e) {
                last = e;
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (serverSocket == null) {
            throw last != null ? last : new IOException("No free port");
        }
        pool = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "share-worker");
                t.setDaemon(true);
                return t;
            }
        });
        running = true;
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "share-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        return serverSocket.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void acceptLoop() {
        while (running) {
            final Socket s;
            try {
                ServerSocket ss = serverSocket;
                if (ss == null) {
                    return;
                }
                s = ss.accept();
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                continue;
            }
            try {
                ExecutorService p = pool;
                if (p == null) {
                    s.close();
                    return;
                }
                p.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(s);
                    }
                });
            } catch (Exception e) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ request handling

    private static final class Request {
        String method = "";
        String path = "/";
        final Map<String, String> query = new HashMap<String, String>();
        final Map<String, String> headers = new HashMap<String, String>();
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30_000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            Request req = readRequest(in);
            if (req == null) {
                return;
            }
            route(req, s.getInetAddress().getHostAddress(), in, out);
            out.flush();
        } catch (Exception ignored) {
            // client went away or timed out; nothing to report
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void route(Request r, String ip, InputStream in, OutputStream out) throws IOException {
        String path = r.path;

        if (path.equals("/") || path.equals("/index.html")) {
            byte[] body = page.get();
            writeHead(out, 200, "text/html; charset=utf-8", body.length);
            if (!"HEAD".equals(r.method)) {
                out.write(body);
            }
            return;
        }
        if (path.equals("/favicon.ico")) {
            writeHead(out, 204, "text/plain", 0);
            return;
        }

        int auth = authorize(ip, r);
        if (auth == 429) {
            sendJson(out, 429, "{\"error\":\"locked\"}");
            return;
        }
        if (auth != 200) {
            sendJson(out, 401, "{\"error\":\"bad code\"}");
            return;
        }

        if (path.equals("/api/ping")) {
            sendJson(out, 200, "{\"ok\":true}");
        } else if (path.equals("/api/files")) {
            sendJson(out, 200, filesJson());
        } else if (path.startsWith("/d/")) {
            download(r, out);
        } else if (path.equals("/api/upload")) {
            upload(r, in, out);
        } else {
            sendJson(out, 404, "{\"error\":\"not found\"}");
        }
    }

    // ------------------------------------------------------------------ auth

    private int authorize(String ip, Request r) {
        String key = r.query.get("key");
        if (key == null) {
            key = r.headers.get("x-key");
        }
        if (isLocked(ip)) {
            return 429;
        }
        if (key == null || key.isEmpty()) {
            return 401;
        }
        if (MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), codeBytes)) {
            return 200;
        }
        recordFailure(ip);
        return isLocked(ip) ? 429 : 401;
    }

    private synchronized boolean isLocked(String ip) {
        long[] f = fails.get(ip);
        if (f == null) {
            return false;
        }
        if (f[1] != 0 && System.currentTimeMillis() >= f[1]) {
            fails.remove(ip);
            return false;
        }
        return f[1] != 0;
    }

    private synchronized void recordFailure(String ip) {
        long[] f = fails.get(ip);
        if (f == null) {
            f = new long[]{0, 0};
            fails.put(ip, f);
        }
        f[0]++;
        if (f[0] >= MAX_FAILS) {
            f[1] = System.currentTimeMillis() + LOCK_MS;
            log("Too many wrong codes from " + ip + ". Locked for a minute.");
        }
    }

    // ------------------------------------------------------------------ routes

    private String filesJson() {
        StringBuilder sb = new StringBuilder("{\"shared\":[");
        List<Storage.Item> items = storage.shared();
        for (int i = 0; i < items.size(); i++) {
            Storage.Item it = items.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(jsonStr(it.id))
                    .append(",\"name\":").append(jsonStr(it.name))
                    .append(",\"size\":").append(it.size).append('}');
        }
        return sb.append("]}").toString();
    }

    private void download(Request r, OutputStream out) throws IOException {
        String id = urlDecode(r.path.substring(3));
        Storage.Item it = storage.find(id);
        if (it == null) {
            sendJson(out, 404, "{\"error\":\"not found\"}");
            return;
        }

        long size = it.size;
        long start = 0;
        long end = size - 1;
        boolean partial = false;

        String range = r.headers.get("range");
        if (range != null && size >= 0 && range.startsWith("bytes=") && range.indexOf(',') < 0) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                try {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    long s;
                    long e;
                    if (a.isEmpty()) {
                        long n = Long.parseLong(b);
                        if (n <= 0) {
                            throw new NumberFormatException();
                        }
                        s = Math.max(0, size - n);
                        e = size - 1;
                    } else {
                        s = Long.parseLong(a);
                        e = b.isEmpty() ? size - 1 : Math.min(Long.parseLong(b), size - 1);
                    }
                    if (s > e || s >= size) {
                        writeHead(out, 416, "application/json", 0, "Content-Range: bytes */" + size);
                        return;
                    }
                    start = s;
                    end = e;
                    partial = true;
                } catch (NumberFormatException ignored) {
                    // malformed range: serve the whole file
                }
            }
        }

        long length = size >= 0 ? end - start + 1 : -1;

        InputStream src;
        try {
            src = storage.open(id);
        } catch (IOException e) {
            sendJson(out, 500, "{\"error\":\"Can't open this file on the phone. Add it again.\"}");
            return;
        }
        try {
            skipFully(src, start);
            String mime = it.mime == null || it.mime.isEmpty() ? "application/octet-stream" : it.mime;
            String disposition = "Content-Disposition: attachment; filename=\"" + asciiFallback(it.name)
                    + "\"; filename*=UTF-8''" + pctEncode(it.name);
            if (partial) {
                writeHead(out, 206, mime, length, disposition, "Accept-Ranges: bytes",
                        "Content-Range: bytes " + start + "-" + end + "/" + size);
            } else {
                writeHead(out, 200, mime, length, disposition, "Accept-Ranges: bytes");
            }
            if (start == 0) {
                log("Sending " + it.name);
            }
            if (!"HEAD".equals(r.method)) {
                byte[] buf = new byte[64 * 1024];
                long left = length;
                while (left != 0) {
                    int want = left < 0 ? buf.length : (int) Math.min(buf.length, left);
                    int n = src.read(buf, 0, want);
                    if (n < 0) {
                        break;
                    }
                    out.write(buf, 0, n);
                    if (left > 0) {
                        left -= n;
                    }
                }
            }
        } finally {
            try {
                src.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void upload(Request r, InputStream in, OutputStream out) throws IOException {
        if (!"POST".equals(r.method) && !"PUT".equals(r.method)) {
            sendJson(out, 405, "{\"error\":\"use POST\"}");
            return;
        }
        String te = r.headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            sendJson(out, 411, "{\"error\":\"length required\"}");
            return;
        }
        String cl = r.headers.get("content-length");
        if (cl == null) {
            sendJson(out, 411, "{\"error\":\"length required\"}");
            return;
        }
        long total;
        try {
            total = Long.parseLong(cl.trim());
        } catch (NumberFormatException e) {
            sendJson(out, 400, "{\"error\":\"bad length\"}");
            return;
        }
        if (total < 0) {
            sendJson(out, 400, "{\"error\":\"bad length\"}");
            return;
        }

        String name = cleanName(r.query.get("name"));
        Storage.Sink sink;
        try {
            sink = storage.createReceived(name);
        } catch (IOException e) {
            sendJson(out, 500, "{\"error\":\"The phone couldn't create the file.\"}");
            return;
        }

        OutputStream dst = sink.stream();
        byte[] buf = new byte[64 * 1024];
        long left = total;
        boolean clientGone = false;
        boolean storageFailed = false;
        while (left > 0) {
            int n;
            try {
                n = in.read(buf, 0, (int) Math.min(buf.length, left));
            } catch (IOException e) {
                clientGone = true;
                break;
            }
            if (n < 0) {
                clientGone = true;
                break;
            }
            try {
                dst.write(buf, 0, n);
            } catch (IOException e) {
                storageFailed = true;
                break;
            }
            left -= n;
        }

        if (clientGone || storageFailed) {
            sink.abort();
            if (clientGone) {
                log("Upload cancelled: " + name);
            } else {
                log("Couldn't save " + name + ". The phone may be out of space.");
                sendJson(out, 507, "{\"error\":\"The phone couldn't save the file. Check its free space.\"}");
            }
            return;
        }

        String finalName;
        try {
            finalName = sink.commit();
        } catch (IOException e) {
            sink.abort();
            sendJson(out, 500, "{\"error\":\"The phone couldn't finish saving the file.\"}");
            return;
        }
        log("Received " + finalName + " (" + humanSize(total) + ")");
        sendJson(out, 200, "{\"ok\":true,\"name\":" + jsonStr(finalName) + ",\"size\":" + total + "}");
    }

    // ------------------------------------------------------------------ helpers

    private void log(String line) {
        if (listener != null) {
            listener.onLog(line);
        }
    }

    private static Request readRequest(InputStream in) throws IOException {
        String first = readLine(in);
        if (first == null || first.isEmpty()) {
            return null;
        }
        String[] parts = first.split(" ");
        if (parts.length < 3) {
            return null;
        }
        Request r = new Request();
        r.method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) {
            for (String pair : target.substring(q + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String k = urlDecode(eq >= 0 ? pair.substring(0, eq) : pair);
                String v = eq >= 0 ? urlDecode(pair.substring(eq + 1)) : "";
                r.query.put(k, v);
            }
        }
        int total = first.length();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            total += line.length();
            if (total > MAX_HEADER_BYTES) {
                return null;
            }
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            r.headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
        }
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        boolean any = false;
        int c;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.write(c);
            }
            if (b.size() > MAX_HEADER_BYTES) {
                throw new IOException("header line too long");
            }
        }
        return any ? new String(b.toByteArray(), StandardCharsets.ISO_8859_1) : null;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    return;
                }
                skipped = 1;
            }
            n -= skipped;
        }
    }

    private static void writeHead(OutputStream out, int status, String contentType, long length, String... extra)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        if (length >= 0) {
            sb.append("Content-Length: ").append(length).append("\r\n");
        }
        sb.append("Connection: close\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("X-Content-Type-Options: nosniff\r\n");
        for (String e : extra) {
            sb.append(e).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void sendJson(OutputStream out, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        writeHead(out, status, "application/json; charset=utf-8", body.length);
        out.write(body);
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    static String cleanName(String raw) {
        String s = raw == null ? "" : raw;
        s = s.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001f]", "_").trim();
        while (s.startsWith(".")) {
            s = s.substring(1).trim();
        }
        if (s.length() > 150) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 12 ? s.substring(dot) : "";
            s = s.substring(0, 150 - ext.length()) + ext;
        }
        return s.isEmpty() ? "file" : s;
    }

    private static String asciiFallback(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' && c != '%' ? c : '_');
        }
        return sb.toString();
    }

    private static String pctEncode(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xff;
            boolean safe = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0;
            if (safe) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return sb.toString();
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    static String humanSize(long b) {
        if (b < 1024) {
            return b + " B";
        }
        double v = b;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }
}
