package ai.arena.qatar.vehicle;

import ai.arena.qatar.physics.SurfaceMaterial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five bodies, flown and driven on flat test ground.
 *
 * <p>These are behaviour tests, not unit tests of arithmetic: a car has to reach 100 km/h in the time a
 * real car does and stop in the distance a real car does, a wing has to refuse to fly below its stall
 * speed, a rotor has to hover at the collective that carries its weight, and a hull has to settle at
 * its own waterline. Every bound here is a published figure for the real machine, with room for the
 * model's simplifications -- which is the point: a mistake in the physics moves these numbers a lot.
 */
final class VehicleDynamicsTest {

    /** The physics rate the game runs at, so the tests fly the same aeroplane the phone does. */
    private static final double DT = 1.0 / 60.0;

    private static final Surface ASPHALT = Surface.land(0, SurfaceMaterial.ID_DRY_ASPHALT);
    private static final Surface SAND = Surface.land(0, SurfaceMaterial.ID_LOOSE_SAND_DUNE);
    private static final Surface SEA = Surface.water(-14.0, 14.0);

    @Test
    @DisplayName("a car reaches 100 km/h in a real car's time and stops in a real car's distance")
    void carAcceleratesAndStops() {
        CarBody car = new CarBody();
        VehicleState state = new VehicleState();
        VehicleInput input = new VehicleInput();
        car.spawn(state, 0, 0, ASPHALT, 0);

        input.throttle = 1.0;
        double hundred = 100.0 / 3.6;
        double timeTo100 = -1;
        double distanceTo100 = 0;
        for (double t = 0; t < 90.0; t += DT) {
            double before = state.speedMS;
            car.step(state, input, ASPHALT, DT);
            if (timeTo100 < 0) {
                distanceTo100 += 0.5 * (before + state.speedMS) * DT;
            }
            if (timeTo100 < 0 && state.speedMS >= hundred) {
                timeTo100 = t;
            }
        }
        assertTrue(timeTo100 > 0, "the car never reached 100 km/h");
        assertTrue(timeTo100 > 6.0 && timeTo100 < 13.0,
                "0-100 km/h took " + timeTo100 + " s, which is not a 92 kW saloon");
        assertTrue(distanceTo100 > 80 && distanceTo100 < 250,
                "reaching 100 km/h covered " + distanceTo100 + " m");
        // Flat out, a 92 kW, 1620 kg saloon runs out of power around 190 km/h -- not at 250, and not
        // at 120 either.
        assertTrue(state.speedMS > 44.0 && state.speedMS < 60.0,
                "top speed settled at " + state.speedMS * 3.6 + " km/h");

        // Braking from 100 km/h, on the same surface.
        VehicleState braking = new VehicleState();
        car.spawn(braking, 0, 0, ASPHALT, 0);
        braking.speedMS = hundred;
        VehicleInput stop = new VehicleInput();
        stop.brake = 1.0;
        double stopping = 0;
        for (double t = 0; t < 20.0 && braking.speedMS > 0.05; t += DT) {
            double before = braking.speedMS;
            car.step(braking, stop, ASPHALT, DT);
            stopping += Math.abs(0.5 * (before + braking.speedMS) * DT);
        }
        assertTrue(stopping > 28 && stopping < 70,
                "stopping from 100 km/h took " + stopping + " m: real cars need about 45 m");
        assertEquals(0.0, braking.speedMS, 0.2, "the car did not come to rest");
    }

    @Test
    @DisplayName("a car corners as hard as its tyres allow and no harder")
    void carGripLimitsCorners() {
        CarBody car = new CarBody();
        VehicleState state = new VehicleState();
        car.spawn(state, 0, 0, ASPHALT, 0);
        state.speedMS = 25.0;
        VehicleInput input = new VehicleInput();
        input.throttle = 0.4;
        input.steer = 1.0;

        double previousHeading = state.headingDegrees;
        double maxYawRate = 0;
        for (double t = 0; t < 3.0; t += DT) {
            car.step(state, input, ASPHALT, DT);
            double yawRate = Math.toRadians(VehicleState.wrap(state.headingDegrees - previousHeading)) / DT;
            previousHeading = state.headingDegrees;
            maxYawRate = Math.max(maxYawRate, Math.abs(yawRate));
        }
        double gripCap = CarBody.LATERAL_GRIP_FRACTION * ASPHALT.materialAt(0, 0).tireGrip * VehicleBody.G;
        assertTrue(maxYawRate <= gripCap / 25.0 + 0.02,
                "yaw rate " + maxYawRate + " rad/s exceeds what the tyres can hold: " + gripCap / 25.0);

        // Loose sand has less grip, so the same steering input turns the car less.
        CarBody onSand = new CarBody();
        VehicleState sandState = new VehicleState();
        onSand.spawn(sandState, 0, 0, SAND, 0);
        sandState.speedMS = 25.0;
        double headingStart = sandState.headingDegrees;
        for (double t = 0; t < 3.0; t += DT) {
            onSand.step(sandState, input, SAND, DT);
        }
        double sandTurn = Math.abs(VehicleState.wrap(sandState.headingDegrees - headingStart));
        double asphaltTurn = Math.abs(VehicleState.wrap(state.headingDegrees - 0.0));
        assertTrue(sandTurn < asphaltTurn,
                "sand turned " + sandTurn + " degrees, asphalt " + asphaltTurn);
    }

