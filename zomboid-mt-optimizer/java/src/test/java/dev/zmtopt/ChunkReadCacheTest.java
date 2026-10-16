package dev.zmtopt;

import dev.zmtopt.optimize.ChunkIOPool;
import dev.zmtopt.optimize.ChunkReadCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChunkReadCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void prefetchesAndServesChunkBytes() throws Exception {
        Path chunkFile = tempDir.resolve("chunk.bin");
        byte[] data = "hello-chunk".getBytes(StandardCharsets.UTF_8);
        Files.write(chunkFile, data);

        ChunkIOPool pool = new ChunkIOPool(1, 16);
        ChunkReadCache cache = new ChunkReadCache(pool, 16);
        cache.prefetch(10, 20, chunkFile.toFile());

        // Wait for the background read.
        ByteBuffer buf = null;
        for (int i = 0; i < 100 && buf == null; i++) {
            buf = cache.get(10, 20);
            if (buf == null) {
                Thread.sleep(10);
            }
        }
        assertNotNull(buf, "chunk bytes should be cached after prefetch");
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        assertArrayEquals(data, out);
        assertEquals(1, cache.getHits());
        pool.shutdown();
    }

    @Test
    void missReturnsNull() {
        ChunkIOPool pool = new ChunkIOPool(1, 16);
        ChunkReadCache cache = new ChunkReadCache(pool, 16);
        assertNull(cache.get(1, 1), "uncached chunk should return null");
        assertEquals(1, cache.getMisses());
        pool.shutdown();
    }

    @Test
    void boundedCacheEvictsOldest() throws Exception {
        ChunkIOPool pool = new ChunkIOPool(1, 16);
        // Design enforces a minimum cache of 16 entries; use 20 files to test LRU eviction.
        ChunkReadCache cache = new ChunkReadCache(pool, 16);
        for (int i = 0; i < 20; i++) {
            Path f = tempDir.resolve("c" + i + ".bin");
            Files.write(f, new byte[]{ (byte) i });
            cache.prefetch(i, 0, f.toFile());
        }
        // Wait until all reads finish (size may stay <= maxEntries due to LRU).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (cache.size() >= 16) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals(16, cache.size(), "cache should be capped at maxEntries");
        pool.shutdown();
    }
}