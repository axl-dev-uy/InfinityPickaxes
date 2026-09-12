package com.infinitygear.mining;

import java.io.*;
import java.util.*;

/** Mining-only transition. Index n is the captured XP cost from level n to n+1.
 * Persisting the costs makes retry independent of later configuration changes. */
public record MiningXpPlan(UUID pickaxeId, String profileId, long expectedRevision, Progress before,
                           double amount, List<Double> requiredXp) {
    public record Progress(int level, double xp, long blocksMined) {
        public Progress {
            if (level < 0 || !Double.isFinite(xp) || xp < 0 || blocksMined < 0)
                throw new IllegalArgumentException("Invalid XP progress");
            if (xp == 0) xp = 0; // canonicalize negative zero before database round trips
        }
    }
    public record Account(UUID pickaxeId, String profileId, long revision, Progress progress) {
        public Account {
            Objects.requireNonNull(pickaxeId); Objects.requireNonNull(progress);
            if (profileId == null || profileId.isBlank() || profileId.length() > 256 || revision < 0)
                throw new IllegalArgumentException("Invalid XP account");
        }
    }
    public MiningXpPlan {
        Objects.requireNonNull(pickaxeId); Objects.requireNonNull(before);
        requiredXp = List.copyOf(requiredXp);
        if (profileId == null || profileId.isBlank() || profileId.length() > 256 || expectedRevision < 0
                || !Double.isFinite(amount) || amount <= 0 || requiredXp.size() > 10000
                || before.level() > requiredXp.size()) throw new IllegalArgumentException("Invalid mining XP plan");
        for (double value : requiredXp) if (!Double.isFinite(value) || value <= 0)
            throw new IllegalArgumentException("Invalid captured level requirement");
    }
    public Progress after() {
        int level = before.level(); double xp = before.xp();
        if (level < requiredXp.size()) {
            xp += amount;
            if (!Double.isFinite(xp)) throw new IllegalArgumentException("XP overflow");
            while (level < requiredXp.size() && xp >= requiredXp.get(level)) xp -= requiredXp.get(level++);
        }
        return new Progress(level, xp, Math.addExact(before.blocksMined(), 1));
    }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(1); out.writeUTF(pickaxeId.toString()); out.writeUTF(profileId);
            out.writeLong(expectedRevision); out.writeInt(before.level()); out.writeDouble(before.xp());
            out.writeLong(before.blocksMined()); out.writeDouble(amount); out.writeInt(requiredXp.size());
            for (double value : requiredXp) out.writeDouble(value);
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public static MiningXpPlan decode(byte[] bytes) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 1) throw new IllegalArgumentException("Unknown mining XP plan version");
            UUID id = UUID.fromString(in.readUTF()); String profile = in.readUTF(); long revision = in.readLong();
            var progress = new Progress(in.readInt(), in.readDouble(), in.readLong());
            double amount = in.readDouble(); int size = in.readInt();
            if (size < 0 || size > 10000) throw new IllegalArgumentException("Invalid XP plan size");
            var requirements = new ArrayList<Double>(size);
            for (int i = 0; i < size; i++) requirements.add(in.readDouble());
            if (in.available() != 0) throw new IllegalArgumentException("Trailing XP plan data");
            return new MiningXpPlan(id, profile, revision, progress, amount, requirements);
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid persisted mining XP plan", invalid); }
    }
}
