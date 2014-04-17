/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "OpenGLRenderer"

#include <math.h>
#include <utils/Log.h>
#include <utils/Vector.h>

#include "AmbientShadow.h"
#include "ShadowTessellator.h"
#include "Vertex.h"

namespace android {
namespace uirenderer {

/**
 * Calculate the shadows as a triangle strips while alpha value as the
 * shadow values.
 *
 * @param isCasterOpaque Whether the caster is opaque.
 * @param vertices The shadow caster's polygon, which is represented in a Vector3
 *                  array.
 * @param vertexCount The length of caster's polygon in terms of number of
 *                    vertices.
 * @param centroid3d The centroid of the shadow caster.
 * @param heightFactor The factor showing the higher the object, the lighter the
 *                     shadow.
 * @param geomFactor The factor scaling the geometry expansion along the normal.
 *
 * @param shadowVertexBuffer Return an floating point array of (x, y, a)
 *               triangle strips mode.
 */
VertexBufferMode AmbientShadow::createAmbientShadow(bool isCasterOpaque,
        const Vector3* vertices, int vertexCount, const Vector3& centroid3d,
        float heightFactor, float geomFactor, VertexBuffer& shadowVertexBuffer) {
    const int rays = SHADOW_RAY_COUNT;
    VertexBufferMode mode = kVertexBufferMode_OnePolyRingShadow;
    // Validate the inputs.
    if (vertexCount < 3 || heightFactor <= 0 || rays <= 0
        || geomFactor <= 0) {
#if DEBUG_SHADOW
        ALOGW("Invalid input for createAmbientShadow(), early return!");
#endif
        return mode; // vertex buffer is empty, so any mode doesn't matter.
    }

    Vector<Vector2> dir; // TODO: use C++11 unique_ptr
    dir.setCapacity(rays);
    float rayDist[rays];
    float rayHeight[rays];
    calculateRayDirections(rays, dir.editArray());

    // Calculate the length and height of the points along the edge.
    //
    // The math here is:
    // Intersect each ray (starting from the centroid) with the polygon.
    for (int i = 0; i < rays; i++) {
        int edgeIndex;
        float edgeFraction;
        float rayDistance;
        calculateIntersection(vertices, vertexCount, centroid3d, dir[i], edgeIndex,
                edgeFraction, rayDistance);
        rayDist[i] = rayDistance;
        if (edgeIndex < 0 || edgeIndex >= vertexCount) {
#if DEBUG_SHADOW
            ALOGW("Invalid edgeIndex!");
#endif
            edgeIndex = 0;
        }
        float h1 = vertices[edgeIndex].z;
        float h2 = vertices[((edgeIndex + 1) % vertexCount)].z;
        rayHeight[i] = h1 + edgeFraction * (h2 - h1);
    }

    // The output buffer length basically is roughly rays * layers, but since we
    // need triangle strips, so we need to duplicate vertices to accomplish that.
    AlphaVertex* shadowVertices =
            shadowVertexBuffer.alloc<AlphaVertex>(SHADOW_VERTEX_COUNT);

    // Calculate the vertex of the shadows.
    //
    // The math here is:
    // Along the edges of the polygon, for each intersection point P (generated above),
    // calculate the normal N, which should be perpendicular to the edge of the
    // polygon (represented by the neighbor intersection points) .
    // Shadow's vertices will be generated as : P + N * scale.
    const Vector2 centroid2d = Vector2(centroid3d.x, centroid3d.y);
    for (int rayIndex = 0; rayIndex < rays; rayIndex++) {
        Vector2 normal(1.0f, 0.0f);
        calculateNormal(rays, rayIndex, dir.array(), rayDist, normal);

        // The vertex should be start from rayDist[i] then scale the
        // normalizeNormal!
        Vector2 intersection = dir[rayIndex] * rayDist[rayIndex] +
                centroid2d;

        // outer ring of points, expanded based upon height of each ray intersection
        float expansionDist = rayHeight[rayIndex] * heightFactor *
                geomFactor;
        AlphaVertex::set(&shadowVertices[rayIndex],
                intersection.x + normal.x * expansionDist,
                intersection.y + normal.y * expansionDist,
                0.0f);

        // inner ring of points
        float opacity = 1.0 / (1 + rayHeight[rayIndex] * heightFactor);
        AlphaVertex::set(&shadowVertices[rays + rayIndex],
                intersection.x,
                intersection.y,
                opacity);
    }

    // If caster isn't opaque, we need to to fill the umbra by storing the umbra's
    // centroid in the innermost ring of vertices.
    if (!isCasterOpaque) {
        mode = kVertexBufferMode_TwoPolyRingShadow;
        float centroidAlpha = 1.0 / (1 + centroid3d.z * heightFactor);
        AlphaVertex centroidXYA;
        AlphaVertex::set(&centroidXYA, centroid2d.x, centroid2d.y, centroidAlpha);
        for (int rayIndex = 0; rayIndex < rays; rayIndex++) {
            shadowVertices[2 * rays + rayIndex] = centroidXYA;
        }
    }

#if DEBUG_SHADOW
    for (int i = 0; i < SHADOW_VERTEX_COUNT; i++) {
        ALOGD("ambient shadow value: i %d, (x:%f, y:%f, a:%f)", i, shadowVertices[i].x,
                shadowVertices[i].y, shadowVertices[i].alpha);
    }
#endif
    return mode;
}

/**
 * Generate an array of rays' direction vectors.
 *
 * @param rays The number of rays shooting out from the centroid.
 * @param dir Return the array of ray vectors.
 */
void AmbientShadow::calculateRayDirections(int rays, Vector2* dir) {
    float deltaAngle = 2 * M_PI / rays;

    for (int i = 0; i < rays; i++) {
        dir[i].x = sinf(deltaAngle * i);
        dir[i].y = cosf(deltaAngle * i);
    }
}

/**
 * Calculate the intersection of a ray hitting the polygon.
 *
 * @param vertices The shadow caster's polygon, which is represented in a
 *                 Vector3 array.
 * @param vertexCount The length of caster's polygon in terms of number of vertices.
 * @param start The starting point of the ray.
 * @param dir The direction vector of the ray.
 *
 * @param outEdgeIndex Return the index of the segment (or index of the starting
 *                     vertex) that ray intersect with.
 * @param outEdgeFraction Return the fraction offset from the segment starting
 *                        index.
 * @param outRayDist Return the ray distance from centroid to the intersection.
 */
void AmbientShadow::calculateIntersection(const Vector3* vertices, int vertexCount,
        const Vector3& start, const Vector2& dir, int& outEdgeIndex,
        float& outEdgeFraction, float& outRayDist) {
    float startX = start.x;
    float startY = start.y;
    float dirX = dir.x;
    float dirY = dir.y;
    // Start the search from the last edge from poly[len-1] to poly[0].
    int p1 = vertexCount - 1;

    for (int p2 = 0; p2 < vertexCount; p2++) {
        float p1x = vertices[p1].x;
        float p1y = vertices[p1].y;
        float p2x = vertices[p2].x;
        float p2y = vertices[p2].y;

        // The math here is derived from:
        // f(t, v) = p1x * (1 - t) + p2x * t - (startX + dirX * v) = 0;
        // g(t, v) = p1y * (1 - t) + p2y * t - (startY + dirY * v) = 0;
        float div = (dirX * (p1y - p2y) + dirY * p2x - dirY * p1x);
        if (div != 0) {
            float t = (dirX * (p1y - startY) + dirY * startX - dirY * p1x) / (div);
            if (t > 0 && t <= 1) {
                float t2 = (p1x * (startY - p2y)
                            + p2x * (p1y - startY)
                            + startX * (p2y - p1y)) / div;
                if (t2 > 0) {
                    outEdgeIndex = p1;
                    outRayDist = t2;
                    outEdgeFraction = t;
                    return;
                }
            }
        }
        p1 = p2;
    }
    return;
};

/**
 * Calculate the normal at the intersection point between a ray and the polygon.
 *
 * @param rays The total number of rays.
 * @param currentRayIndex The index of the ray which the normal is based on.
 * @param dir The array of the all the rays directions.
 * @param rayDist The pre-computed ray distances array.
 *
 * @param normal Return the normal.
 */
void AmbientShadow::calculateNormal(int rays, int currentRayIndex,
        const Vector2* dir, const float* rayDist, Vector2& normal) {
    int preIndex = (currentRayIndex - 1 + rays) % rays;
    int postIndex = (currentRayIndex + 1) % rays;
    Vector2 p1 = dir[preIndex] * rayDist[preIndex];
    Vector2 p2 = dir[postIndex] * rayDist[postIndex];

    // Now the V (deltaX, deltaY) is the vector going CW around the poly.
    Vector2 delta = p2 - p1;
    if (delta.length() != 0) {
        delta.normalize();
        // Calculate the normal , which is CCW 90 rotate to the V.
        // 90 degrees CCW about z-axis: (x, y, z) -> (-y, x, z)
        normal.x = -delta.y;
        normal.y = delta.x;
    }
}

}; // namespace uirenderer
}; // namespace android