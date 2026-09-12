package com.infinitygear.mining;

import com.infinitygear.api.v1.MiningIncidentService.Evidence;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** Versioned forensic payload; never decoded into reward permission. */
public final class MiningEvidenceCodec {
    private MiningEvidenceCodec() { }
    public static byte[] encode(Evidence e) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(1); uuid(out, e.operationId()); uuid(out, e.playerId()); uuid(out, e.itemId());
            out.writeUTF(e.mine()); uuid(out, e.generationId()); uuid(out, e.worldId());
            out.writeInt(e.x()); out.writeInt(e.y()); out.writeInt(e.z());
            out.writeUTF(e.originalData()); out.writeUTF(e.placement()); out.writeUTF(e.producerEvidence());
            out.writeUTF(e.stage()); out.writeUTF(e.reason()); out.writeUTF(e.observedAt().toString());
            out.writeInt(e.versions().size());
            for (var entry : new TreeMap<>(e.versions()).entrySet()) { out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue()); }
            out.writeUTF(e.configurationRevision()); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalArgumentException("Evidence cannot be encoded", impossible); }
    }
    public static Evidence decode(byte[] bytes) {
        if (bytes.length > 1000000) throw new IllegalArgumentException("Oversized mining evidence");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 1) throw new IllegalArgumentException("Unknown mining evidence version");
            UUID operation = uuid(in), player = uuid(in), item = uuid(in);
            String mine = in.readUTF(); UUID generation = uuid(in), world = uuid(in);
            int x = in.readInt(), y = in.readInt(), z = in.readInt();
            String original = in.readUTF(), placement = in.readUTF(), producer = in.readUTF(), stage = in.readUTF(), reason = in.readUTF();
            Instant at = Instant.parse(in.readUTF()); int count = in.readInt();
            if (count < 0 || count > 32) throw new IllegalArgumentException("Invalid versions");
            var versions = new TreeMap<String, String>();
            for (int i = 0; i < count; i++) if (versions.put(in.readUTF(), in.readUTF()) != null) throw new IllegalArgumentException("Duplicate version");
            var result = new Evidence(operation, player, item, mine, generation, world, x, y, z,
                    original, placement, producer, stage, reason, at, versions, in.readUTF());
            if (in.available() != 0) throw new IllegalArgumentException("Trailing mining evidence");
            return result;
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid mining evidence", failure); }
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeBoolean(id != null); if (id != null) { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    }
    private static UUID uuid(DataInputStream in) throws IOException { return in.readBoolean() ? new UUID(in.readLong(), in.readLong()) : null; }
}
