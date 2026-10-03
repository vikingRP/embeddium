package me.jellysquid.mods.sodium.client.render.viewport.frustum;

import org.joml.FrustumIntersection;

public final class SimpleFrustum implements Frustum {
    private final FrustumIntersection frustum;

    public SimpleFrustum(FrustumIntersection frustumIntersection) {
        this.frustum = frustumIntersection;
    }

    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return this.frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        // JOML returns the index (>= 0) of the culling plane when the box is outside
        return switch (this.frustum.intersectAab(minX, minY, minZ, maxX, maxY, maxZ)) {
            case FrustumIntersection.INSIDE -> INSIDE;
            case FrustumIntersection.INTERSECT -> INTERSECT;
            default -> OUTSIDE;
        };
    }
}
