package com.wifishare.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Files shared from the phone live in memory as content Uris; received files go to Downloads/WiFiShare. */
final class AndroidStorage implements Storage {

    private static final class Entry {
        final String id;
        final Uri uri;
        final Item item;

        Entry(String id, Uri uri, Item item) {
            this.id = id;
            this.uri = uri;
            this.item = item;
        }
    }

    private final Context context;
    private final List<Entry> entries = new ArrayList<Entry>();
    private int nextId = 1;

    AndroidStorage(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void add(Uri uri) {
        for (Entry e : entries) {
            if (e.uri.equals(uri)) {
                return;
            }
        }
        ContentResolver cr = context.getContentResolver();
        String name = "file";
        long size = -1;
        Cursor c = null;
        try {
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) {
                    name = c.getString(ni);
                }
                if (si >= 0 && !c.isNull(si)) {
                    size = c.getLong(si);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String mime = cr.getType(uri);
        if (mime == null) {
            mime = mimeFor(name);
        }
        String id = String.valueOf(nextId++);
        entries.add(new Entry(id, uri, new Item(id, name, size, mime)));
    }

    synchronized void remove(String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) {
                entries.remove(i);
                return;
            }
        }
    }

    @Override
    public synchronized List<Item> shared() {
        List<Item> out = new ArrayList<Item>();
        for (Entry e : entries) {
            out.add(e.item);
        }
        return out;
    }

    @Override
    public synchronized Item find(String id) {
        for (Entry e : entries) {
            if (e.id.equals(id)) {
                return e.item;
            }
        }
        return null;
    }

    @Override
    public InputStream open(String id) throws IOException {
        Uri uri = null;
        synchronized (this) {
            for (Entry e : entries) {
                if (e.id.equals(id)) {
                    uri = e.uri;
                }
            }
        }
        if (uri == null) {
            throw new FileNotFoundException(id);
        }
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new FileNotFoundException(id);
        }
        return in;
    }

    @Override
    public Sink createReceived(final String name) throws IOException {
        final ContentResolver cr = context.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WiFiShare");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        final Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            throw new IOException("Could not create file");
        }
        final OutputStream os = cr.openOutputStream(uri, "w");
        if (os == null) {
            cr.delete(uri, null, null);
            throw new IOException("Could not open file");
        }
        return new Sink() {
            @Override
            public OutputStream stream() {
                return os;
            }

            @Override
            public String commit() throws IOException {
                os.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                String finalName = name;
                Cursor c = null;
                try {
                    c = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
                    if (c != null && c.moveToFirst() && !c.isNull(0)) {
                        finalName = c.getString(0);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
                return finalName;
            }

            @Override
            public void abort() {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
                try {
                    cr.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
        };
    }

    private static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String m = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) {
                return m;
            }
        }
        return "application/octet-stream";
    }
}