    @Test
    @DisplayName("soft ground costs a car a third of its speed and digs the tyres in")
    void carBogsDownOnSand() {
        double asphaltTop = topSpeed(new CarBody(), ASPHALT);
        double sandTop = topSpeed(new CarBody(), SAND);
        assertTrue(sandTop < asphaltTop * 0.78,
                "sand top speed " + sandTop + " m/s is not much worse than asphalt " + asphaltTop);
        assertTrue(sandTop > 5.0, "the car should still be able to drive on sand: " + sandTop);

        VehicleState state = new VehicleState();
        CarBody car = new CarBody();
        car.spawn(state, 0, 0, SAND, 0);
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        for (double t = 0; t < 6.0; t += DT) {
            car.step(state, input, SAND, DT);
        }
        assertTrue(state.sinkage > 0.05, "the tyres never sank into the sand: " + state.sinkage);
    }

    private static double topSpeed(CarBody car, Surface surface) {
        VehicleState state = new VehicleState();
        car.spawn(state, 0, 0, surface, 0);
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        for (double t = 0; t < 90.0; t += DT) {
            car.step(state, input, surface, DT);
        }
        return state.speedMS;
    }

    @Test
    @DisplayName("a person walks, runs, jumps a low kerb and refuses a wall")
    void personMovesLikeAPerson() {
        HumanBody human = new HumanBody();
        VehicleState state = new VehicleState();

        // Walking pace.
        human.spawn(state, 0, 0, ASPHALT, 90.0);
        VehicleInput walk = new VehicleInput();
        walk.throttle = 1.0;
        for (double t = 0; t < 6.0; t += DT) {
            human.step(state, walk, ASPHALT, DT);
        }
        assertEquals(HumanBody.WALK_SPEED_MS, state.speedMS, 0.1, "walking speed");
        assertTrue(state.eastM > 8.0, "six seconds of walking covered " + state.eastM + " m");

        // Running.
        VehicleInput run = new VehicleInput();
        run.throttle = 1.0;
        run.boost = true;
        for (double t = 0; t < 6.0; t += DT) {
            human.step(state, run, ASPHALT, DT);
        }
        assertEquals(HumanBody.RUN_SPEED_MS, state.speedMS, 0.2, "running speed");

        // A 0.3 m kerb is a step; a 1.0 m wall is not.
        HumanBody kerbWalker = new HumanBody();
        VehicleState kerb = new VehicleState();
        Surface kerbSurface = Surface.step(0, SurfaceMaterial.ID_URBAN_PAD, 2.0, 0.3);
        kerbWalker.spawn(kerb, 0, 0, kerbSurface, 90.0);
        for (double t = 0; t < 3.0; t += DT) {
            kerbWalker.step(kerb, walk, kerbSurface, DT);
        }
        assertTrue(kerb.eastM > 2.5, "the walker stopped at the kerb: " + kerb.eastM);
        assertTrue(kerb.heightM >= 0.3 - 1e-6, "the walker did not step up: " + kerb.heightM);

        HumanBody wallHit = new HumanBody();
        VehicleState wall = new VehicleState();
        Surface wallSurface = Surface.step(0, SurfaceMaterial.ID_URBAN_PAD, 2.0, 1.0);
        wallHit.spawn(wall, 0, 0, wallSurface, 90.0);
        for (double t = 0; t < 3.0; t += DT) {
            wallHit.step(wall, walk, wallSurface, DT);
        }
        assertTrue(wall.eastM < 2.6, "the walker climbed a 1 m wall: " + wall.eastM);
        assertTrue(wall.blocked, "the wall was not reported as blocked");
        assertEquals(0.0, wall.heightM, 0.01, "the walker did not stay on the ground");
    }

