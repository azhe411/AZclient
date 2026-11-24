package com.lite.agent;

/**
 * One authoritative timeline for player pose state.
 *
 * <p>Minecraft exposes the same yaw/pitch fields to several systems at
 * different points in a tick.  Keeping independent "camera", "sent" and
 * "model" booleans in feature code makes it possible to combine a position
 * produced from one pose with a packet or rendered model produced from
 * another.  This class records the exact pose owned by every stage and keeps
 * context changes from leaking state into a replacement player or world.
 *
 * <p>The class is deliberately free of Minecraft types.  Hook owns reflection;
 * this class owns ordering, identity and pose coherence.
 */
final class PlayerStatePipeline {

    enum Owner { VANILLA, SCAFFOLD, COMBAT, OTHER }

    enum Phase {
        DETACHED,
        TICK_STARTED,
        INPUT_SAMPLED,
        MOVEMENT_FINISHED,
        PACKET_COMMITTED,
        TICK_FINISHED
    }

    static final class Pose {
        final float yaw;
        final float pitch;

        Pose(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = clampPitch(pitch);
        }

        boolean same(Pose other) {
            return other != null
                    && Math.abs(wrap(yaw - other.yaw)) <= 0.001f
                    && Math.abs(pitch - other.pitch) <= 0.001f;
        }

        @Override public String toString() {
            return "(" + yaw + "," + pitch + ")";
        }
    }

    static final class Snapshot {
        final long epoch;
        final int tick;
        final Phase phase;
        final Owner movementOwner;
        final Owner packetOwner;
        final Owner modelOwner;
        final Pose camera;
        final Pose movement;
        final Pose packet;
        final Pose model;
        final String violation;

        Snapshot(long epoch, int tick, Phase phase,
                 Owner movementOwner, Owner packetOwner, Owner modelOwner,
                 Pose camera, Pose movement, Pose packet, Pose model,
                 String violation) {
            this.epoch = epoch;
            this.tick = tick;
            this.phase = phase;
            this.movementOwner = movementOwner;
            this.packetOwner = packetOwner;
            this.modelOwner = modelOwner;
            this.camera = camera;
            this.movement = movement;
            this.packet = packet;
            this.model = model;
            this.violation = violation;
        }
    }

    private Object player;
    private Object world;
    private int contextTick = Integer.MIN_VALUE;
    private int tick = Integer.MIN_VALUE;
    private long epoch;
    private Phase phase = Phase.DETACHED;
    private Owner movementOwner = Owner.VANILLA;
    private Owner packetOwner = Owner.VANILLA;
    private Owner modelOwner = Owner.VANILLA;
    private Pose camera;
    private Pose movement;
    private Pose packet;
    private Pose model;
    private String violation;

    /** Returns true when a replacement player/world or rewound clock reset state. */
    boolean syncContext(Object nextPlayer, Object nextWorld, int nextTick) {
        boolean known = player != null || world != null || contextTick != Integer.MIN_VALUE;
        boolean changed = known && (player != nextPlayer || world != nextWorld
                || (nextTick != Integer.MIN_VALUE && contextTick != Integer.MIN_VALUE
                    && nextTick < contextTick));
        player = nextPlayer;
        world = nextWorld;
        contextTick = nextTick;
        if (changed) resetTimeline();
        return changed;
    }

    void beginTick(Object currentPlayer, Object currentWorld, int currentTick,
                   float cameraYaw, float cameraPitch) {
        syncContext(currentPlayer, currentWorld, currentTick);
        violation = null;
        if (phase != Phase.DETACHED && phase != Phase.TICK_FINISHED && tick == currentTick)
            note("beginTick repeated before previous tick finished");
        tick = currentTick;
        contextTick = currentTick;
        phase = Phase.TICK_STARTED;
        camera = new Pose(cameraYaw, cameraPitch);
        movement = camera;
        packet = null;
        model = null;
        movementOwner = packetOwner = modelOwner = Owner.VANILLA;
    }

    Pose planMovement(Owner owner, float yaw, float pitch) {
        if (phase != Phase.TICK_STARTED)
            note("movement pose planned after input sampling: " + phase);
        movementOwner = owner == null ? Owner.OTHER : owner;
        movement = new Pose(yaw, pitch);
        return movement;
    }

