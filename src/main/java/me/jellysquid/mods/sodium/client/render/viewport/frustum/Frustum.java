package me.jellysquid.mods.sodium.client.render.viewport.frustum;

public interface Frustum {
    /** The box is entirely inside the frustum. */
    int INSIDE = 0;
    /** The box is entirely outside the frustum. */
    int OUTSIDE = 1;
    /** The box may cross the frustum boundary: smaller boxes inside it must be tested individually. */
    int INTERSECT = 2;

    boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);

    /**
     * Classifies a box against the frustum, consistently with {@link #testAab}. Implementations which cannot do
     * this cheaply may always return {@link #INTERSECT}.
     */
    default int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return INTERSECT;
    }
}