    @Test
    @DisplayName("a jump leaves the ground and lands again")
    void personJump() {
        HumanBody human = new HumanBody();
        VehicleState state = new VehicleState();
        human.spawn(state, 0, 0, ASPHALT, 0);
        VehicleInput jump = new VehicleInput();
        jump.jump = true;
        human.step(state, jump, ASPHALT, DT);
        assertFalse(state.grounded, "the jump did not leave the ground");

        VehicleInput none = new VehicleInput();
        double apex = 0;
        double airborneSeconds = 0;
        for (double t = 0; t < 3.0; t += DT) {
            human.step(state, none, ASPHALT, DT);
            apex = Math.max(apex, state.heightM);
            if (!state.grounded) {
                airborneSeconds += DT;
            }
        }
        assertTrue(apex > 0.30 && apex < 0.65, "the jump cleared " + apex + " m");
        assertTrue(state.grounded, "the walker never landed");
        assertTrue(airborneSeconds > 0.4 && airborneSeconds < 0.9,
                "the jump lasted " + airborneSeconds + " s");
    }

    @Test
    @DisplayName("an aeroplane leaves the ground only once the wing can carry it")
    void aircraftTakesOff() {
        AircraftBody plane = new AircraftBody();
        VehicleState state = new VehicleState();
        plane.spawn(state, 0, 0, ASPHALT, 90.0);
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;

        double stall = plane.stallSpeed(VehicleBody.SEA_LEVEL_DENSITY, 0);
        double lifted = -1;
        double airborneSince = -1;
        double peakAltitude = 0;
        boolean earlyLift = false;
        for (double t = 0; t < 120.0; t += DT) {
            if (state.grounded) {
                // Rotate once there is a hint of flying speed, and rotate properly: a token pull on the
                // stick leaves the aeroplane hopping along the runway, which is exactly what a real one
                // does. Below flying speed, holding the stick back does nothing at all.
                input.pitch = state.speedMS > stall * 0.85 ? 0.8 : 0.0;
            } else {
                if (airborneSince < 0) {
                    airborneSince = t;
                }
                // Climb out at the attitude a 172 climbs at, then let go of the stick and trim.
                input.pitch = (t - airborneSince) < 60.0
                        ? Math.max(-1.0, Math.min(1.0, (14.0 - state.pitchDegrees) / 8.0)) : 0.0;
            }
            plane.step(state, input, ASPHALT, DT);
            if (!state.grounded && lifted < 0) {
                lifted = t;
                if (state.speedMS < stall * 0.95) {
                    earlyLift = true;
                }
            }
            peakAltitude = Math.max(peakAltitude, state.heightM);
        }
        assertFalse(earlyLift, "the aeroplane flew below its stall speed");
        assertTrue(lifted > 0 && lifted < 30.0,
                "the aeroplane " + (lifted < 0 ? "never took off in 120 s" : "took " + lifted + " s to lift off"));
        assertTrue(peakAltitude > 60.0, "it got airborne but barely climbed: " + peakAltitude + " m");
        assertFalse(state.grounded, "it took off and then landed again");
        assertFalse(state.stalled, "the climb-out ended in a stall");
        assertTrue(state.speedMS > stall, "climb-out speed is below the stall speed");
    }

    @Test
    @DisplayName("an aeroplane below its stall speed sinks, drops its nose and flies again")
    void aircraftStallsAndRecovers() {
        AircraftBody plane = new AircraftBody();
        VehicleState state = new VehicleState();
        plane.spawn(state, 0, 0, ASPHALT, 90.0);
        state.grounded = false;
        state.heightM = 300.0;
        state.speedMS = 20.0;

        double stall = plane.stallSpeed(1.19, 0);
        assertTrue(stall > 20.0, "the test needs to start below the stall speed: " + stall);

        VehicleInput idle = new VehicleInput();
        // Half a second in: the wing is stalled, the nose is down and the aeroplane is sinking hard.
        for (double t = 0; t < 0.5; t += DT) {
            plane.step(state, idle, ASPHALT, DT);
        }
        assertTrue(state.stalled, "20 m/s in level flight should be stalled");
        assertTrue(state.verticalMS < -2.0, "a stalled aeroplane must sink: " + state.verticalMS);
        assertTrue(state.pitchDegrees < 0, "the nose should have dropped: " + state.pitchDegrees);

        // The stall is recovered by trading height for speed, which is what the nose drop buys.
        double lowest = state.heightM;
        for (double t = 0; t < 60.0; t += DT) {
            plane.step(state, idle, ASPHALT, DT);
            lowest = Math.min(lowest, state.heightM);
        }
        assertFalse(state.stalled, "the aeroplane never recovered from the stall");
        assertTrue(state.speedMS > stall * 0.8 && state.speedMS < stall * 2.0,
                "after recovery the speed is " + state.speedMS + " m/s against a stall speed of " + stall);
        assertTrue(state.verticalMS > -6.0, "it is still diving away: " + state.verticalMS);
        assertTrue(state.heightM > 5.0, "the stall recovery flew it into the ground");
        assertTrue(lowest < 290.0, "it never lost any height at all");
    }

