/*
 * Copyright 2003-2012 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.contentpump;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

public class OutputArchive {
    public static final Log LOG = LogFactory.getLog(OutputArchive.class);
    // avoid maximum number of zip entry to reach over 65535
    // number of entries is even as we have metadata, so max is 65534
    static final int MAX_ENTRIES = 65534;
    public static String EXTENSION = ".zip";
    private long currentFileBytes = 0;
    private ZipOutputStream outputStream;
    private String path;
    private static AtomicInteger fileCount = new AtomicInteger();
    private int currentEntries;
    private Configuration conf;
    
    public OutputArchive(String path, Configuration conf) {
        if (path.toLowerCase().endsWith(EXTENSION)) {
            this.path = path;
        } else {
            this.path = path + EXTENSION;
        }
        this.conf = conf;
    }

    private void newOutputStream() throws IOException {
        String file = path;
        // use the constructor filename for the first zip,
        // then add filecount to subsequent archives, if any.
        int count = fileCount.getAndIncrement();
        file = newPackagePath(path, count, 6);
        if (outputStream != null) {
            outputStream.flush();
            outputStream.close();
        }
        currentFileBytes = 0;
        currentEntries = 0;

        Path zpath = new Path(file);
        FileSystem fs = zpath.getFileSystem(conf);
        if (fs.exists(zpath)) {
            throw new IOException(zpath + " already exists.");
        }
        FSDataOutputStream fsout = fs.create(zpath, false);

        outputStream = new ZipOutputStream(fsout);
    }

    /**
     * @param canonicalPath
     * @param count
     * @param width
     * @return
     */
    static protected String newPackagePath(String canonicalPath, int count,
        int width) {
        String path = canonicalPath;
        if (path.endsWith(EXTENSION)) {
            int index1 = path.lastIndexOf(EXTENSION);
            String subStr = path.substring(0, index1);
            int index2 = subStr.lastIndexOf('.');
            path = path.substring(0, index2)
                + String.format("-%0" + width + "d", count)
                + path.substring(index2);
        } else {
            path = path + "-" + count;
        }
        assert path.equals(canonicalPath);
        return path;
    }

    public long write(String outputPath, byte[] bytes) throws IOException {

        if (null == outputPath) {
            throw new NullPointerException("null path");
        }
        if (null == bytes) {
            throw new NullPointerException("null content bytes");
        }

        long total = bytes.length;
        ZipEntry entry = new ZipEntry(outputPath);

        if (outputStream == null) {
            // lazily construct a new zipfile outputstream
            LOG.info("constructing new output stream");
            newOutputStream();
        }

        // by checking outputBytes first, we should avoid infinite loops -
        // at the cost of fatal exceptions.
        if (currentFileBytes > 0
            && currentFileBytes + total > Integer.MAX_VALUE) {
            LOG.warn("too many bytes in current package");
            newOutputStream();
        }

        // don't create zips that Java can't read back in
        if (currentEntries > 0 && (currentEntries + 2) >= MAX_ENTRIES) {
            LOG.warn("too many entries in current package");
            newOutputStream();
        }

        try {
            outputStream.putNextEntry(entry);
            outputStream.write(bytes);
            outputStream.closeEntry();
            /*
             * // Flush once in a while to see if this frees up memory if
             * (currentEntries % 10 == 0) { outputStream.flush(); }
             */
        } catch (ZipException e) {
            // if (configuration.isSkipExisting()
            // && e.getMessage().startsWith("duplicate entry")) {
            LOG.warn("skipping duplicate entry: " + entry.getName());
            return 0;
            // }
//            throw e;
        }

        currentFileBytes += total;
        currentEntries++;

        return total;

    }

    public void close() throws IOException {
        if (outputStream != null) {
            outputStream.flush();
            outputStream.close();
        }
    }

}
