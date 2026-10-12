package ai.arena.qatar.physics;

import ai.arena.qatar.math.Quat;
import ai.arena.qatar.math.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared simulation contracts: gravity, resting contact, friction, sleeping, CCD, determinism and
 * finite-state guarantees. These run head-less in CI on every push.
 */
final class PhysicsWorldTest {

    private static RigidBody boxBody(double mass, double halfExtent) {
        Collider collider = new Collider(new Vec3[]{
                new Vec3(-halfExtent, 0, -halfExtent),
                new Vec3(halfExtent, 0, -halfExtent),
                new Vec3(halfExtent, 0, halfExtent),
                new Vec3(-halfExtent, 0, halfExtent),
                new Vec3(-halfExtent, 2 * halfExtent, -halfExtent),
                new Vec3(halfExtent, 2 * halfExtent, -halfExtent),
                new Vec3(halfExtent, 2 * halfExtent, halfExtent),
                new Vec3(-halfExtent, 2 * halfExtent, halfExtent)
        }, 0.05);
        collider.withLayers(CollisionLayers.PLAYER_CAR, CollisionLayers.TERRAIN | CollisionLayers.STATIC_BUILDING);
        double i = mass * (halfExtent * halfExtent) * 0.66;
        return new RigidBody(mass, Vec3.of(i, i, i), collider);
    }

    @Test
    @DisplayName("free fall matches the analytic solution")
    void freeFall() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody body = boxBody(1200, 1.0);
        body.position.set(0, 100, 0);
        body.angularDamping = 0;
        body.linearDamping = 0;
        world.addBody(body);

