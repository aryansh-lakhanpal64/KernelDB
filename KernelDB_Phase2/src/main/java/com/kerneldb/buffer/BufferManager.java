package com.kerneldb.buffer;

import com.kerneldb.core.KernelDBMetrics;
import com.kerneldb.storage.FileManager;
import com.kerneldb.storage.Page;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe page buffer with access-order (LRU) replacement and dirty-page write-back. */
public final class BufferManager {
    private static final class Frame {
        private final Page page;
        private boolean dirty;
        private Frame(Page page) { this.page = page; }
    }

    private final FileManager fileManager;
    private final KernelDBMetrics metrics;
    private final int capacity;
    private final LinkedHashMap<Integer, Frame> cache = new LinkedHashMap<>(16, 0.75f, true);

    public BufferManager(FileManager fileManager, KernelDBMetrics metrics, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Buffer capacity must be at least one page.");
        this.fileManager = fileManager;
        this.metrics = metrics;
        this.capacity = capacity;
    }

    /** Returns a page from RAM or reads it from the file on a cache miss. */
    public synchronized Page getPage(int pageNumber) throws IOException {
        if (pageNumber < 0) throw new IllegalArgumentException("Page number cannot be negative.");
        Frame existing = cache.get(pageNumber);
        if (existing != null) {
            metrics.cacheHit();
            return existing.page;
        }

        metrics.cacheMiss();
        int diskPageCount = fileManager.pageCount();
        Page page = pageNumber < diskPageCount
                ? fileManager.readPage(pageNumber)
                : Page.empty(pageNumber);
        evictIfNeeded();
        cache.put(pageNumber, new Frame(page));
        return page;
    }

    public synchronized void markDirty(int pageNumber) throws IOException {
        Frame frame = cache.get(pageNumber);
        if (frame == null) throw new IOException("Cannot mark page " + pageNumber + " dirty because it is not in the buffer.");
        frame.dirty = true;
    }

    public synchronized void flushPage(int pageNumber) throws IOException {
        Frame frame = cache.get(pageNumber);
        if (frame != null && frame.dirty) {
            fileManager.writePage(frame.page);
            frame.dirty = false;
        }
    }

    public synchronized void flushAll() throws IOException {
        for (Frame frame : cache.values()) {
            if (frame.dirty) {
                fileManager.writePage(frame.page);
                frame.dirty = false;
            }
        }
        fileManager.force();
    }

    /** Flushes dirty pages, then drops cached pages so the next access is a cold-buffer access. */
    public synchronized void clearCache() throws IOException {
        flushAll();
        cache.clear();
    }

    public synchronized int cachedPageCount() { return cache.size(); }

    public synchronized String[] cachedPageLabels() {
        String[] labels = new String[cache.size()];
        int i = 0;
        for (Map.Entry<Integer, Frame> entry : cache.entrySet()) {
            labels[i++] = "Page " + entry.getKey() + (entry.getValue().dirty ? "  • dirty" : "  • clean");
        }
        return labels;
    }

    public int capacity() { return capacity; }

    private void evictIfNeeded() throws IOException {
        if (cache.size() < capacity) return;
        Iterator<Map.Entry<Integer, Frame>> iterator = cache.entrySet().iterator();
        if (!iterator.hasNext()) return;
        Map.Entry<Integer, Frame> eldest = iterator.next();
        if (eldest.getValue().dirty) fileManager.writePage(eldest.getValue().page);
        iterator.remove();
    }
}
