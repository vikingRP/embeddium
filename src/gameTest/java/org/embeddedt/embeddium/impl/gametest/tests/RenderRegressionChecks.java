package org.embeddedt.embeddium.impl.gametest.tests;

import me.jellysquid.mods.sodium.client.gl.arena.staging.MappedStagingBuffer;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBufferTarget;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBufferUsage;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBufferSorter;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import org.embeddedt.embeddium.render.chunk.sorting.TranslucentQuadAnalyzer;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;

/** Regression checks run by the client gametest suite, including real GPU copy/readback. */
final class RenderRegressionChecks {
    static void checkSorting() {
        Random random = new Random(0x56494B49L);
        int[] sizes = {1, 2, 7, 15, 16, 17, 31, 32, 33, 64, 257, 1024};
        for (int size : sizes) {
            for (boolean isStatic : new boolean[]{false, true}) {
                for (int fixture = 0; fixture < 8; fixture++) {
                    float[] centers = new float[size * 3];
                    float[] distances = new float[size];
                    BitSet signs = new BitSet();
                    Integer[] expected = new Integer[size];
                    for (int i = 0; i < size; i++) {
                        // Repeated distances exercise stability; the final fixture includes exceptional floats.
                        float x = fixture == 0 ? 0 : random.nextInt(33) - 16;
                        if (fixture == 7 && i % 5 == 0) {
                            x = i % 2 == 0 ? Float.NaN : Float.POSITIVE_INFINITY;
                        }
                        centers[i * 3] = x;
                        signs.set(i, random.nextBoolean());
                        expected[i] = i;
                    }
                    for (int i = 0; i < size; i++) {
                        float x = centers[i * 3];
                        distances[i] = isStatic
                                ? (x - (centers[0] + 1000)) * (signs.get(i) ? 1 : -1)
                                : x * x;
                    }
                    Arrays.sort(expected, (a, b) -> Float.compare(distances[b], distances[a]));
                    var state = new TranslucentQuadAnalyzer.SortState(isStatic
                            ? TranslucentQuadAnalyzer.Level.STATIC : TranslucentQuadAnalyzer.Level.DYNAMIC,
                            centers, signs, new Vector3f(1, 0, 0));
                    NativeBuffer indices = new NativeBuffer(ChunkBufferSorter.getIndexBufferSize(size));
                    try {
                        ChunkBufferSorter.sort(indices, state, 0, 0, 0);
                        ByteBuffer result = indices.getDirectBuffer();
                        int[] corners = {0, 1, 2, 2, 3, 0};
                        for (int i = 0; i < size; i++) {
                            for (int corner = 0; corner < corners.length; corner++) {
                                require(result.getInt((i * 6 + corner) * 4) == expected[i] * 4 + corners[corner],
                                        "Incorrect translucent index order: size=" + size + ", static=" + isStatic);
                            }
                        }
                    } finally {
                        indices.free();
                    }
                }
            }
        }
    }

    static void checkStagingBuffer() {
        RenderDevice.enterManagedCode();
        try (CommandList real = RenderDevice.INSTANCE.createCommandList()) {
            // Record driver calls as well as checking readback: coherent drivers can hide a missing explicit flush.
            int[] flushedBytes = {0};
            int[] copies = {0};
            CommandList recorded = (CommandList) Proxy.newProxyInstance(CommandList.class.getClassLoader(),
                    new Class<?>[]{CommandList.class}, (proxy, method, args) -> {
                        if (method.getName().equals("flushMappedRange")) {
                            require((int) args[2] > 0, "Empty mapped flush");
                            flushedBytes[0] += (int) args[2];
                        } else if (method.getName().equals("copyBufferSubData")) {
                            copies[0]++;
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
            MappedStagingBuffer staging = new MappedStagingBuffer(recorded, 64);
            var destination = real.createMutableBuffer();
            ByteBuffer source = MemoryUtil.memAlloc(80);
            ByteBuffer readback = MemoryUtil.memAlloc(64);
            try {
                real.allocateStorage(destination, 64, GlBufferUsage.STREAM_COPY);
                for (int i = 0; i < source.capacity(); i++) {
                    source.put(i, (byte) (i + 1));
                }

                // Contiguous transfers must become one GPU call, without an intermediate list of copies.
                source.limit(8);
                staging.enqueueCopy(recorded, source, destination, 0);
                source.position(8).limit(16);
                staging.enqueueCopy(recorded, source, destination, 8);
                staging.flush(recorded);
                require(copies[0] == 1 && flushedBytes[0] == 16, "Contiguous copies were not merged");
                GL15C.glFinish();
                staging.flip();
                require(staging.getAvailableBytes() == 64, "Completed copy did not release staging space");

                // Fill the whole ring starting at 16, with a nonzero source position. End == start.
                copies[0] = flushedBytes[0] = 0;
                source.position(8).limit(72);
                staging.enqueueCopy(recorded, source, destination, 0);
                require(source.position() == 8 && source.limit() == 72, "Upload changed source bounds");
                staging.flush(recorded);
                require(copies[0] == 2 && flushedBytes[0] == 64, "Full wrapped ring was not completely flushed");
                real.bindBuffer(GlBufferTarget.COPY_READ_BUFFER, destination);
                GL15C.glGetBufferSubData(GlBufferTarget.COPY_READ_BUFFER.getTargetParameter(), 0, readback);
                for (int i = 0; i < 64; i++) {
                    require(readback.get(i) == (byte) (i + 9), "Wrapped copy read incorrect source byte " + i);
                }

                // With the ring full, the fallback path must still copy data correctly.
                source.position(0).limit(8);
                staging.enqueueCopy(recorded, source, destination, 0);
                staging.flush(recorded);
                real.bindBuffer(GlBufferTarget.COPY_READ_BUFFER, destination);
                GL15C.glGetBufferSubData(GlBufferTarget.COPY_READ_BUFFER.getTargetParameter(), 0, readback);
                for (int i = 0; i < 8; i++) {
                    require(readback.get(i) == source.get(i), "Fallback copy mismatch");
                }
                copies[0] = flushedBytes[0] = 0;
                source.position(8);
                staging.enqueueCopy(recorded, source, destination, 0);
                staging.flush(recorded);
                require(copies[0] == 0 && flushedBytes[0] == 0, "Empty upload issued GPU work");
            } finally {
                staging.delete(real);
                real.deleteBuffer(destination);
                MemoryUtil.memFree(source);
                MemoryUtil.memFree(readback);
            }
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
