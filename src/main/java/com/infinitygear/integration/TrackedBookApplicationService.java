package com.infinitygear.integration;

import com.infinitygear.api.InfinityGearServiceImpl;
import com.infinitygear.api.v1.BookApplicationService;
import com.infinitygear.api.v1.BookLedger;
import com.infinitygear.api.v1.BookLifecycleRequest;
import com.infinitygear.api.v1.BookLifecycleTransaction;
import com.infinitygear.data.GearData;
import com.infinitygear.data.TrackedItemData;
import com.infinitygear.data.TrackedKind;
import com.infinitygear.persistence.MariaBookLifecycleTransaction;
import com.infinitypickaxes.InfinityPickaxes;
import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import static com.infinitygear.api.v1.BookLifecycleTransaction.Phase.*;

/** First physical lifecycle slice: one tracked book applied to an empty enchantment slot. */
public final class TrackedBookApplicationService implements BookApplicationService, AutoCloseable {
    private static final String INVENTORY = "player-inventory";

    private final InfinityPickaxes plugin;
    private final BookLedger ledger;
    private final MariaBookLifecycleTransaction lifecycle;
    private final IntegrationTasks tasks;
    private final java.util.function.BooleanSupplier active;
    private final ConcurrentHashMap<UUID, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public TrackedBookApplicationService(InfinityPickaxes plugin, BookLedger ledger,
                                         MariaBookLifecycleTransaction lifecycle, IntegrationTasks tasks,
                                         java.util.function.BooleanSupplier active) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.active = Objects.requireNonNull(active, "active");
    }

    @Override
    public CompletionStage<Result> apply(Request request) {
        requireServerThread();
        Objects.requireNonNull(request, "request");
        if (!available()) return completed(request.operationId(), Outcome.REJECTED,
                "Archive book application is unavailable", -1, 0);

        CompletableFuture<Result> existing = inFlight.get(request.operationId());
        if (existing != null) return existing;
        CompletableFuture<Result> exposed = new CompletableFuture<>();
        existing = inFlight.putIfAbsent(request.operationId(), exposed);
        if (existing != null) return existing;

        try {
            Initial initial = capture(request);
            PolicyAuthority authority = plugin.getServer().getServicesManager().load(PolicyAuthority.class);
            if (authority == null) {
                finish(exposed, rejected(request.operationId(), "No production Archive application policy is registered"));
                return exposed;
            }
            tasks.database(() -> lifecycle.find(request.operationId()))
                    .thenCompose(found -> found.isPresent()
                            ? verifyReplay(request, found.orElseThrow())
                            : prepareNew(initial, authority))
                    .thenCompose(this::drive)
                    .whenComplete((result, failure) -> {
                        if (failure == null) finish(exposed, result);
                        else finish(exposed, recoveryRequired(request.operationId(), failure));
                    });
        } catch (RuntimeException rejected) {
            finish(exposed, rejected(request.operationId(), safeReason(rejected)));
        }
        return exposed;
    }

    @Override
    public CompletionStage<Result> recover(UUID operationId) {
        requireServerThread();
        Objects.requireNonNull(operationId, "operationId");
        if (!available()) return completed(operationId, Outcome.RECOVERY_REQUIRED,
                "Archive book recovery is unavailable", -1, 0);
        CompletableFuture<Result> existing = inFlight.get(operationId);
        if (existing != null) return existing;
        CompletableFuture<Result> exposed = new CompletableFuture<>();
        existing = inFlight.putIfAbsent(operationId, exposed);
        if (existing != null) return existing;
        tasks.database(() -> lifecycle.find(operationId))
                .thenCompose(found -> found.<CompletionStage<Result>>map(this::drive)
                        .orElseGet(() -> completed(operationId, Outcome.REJECTED,
                                "Unknown lifecycle operation", -1, 0)))
                .whenComplete((result, failure) -> {
                    if (failure == null) finish(exposed, result);
                    else finish(exposed, recoveryRequired(operationId, failure));
                });
        return exposed;
    }

    private CompletionStage<BookLifecycleTransaction.State> verifyReplay(
            Request requested, BookLifecycleTransaction.State state) {
        BookLifecycleRequest stored = state.request();
        boolean matches = stored.operation() == com.infinitygear.api.v1.ProvenancePolicy.Operation.APPLY
                && stored.actorId().equals(requested.actorId())
                && stored.equipment().orElseThrow().slot().slot() == requested.equipmentSlot()
                && stored.inputs().getFirst().slot().slot() == requested.sourceSlot()
                && stored.enchantmentChange().orElseThrow().enchantmentKey().equals(requested.enchantmentKey());
        return matches ? CompletableFuture.completedFuture(state)
                : CompletableFuture.failedFuture(new IllegalArgumentException(
                "Operation ID reused with conflicting application coordinates"));
    }

    private CompletionStage<BookLifecycleTransaction.State> prepareNew(Initial initial,
                                                                        PolicyAuthority authority) {
        return tasks.database(() -> {
            BookLedger.Receipt receipt = ledger.find(initial.bookId()).orElseThrow(() ->
                    new IllegalArgumentException("Unknown Archive source book"));
            if (receipt.consumed()) throw new IllegalArgumentException("Archive source book is already consumed");
            long revision = lifecycle.equipmentRevision(initial.equipmentId()).orElse(0L);
            if (lifecycle.attachment(initial.equipmentId(), initial.enchantmentKey()).isPresent()) {
                throw new IllegalArgumentException("Archive attachment replacement is not supported");
            }
            if (!receipt.issue().enchantmentKey().equals(initial.enchantmentKey())
                    || receipt.issue().level() != initial.level()) {
                throw new IllegalArgumentException("Archive source identity does not match its enchantment");
            }
            Decision decision = authority.authorize(new PolicyRequest(initial.request().operationId(),
                    initial.request().actorId(), initial.equipmentId(), initial.profileId(), initial.bookId(),
                    initial.enchantmentKey(), initial.level(), receipt.issue().provenanceReference(),
                    receipt.issue().sourceValue()));
            if (decision == null || !decision.allowed()) {
                throw new IllegalArgumentException(decision == null ? "Application policy returned no decision"
                        : decision.reason());
            }
            return new DurableContext(receipt, revision, decision);
        }).thenCompose(context -> tasks.server(() -> buildRequest(initial, context)))
                .thenCompose(request -> tasks.database(() -> lifecycle.prepare(request)));
    }

    private Initial capture(Request request) {
        Player actor = actor(request.actorId());
        ItemStack equipment = slot(actor, request.equipmentSlot());
        ItemStack source = slot(actor, request.sourceSlot());
        if (equipment == null || source == null) throw new IllegalArgumentException("Application participant is missing");
        if (BookLifecycleItems.hasCustodyMarker(equipment) || BookLifecycleItems.hasCustodyMarker(source)) {
            throw new IllegalArgumentException("A lifecycle custody marker already owns a participant");
        }
        TrackedItemData.Identity bookIdentity = TrackedItemData.read(source);
        if (bookIdentity == null || bookIdentity.kind() != TrackedKind.ARCHIVE_BOOK
                || bookIdentity.quarantined() || !ArchiveBookIdentity.marked(source)) {
            throw new IllegalArgumentException("Valid unstacked Archive source book required");
        }
        var gear = plugin.getGearManager().inspect(equipment, true)
                .orElseThrow(() -> new IllegalArgumentException("Valid tracked equipment required"));
        if (!plugin.getDuplicateService().isUsable(equipment)
                || !plugin.getDuplicateService().isUsable(source)) {
            throw new IllegalArgumentException("Quarantined or restricted participant");
        }
        if (visibleCopies(gear.uuid()) != 1 || visibleCopies(bookIdentity.uuid()) != 1) {
            throw new IllegalArgumentException("Unique visible custody is required");
        }
        var managed = plugin.getEnchantManager().getManagedBookEnchants(source);
        if (managed.size() != 1 || !managed.getFirst().socket().getKeyString()
                .equals(request.enchantmentKey())) {
            throw new IllegalArgumentException("Archive source contains the wrong managed enchantment");
        }
        var nativeEnchant = plugin.getEnchantManager().getEnchantment(request.enchantmentKey());
        if (nativeEnchant == null || equipment.getEnchantmentLevel(nativeEnchant) != 0) {
            throw new IllegalArgumentException("Only no-replacement Archive application is supported");
        }
        return new Initial(request, gear.uuid(), gear.profileId(), bookIdentity.uuid(),
                request.enchantmentKey(), managed.getFirst().level(), equipment.clone(), source.clone());
    }

    private BookLifecycleRequest buildRequest(Initial initial, DurableContext context) {
        if (!available()) throw new IllegalStateException("Integration changed before lifecycle preparation");
        Player actor = actor(initial.request().actorId());
        ItemStack equipment = slot(actor, initial.request().equipmentSlot());
        ItemStack source = slot(actor, initial.request().sourceSlot());
        if (!same(equipment, initial.equipment()) || !same(source, initial.source())) {
            throw new IllegalStateException("Application participants changed before preparation");
        }
        if (!ArchiveBookIdentity.matches(source, context.receipt())) {
            throw new IllegalArgumentException("Archive source artifact or identity mismatch");
        }
        var gear = plugin.getGearManager().inspect(equipment, true).orElseThrow();
        if (!gear.uuid().equals(initial.equipmentId()) || !gear.profileId().equals(initial.profileId())) {
            throw new IllegalStateException("Equipment identity changed before preparation");
        }
        var plan = ((InfinityGearServiceImpl) plugin.getGearService()).prepareArchiveApplication(
                equipment, source, initial.enchantmentKey(), initial.request().operationId(),
                Math.addExact(context.revision(), 1));
        if (!plan.equipmentId().equals(initial.equipmentId())
                || !plan.profileId().equals(initial.profileId()) || plan.resultingLevel() != initial.level()) {
            throw new IllegalStateException("Application plan changed before preparation");
        }

        var equipmentSlot = inventorySlot(initial.request().actorId(), initial.request().equipmentSlot());
        var sourceSlot = inventorySlot(initial.request().actorId(), initial.request().sourceSlot());
        var beforeEquipment = image(equipment);
        var beforeSource = image(source);
        var equipmentParticipant = new BookLifecycleRequest.Equipment(initial.equipmentId(),
                context.revision(), Math.addExact(context.revision(), 1), equipmentSlot,
                beforeEquipment, image(plan.afterImage()));
        var input = new BookLifecycleRequest.Input(BookLifecycleRequest.InputKind.ARCHIVE_BOOK,
                Optional.of(initial.bookId()), sourceSlot, 1, 1, beforeSource);
        var change = new BookLifecycleRequest.EnchantmentChange(initial.enchantmentKey(), 0,
                initial.level(), false, true);
        var sourceNode = new BookLifecycleRequest.LineageNode(BookLifecycleRequest.LineageKind.BOOK,
                initial.bookId(), initial.enchantmentKey());
        var attachmentNode = new BookLifecycleRequest.LineageNode(BookLifecycleRequest.LineageKind.ATTACHMENT,
                initial.equipmentId(), initial.enchantmentKey());
        BigDecimal value = context.receipt().issue().sourceValue();
        var lineage = new BookLifecycleRequest.LineageDecision(context.decision().policyReference(),
                context.decision().reason(), List.of(new BookLifecycleRequest.ValueEntry(sourceNode, value)),
                List.of(new BookLifecycleRequest.ValueEntry(attachmentNode, value)),
                BookLedger.exactValue(BigDecimal.ZERO));
        return new BookLifecycleRequest(BookLifecycleRequest.CURRENT_SCHEMA_VERSION,
                initial.request().operationId(), initial.request().actorId(),
                com.infinitygear.api.v1.ProvenancePolicy.Operation.APPLY,
                Optional.of(equipmentParticipant), List.of(input), Optional.of(change), List.of(), lineage);
    }

    private CompletionStage<Result> drive(BookLifecycleTransaction.State state) {
        if (!available()) return CompletableFuture.completedFuture(result(state, Outcome.RECOVERY_REQUIRED,
                "Integration is reloading or stopped"));
        return switch (state.phase()) {
            case PREPARED -> tasks.server(() -> markCustody(state)).thenCompose(ok -> ok
                    ? advance(state, CUSTODY_MARKED) : abortPrepared(state));
            case CUSTODY_MARKED -> tasks.server(() -> removeSource(state)).thenCompose(ok -> ok
                    ? advance(state, SOURCES_REMOVED) : rollbackOrRequire(state, "Source custody changed"));
            case SOURCES_REMOVED -> tasks.server(() -> mutateEquipment(state)).thenCompose(ok -> ok
                    ? advance(state, EQUIPMENT_MUTATED) : rollbackOrRequire(state, "Equipment custody changed"));
            case EQUIPMENT_MUTATED -> tasks.server(() -> physicalAfterPresent(state)).thenCompose(ok -> ok
                    ? advance(state, FINALIZED) : rollbackOrRequire(state, "Physical after-state is not exact"));
            case FINALIZED -> finalizedAuthority(state).thenCompose(authority -> authority
                    ? tasks.server(() -> acknowledgePhysical(state)).thenCompose(ok -> ok
                    ? advance(state, ACKNOWLEDGED) : CompletableFuture.completedFuture(result(state,
                    Outcome.RECOVERY_REQUIRED, "Finalized physical cleanup requires operator recovery")))
                    : CompletableFuture.completedFuture(result(state, Outcome.RECOVERY_REQUIRED,
                    "Finalized attachment authority does not match the physical request")));
            case ACKNOWLEDGED -> CompletableFuture.completedFuture(result(state, Outcome.ACKNOWLEDGED,
                    "Archive book application acknowledged"));
            case ABORTED, ROLLED_BACK -> CompletableFuture.completedFuture(result(state, Outcome.REJECTED,
                    "Archive book application is terminal: " + state.phase()));
            case OUTPUTS_INSERTED -> CompletableFuture.completedFuture(result(state, Outcome.RECOVERY_REQUIRED,
                    "Unexpected output phase for application"));
        };
    }

    private CompletionStage<Result> advance(BookLifecycleTransaction.State state,
                                             BookLifecycleTransaction.Phase next) {
        return tasks.database(() -> lifecycle.advance(new BookLifecycleTransaction.Advance(
                        state.request().operationId(), state.fingerprint(), state.phase(), next)))
                .thenCompose(this::drive);
    }

    private CompletionStage<Result> abortPrepared(BookLifecycleTransaction.State state) {
        return tasks.server(() -> participantsHaveNoOperationMarkers(state)).thenCompose(unmarked -> unmarked
                ? advance(state, ABORTED)
                : CompletableFuture.completedFuture(result(state, Outcome.RECOVERY_REQUIRED,
                "Prepared participants changed or contain partial custody")));
    }

    private CompletionStage<Result> rollbackOrRequire(BookLifecycleTransaction.State state, String reason) {
        return tasks.server(() -> restoreBefore(state)).thenCompose(restored -> restored
                ? advance(state, ROLLED_BACK)
                : CompletableFuture.completedFuture(result(state, Outcome.RECOVERY_REQUIRED, reason)));
    }

    private boolean markCustody(BookLifecycleTransaction.State state) {
        Participant participant = participant(state);
        if (participant == null) return false;
        var equipment = state.request().equipment().orElseThrow();
        var input = state.request().inputs().getFirst();
        boolean equipmentMarked = BookLifecycleItems.markedBeforeMatches(participant.equipment(),
                state.request().operationId(), BookLifecycleItems.EQUIPMENT, equipment.beforeImage());
        boolean sourceMarked = BookLifecycleItems.markedBeforeMatches(participant.source(),
                state.request().operationId(), BookLifecycleItems.SOURCE, input.beforeImage());
        boolean equipmentClean = BookLifecycleItems.exact(participant.equipment(), equipment.beforeImage())
                && !BookLifecycleItems.hasCustodyMarker(participant.equipment());
        boolean sourceClean = BookLifecycleItems.exact(participant.source(), input.beforeImage())
                && !BookLifecycleItems.hasCustodyMarker(participant.source());
        if ((!equipmentMarked && !equipmentClean) || (!sourceMarked && !sourceClean)) return false;
        if (!equipmentMarked) {
            ItemStack markedEquipment = participant.equipment().clone();
            BookLifecycleItems.markBefore(markedEquipment, state.request().operationId(),
                    BookLifecycleItems.EQUIPMENT, equipment.beforeImage());
            participant.player().getInventory().setItem(equipment.slot().slot(), markedEquipment);
        }
        if (!sourceMarked) {
            ItemStack markedSource = participant.source().clone();
            BookLifecycleItems.markBefore(markedSource, state.request().operationId(),
                    BookLifecycleItems.SOURCE, input.beforeImage());
            participant.player().getInventory().setItem(input.slot().slot(), markedSource);
        }
        Participant marked = participant(state);
        return marked != null && BookLifecycleItems.markedBeforeMatches(marked.equipment(),
                state.request().operationId(), BookLifecycleItems.EQUIPMENT, equipment.beforeImage())
                && BookLifecycleItems.markedBeforeMatches(marked.source(), state.request().operationId(),
                BookLifecycleItems.SOURCE, input.beforeImage());
    }

    private boolean removeSource(BookLifecycleTransaction.State state) {
        Participant participant = participant(state);
        if (participant == null) return false;
        var input = state.request().inputs().getFirst();
        if (BookLifecycleItems.sourceTokenMatches(participant.source(), state.request().operationId(),
                input.beforeImage())) return equipmentBeforeMarked(participant.equipment(), state);
        if (!equipmentBeforeMarked(participant.equipment(), state)
                || !BookLifecycleItems.markedBeforeMatches(participant.source(), state.request().operationId(),
                BookLifecycleItems.SOURCE, input.beforeImage())) return false;
        ItemStack token = BookLifecycleItems.sourceToken(state.request().operationId(), input.beforeImage());
        participant.player().getInventory().setItem(input.slot().slot(), token);
        return BookLifecycleItems.sourceTokenMatches(slot(participant.player(), input.slot().slot()),
                state.request().operationId(), input.beforeImage());
    }

    private boolean mutateEquipment(BookLifecycleTransaction.State state) {
        Participant participant = participant(state);
        if (participant == null) return false;
        var equipment = state.request().equipment().orElseThrow();
        var input = state.request().inputs().getFirst();
        if (!BookLifecycleItems.sourceTokenMatches(participant.source(), state.request().operationId(),
                input.beforeImage())) return false;
        if (BookLifecycleItems.exact(participant.equipment(), equipment.afterImage())) return true;
        if (!equipmentBeforeMarked(participant.equipment(), state)) return false;
        participant.player().getInventory().setItem(equipment.slot().slot(),
                BookLifecycleItems.deserialize(equipment.afterImage()));
        return BookLifecycleItems.exact(slot(participant.player(), equipment.slot().slot()), equipment.afterImage());
    }

    private boolean physicalAfterPresent(BookLifecycleTransaction.State state) {
        Participant participant = participant(state);
        if (participant == null) return false;
        var input = state.request().inputs().getFirst();
        return BookLifecycleItems.sourceTokenMatches(participant.source(), state.request().operationId(),
                input.beforeImage()) && BookLifecycleItems.exact(participant.equipment(),
                state.request().equipment().orElseThrow().afterImage());
    }

    private boolean acknowledgePhysical(BookLifecycleTransaction.State state) {
        Player player;
        try { player = actor(state.request().actorId()); }
        catch (RuntimeException offline) { return false; }
        var equipment = state.request().equipment().orElseThrow();
        var input = state.request().inputs().getFirst();
        ItemStack currentEquipment = slot(player, equipment.slot().slot());
        ItemStack currentSource = slot(player, input.slot().slot());
        boolean sourceToken = BookLifecycleItems.sourceTokenMatches(currentSource,
                state.request().operationId(), input.beforeImage());
        boolean sourceClean = currentSource == null;
        if (!sourceToken && !sourceClean) return false;
        if (!BookLifecycleItems.finalizedEquipmentMatches(currentEquipment, equipment)) return false;
        if (sourceToken) player.getInventory().setItem(input.slot().slot(), null);
        if (BookLifecycleItems.markerMatches(currentEquipment, state.request().operationId(),
                BookLifecycleItems.EQUIPMENT, null)) {
            ItemStack cleaned = currentEquipment.clone();
            BookLifecycleItems.clearCustody(cleaned);
            player.getInventory().setItem(equipment.slot().slot(), cleaned);
        }
        return slot(player, input.slot().slot()) == null
                && BookLifecycleItems.finalizedEquipmentMatches(slot(player, equipment.slot().slot()), equipment)
                && !BookLifecycleItems.hasCustodyMarker(slot(player, equipment.slot().slot()));
    }

    private CompletionStage<Boolean> finalizedAuthority(BookLifecycleTransaction.State state) {
        return tasks.database(() -> {
            var equipment = state.request().equipment().orElseThrow();
            var change = state.request().enchantmentChange().orElseThrow();
            var attachment = lifecycle.attachment(equipment.equipmentId(), change.enchantmentKey());
            if (attachment.isEmpty()) return false;
            var expectedNode = new BookLifecycleRequest.LineageNode(
                    BookLifecycleRequest.LineageKind.ATTACHMENT, equipment.equipmentId(),
                    change.enchantmentKey());
            BigDecimal expectedValue = state.request().lineageDecision().destinations().stream()
                    .filter(entry -> entry.node().equals(expectedNode)).map(BookLifecycleRequest.ValueEntry::value)
                    .findFirst().orElseThrow();
            var durable = attachment.orElseThrow();
            return durable.operationId().equals(state.request().operationId())
                    && durable.equipmentRevision() == equipment.resultingRevision()
                    && durable.sourceValue().compareTo(expectedValue) == 0
                    && lifecycle.equipmentRevision(equipment.equipmentId())
                    .filter(revision -> revision == equipment.resultingRevision()).isPresent();
        });
    }

    private boolean restoreBefore(BookLifecycleTransaction.State state) {
        Participant participant = participant(state);
        if (participant == null) return false;
        var equipment = state.request().equipment().orElseThrow();
        var input = state.request().inputs().getFirst();
        boolean equipmentRecognized = BookLifecycleItems.exact(participant.equipment(), equipment.beforeImage())
                || equipmentBeforeMarked(participant.equipment(), state)
                || BookLifecycleItems.exact(participant.equipment(), equipment.afterImage());
        boolean sourceRecognized = BookLifecycleItems.exact(participant.source(), input.beforeImage())
                || BookLifecycleItems.markedBeforeMatches(participant.source(), state.request().operationId(),
                BookLifecycleItems.SOURCE, input.beforeImage())
                || BookLifecycleItems.sourceTokenMatches(participant.source(), state.request().operationId(),
                input.beforeImage());
        if (!equipmentRecognized || !sourceRecognized) return false;
        participant.player().getInventory().setItem(equipment.slot().slot(),
                BookLifecycleItems.deserialize(equipment.beforeImage()));
        participant.player().getInventory().setItem(input.slot().slot(),
                BookLifecycleItems.deserialize(input.beforeImage()));
        return participantsUnmarked(state);
    }

    private boolean participantsUnmarked(BookLifecycleTransaction.State state) {
        Participant participant = participant(state);
        if (participant == null) return false;
        var equipment = state.request().equipment().orElseThrow();
        var input = state.request().inputs().getFirst();
        return BookLifecycleItems.exact(participant.equipment(), equipment.beforeImage())
                && BookLifecycleItems.exact(participant.source(), input.beforeImage())
                && !BookLifecycleItems.hasCustodyMarker(participant.equipment())
                && !BookLifecycleItems.hasCustodyMarker(participant.source());
    }

    private boolean participantsHaveNoOperationMarkers(BookLifecycleTransaction.State state) {
        try {
            Player player = actor(state.request().actorId());
            var equipment = state.request().equipment().orElseThrow();
            var input = state.request().inputs().getFirst();
            ItemStack equipmentItem = slot(player, equipment.slot().slot());
            ItemStack sourceItem = slot(player, input.slot().slot());
            return !BookLifecycleItems.markerMatches(equipmentItem, state.request().operationId(),
                    BookLifecycleItems.EQUIPMENT, null)
                    && !BookLifecycleItems.markerMatches(sourceItem, state.request().operationId(),
                    BookLifecycleItems.SOURCE, null)
                    && !BookLifecycleItems.sourceTokenMatches(sourceItem, state.request().operationId(),
                    input.beforeImage());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean equipmentBeforeMarked(ItemStack item, BookLifecycleTransaction.State state) {
        var equipment = state.request().equipment().orElseThrow();
        return BookLifecycleItems.markedBeforeMatches(item, state.request().operationId(),
                BookLifecycleItems.EQUIPMENT, equipment.beforeImage());
    }

    private Participant participant(BookLifecycleTransaction.State state) {
        try {
            Player player = actor(state.request().actorId());
            var equipment = state.request().equipment().orElseThrow();
            var input = state.request().inputs().getFirst();
            if (!INVENTORY.equals(equipment.slot().inventoryId())
                    || !INVENTORY.equals(input.slot().inventoryId())) return null;
            return new Participant(player, slot(player, equipment.slot().slot()),
                    slot(player, input.slot().slot()));
        } catch (RuntimeException missing) { return null; }
    }

    private int visibleCopies(UUID identity) {
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ItemStack> matches = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            collect(player.getInventory(), identity, seen, matches, 0);
            collect(player.getEnderChest(), identity, seen, matches, 0);
            collect(player.getOpenInventory().getTopInventory(), identity, seen, matches, 0);
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                collectItem(item.getItemStack(), identity, seen, matches, 0);
            }
        }
        return matches.stream().mapToInt(item -> Math.max(1, item.getAmount())).sum();
    }

    private void collect(Inventory inventory, UUID identity, Set<ItemStack> seen,
                         List<ItemStack> matches, int depth) {
        if (inventory == null) return;
        for (ItemStack item : inventory.getContents()) collectItem(item, identity, seen, matches, depth);
    }

    private void collectItem(ItemStack item, UUID identity, Set<ItemStack> seen,
                             List<ItemStack> matches, int depth) {
        if (item == null || !seen.add(item)) return;
        TrackedItemData.Identity read = TrackedItemData.readRaw(item);
        if (read != null && identity.equals(read.uuid())) matches.add(item);
        int maximumDepth = Math.max(0, plugin.getConfigManager().getConfig()
                .getInt("duplicate-protection.container-recursion-depth", 3));
        if (depth >= maximumDepth || !(item.getItemMeta() instanceof BlockStateMeta blockMeta)
                || !(blockMeta.getBlockState() instanceof TileStateInventoryHolder holder)) return;
        collect(holder.getInventory(), identity, seen, matches, depth + 1);
    }

    private Player actor(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) throw new IllegalStateException("Lifecycle actor is offline");
        return player;
    }

    private static ItemStack slot(Player player, int slot) {
        if (slot < 0 || slot >= player.getInventory().getSize()) {
            throw new IllegalArgumentException("Inventory slot is out of bounds");
        }
        ItemStack item = player.getInventory().getItem(slot);
        return item == null || item.getType() == Material.AIR ? null : item;
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return left != null && right != null && left.getAmount() == right.getAmount()
                && left.isSimilar(right)
                && java.util.Arrays.equals(left.serializeAsBytes(), right.serializeAsBytes());
    }

    private static BookLifecycleRequest.InventorySlot inventorySlot(UUID actor, int slot) {
        return new BookLifecycleRequest.InventorySlot(actor, INVENTORY, slot);
    }

    private static BookLifecycleRequest.ItemImage image(ItemStack item) {
        return new BookLifecycleRequest.ItemImage(item.serializeAsBytes());
    }

    private Result result(BookLifecycleTransaction.State state, Outcome outcome, String reason) {
        var equipment = state.request().equipment().orElseThrow();
        return new Result(state.request().operationId(), outcome, reason, equipment.resultingRevision(),
                state.request().enchantmentChange().orElseThrow().afterLevel());
    }

    private static Result rejected(UUID id, String reason) {
        return new Result(id, Outcome.REJECTED, reason, -1, 0);
    }

    private static CompletionStage<Result> completed(UUID id, Outcome outcome, String reason,
                                                     long revision, int level) {
        return CompletableFuture.completedFuture(new Result(id, outcome, reason, revision, level));
    }

    private static Result recoveryRequired(UUID id, Throwable failure) {
        return new Result(id, Outcome.RECOVERY_REQUIRED,
                "Lifecycle outcome requires operation-scoped recovery: " + safeReason(failure), -1, 0);
    }

    private static String safeReason(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private void finish(CompletableFuture<Result> future, Result result) {
        future.complete(result);
        inFlight.remove(result.operationId(), future);
    }

    @Override public boolean available() { return !closed && active.getAsBoolean(); }

    private static void requireServerThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Server thread required");
    }

    @Override public void close() {
        closed = true;
        inFlight.values().forEach(future -> future.completeExceptionally(
                new IllegalStateException("Archive application stopped; recover by operation ID")));
        inFlight.clear();
    }

    private record Initial(Request request, UUID equipmentId, String profileId, UUID bookId,
                           String enchantmentKey, int level, ItemStack equipment, ItemStack source) { }
    private record DurableContext(BookLedger.Receipt receipt, long revision, Decision decision) { }
    private record Participant(Player player, ItemStack equipment, ItemStack source) { }
}
