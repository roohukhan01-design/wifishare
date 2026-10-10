package com.wifishare.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Runs the real ShareServer on this computer against an in-memory Storage.
 *
 * From the project folder:
 *   javac -d /tmp/out app/src/main/java/com/wifishare/app/Storage.java \
 *       app/src/main/java/com/wifishare/app/ShareServer.java tools/ServerSelfTest.java
 *   java -cp /tmp/out com.wifishare.app.ServerSelfTest
 */
public class ServerSelfTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            fail++;
            System.out.println("FAIL  " + name);
        }
    }

    static final class MemStorage implements Storage {
        final ConcurrentHashMap<String, byte[]> data = new ConcurrentHashMap<String, byte[]>();
        final List<Item> items = new ArrayList<Item>();
        final ConcurrentHashMap<String, byte[]> received = new ConcurrentHashMap<String, byte[]>();
        final ConcurrentLinkedQueue<String> aborted = new ConcurrentLinkedQueue<String>();

        void add(String id, String name, byte[] bytes) {
            data.put(id, bytes);
            items.add(new Item(id, name, bytes.length, "application/octet-stream"));
        }

        public List<Item> shared() {
            return new ArrayList<Item>(items);
        }

        public Item find(String id) {
            for (Item i : items) {
                if (i.id.equals(id)) {
                    return i;
                }
            }
            return null;
        }

        public InputStream open(String id) throws IOException {
            byte[] b = data.get(id);
            if (b == null) {
                throw new IOException("gone");
            }
            return new ByteArrayInputStream(b);
        }

        public Sink createReceived(final String name) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            return new Sink() {
                public OutputStream stream() {
                    return bos;
                }

                public String commit() {
                    String finalName = name;
                    int n = 1;
                    while (received.containsKey(finalName)) {
                        finalName = name + " (" + (n++) + ")";
                    }
                    received.put(finalName, bos.toByteArray());
                    return finalName;
                }

                public void abort() {
                    aborted.add(name);
                }
            };
        }
    }

    static String sha(byte[] b) throws Exception {
        return Arrays.toString(MessageDigest.getInstance("SHA-256").digest(b));
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        return b.toByteArray();
    }

    static HttpURLConnection open(int port, String path, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(20000);
        return c;
    }

    static int status(int port, String path) throws Exception {
        HttpURLConnection c = open(port, path, "GET");
        int s = c.getResponseCode();
        InputStream in = s >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) {
            readAll(in);
        }
        return s;
    }

    static int upload(int port, String code, String name, byte[] body) throws Exception {
        HttpURLConnection c = open(port, "/api/upload?key=" + code + "&name="
                + URLEncoder.encode(name, "UTF-8"), "POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        OutputStream o = c.getOutputStream();
        o.write(body);
        o.close();
        int s = c.getResponseCode();
        InputStream in = s >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) {
            readAll(in);
        }
        return s;
    }

    public static void main(String[] args) throws Exception {
        final MemStorage st = new MemStorage();
        Random rnd = new Random(42);
        byte[] big = new byte[3 * 1024 * 1024 + 123];
        rnd.nextBytes(big);
        st.add("1", "photo \u00e9\u00e8 \u65e5\u672c.jpg", big);
        st.add("2", "empty.txt", new byte[0]);
        st.add("3", "quote\"name.txt", "hello world".getBytes(StandardCharsets.UTF_8));

        final String code = "123456";
        ShareServer server = new ShareServer(st, code, new Supplier<byte[]>() {
            public byte[] get() {
                return "<html>page</html>".getBytes(StandardCharsets.UTF_8);
            }
        }, null);
        int port = server.start(18080, 18099);
        System.out.println("Server on port " + port);

        // page + auth
        check("page needs no code", status(port, "/") == 200);
        check("api without code is 401", status(port, "/api/files") == 401);
        check("wrong code is 401", status(port, "/api/ping?key=000000") == 401);
        check("right code pings", status(port, "/api/ping?key=" + code) == 200);

        HttpURLConnection c = open(port, "/api/files?key=" + code, "GET");
        String json = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
        check("files list has 3 items and unicode name", json.contains("\"id\":\"3\"") && json.contains("\u65e5\u672c")
                && json.contains("quote\\\"name.txt"));

        // download whole file
        c = open(port, "/d/1?key=" + code, "GET");
        byte[] got = readAll(c.getInputStream());
        check("download is byte-identical", sha(got).equals(sha(big)));
        String cd = c.getHeaderField("Content-Disposition");
        check("download has UTF-8 filename", cd != null && cd.contains("filename*=UTF-8''photo%20%C3%A9"));
        check("download has content length", c.getContentLengthLong() == big.length);

        // empty + quote file
        c = open(port, "/d/2?key=" + code, "GET");
        check("empty file downloads", readAll(c.getInputStream()).length == 0);
        c = open(port, "/d/3?key=" + code, "GET");
        check("small file downloads", new String(readAll(c.getInputStream()), StandardCharsets.UTF_8).equals("hello world"));
        check("unknown id is 404", status(port, "/d/99?key=" + code) == 404);

        // ranges
        c = open(port, "/d/1?key=" + code, "GET");
        c.setRequestProperty("Range", "bytes=1000-1999");
        byte[] part = readAll(c.getInputStream());
        check("range 206", c.getResponseCode() == 206
                && Arrays.equals(part, Arrays.copyOfRange(big, 1000, 2000)));
        check("content-range header", ("bytes 1000-1999/" + big.length).equals(c.getHeaderField("Content-Range")));
        c = open(port, "/d/1?key=" + code, "GET");
        c.setRequestProperty("Range", "bytes=" + (big.length - 10) + "-");
        part = readAll(c.getInputStream());
        check("open-ended range", Arrays.equals(part, Arrays.copyOfRange(big, big.length - 10, big.length)));
        c = open(port, "/d/1?key=" + code, "GET");
        c.setRequestProperty("Range", "bytes=-5");
        part = readAll(c.getInputStream());
        check("suffix range", Arrays.equals(part, Arrays.copyOfRange(big, big.length - 5, big.length)));
        c = open(port, "/d/1?key=" + code, "GET");
        c.setRequestProperty("Range", "bytes=" + (big.length + 5) + "-");
        check("range past end is 416", c.getResponseCode() == 416);

        // uploads
        byte[] up = new byte[20 * 1024 * 1024];
        rnd.nextBytes(up);
        check("20 MB upload ok", upload(port, code, "big \u00fc\u00f1\u00ed.bin", up) == 200);
        check("20 MB upload identical", st.received.get("big \u00fc\u00f1\u00ed.bin") != null
                && sha(st.received.get("big \u00fc\u00f1\u00ed.bin")).equals(sha(up)));
        check("empty upload ok", upload(port, code, "zero.txt", new byte[0]) == 200
                && st.received.get("zero.txt").length == 0);
        check("path tricks are cleaned", upload(port, code, "../../etc/passwd", "x".getBytes()) == 200
                && st.received.keySet().toString().contains("etc_passwd")
                && !st.received.keySet().toString().contains("/"));
        check("same name gets a new name", upload(port, code, "zero.txt", "again".getBytes()) == 200
                && st.received.containsKey("zero.txt (1)"));
        check("upload with wrong code is 401", upload(port, "999999", "no.txt", "x".getBytes()) == 401);
        check("rejected upload saves nothing", !st.received.containsKey("no.txt"));

        // aborted upload: promise 1 MB, send 100 KB, hang up
        Socket s = new Socket("127.0.0.1", port);
        OutputStream so = s.getOutputStream();
        so.write(("POST /api/upload?key=" + code + "&name=partial.bin HTTP/1.1\r\nHost: x\r\n"
                + "Content-Length: 1048576\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        so.write(new byte[100 * 1024]);
        so.flush();
        s.close();
        Thread.sleep(500);
        check("aborted upload is discarded", st.aborted.contains("partial.bin") && !st.received.containsKey("partial.bin"));

        // chunked upload is refused clearly
        s = new Socket("127.0.0.1", port);
        so = s.getOutputStream();
        so.write(("POST /api/upload?key=" + code + "&name=c.bin HTTP/1.1\r\nHost: x\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        so.flush();
        String resp = new String(readAll(s.getInputStream()), StandardCharsets.ISO_8859_1);
        check("chunked upload gets 411", resp.startsWith("HTTP/1.1 411"));
        s.close();

        // garbage request does not kill the server
        s = new Socket("127.0.0.1", port);
        s.getOutputStream().write("\u0000\u0001garbage\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        s.close();
        check("server survives garbage", status(port, "/api/ping?key=" + code) == 200);

        // parallel mixed traffic
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> fs = new ArrayList<Future<Boolean>>();
        for (int i = 0; i < 8; i++) {
            final int n = i;
            fs.add(ex.submit(new java.util.concurrent.Callable<Boolean>() {
                public Boolean call() throws Exception {
                    if (n % 2 == 0) {
                        HttpURLConnection cc = open(port, "/d/1?key=" + code, "GET");
                        return sha(readAll(cc.getInputStream())).equals(sha(big));
                    }
                    byte[] b = new byte[2 * 1024 * 1024];
                    new Random(n).nextBytes(b);
                    int sc = upload(port, code, "par" + n + ".bin", b);
                    return sc == 200 && sha(st.received.get("par" + n + ".bin")).equals(sha(b));
                }
            }));
        }
        boolean allOk = true;
        for (Future<Boolean> f : fs) {
            allOk &= f.get();
        }
        ex.shutdown();
        check("8 parallel transfers all correct", allOk);

        // lockout
        for (int i = 0; i < 8; i++) {
            status(port, "/api/ping?key=bad" + i);
        }
        check("locked after repeated wrong codes", status(port, "/api/ping?key=bad9") == 429);
        check("right code is refused while locked", status(port, "/api/ping?key=" + code) == 429);

        server.stop();
        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
