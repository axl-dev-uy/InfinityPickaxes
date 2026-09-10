package com.infinitygear.persistence;

import com.infinitygear.api.v1.BookLifecycleRequest;
import com.infinitygear.api.v1.ProvenancePolicy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/** Private durable encoding. The public request schema remains implementation independent. */
final class BookLifecycleRequestCodec {
    private static final int MAGIC = 0x49474c43; // IGLC
    private static final int FORMAT = 1;
    private static final int MAX_COLLECTION = 100_000;
    private static final int MAX_TEXT_BYTES = 1_048_576;
    private static final int MAX_ITEM_BYTES = 16_777_215;

    private BookLifecycleRequestCodec() { }

    static byte[] encode(BookLifecycleRequest request) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT);
                out.writeInt(request.schemaVersion());
                uuid(out, request.operationId());
                uuid(out, request.actorId());
                text(out, request.operation().name());
                out.writeBoolean(request.equipment().isPresent());
                if (request.equipment().isPresent()) equipment(out, request.equipment().orElseThrow());
                out.writeInt(request.inputs().size());
                for (var input : request.inputs()) input(out, input);
                out.writeBoolean(request.enchantmentChange().isPresent());
                if (request.enchantmentChange().isPresent()) change(out, request.enchantmentChange().orElseThrow());
                out.writeInt(request.outputs().size());
                for (var output : request.outputs()) output(out, output);
                lineage(out, request.lineageDecision());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static BookLifecycleRequest decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Missing lifecycle request payload");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) {
                throw new IllegalArgumentException("Unsupported lifecycle request encoding");
            }
            int schema = in.readInt();
            UUID operationId = uuid(in);
            UUID actorId = uuid(in);
            var operation = ProvenancePolicy.Operation.valueOf(text(in));
            Optional<BookLifecycleRequest.Equipment> equipment = in.readBoolean()
                    ? Optional.of(equipment(in)) : Optional.empty();
            var inputs = new ArrayList<BookLifecycleRequest.Input>();
            for (int i = 0, size = count(in); i < size; i++) inputs.add(input(in));
            Optional<BookLifecycleRequest.EnchantmentChange> change = in.readBoolean()
                    ? Optional.of(change(in)) : Optional.empty();
            var outputs = new ArrayList<BookLifecycleRequest.Output>();
            for (int i = 0, size = count(in); i < size; i++) outputs.add(output(in));
            var request = new BookLifecycleRequest(schema, operationId, actorId, operation, equipment,
                    inputs, change, outputs, lineage(in));
            if (in.read() != -1) throw new IllegalArgumentException("Trailing lifecycle request data");
            return request;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("Invalid lifecycle request payload", failure);
        }
    }

    private static void equipment(DataOutputStream out, BookLifecycleRequest.Equipment value) throws IOException {
        uuid(out, value.equipmentId());
        out.writeLong(value.expectedRevision());
        out.writeLong(value.resultingRevision());
        slot(out, value.slot());
        image(out, value.beforeImage());
        image(out, value.afterImage());
    }

    private static BookLifecycleRequest.Equipment equipment(DataInputStream in) throws IOException {
        return new BookLifecycleRequest.Equipment(uuid(in), in.readLong(), in.readLong(), slot(in), image(in), image(in));
    }

    private static void input(DataOutputStream out, BookLifecycleRequest.Input value) throws IOException {
        text(out, value.kind().name());
        out.writeBoolean(value.bookId().isPresent());
        if (value.bookId().isPresent()) uuid(out, value.bookId().orElseThrow());
        slot(out, value.slot());
        out.writeInt(value.expectedAmount());
        out.writeInt(value.consumedAmount());
        image(out, value.beforeImage());
    }

    private static BookLifecycleRequest.Input input(DataInputStream in) throws IOException {
        var kind = BookLifecycleRequest.InputKind.valueOf(text(in));
        Optional<UUID> id = in.readBoolean() ? Optional.of(uuid(in)) : Optional.empty();
        return new BookLifecycleRequest.Input(kind, id, slot(in), in.readInt(), in.readInt(), image(in));
    }

    private static void change(DataOutputStream out, BookLifecycleRequest.EnchantmentChange value) throws IOException {
        text(out, value.enchantmentKey());
        out.writeInt(value.beforeLevel());
        out.writeInt(value.afterLevel());
        out.writeBoolean(value.archiveAttachmentBefore());
        out.writeBoolean(value.archiveAttachmentAfter());
    }

    private static BookLifecycleRequest.EnchantmentChange change(DataInputStream in) throws IOException {
        return new BookLifecycleRequest.EnchantmentChange(text(in), in.readInt(), in.readInt(),
                in.readBoolean(), in.readBoolean());
    }

    private static void output(DataOutputStream out, BookLifecycleRequest.Output value) throws IOException {
        uuid(out, value.bookId());
        text(out, value.enchantmentKey());
        out.writeInt(value.level());
        slot(out, value.slot());
        image(out, value.canonicalItem());
    }

    private static BookLifecycleRequest.Output output(DataInputStream in) throws IOException {
        return new BookLifecycleRequest.Output(uuid(in), text(in), in.readInt(), slot(in), image(in));
    }

    private static void lineage(DataOutputStream out, BookLifecycleRequest.LineageDecision value) throws IOException {
        text(out, value.policyReference());
        text(out, value.reason());
        out.writeInt(value.sources().size());
        for (var entry : value.sources()) value(out, entry);
        out.writeInt(value.destinations().size());
        for (var entry : value.destinations()) value(out, entry);
        text(out, value.disposedValue().toPlainString());
    }

    private static BookLifecycleRequest.LineageDecision lineage(DataInputStream in) throws IOException {
        String policy = text(in);
        String reason = text(in);
        var sources = new ArrayList<BookLifecycleRequest.ValueEntry>();
        for (int i = 0, size = count(in); i < size; i++) sources.add(value(in));
        var destinations = new ArrayList<BookLifecycleRequest.ValueEntry>();
        for (int i = 0, size = count(in); i < size; i++) destinations.add(value(in));
        return new BookLifecycleRequest.LineageDecision(policy, reason, sources, destinations,
                new BigDecimal(text(in)));
    }

    private static void value(DataOutputStream out, BookLifecycleRequest.ValueEntry value) throws IOException {
        text(out, value.node().kind().name());
        uuid(out, value.node().identity());
        text(out, value.node().enchantmentKey());
        text(out, value.value().toPlainString());
    }

    private static BookLifecycleRequest.ValueEntry value(DataInputStream in) throws IOException {
        var node = new BookLifecycleRequest.LineageNode(BookLifecycleRequest.LineageKind.valueOf(text(in)),
                uuid(in), text(in));
        return new BookLifecycleRequest.ValueEntry(node, new BigDecimal(text(in)));
    }

    private static void slot(DataOutputStream out, BookLifecycleRequest.InventorySlot value) throws IOException {
        uuid(out, value.holderId());
        text(out, value.inventoryId());
        out.writeInt(value.slot());
    }

    private static BookLifecycleRequest.InventorySlot slot(DataInputStream in) throws IOException {
        return new BookLifecycleRequest.InventorySlot(uuid(in), text(in), in.readInt());
    }

    private static void image(DataOutputStream out, BookLifecycleRequest.ItemImage value) throws IOException {
        byte[] bytes = value.serializedItem();
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static BookLifecycleRequest.ItemImage image(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 1 || size > MAX_ITEM_BYTES) throw new IllegalArgumentException("Invalid lifecycle item image size");
        return new BookLifecycleRequest.ItemImage(bytes(in, size));
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_TEXT_BYTES) throw new IllegalArgumentException("Invalid lifecycle text size");
        return new String(bytes(in, size), StandardCharsets.UTF_8);
    }

    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_COLLECTION) throw new IllegalArgumentException("Invalid lifecycle collection size");
        return count;
    }

    private static byte[] bytes(DataInputStream in, int size) throws IOException {
        byte[] result = in.readNBytes(size);
        if (result.length != size) throw new IllegalArgumentException("Truncated lifecycle request payload");
        return result;
    }
}