    @Test
    @DisplayName("an aeroplane at full power settles at its real cruise speed")
    void aircraftCruise() {
        AircraftBody plane = new AircraftBody();
        VehicleState state = new VehicleState();
        plane.spawn(state, 0, 0, ASPHALT, 90.0);
        state.grounded = false;
        state.heightM = 1000.0;
        state.speedMS = 40.0;
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        for (double t = 0; t < 180.0; t += DT) {
            plane.step(state, input, ASPHALT, DT);
        }
        // A 110 kW light aircraft cruises at about 226 km/h; anything outside this band means the
        // thrust or the drag polar is wrong, not that the numbers are merely different.
        assertTrue(state.speedMS > 55.0 && state.speedMS < 72.0,
                "full power settled at " + state.speedMS * 3.6 + " km/h");
        assertFalse(state.stalled, "cruising at full power should not be stalled");
    }

    @Test
    @DisplayName("a helicopter hovers at the collective that carries its weight")
    void helicopterHovers() {
        HelicopterBody heli = new HelicopterBody();
        VehicleState state = new VehicleState();
        heli.spawn(state, 0, 0, ASPHALT, 0);
        state.grounded = false;
        state.heightM = 400.0;

        double densityFactor = VehicleBody.airDensity(400.0) / VehicleBody.SEA_LEVEL_DENSITY;
        double hoverCollective = HelicopterBody.MASS_KG * VehicleBody.G
                / (HelicopterBody.THRUST_MAX_N * densityFactor);
        VehicleInput input = new VehicleInput();
        input.collective = hoverCollective;
        for (double t = 0; t < 10.0; t += DT) {
            heli.step(state, input, ASPHALT, DT);
        }
        assertTrue(Math.abs(state.verticalMS) < 0.8,
                "hover collective gave " + state.verticalMS + " m/s");
        assertTrue(Math.abs(state.heightM - 400.0) < 12.0, "hover drifted " + (state.heightM - 400.0) + " m");

        // Full collective climbs, and it climbs at a rate a helicopter can actually do.
        input.collective = 1.0;
        for (double t = 0; t < 12.0; t += DT) {
            heli.step(state, input, ASPHALT, DT);
        }
        assertTrue(state.verticalMS > 4.0 && state.verticalMS < 13.0,
                "full collective climbed at " + state.verticalMS + " m/s");
        assertTrue(state.rotorClimb, "the rotor is not reporting a climb");
    }

    @Test
    @DisplayName("a helicopter needs the cyclic to go anywhere, and the disc has a limit")
    void helicopterNeedsCyclic() {
        HelicopterBody heli = new HelicopterBody();
        VehicleState hovering = new VehicleState();
        heli.spawn(hovering, 0, 0, ASPHALT, 0);
        hovering.grounded = false;
        hovering.heightM = 400.0;

        VehicleInput level = new VehicleInput();
        level.collective = 0.75;
        for (double t = 0; t < 20.0; t += DT) {
            heli.step(hovering, level, ASPHALT, DT);
        }
        assertTrue(Math.abs(hovering.speedMS) < 1.5,
                "a helicopter with a level disc drifted " + hovering.speedMS + " m/s");

        HelicopterBody heli2 = new HelicopterBody();
        VehicleState moving = new VehicleState();
        heli2.spawn(moving, 0, 0, ASPHALT, 0);
        moving.grounded = false;
        moving.heightM = 400.0;
        VehicleInput forward = new VehicleInput();
        forward.collective = 0.75;
        forward.pitch = 1.0;
        double topSpeed = 0;
        for (double t = 0; t < 120.0; t += DT) {
            heli2.step(moving, forward, ASPHALT, DT);
            topSpeed = Math.max(topSpeed, moving.speedMS);
        }
        assertTrue(topSpeed > 30.0, "cyclic forward only reached " + topSpeed + " m/s");
        assertTrue(topSpeed < HelicopterBody.SPEED_LIMIT_MS * 1.2,
                "the helicopter exceeded its own speed limit: " + topSpeed);
    }

