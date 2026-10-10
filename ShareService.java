package com.wifishare.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public class ShareService extends Service {

    static final String ACTION_START = "com.wifishare.app.START";
    static final String ACTION_STOP = "com.wifishare.app.STOP";

    private static final String CHANNEL_ID = "share";
    private static final int NOTIFICATION_ID = 1;

    // Read by MainActivity to draw the screen.
    static volatile boolean running = false;
    static volatile String code = "";
    static volatile List<String> urls = Collections.emptyList();
    static final List<String> log = Collections.synchronizedList(new ArrayList<String>());

    private static AndroidStorage storage;

    static synchronized AndroidStorage storage(Context c) {
        if (storage == null) {
            storage = new AndroidStorage(c);
        }
        return storage;
    }

    static void addLog(String line) {
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        log.add(time + "  " + line);
        while (log.size() > 40) {
            log.remove(0);
        }
    }

    private ShareServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Sharing status",
                NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopServer();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (server == null) {
            startServer();
        }
        return START_NOT_STICKY;
    }

    private void startServer() {
        final byte[] page = readAsset("index.html");
        String newCode = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
        ShareServer s = new ShareServer(storage(this), newCode, new Supplier<byte[]>() {
            @Override
            public byte[] get() {
                return page;
            }
        }, new ShareServer.Listener() {
            @Override
            public void onLog(String line) {
                addLog(line);
            }
        });
        int port;
        try {
            port = s.start(8080, 8099);
        } catch (IOException e) {
            addLog("Couldn't start: " + e.getMessage());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        server = s;

        List<String> list = new ArrayList<String>();
        for (String ip : localAddresses()) {
            list.add("http://" + ip + ":" + port);
        }
        code = newCode;
        urls = list;
        running = true;
        acquireLocks();
        addLog("Sharing started");
        updateNotification(list.isEmpty() ? "Connect to Wi-Fi to share" : list.get(0));
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        releaseLocks();
        if (running) {
            addLog("Sharing stopped");
        }
        running = false;
        urls = Collections.emptyList();
        code = "";
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ locks

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wifishare:server");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        } catch (Exception ignored) {
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wifishare:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
        wakeLock = null;
        wifiLock = null;
    }

    // ------------------------------------------------------------------ notification

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ShareService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), "Stop sharing", stop).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Wi-Fi Share is on")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(stopAction)
                .build();
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, buildNotification(text));
    }

    // ------------------------------------------------------------------ helpers

    private byte[] readAsset(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
            return b.toByteArray();
        } catch (IOException e) {
            return "Page missing".getBytes();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Private IPv4 addresses, Wi-Fi and hotspot interfaces first. */
    static List<String> localAddresses() {
        List<String> preferred = new ArrayList<String>();
        List<String> other = new ArrayList<String>();
        try {
            List<NetworkInterface> nis = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : nis) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String n = ni.getName();
                boolean wifiLike = n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        (wifiLike ? preferred : other).add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        preferred.addAll(other);
        return preferred;
    }
}
