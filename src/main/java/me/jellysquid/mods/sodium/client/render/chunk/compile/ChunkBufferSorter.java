package me.jellysquid.mods.sodium.client.render.chunk.compile;

import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import org.embeddedt.embeddium.render.chunk.sorting.TranslucentQuadAnalyzer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.BitSet;

public class ChunkBufferSorter {
    private static final int ELEMENTS_PER_PRIMITIVE = 6;
    private static final int VERTICES_PER_PRIMITIVE = 4;

    private static final int FAKE_STATIC_CAMERA_OFFSET = 1000;

    public static int getIndexBufferSize(int numPrimitives) {
        return numPrimitives * ELEMENTS_PER_PRIMITIVE * 4;
    }

    public static NativeBuffer generateSimpleIndexBuffer(NativeBuffer indexBuffer, int numPrimitives, int offset) {
        int minimumRequiredBufferSize = getIndexBufferSize(numPrimitives) + (offset * 4);
        if(indexBuffer.getLength() < minimumRequiredBufferSize) {
            throw new IllegalStateException("Given index buffer has length " + indexBuffer.getLength() + " but we need " + minimumRequiredBufferSize);
        }
        long ptr = MemoryUtil.memAddress(indexBuffer.getDirectBuffer()) + (offset * 4L);

        for (int primitiveIndex = 0; primitiveIndex < numPrimitives; primitiveIndex++) {
            int indexOffset = primitiveIndex * ELEMENTS_PER_PRIMITIVE;
            int vertexOffset = primitiveIndex * VERTICES_PER_PRIMITIVE;

            MemoryUtil.memPutInt(ptr + (indexOffset + 0) * 4, vertexOffset + 0);
            MemoryUtil.memPutInt(ptr + (indexOffset + 1) * 4, vertexOffset + 1);
            MemoryUtil.memPutInt(ptr + (indexOffset + 2) * 4, vertexOffset + 2);

            MemoryUtil.memPutInt(ptr + (indexOffset + 3) * 4, vertexOffset + 2);
            MemoryUtil.memPutInt(ptr + (indexOffset + 4) * 4, vertexOffset + 3);
            MemoryUtil.memPutInt(ptr + (indexOffset + 5) * 4, vertexOffset + 0);
        }

        return indexBuffer;
    }

    private static NativeBuffer generateIndexBuffer(NativeBuffer indexBuffer, int[] primitiveMapping, int primitiveCount) {
        int bufferSize = getIndexBufferSize(primitiveCount);
        if(indexBuffer.getLength() != bufferSize) {
            throw new IllegalStateException("Given index buffer has length " + indexBuffer.getLength() + " but we expected " + bufferSize);
        }
        long ptr = MemoryUtil.memAddress(indexBuffer.getDirectBuffer());

        for (int primitiveIndex = 0; primitiveIndex < primitiveCount; primitiveIndex++) {
            int indexOffset = primitiveIndex * ELEMENTS_PER_PRIMITIVE;

            // Map to the desired primitive
            int vertexOffset = primitiveMapping[primitiveIndex] * VERTICES_PER_PRIMITIVE;

            MemoryUtil.memPutInt(ptr + (indexOffset + 0) * 4, vertexOffset + 0);
            MemoryUtil.memPutInt(ptr + (indexOffset + 1) * 4, vertexOffset + 1);
            MemoryUtil.memPutInt(ptr + (indexOffset + 2) * 4, vertexOffset + 2);

            MemoryUtil.memPutInt(ptr + (indexOffset + 3) * 4, vertexOffset + 2);
            MemoryUtil.memPutInt(ptr + (indexOffset + 4) * 4, vertexOffset + 3);
            MemoryUtil.memPutInt(ptr + (indexOffset + 5) * 4, vertexOffset + 0);
        }

        return indexBuffer;
    }

    private static void buildStaticDistanceArray(float[] centers, float[] distanceArray, float x, float y, float z,
                                                 float normX, float normY, float normZ, int quadCount, BitSet normalSigns) {
        for (int quadIdx = 0; quadIdx < quadCount; ++quadIdx) {
            int centerIdx = quadIdx * 3;

            // Compute distance using projection of vector from camera->quad center onto shared normal, flipped by sign
            // to accommodate backwards-facing quads in the same plane extensions

            float qX = centers[centerIdx + 0] - x;
            float qY = centers[centerIdx + 1] - y;
            float qZ = centers[centerIdx + 2] - z;

            distanceArray[quadIdx] = (normX * qX + normY * qY + normZ * qZ) * (normalSigns.get(quadIdx) ? 1 : -1);
        }
    }

    private static void buildDynamicDistanceArray(float[] centers, float[] distanceArray, int quadCount, float x,
                                                  float y, float z) {
        // Sort using distance to camera directly
        for (int quadIdx = 0; quadIdx < quadCount; ++quadIdx) {
            int centerIdx = quadIdx * 3;

            float qX = centers[centerIdx + 0] - x;
            float qY = centers[centerIdx + 1] - y;
            float qZ = centers[centerIdx + 2] - z;
            distanceArray[quadIdx] = qX * qX + qY * qY + qZ * qZ;
        }
    }

