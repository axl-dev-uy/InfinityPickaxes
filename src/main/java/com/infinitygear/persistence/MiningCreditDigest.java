package com.infinitygear.persistence;

import com.infinitygear.api.v1.MiningCredit;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Canonical v1 digest of every immutable credit field, independent of Java serialization. */
public final class MiningCreditDigest {
    public static final int VERSION = 1;
    private static final byte[] DOMAIN = "InfinityGear/MiningCredit/v1".getBytes(StandardCharsets.US_ASCII);

    private MiningCreditDigest() { }

    public static byte[] sha256(MiningCredit credit) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.write(DOMAIN);
            uuid(out, credit.creditId()); uuid(out, credit.instanceId()); uuid(out, credit.playerId());
            uuid(out, credit.pickaxeId()); string(out, credit.profileId()); uuid(out, credit.worldId());
            out.writeInt(credit.x()); out.writeInt(credit.y()); out.writeInt(credit.z());
            string(out, credit.originalBlockData()); string(out, credit.source().name());
            string(out, credit.generation()); out.writeByte(credit.legitimate() ? 1 : 0);
            out.writeByte(credit.successful() ? 1 : 0);
            out.flush();
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }
}