    void inputSampled() {
        if (phase.ordinal() < Phase.TICK_STARTED.ordinal())
            note("input sampled without an open tick");
        if (phase.ordinal() < Phase.INPUT_SAMPLED.ordinal()) phase = Phase.INPUT_SAMPLED;
    }

    /** Ground movement has already selected its horizontal acceleration. Pitch
     * may still be resolved before Entity.move consumes that displacement; yaw
     * and the selected keys must remain exactly the planned movement values. */
    Pose refineGroundPitch(float pitch) {
        if (!canRefineGroundPitch()) return null;
        movement = new Pose(movement.yaw, pitch);
        return movement;
    }

    boolean canRefineGroundPitch() {
        return movementOwner == Owner.SCAFFOLD && movement != null
                && (phase == Phase.TICK_STARTED || phase == Phase.INPUT_SAMPLED);
    }

    void movementFinished(float yaw, float pitch) {
        Pose actual = new Pose(yaw, pitch);
        if (phase.ordinal() < Phase.TICK_STARTED.ordinal())
            note("movement finished without an open tick");
        if (movementOwner != Owner.VANILLA && movement != null && !movement.same(actual))
            note("movement pose diverged: planned=" + movement + " actual=" + actual);
        if (movementOwner == Owner.VANILLA) movement = actual;
        phase = Phase.MOVEMENT_FINISHED;
    }

    /**
     * Commit the pose read by the outbound movement packet.
     *
     * <p>If one feature owned the vanilla movement update, its packet must use
     * that exact pose.  Returning the planned movement pose makes that rule
     * executable instead of leaving it as a comment in Hook.
     */
    Pose commitPacket(Owner owner, float yaw, float pitch) {
        Owner nextOwner = owner == null ? Owner.OTHER : owner;
        Pose next = new Pose(yaw, pitch);
        if (phase.ordinal() < Phase.MOVEMENT_FINISHED.ordinal())
            note("packet committed before movement finished: " + phase);
        if (movementOwner != Owner.VANILLA && movementOwner == nextOwner
                && movement != null && !movement.same(next)) {
            note("packet pose diverged from movement: movement=" + movement + " packet=" + next);
            next = movement;
        }
        packetOwner = nextOwner;
        packet = next;
        phase = Phase.PACKET_COMMITTED;
        return packet;
    }

    /** Keep the rendered head pose on the packet pose for the same owner/tick. */
    Pose commitModel(Owner owner, float yaw, float pitch) {
        Owner nextOwner = owner == null ? Owner.OTHER : owner;
        Pose next = new Pose(yaw, pitch);
        if (packet != null && packetOwner == nextOwner && !packet.same(next)) {
            note("model pose diverged from packet: packet=" + packet + " model=" + next);
            next = packet;
        }
        modelOwner = nextOwner;
        model = next;
        return model;
    }

    Pose cameraPose() { return camera; }
    Pose movementPose() { return movement; }
    Pose packetPose() { return packet; }

    void finishTick() {
        if (phase.ordinal() < Phase.MOVEMENT_FINISHED.ordinal())
            note("tick finished before movement hook: " + phase);
        phase = Phase.TICK_FINISHED;
    }

    String consumeViolation() {
        String out = violation;
        violation = null;
        return out;
    }

    Snapshot snapshot() {
        return new Snapshot(epoch, tick, phase, movementOwner, packetOwner, modelOwner,
                camera, movement, packet, model, violation);
    }

    private void resetTimeline() {
        epoch++;
        tick = Integer.MIN_VALUE;
        phase = Phase.DETACHED;
        movementOwner = packetOwner = modelOwner = Owner.VANILLA;
        camera = movement = packet = model = null;
        violation = null;
    }

    private void note(String text) {
        if (violation == null) violation = text;
        else if (violation.indexOf(text) < 0) violation += " | " + text;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }

    private static float wrap(float value) {
        value %= 360.0f;
        if (value >= 180.0f) value -= 360.0f;
        if (value < -180.0f) value += 360.0f;
        return value;
    }
}
