package com.infinitygear.api.v1;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned, implementation-free description of one Archive book lifecycle operation.
 *
 * <p>This is a transaction payload, not permission to mutate an inventory. A provider must
 * durably prepare this exact payload before performing server-thread compare-and-set mutation.
 * Policy references and decisions are evidence only; their presence does not authorize a
 * production policy or advertise a lifecycle capability.</p>
 */
public record BookLifecycleRequest(
        int schemaVersion,
        UUID operationId,
        UUID actorId,
        ProvenancePolicy.Operation operation,
        Optional<Equipment> equipment,
        List<Input> inputs,
        Optional<EnchantmentChange> enchantmentChange,
        List<Output> outputs,
        LineageDecision lineageDecision
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAX_SERIALIZED_ITEM_BYTES = 16_777_215;
    private static final Comparator<InventorySlot> SLOT_ORDER = Comparator
            .comparing((InventorySlot slot) -> slot.holderId().toString())
            .thenComparing(InventorySlot::inventoryId)
            .thenComparingInt(InventorySlot::slot);
    private static final Comparator<Input> INPUT_ORDER = Comparator
            .comparing(Input::slot, SLOT_ORDER)
            .thenComparing(input -> input.bookId().map(UUID::toString).orElse(""));
    private static final Comparator<Output> OUTPUT_ORDER = Comparator
            .comparing((Output output) -> output.bookId().toString());
    private static final Comparator<ValueEntry> VALUE_ORDER = Comparator
            .comparing((ValueEntry entry) -> entry.node().kind().name())
            .thenComparing(entry -> entry.node().identity().toString())
            .thenComparing(entry -> entry.node().enchantmentKey());

    public BookLifecycleRequest {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported lifecycle schema version");
        }
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operation, "operation");
        equipment = Objects.requireNonNull(equipment, "equipment");
        enchantmentChange = Objects.requireNonNull(enchantmentChange, "enchantmentChange");
        inputs = sortedCopy(inputs, INPUT_ORDER, "inputs");
        outputs = sortedCopy(outputs, OUTPUT_ORDER, "outputs");
        Objects.requireNonNull(lineageDecision, "lineageDecision");

        requireDistinctSlots(inputs.stream().map(Input::slot).toList(), "input");
        requireDistinctSlots(outputs.stream().map(Output::slot).toList(), "output");
        requireDistinct(inputs.stream().flatMap(input -> input.bookId().stream()).toList(), "input book");
        requireDistinct(outputs.stream().map(Output::bookId).toList(), "output book");
        Set<UUID> inputBooks = new HashSet<>(inputs.stream().flatMap(input -> input.bookId().stream()).toList());
        if (outputs.stream().anyMatch(output -> inputBooks.contains(output.bookId()))) {
            throw new IllegalArgumentException("Output identities must be fresh");
        }

        boolean equipmentOperation = switch (operation) {
            case APPLY, REMOVE, TRANSFER, REPLACE -> true;
            case PAIR_FUSION, BULK_FUSION -> false;
        };
        if (equipmentOperation != equipment.isPresent() || equipmentOperation != enchantmentChange.isPresent()) {
            throw new IllegalArgumentException("Equipment operations require equipment and an enchantment change only");
        }
        if (equipment.isPresent()) {
            InventorySlot equipmentSlot = equipment.get().slot();
            if (!equipmentSlot.holderId().equals(actorId)) {
                throw new IllegalArgumentException("Equipment holder must be the actor");
            }
            if (inputs.stream().anyMatch(input -> input.slot().equals(equipmentSlot))) {
                throw new IllegalArgumentException("Equipment and input slots must be distinct");
            }
            if (outputs.stream().anyMatch(output -> output.slot().equals(equipmentSlot))) {
                throw new IllegalArgumentException("Equipment and output slots must be distinct");
            }
        }
        if (inputs.stream().anyMatch(input -> !input.slot().holderId().equals(actorId))
                || outputs.stream().anyMatch(output -> !output.slot().holderId().equals(actorId))) {
            throw new IllegalArgumentException("Physical inventory participants must belong to the actor");
        }
        for (Output output : outputs) {
            inputs.stream().filter(input -> input.slot().equals(output.slot())).findFirst().ifPresent(input -> {
                if (input.expectedAmount() != input.consumedAmount()) {
                    throw new IllegalArgumentException("An output slot must be empty after its prepared input is consumed");
                }
            });
        }

        switch (operation) {
            case APPLY -> {
                if (inputs.size() != 1 || inputs.getFirst().kind() != InputKind.ARCHIVE_BOOK) {
                    throw new IllegalArgumentException("Application requires one tracked Archive book");
                }
                if (!outputs.isEmpty()) {
                    throw new IllegalArgumentException("Application does not create book outputs");
                }
                requireChange(enchantmentChange.orElseThrow(), false, true, false, true, "application");
            }
            case REPLACE -> {
                if (inputs.size() != 1 || inputs.getFirst().kind() != InputKind.ARCHIVE_BOOK) {
                    throw new IllegalArgumentException("Replacement requires one tracked Archive book");
                }
                EnchantmentChange change = enchantmentChange.orElseThrow();
                if (change.beforeLevel() < 1 || change.afterLevel() < 1 || !change.archiveAttachmentAfter()) {
                    throw new IllegalArgumentException("Invalid replacement enchantment change");
                }
            }
            case REMOVE -> {
                if (!inputs.isEmpty()) {
                    throw new IllegalArgumentException("Removal takes its source from attachment lineage");
                }
                requireChange(enchantmentChange.orElseThrow(), true, false, true, false, "removal");
            }
            case TRANSFER -> {
                if (inputs.size() != 1 || inputs.getFirst().kind() != InputKind.ORDINARY_BOOK) {
                    throw new IllegalArgumentException("Transfer requires one ordinary physical book input");
                }
                requireChange(enchantmentChange.orElseThrow(), true, false, true, false, "transfer");
            }
            case PAIR_FUSION -> {
                if (inputs.size() != 2 || inputs.stream().noneMatch(input -> input.kind() == InputKind.ARCHIVE_BOOK)
                        || outputs.size() != 1) {
                    throw new IllegalArgumentException("Pair fusion lifecycle requires an Archive participant and one output");
                }
            }
            case BULK_FUSION -> {
                if (inputs.size() < 2 || inputs.stream().noneMatch(input -> input.kind() == InputKind.ARCHIVE_BOOK)
                        || outputs.isEmpty()) {
                    throw new IllegalArgumentException("Bulk fusion lifecycle requires an Archive participant and explicit outputs");
                }
            }
        }
        validateLineageParticipants(equipment, inputs, enchantmentChange, outputs, lineageDecision);
    }

    public String fingerprint() {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(schemaVersion);
                writeUuid(out, operationId);
                writeUuid(out, actorId);
                writeString(out, operation.name());
                out.writeBoolean(equipment.isPresent());
                if (equipment.isPresent()) writeEquipment(out, equipment.get());
                out.writeInt(inputs.size());
                for (Input input : inputs) writeInput(out, input);
                out.writeBoolean(enchantmentChange.isPresent());
                if (enchantmentChange.isPresent()) writeChange(out, enchantmentChange.get());
                out.writeInt(outputs.size());
                for (Output output : outputs) writeOutput(out, output);
                writeLineage(out, lineageDecision);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public enum InputKind { ARCHIVE_BOOK, ORDINARY_BOOK }

    public enum LineageKind { BOOK, ATTACHMENT }

    public record InventorySlot(UUID holderId, String inventoryId, int slot) {
        public InventorySlot {
            Objects.requireNonNull(holderId, "holderId");
            inventoryId = boundedText(inventoryId, 128, "inventoryId");
            if (slot < 0) throw new IllegalArgumentException("Negative inventory slot");
        }
    }

    public record ItemImage(byte[] serializedItem) {
        public ItemImage {
            Objects.requireNonNull(serializedItem, "serializedItem");
            if (serializedItem.length == 0 || serializedItem.length > MAX_SERIALIZED_ITEM_BYTES) {
                throw new IllegalArgumentException("Invalid serialized item size");
            }
            serializedItem = serializedItem.clone();
        }

        @Override public byte[] serializedItem() {
            return serializedItem.clone();
        }

        @Override public boolean equals(Object other) {
            return other instanceof ItemImage image && Arrays.equals(serializedItem, image.serializedItem);
        }

        @Override public int hashCode() {
            return Arrays.hashCode(serializedItem);
        }

        public String sha256() {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serializedItem));
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    public record Input(InputKind kind, Optional<UUID> bookId, InventorySlot slot,
                        int expectedAmount, int consumedAmount,
                        ItemImage beforeImage) {
        public Input {
            Objects.requireNonNull(kind, "kind");
            bookId = Objects.requireNonNull(bookId, "bookId");
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(beforeImage, "beforeImage");
            if (expectedAmount < 1 || consumedAmount < 1 || consumedAmount > expectedAmount) {
                throw new IllegalArgumentException("Input amounts must describe a positive bounded consumption");
            }
            if ((kind == InputKind.ARCHIVE_BOOK) != bookId.isPresent()) {
                throw new IllegalArgumentException("Only Archive inputs have tracked book identities");
            }
            if (kind == InputKind.ARCHIVE_BOOK && (expectedAmount != 1 || consumedAmount != 1)) {
                throw new IllegalArgumentException("Tracked Archive books must be unstacked");
            }
        }
    }

    public record Equipment(UUID equipmentId, long expectedRevision, long resultingRevision, InventorySlot slot,
                            ItemImage beforeImage, ItemImage afterImage) {
        public Equipment {
            Objects.requireNonNull(equipmentId, "equipmentId");
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(beforeImage, "beforeImage");
            Objects.requireNonNull(afterImage, "afterImage");
            if (expectedRevision < 0) throw new IllegalArgumentException("Negative equipment revision");
            if (resultingRevision != Math.addExact(expectedRevision, 1)) {
                throw new IllegalArgumentException("Equipment revision must advance exactly once");
            }
            if (beforeImage.sha256().equals(afterImage.sha256())) {
                throw new IllegalArgumentException("Equipment mutation requires distinct before and after images");
            }
        }
    }

    public record EnchantmentChange(String enchantmentKey, int beforeLevel, int afterLevel,
                                    boolean archiveAttachmentBefore, boolean archiveAttachmentAfter) {
        public EnchantmentChange {
            enchantmentKey = BookLifecycleRequest.enchantmentKey(enchantmentKey);
            if (beforeLevel < 0 || afterLevel < 0 || beforeLevel == afterLevel) {
                throw new IllegalArgumentException("Enchantment levels must be non-negative and change");
            }
            if ((archiveAttachmentBefore && beforeLevel == 0) || (archiveAttachmentAfter && afterLevel == 0)) {
                throw new IllegalArgumentException("Archive attachment requires an installed enchantment");
            }
        }
    }

    public record Output(UUID bookId, String enchantmentKey, int level, InventorySlot slot,
                         ItemImage canonicalItem) {
        public Output {
            Objects.requireNonNull(bookId, "bookId");
            enchantmentKey = BookLifecycleRequest.enchantmentKey(enchantmentKey);
            if (level < 1) throw new IllegalArgumentException("Output level must be positive");
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(canonicalItem, "canonicalItem");
        }
    }

    public record LineageNode(LineageKind kind, UUID identity, String enchantmentKey) {
        public LineageNode {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(identity, "identity");
            enchantmentKey = BookLifecycleRequest.enchantmentKey(enchantmentKey);
        }
    }

    public record ValueEntry(LineageNode node, BigDecimal value) {
        public ValueEntry {
            Objects.requireNonNull(node, "node");
            value = BookLedger.exactValue(value);
        }
    }

    public record LineageDecision(String policyReference, String reason,
                                  List<ValueEntry> sources, List<ValueEntry> destinations,
                                  BigDecimal disposedValue) {
        public LineageDecision {
            policyReference = boundedText(policyReference, 512, "policyReference");
            reason = boundedText(reason, 1024, "reason");
            sources = sortedCopy(sources, VALUE_ORDER, "lineage sources");
            destinations = sortedCopy(destinations, VALUE_ORDER, "lineage destinations");
            disposedValue = BookLedger.exactValue(disposedValue);
            requireDistinct(sources.stream().map(ValueEntry::node).toList(), "lineage source");
            requireDistinct(destinations.stream().map(ValueEntry::node).toList(), "lineage destination");
            BigDecimal sourceTotal = BookLedger.exactValue(sources.stream().map(ValueEntry::value)
                    .reduce(BookLedger.exactValue(BigDecimal.ZERO), BigDecimal::add));
            BigDecimal destinationTotal = BookLedger.exactValue(destinations.stream().map(ValueEntry::value)
                    .reduce(BookLedger.exactValue(BigDecimal.ZERO), BigDecimal::add));
            if (BookLedger.exactValue(destinationTotal.add(disposedValue)).compareTo(sourceTotal) != 0) {
                throw new IllegalArgumentException("Lineage decision must explicitly conserve or dispose every exact value");
            }
        }
    }

    private static void validateLineageParticipants(Optional<Equipment> equipment, List<Input> inputs,
                                                    Optional<EnchantmentChange> change, List<Output> outputs,
                                                    LineageDecision decision) {
        Set<LineageNode> allowedSources = new HashSet<>();
        for (Input input : inputs) input.bookId().ifPresent(id -> allowedSources.add(new LineageNode(
                LineageKind.BOOK, id, inputEnchantmentKey(id, decision))));
        if (equipment.isPresent() && change.orElseThrow().archiveAttachmentBefore()) {
            allowedSources.add(new LineageNode(LineageKind.ATTACHMENT, equipment.get().equipmentId(),
                    change.get().enchantmentKey()));
        }
        Set<LineageNode> allowedDestinations = new HashSet<>();
        for (Output output : outputs) allowedDestinations.add(new LineageNode(
                LineageKind.BOOK, output.bookId(), output.enchantmentKey()));
        if (equipment.isPresent() && change.orElseThrow().archiveAttachmentAfter()) {
            allowedDestinations.add(new LineageNode(LineageKind.ATTACHMENT, equipment.get().equipmentId(),
                    change.get().enchantmentKey()));
        }
        if (!allowedSources.containsAll(decision.sources().stream().map(ValueEntry::node).toList())
                || !allowedDestinations.containsAll(decision.destinations().stream().map(ValueEntry::node).toList())) {
            throw new IllegalArgumentException("Lineage decision references a non-participant identity");
        }
        Set<LineageNode> declaredSources = new HashSet<>(decision.sources().stream().map(ValueEntry::node).toList());
        Set<LineageNode> declaredDestinations = new HashSet<>(decision.destinations().stream().map(ValueEntry::node).toList());
        if (!declaredSources.equals(allowedSources) || !declaredDestinations.equals(allowedDestinations)) {
            throw new IllegalArgumentException("Every tracked lineage participant requires an exact value");
        }
    }

    private static String inputEnchantmentKey(UUID id, LineageDecision decision) {
        return decision.sources().stream()
                .map(ValueEntry::node)
                .filter(node -> node.kind() == LineageKind.BOOK && node.identity().equals(id))
                .map(LineageNode::enchantmentKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tracked input is missing from lineage sources"));
    }

    private static void writeEquipment(DataOutputStream out, Equipment equipment) throws IOException {
        writeUuid(out, equipment.equipmentId());
        out.writeLong(equipment.expectedRevision());
        out.writeLong(equipment.resultingRevision());
        writeSlot(out, equipment.slot());
        writeImage(out, equipment.beforeImage());
        writeImage(out, equipment.afterImage());
    }

    private static void writeInput(DataOutputStream out, Input input) throws IOException {
        writeString(out, input.kind().name());
        out.writeBoolean(input.bookId().isPresent());
        if (input.bookId().isPresent()) writeUuid(out, input.bookId().get());
        writeSlot(out, input.slot());
        out.writeInt(input.expectedAmount());
        out.writeInt(input.consumedAmount());
        writeImage(out, input.beforeImage());
    }

    private static void writeChange(DataOutputStream out, EnchantmentChange change) throws IOException {
        writeString(out, change.enchantmentKey());
        out.writeInt(change.beforeLevel());
        out.writeInt(change.afterLevel());
        out.writeBoolean(change.archiveAttachmentBefore());
        out.writeBoolean(change.archiveAttachmentAfter());
    }

    private static void writeOutput(DataOutputStream out, Output output) throws IOException {
        writeUuid(out, output.bookId());
        writeString(out, output.enchantmentKey());
        out.writeInt(output.level());
        writeSlot(out, output.slot());
        writeImage(out, output.canonicalItem());
    }

    private static void writeLineage(DataOutputStream out, LineageDecision decision) throws IOException {
        writeString(out, decision.policyReference());
        writeString(out, decision.reason());
        out.writeInt(decision.sources().size());
        for (ValueEntry source : decision.sources()) writeValue(out, source);
        out.writeInt(decision.destinations().size());
        for (ValueEntry destination : decision.destinations()) writeValue(out, destination);
        writeString(out, decision.disposedValue().toPlainString());
    }

    private static void writeValue(DataOutputStream out, ValueEntry entry) throws IOException {
        writeString(out, entry.node().kind().name());
        writeUuid(out, entry.node().identity());
        writeString(out, entry.node().enchantmentKey());
        writeString(out, entry.value().toPlainString());
    }

    private static void writeSlot(DataOutputStream out, InventorySlot slot) throws IOException {
        writeUuid(out, slot.holderId());
        writeString(out, slot.inventoryId());
        out.writeInt(slot.slot());
    }

    private static void writeImage(DataOutputStream out, ItemImage image) throws IOException {
        byte[] bytes = image.serializedItem();
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String boundedText(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private static String enchantmentKey(String value) {
        if (value == null || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid enchantment key");
        }
        return value;
    }

    private static <T> List<T> sortedCopy(List<T> values, Comparator<? super T> comparator, String name) {
        Objects.requireNonNull(values, name);
        var copy = new ArrayList<T>(values.size());
        for (T value : values) copy.add(Objects.requireNonNull(value, name + " element"));
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    private static <T> void requireDistinct(List<T> values, String name) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("Distinct " + name + " values required");
        }
    }

    private static void requireDistinctSlots(List<InventorySlot> slots, String name) {
        requireDistinct(slots, name + " slot");
    }

    private static void requireChange(EnchantmentChange change, boolean beforePresent,
                                      boolean afterPresent, boolean attachmentBefore,
                                      boolean attachmentAfter, String operation) {
        if ((change.beforeLevel() > 0) != beforePresent || (change.afterLevel() > 0) != afterPresent
                || change.archiveAttachmentBefore() != attachmentBefore
                || change.archiveAttachmentAfter() != attachmentAfter) {
            throw new IllegalArgumentException("Invalid " + operation + " enchantment change");
        }
    }
}