        for (int i = 0; i < 60; i++) {
            world.step(FixedStepClock.FIXED_DT);
        }
        assertEquals(PhysicsWorld.freeFallDistance(1.0), 100 - body.position.y, 0.05,
                "y after 1 s of free fall");
        assertEquals(PhysicsWorld.STANDARD_GRAVITY, body.linearVelocity.length(), 0.01, "speed after 1 s");
    }

    @Test
    @DisplayName("a dropped crate settles on the ground and eventually sleeps")
    void crateSettles() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody body = boxBody(400, 0.5);
        body.position.set(3, 6, -2);
        body.allowSleep = true;
        world.addBody(body);

        for (int i = 0; i < 600; i++) {
            world.step(FixedStepClock.FIXED_DT);
        }
        assertTrue(body.position.y < 0.4, "crate rests near the surface, y=" + body.position.y);
        assertTrue(body.position.y > -0.2, "crate does not sink through the terrain, y=" + body.position.y);
        assertTrue(body.sleeping, "crate sleeps once quiet");
        assertTrue(world.validate(), "no NaN in the world after settling");
    }

    @Test
    @DisplayName("gravity direction and magnitude are configurable but consistent")
    void gravityIsConfigurable() {
        PhysicsWorld world = new PhysicsWorld();
        world.gravity = 3.71; // Mars, purely as a configurability check
        RigidBody body = boxBody(1, 0.2);
        body.position.set(0, 50, 0);
        body.linearDamping = 0;
        world.addBody(body);
        for (int i = 0; i < 60; i++) {
            world.step(FixedStepClock.FIXED_DT);
        }
        assertEquals(3.71, body.linearVelocity.length(), 0.01);
    }

    @Test
    @DisplayName("determinism: identical inputs produce identical states")
    void deterministicStateChecksum() {
        long first = runScenarioChecksum();
        long second = runScenarioChecksum();
        assertEquals(first, second, "world checksum must be reproducible (no wall-clock or RNG input)");
    }

    private long runScenarioChecksum() {
        PhysicsWorld world = new PhysicsWorld();
        for (int i = 0; i < 6; i++) {
            RigidBody b = boxBody(900 + i * 50, 0.6);
            b.position.set(i * 2.5, 4 + i, -i);
            b.linearVelocity.set(1.5, 0, 0.5);
            world.addBody(b);
        }
        StaticBox wall = new StaticBox(new Vec3(12, 1.0, 0), new Vec3(0.3, 1.0, 8), Quat.identity());
        world.addStaticBox(wall);
        for (int i = 0; i < 480; i++) {
            world.step(FixedStepClock.FIXED_DT);
        }
        return world.stateChecksum();
    }

    @Test
    @DisplayName("static boxes stop bodies: no tunnelling at highway speed")
    void ccdAgainstStaticBox() {
        PhysicsWorld world = new PhysicsWorld();
        StaticBox wall = new StaticBox(new Vec3(40, 1.5, 0), new Vec3(0.5, 1.5, 20), Quat.identity());
        world.addStaticBox(wall);

        RigidBody car = boxBody(2200, 1.0);
        car.position.set(0, 0.6, 0);
        car.linearVelocity.set(33.3, 0, 0); // 120 km/h
        car.ccdEnabled = true;
        car.allowSleep = false;
        world.addBody(car);

        for (int i = 0; i < 240; i++) {
            world.step(FixedStepClock.FIXED_DT);
        }
        assertTrue(car.position.x < 40.0, "car must not pass through the wall, x=" + car.position.x);
        assertTrue(car.position.x > 30.0, "car should reach the wall region, x=" + car.position.x);
        assertEquals(0, world.overflowContacts(), "contact pool must not overflow");
    }

    @Test
    @DisplayName("sleeping bodies stay put until disturbed")
    void sleepingBodiesRemainStill() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody body = boxBody(500, 0.5);
        body.position.set(0, 0.1, 0);
        world.addBody(body);
        for (int i = 0; i < 240; i++) {
            world.step(FixedStepClock.FIXED_DT);
        }
        assertTrue(body.sleeping);
        Vec3 resting = new Vec3(body.position);
        for (int i = 0; i < 120; i++) {
            world.step(FixedStepClock.FIXED_DT);
        }
        assertEquals(resting.x, body.position.x, 1e-9);
        assertEquals(resting.y, body.position.y, 1e-9);
        body.wake();
        assertFalse(body.sleeping);
    }

    @Test
    @DisplayName("configuration validation rejects impossible bodies")
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RigidBody(0, Vec3.of(1, 1, 1), boxCollider()));
        assertThrows(IllegalArgumentException.class, () -> new RigidBody(-5, Vec3.of(1, 1, 1), boxCollider()));
        assertThrows(IllegalArgumentException.class, () -> new RigidBody(10, Vec3.of(0, 1, 1), boxCollider()));
        assertThrows(IllegalArgumentException.class, () -> new RigidBody(10, Vec3.of(1, Double.NaN, 1), boxCollider()));
    }

    private static Collider boxCollider() {
        return new Collider(new Vec3[]{new Vec3(0, 0, 0)}, 0.5);
    }

    @Test
    @DisplayName("material registry covers every compiled id with sane values")
    void materialRegistrySanity() {
        assertEquals(17, MaterialRegistry.count(), "17 surface materials are compiled into chunks");
        for (int id = 0; id < 17; id++) {
            SurfaceMaterial m = MaterialRegistry.get(id);
            assertEquals(id, m.id);
            assertTrue(m.contactFriction > 0 && m.contactFriction <= 1.2, m.key + " friction");
            assertTrue(m.restitution >= 0 && m.restitution < 0.5, m.key + " restitution stays low");
            assertTrue(m.dustFactor >= 0 && m.dustFactor <= 1.0, m.key + " dust factor");
        }
        // Loose sand must grip worse and roll harder than asphalt -- the desert has to feel different.
        assertTrue(MaterialRegistry.get(SurfaceMaterial.ID_LOOSE_SAND_DUNE).tireGrip
                < MaterialRegistry.get(SurfaceMaterial.ID_DRY_ASPHALT).tireGrip);
        assertTrue(MaterialRegistry.get(SurfaceMaterial.ID_LOOSE_SAND_DUNE).rollingResistance
                > MaterialRegistry.get(SurfaceMaterial.ID_DRY_ASPHALT).rollingResistance);
        assertTrue(MaterialRegistry.get(SurfaceMaterial.ID_SABKHA).sinkage
                > MaterialRegistry.get(SurfaceMaterial.ID_COMPACT_SAND).sinkage);
    }

    @Test
    @DisplayName("collision matrix is symmetric and triggers never act solid")
    void collisionMatrix() {
        int[] layers = {CollisionLayers.TERRAIN, CollisionLayers.STATIC_BUILDING, CollisionLayers.ROAD_BARRIER,
                CollisionLayers.HUMAN, CollisionLayers.PLAYER_CAR, CollisionLayers.TRAFFIC_VEHICLE,
                CollisionLayers.BOAT, CollisionLayers.HELICOPTER, CollisionLayers.AEROPLANE,
                CollisionLayers.TRANSIT, CollisionLayers.WILDLIFE, CollisionLayers.DYNAMIC_PROP,
                CollisionLayers.WATER_TRIGGER, CollisionLayers.INTERACTION_TRIGGER,
                CollisionLayers.RESTRICTED_ZONE_TRIGGER, CollisionLayers.VISUAL_ONLY};
        for (int a : layers) {
            for (int b : layers) {
                assertEquals(CollisionLayers.interacts(a, b), CollisionLayers.interacts(b, a),
                        "matrix must be symmetric for layers " + a + "/" + b);
            }
        }
        assertTrue(CollisionLayers.interacts(CollisionLayers.HUMAN, CollisionLayers.TERRAIN));
        assertFalse(CollisionLayers.isSolid(CollisionLayers.WATER_TRIGGER));
        assertFalse(CollisionLayers.isSolid(CollisionLayers.INTERACTION_TRIGGER));
        assertFalse(CollisionLayers.isSolid(CollisionLayers.RESTRICTED_ZONE_TRIGGER));
        assertFalse(CollisionLayers.isSolid(CollisionLayers.VISUAL_ONLY));
        assertTrue(CollisionLayers.isSolid(CollisionLayers.BOAT));
    }

    @Test
    @DisplayName("upward-pointing gravity is applied along +Y only")
    void gravityAxisConvention() {
        PhysicsWorld world = new PhysicsWorld();
        RigidBody body = boxBody(10, 0.2);
        body.position.set(0, 20, 0);
        body.linearDamping = 0;
        world.addBody(body);
        world.step(FixedStepClock.FIXED_DT);
        assertEquals(0.0, body.linearVelocity.x, 1e-12);
        assertTrue(body.linearVelocity.y < 0, "gravity pulls down the +Y up axis");
        assertEquals(0.0, body.linearVelocity.z, 1e-12);
    }
}
