package com.kerneldb.storage;

import com.kerneldb.core.KernelDBMetrics;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Performs positional I/O on the custom students.db file. */
public final class FileManager implements AutoCloseable {
    private final Path databasePath;
    private final FileChannel channel;
    private final KernelDBMetrics metrics;

    public FileManager(Path databasePath, KernelDBMetrics metrics) throws IOException {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.metrics = metrics;
        Path parent = this.databasePath.getParent();
        if (parent != null) Files.createDirectories(parent);
        this.channel = FileChannel.open(this.databasePath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long size = channel.size();
        if (size % Page.PAGE_SIZE != 0) {
            channel.close();
            throw new IOException("Database file size is not a multiple of the " + Page.PAGE_SIZE + "-byte page size.");
        }
    }

    public Path databasePath() { return databasePath; }

    public synchronized int pageCount() throws IOException {
        long size = channel.size();
        if (size % Page.PAGE_SIZE != 0) throw new IOException("Database file contains a partial page.");
        long count = size / Page.PAGE_SIZE;
        if (count > Integer.MAX_VALUE) throw new IOException("Database contains too many pages for this demo.");
        return (int) count;
    }

    public synchronized Page readPage(int pageNumber) throws IOException {
        if (pageNumber < 0 || pageNumber >= pageCount()) {
            throw new IOException("Page " + pageNumber + " does not exist on disk.");
        }
        byte[] bytes = new byte[Page.PAGE_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long position = (long) pageNumber * Page.PAGE_SIZE;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) throw new EOFException("Unexpected end of database file at page " + pageNumber + ".");
            if (read == 0) Thread.onSpinWait();
        }
        metrics.fileRead();
        return Page.fromBytes(pageNumber, bytes);
    }

    public synchronized void writePage(Page page) throws IOException {
        byte[] bytes = page.toBytes();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long position = (long) page.pageNumber() * Page.PAGE_SIZE;
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer, position + buffer.position());
            if (written == 0) Thread.onSpinWait();
        }
        metrics.fileWrite();
    }

    public synchronized void force() throws IOException {
        channel.force(true);
    }

    @Override
    public synchronized void close() throws IOException {
        channel.force(true);
        channel.close();
    }
}
