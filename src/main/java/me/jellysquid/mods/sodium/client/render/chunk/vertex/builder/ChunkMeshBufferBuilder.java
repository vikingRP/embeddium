package me.jellysquid.mods.sodium.client.render.chunk.vertex.builder;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.embeddedt.embeddium.render.chunk.sorting.TranslucentQuadAnalyzer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

public class ChunkMeshBufferBuilder {
    private final ChunkVertexEncoder encoder;
    private final int stride;

    /**
     * If a buffer grew beyond this many multiples of the initial capacity while building a large section, it is
     * shrunk back to the initial capacity at the start of the next build so that one huge section does not pin a
     * large amount of native memory per worker forever.
     */
    private static final int SHRINK_THRESHOLD_MULTIPLIER = 4;

    // All capacities are expressed in vertices, not bytes
    private final int initialCapacity;
    private final TranslucentQuadAnalyzer analyzer;

    private ByteBuffer buffer;
    private int count;
    private int capacity;
    private int sectionIndex;

    public ChunkMeshBufferBuilder(ChunkVertexType vertexType, int initialCapacity, boolean collectSortState) {
        this.encoder = vertexType.getEncoder();
        this.stride = vertexType.getVertexFormat().getStride();

        this.buffer = null;

        this.capacity = 0;
        this.initialCapacity = initialCapacity;

        this.analyzer = collectSortState ? new TranslucentQuadAnalyzer() : null;
    }

    public void push(ChunkVertexEncoder.Vertex[] vertices, Material material) {
        var vertexStart = this.count;
        var vertexCount = vertices.length;

        if (this.count + vertexCount >= this.capacity) {
            this.grow(vertexCount);
        }

        long ptr = MemoryUtil.memAddress(this.buffer, this.count * this.stride);

        if (this.analyzer != null) {
            for (ChunkVertexEncoder.Vertex vertex : vertices) {
                this.analyzer.capture(vertex);
            }
        }

        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            ptr = this.encoder.write(ptr, material, vertex, this.sectionIndex);
        }

        this.count += vertexCount;
    }

    private void grow(int len) {
        // The new capacity will at least as large as the write it needs to service (len is in vertices)
        int cap = Math.max(this.capacity * 2, this.capacity + len);
        // Allocate at least the initial capacity on first use
        cap = Math.max(cap, this.initialCapacity);

        // Update the buffer and capacity now (setBufferSize takes a vertex count and applies the stride itself;
        // multiplying by the stride here as well used to over-allocate by a factor of stride)
        this.setBufferSize(cap);
    }

    private void setBufferSize(int capacity) {
        this.buffer = MemoryUtil.memRealloc(this.buffer, capacity * this.stride);
        this.capacity = capacity;
    }

    public void start(int sectionIndex) {
        this.count = 0;
        this.sectionIndex = sectionIndex;
        if(this.analyzer != null) {
            this.analyzer.clear();
        }

        // Keep the native buffer alive between builds; only give memory back if a previous large section made it
        // grow far beyond the initial capacity. The buffer is allocated lazily on the first push.
        if (this.buffer != null && this.capacity > this.initialCapacity * SHRINK_THRESHOLD_MULTIPLIER) {
            this.setBufferSize(this.initialCapacity);
        }
    }

    @Nullable
    public TranslucentQuadAnalyzer.SortState getSortState() {
        return this.analyzer != null ? this.analyzer.getSortState() : null;
    }

    public void destroy() {
        if (this.buffer != null) {
            MemoryUtil.memFree(this.buffer);
        }

        this.buffer = null;
        this.capacity = 0;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public ByteBuffer slice() {
        if (this.isEmpty()) {
            throw new IllegalStateException("No vertex data in buffer");
        }

        return MemoryUtil.memSlice(this.buffer, 0, this.stride * this.count);
    }

    public int count() {
        return this.count;
    }
}
