package com.wifishare.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_NOTIFICATIONS = 2;

    private static final int PAPER = Color.parseColor("#F1F3EF");
    private static final int INK = Color.parseColor("#16252B");
    private static final int MUTED = Color.parseColor("#5C6B70");
    private static final int LINE = Color.parseColor("#D3DAD5");
    private static final int COBALT = Color.parseColor("#2447F5");
    private static final int DANGER = Color.parseColor("#B3261E");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    private TextView statusView;
    private TextView urlView;
    private TextView codeView;
    private TextView hintView;
    private TextView emptyView;
    private TextView logView;
    private Button toggleButton;
    private Button copyButton;
    private LinearLayout filesBox;
    private String lastFilesSignature = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    // ------------------------------------------------------------------ UI construction

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAPER);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Wi-Fi Share", 30, INK, true);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        root.addView(title);

        TextView sub = text("Send files between this phone and a PC on the same Wi-Fi. "
                + "Nothing goes through the internet.", 15, MUTED, false);
        sub.setPadding(0, dp(6), 0, dp(20));
        root.addView(sub);

        // Status card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        card.setBackground(rounded(Color.WHITE, 16, LINE));
        root.addView(card, matchWrap());

        statusView = text("Sharing is off", 14, MUTED, false);
        card.addView(statusView);

        urlView = text("", 20, INK, true);
        urlView.setTextIsSelectable(true);
        urlView.setPadding(0, dp(8), 0, 0);
        card.addView(urlView);

        codeView = text("", 28, COBALT, true);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setPadding(0, dp(6), 0, 0);
        card.addView(codeView);

        hintView = text("", 14, MUTED, false);
        hintView.setPadding(0, dp(8), 0, 0);
        card.addView(hintView);

        copyButton = button("Copy link", false);
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<String> urls = ShareService.urls;
                if (!urls.isEmpty()) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Wi-Fi Share link", urls.get(0)));
                    Toast.makeText(MainActivity.this, "Link copied", Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams cp = matchWrap();
        cp.topMargin = dp(12);
        card.addView(copyButton, cp);

        toggleButton = button("Start sharing", true);
        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggle();
            }
        });
        LinearLayout.LayoutParams tp = matchWrap();
        tp.topMargin = dp(14);
        root.addView(toggleButton, tp);

        // Files for the PC
        TextView filesTitle = text("Files for your PC", 18, INK, true);
        filesTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
        filesTitle.setPadding(0, dp(28), 0, dp(4));
        root.addView(filesTitle);

        TextView filesHint = text("Pick files here, then download them from the page on your PC.", 14, MUTED, false);
        filesHint.setPadding(0, 0, 0, dp(10));
        root.addView(filesHint);

        filesBox = new LinearLayout(this);
        filesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(filesBox, matchWrap());

        emptyView = text("Nothing added yet.", 14, MUTED, false);
        emptyView.setPadding(0, dp(4), 0, dp(4));
        root.addView(emptyView);

        Button add = button("Add files", false);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_PICK);
            }
        });
        LinearLayout.LayoutParams ap = matchWrap();
        ap.topMargin = dp(10);
        root.addView(add, ap);

        // Received
        TextView recvTitle = text("From your PC", 18, INK, true);
        recvTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
        recvTitle.setPadding(0, dp(28), 0, dp(4));
        root.addView(recvTitle);

        TextView recvHint = text("Files you send from the PC are saved in Downloads/WiFiShare.", 14, MUTED, false);
        recvHint.setPadding(0, 0, 0, dp(10));
        root.addView(recvHint);

        logView = text("", 13, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        root.addView(logView);

        return scroll;
    }

    // ------------------------------------------------------------------ refresh

    private void refresh() {
        boolean on = ShareService.running;
        List<String> urls = ShareService.urls;

        if (on) {
            statusView.setText("Sharing is on. Open this link on your PC:");
            if (urls.isEmpty()) {
                urlView.setText("No Wi-Fi found");
                urlView.setTextColor(DANGER);
                codeView.setText("");
                hintView.setText("Connect to Wi-Fi, or turn on this phone's hotspot and join it from your PC.");
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < urls.size(); i++) {
                    if (i > 0) {
                        sb.append('\n');
                    }
                    sb.append(urls.get(i));
                }
                urlView.setText(sb.toString());
                urlView.setTextColor(INK);
                String c = ShareService.code;
                codeView.setText(c.length() == 6 ? c.substring(0, 3) + " " + c.substring(3) : c);
                hintView.setText("Type this code on the page. Anyone on your Wi-Fi who has the code can connect.");
            }
        } else {
            statusView.setText("Sharing is off");
            urlView.setText("Tap Start sharing to get a link and a code.");
            urlView.setTextColor(MUTED);
            urlView.setTextSize(16);
            codeView.setText("");
            hintView.setText("");
        }
        if (on) {
            urlView.setTextSize(20);
        }
        hintView.setVisibility(hintView.getText().length() == 0 ? View.GONE : View.VISIBLE);
        codeView.setVisibility(codeView.getText().length() == 0 ? View.GONE : View.VISIBLE);
        copyButton.setVisibility(on && !urls.isEmpty() ? View.VISIBLE : View.GONE);
        styleButton(toggleButton, on ? "Stop sharing" : "Start sharing", !on);

        List<Storage.Item> items = ShareService.storage(this).shared();
        StringBuilder sig = new StringBuilder();
        for (Storage.Item it : items) {
            sig.append(it.id).append(',');
        }
        if (!sig.toString().equals(lastFilesSignature)) {
            lastFilesSignature = sig.toString();
            rebuildFiles(items);
        }

        synchronized (ShareService.log) {
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, ShareService.log.size() - 10);
            for (int i = ShareService.log.size() - 1; i >= from; i--) {
                sb.append(ShareService.log.get(i)).append('\n');
            }
            logView.setText(sb.length() == 0 ? "Nothing yet." : sb.toString().trim());
        }
    }

    private void rebuildFiles(List<Storage.Item> items) {
        filesBox.removeAllViews();
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        for (final Storage.Item it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(8), dp(10));
            row.setBackground(rounded(Color.WHITE, 12, LINE));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(it.name, 15, INK, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            col.addView(name);
            col.addView(text(it.size >= 0 ? humanSize(it.size) : "Size unknown", 13, MUTED, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button remove = new Button(this);
            remove.setText("Remove");
            remove.setAllCaps(false);
            remove.setTextColor(DANGER);
            remove.setTextSize(14);
            remove.setBackgroundColor(Color.TRANSPARENT);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ShareService.storage(MainActivity.this).remove(it.id);
                    refresh();
                }
            });
            row.addView(remove, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));

            LinearLayout.LayoutParams lp = matchWrap();
            lp.bottomMargin = dp(8);
            filesBox.addView(row, lp);
        }
    }

    // ------------------------------------------------------------------ actions

    private void onToggle() {
        if (ShareService.running) {
            startService(new Intent(this, ShareService.class).setAction(ShareService.ACTION_STOP));
        } else if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        } else {
            startSharing();
        }
    }

    private void startSharing() {
        startForegroundService(new Intent(this, ShareService.class).setAction(ShareService.ACTION_START));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            // Sharing works without the notification permission, so start either way.
            startSharing();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        AndroidStorage storage = ShareService.storage(this);
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                addUri(storage, clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            addUri(storage, data.getData());
        }
        refresh();
    }

    private void addUri(AndroidStorage storage, android.net.Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        storage.add(uri);
    }

    // ------------------------------------------------------------------ view helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            d.setStroke(dp(1), stroke);
        }
        return d;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(52));
        styleButton(b, label, primary);
        return b;
    }

    private void styleButton(Button b, String label, boolean primary) {
        b.setText(label);
        if (primary) {
            b.setTextColor(Color.WHITE);
            b.setBackground(rounded(COBALT, 14, 0));
        } else {
            b.setTextColor(INK);
            b.setBackground(rounded(Color.WHITE, 14, LINE));
        }
    }

    private static String humanSize(long b) {
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
