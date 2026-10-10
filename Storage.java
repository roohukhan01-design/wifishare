package com.wifishare.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Where the server reads shared files from and writes received files to.
 * Pure Java so the server can be tested on a normal JVM.
 */
public interface Storage {

    final class Item {
        public final String id;
        public final String name;
        public final long size; // -1 when unknown
        public final String mime;

        public Item(String id, String name, long size, String mime) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.mime = mime;
        }
    }

    /** A file being received from the PC. */
    interface Sink {
        OutputStream stream();

        /** Finishes the file and returns the name it was saved under. */
        String commit() throws IOException;

        /** Throws away a partial file. */
        void abort();
    }

    /** Files on the phone that the PC can download. */
    List<Item> shared();

    Item find(String id);

    InputStream open(String id) throws IOException;

    Sink createReceived(String name) throws IOException;
}