    @Test
    @DisplayName("a boat settles at its waterline, then climbs onto the plane")
    void boatFloatsAndPlanes() {
        BoatBody boat = new BoatBody();
        VehicleState state = new VehicleState();
        boat.spawn(state, 0, 0, SEA, 0);

        // Settling: the hull finds its own waterline and stays there.
        VehicleInput idle = new VehicleInput();
        for (double t = 0; t < 5.0; t += DT) {
            boat.step(state, idle, SEA, DT);
        }
        double submerged = SEA.waterSurfaceM(0, 0) - state.heightM;
        assertTrue(submerged > 0.08 && submerged < 0.35,
                "the hull settled " + submerged + " m into the water");
        assertTrue(state.floating, "the boat is not floating");
        assertTrue(Math.abs(state.speedMS) < 0.2, "the boat drifted with no throttle");
        assertTrue(Math.abs(state.verticalMS) < 0.15, "the boat is still bouncing: " + state.verticalMS);

        // Full throttle: it climbs onto the plane and tops out at a real 30 knot hull speed.
        VehicleInput full = new VehicleInput();
        full.throttle = 1.0;
        double distance = 0;
        for (double t = 0; t < 60.0; t += DT) {
            double before = state.speedMS;
            boat.step(state, full, SEA, DT);
            distance += Math.abs(0.5 * (before + state.speedMS) * DT);
        }
        assertTrue(state.speedMS > 11.0, "full throttle reached only " + state.speedMS + " m/s");
        assertTrue(state.speedMS < 22.0, "the hull is doing " + state.speedMS + " m/s: not this boat");
        assertTrue(distance > 300.0, "a minute under power covered only " + distance + " m");
        // Planing: the hull runs shallower at speed than it did at rest.
        double planingDraft = SEA.waterSurfaceM(state.eastM, state.northM) - state.heightM;
        assertTrue(planingDraft < submerged, "the hull never came up onto the plane: " + planingDraft);
    }

    @Test
    @DisplayName("a boat driven onto the sand stops being a boat")
    void boatAgroundDoesNotSail() {
        BoatBody boat = new BoatBody();
        VehicleState state = new VehicleState();
        Surface beach = Surface.land(0.0, SurfaceMaterial.ID_COMPACT_SAND);
        boat.spawn(state, 0, 0, beach, 0);
        VehicleInput full = new VehicleInput();
        full.throttle = 1.0;
        for (double t = 0; t < 20.0; t += DT) {
            boat.step(state, full, beach, DT);
        }
        assertTrue(state.speedMS < 3.0, "a beached hull is moving at " + state.speedMS + " m/s");
        assertFalse(state.floating, "the boat reports floating on sand");
        assertTrue(state.slip > 0.5, "the hull is not reporting ground contact");
    }

    @Test
    @DisplayName("every body refuses to move over terrain that has not streamed in")
    void bodiesWaitForTheWorld() {
        Surface unloaded = new Surface() {
            @Override
            public double groundHeightM(double eastM, double northM) {
                return 0;
            }

            @Override
            public int materialId(double eastM, double northM) {
                return SurfaceMaterial.ID_COMPACT_SAND;
            }

            @Override
            public double waterDepthM(double eastM, double northM) {
                return 0;
            }

            @Override
            public boolean collisionLoaded(double eastM, double northM) {
                return false;
            }
        };
        VehicleBody[] bodies = {new HumanBody(), new CarBody(), new AircraftBody(),
                new HelicopterBody(), new BoatBody()};
        VehicleInput input = new VehicleInput();
        input.throttle = 1.0;
        input.collective = 1.0;
        for (VehicleBody body : bodies) {
            VehicleState state = new VehicleState();
            body.spawn(state, 100, 200, unloaded, 45.0);
            double east = state.eastM;
            double north = state.northM;
            for (int i = 0; i < 120; i++) {
                body.step(state, input, unloaded, DT);
            }
            assertTrue(state.blocked, body.label() + " did not report blocked terrain");
            assertEquals(east, state.eastM, 1e-9, body.label() + " moved east over no terrain");
            assertEquals(north, state.northM, 1e-9, body.label() + " moved north over no terrain");
        }
    }
}