    public static NativeBuffer sort(NativeBuffer indexBuffer, @Nullable TranslucentQuadAnalyzer.SortState chunkData, float x, float y, float z) {
        if (chunkData == null || chunkData.level() == TranslucentQuadAnalyzer.Level.NONE || chunkData.centers().length < 3) {
            return indexBuffer;
        }

        float[] centers = chunkData.centers();
        int quadCount = centers.length / 3;

        SortScratch scratch = SCRATCH.get();
        scratch.ensureCapacity(quadCount);

        float[] distanceArray = scratch.distances;
        boolean isStatic = chunkData.level() == TranslucentQuadAnalyzer.Level.STATIC;

        if (isStatic) {
            buildStaticDistanceArray(centers, distanceArray,
                    centers[0] + chunkData.sharedNormal().x * FAKE_STATIC_CAMERA_OFFSET,
                    centers[1] + chunkData.sharedNormal().y * FAKE_STATIC_CAMERA_OFFSET,
                    centers[2] + chunkData.sharedNormal().z * FAKE_STATIC_CAMERA_OFFSET,
                    chunkData.sharedNormal().x,
                    chunkData.sharedNormal().y,
                    chunkData.sharedNormal().z,
                    quadCount,
                    chunkData.normalSigns());
        } else {
            buildDynamicDistanceArray(centers, distanceArray, quadCount, x, y, z);
        }

        int[] indicesArray = sortByDescendingDistance(distanceArray, quadCount, scratch);

        return generateIndexBuffer(indexBuffer, indicesArray, quadCount);
    }

    /**
     * VikingRP: stable LSD radix sort of the quad indices by descending distance, equivalent to the previous merge
     * sort with {@code Float.compare(distance[b], distance[a])} but without comparator calls or allocations.
     *
     * @return the array holding the sorted indices in its first {@code count} entries
     */
    private static int[] sortByDescendingDistance(float[] distances, int count, SortScratch scratch) {
        int[] keys = scratch.keys, keysAlt = scratch.keysAlt;
        int[] indices = scratch.indices, indicesAlt = scratch.indicesAlt;
        int[] counts = scratch.counts;

        // Small meshes do not amortize clearing and scanning the 256 radix buckets four times.
        // Strict comparison preserves the original order of equal distances, including NaNs.
        if (count <= 16) {
            for (int i = 0; i < count; i++) {
                int j = i;
                while (j > 0 && Float.compare(distances[i], distances[indices[j - 1]]) > 0) {
                    indices[j] = indices[j - 1];
                    j--;
                }
                indices[j] = i;
            }
            return indices;
        }

        for (int i = 0; i < count; i++) {
            // Map the float to an int whose unsigned order matches Float.compare, then invert it for a descending order
            int bits = Float.floatToIntBits(distances[i]);
            keys[i] = ~(bits ^ ((bits >> 31) | 0x80000000));
            indices[i] = i;
        }

        for (int shift = 0; shift < 32; shift += 8) {
            Arrays.fill(counts, 0);

            for (int i = 0; i < count; i++) {
                counts[(keys[i] >>> shift) & 0xFF]++;
            }

            // All keys share this digit: the pass would not change the order
            if (counts[(keys[0] >>> shift) & 0xFF] == count) {
                continue;
            }

            int sum = 0;

            for (int bucket = 0; bucket < counts.length; bucket++) {
                int c = counts[bucket];
                counts[bucket] = sum;
                sum += c;
            }

            for (int i = 0; i < count; i++) {
                int key = keys[i];
                int dst = counts[(key >>> shift) & 0xFF]++;

                keysAlt[dst] = key;
                indicesAlt[dst] = indices[i];
            }

            int[] swap = keys; keys = keysAlt; keysAlt = swap;
            swap = indices; indices = indicesAlt; indicesAlt = swap;
        }

        return indices;
    }

    private static final ThreadLocal<SortScratch> SCRATCH = ThreadLocal.withInitial(SortScratch::new);

    /** Per-thread working arrays, reused between sorts (sorting runs on the chunk builder threads). */
    private static final class SortScratch {
        final int[] counts = new int[256];

        float[] distances = new float[0];
        int[] keys = new int[0], keysAlt = new int[0];
        int[] indices = new int[0], indicesAlt = new int[0];

        void ensureCapacity(int count) {
            if (this.keys.length < count) {
                int capacity = Math.max(count, this.keys.length + (this.keys.length >> 1));

                this.distances = new float[capacity];
                this.keys = new int[capacity];
                this.keysAlt = new int[capacity];
                this.indices = new int[capacity];
                this.indicesAlt = new int[capacity];
            }
        }
    }
}
