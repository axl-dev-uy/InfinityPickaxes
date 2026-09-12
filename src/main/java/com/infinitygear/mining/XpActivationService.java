package com.infinitygear.mining;

import com.infinitygear.data.GearData;
import com.infinitygear.gear.GearProgressionMode;
import com.infinitygear.integration.IntegrationTasks;
import com.infinitygear.persistence.MariaMiningXpLedger;
import com.infinitygear.persistence.MariaMiningXpLedger.AdministrativeAction;
import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.core.pickaxe.PickaxeData;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Explicit adoption and receipt-only lifecycle recovery. It is not a mining completion receiver. */
public final class XpActivationService implements Listener {
    public enum State { ADOPTED, PROJECTED, CURRENT, MISSING, DUPLICATED, QUARANTINED, STALE, CONFLICT, UNADOPTED }
    public record Report(UUID pickaxeId, State state, String detail) { }
    private record AccountState(MariaMiningXpLedger.Adoption adoption, MiningXpPlan.Account account) { }

    private final InfinityPickaxes plugin;
    private final IntegrationTasks tasks;
    private final MariaMiningXpLedger ledger;
    private final MiningXpItemProjection projection = new MiningXpItemProjection();
    private final XpItemCustody custody;

    public XpActivationService(InfinityPickaxes plugin, IntegrationTasks tasks, MariaMiningXpLedger ledger) {
        this.plugin = Objects.requireNonNull(plugin);
        this.tasks = Objects.requireNonNull(tasks);
        this.ledger = Objects.requireNonNull(ledger);
        this.custody = new XpItemCustody(plugin);
    }

    /** The guard marker is written before any asynchronous database work. */
    public CompletableFuture<Report> adopt(CommandSender actor, Player owner) {
        if (!Bukkit.isPrimaryThread()) return CompletableFuture.failedFuture(new IllegalStateException("Server thread required"));
        ItemStack held = owner.getInventory().getItemInMainHand();
        var gear = plugin.getGearManager().inspect(held, true).orElse(null);
        var profile = gear == null ? null : plugin.getGearProfiles().find(gear.profileId()).orElse(null);
        if (gear == null || profile == null || !profile.enabled() || profile.progressionMode() != GearProgressionMode.EXPERIENCE)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Hold one enabled EXPERIENCE gear item"));
        XpItemCustody.Located located = custody.locate(gear.uuid());
        if (!located.uniqueMainHand(owner, gear.uuid()))
            return issue(gear.uuid(), located.copies() == 0 ? State.MISSING : State.DUPLICATED,
                    "Adoption requires exactly one visible copy held in the main hand", located.location());
        if (located.quarantined() || plugin.getDuplicateService().isRestricted(gear.uuid()))
            return issue(gear.uuid(), State.QUARANTINED, "Restricted or quarantined item cannot be adopted", located.location());

        UUID adoptionId = UUID.randomUUID();
        MiningXpItemProjection.AdoptionFence fence;
        try { fence = projection.beginAdoption(held, adoptionId); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        String actorName = actor.getName();
        return tasks.database(() -> {
            try { return ledger.adopt(adoptionId, owner.getUniqueId(), fence.pickaxeId(), fence.profileId(), fence.baseline(), actorName); }
            catch (Exception failure) {
                var recovered = ledger.findAdoption(fence.pickaxeId());
                if (recovered.isPresent() && recovered.get().adoptionId().equals(adoptionId)) return recovered.get();
                throw failure;
            }
        }).thenCompose(adoption -> tasks.server(() -> {
            XpItemCustody.Located current = custody.locate(fence.pickaxeId());
            if (!current.uniqueMainHand(owner, fence.pickaxeId()) || current.quarantined())
                return new Report(fence.pickaxeId(), State.CONFLICT, "Custody changed before first projection");
            ItemStack currentHeld = owner.getInventory().getItemInMainHand();
            var applied = projection.completeAdoption(currentHeld, fence, adoption.account());
            return new Report(fence.pickaxeId(), applied == MiningXpItemProjection.Result.APPLIED ? State.ADOPTED : State.CONFLICT,
                    applied == MiningXpItemProjection.Result.APPLIED ? "MariaDB authority established at revision 0" : "First projection conflicted");
        })).thenCompose(report -> report.state() == State.ADOPTED ? clear(report)
                : issue(report.pickaxeId(), report.state(), report.detail(), "adoption"))
                .exceptionallyCompose(failure -> issue(fence.pickaxeId(), State.CONFLICT,
                        "Durable adoption did not complete; the fail-closed marker was retained", "adoption"));
    }

    public CompletableFuture<Report> recover(UUID pickaxeId) {
        return tasks.server(() -> custody.locate(pickaxeId)).thenCompose(located -> recoverLocated(pickaxeId, located));
    }

    private CompletableFuture<Report> recoverLocated(UUID id, XpItemCustody.Located located) {
        if (located.copies() == 0) return issue(id, State.MISSING, "No visible physical item", located.location());
        if (located.copies() != 1) return issue(id, State.DUPLICATED, "Multiple visible physical copies", located.location());
        if (located.quarantined() || plugin.getDuplicateService().isRestricted(id))
            return issue(id, State.QUARANTINED, "Physical item is quarantined or restricted", located.location());
        return tasks.database(() -> new AccountState(ledger.findAdoption(id).orElse(null), ledger.findAccount(id).orElse(null)))
                .thenCompose(state -> {
                    if (state.adoption() == null || state.account() == null)
                        return issue(id, State.UNADOPTED, "No explicit ledger adoption exists", located.location());
                    UUID pending = MiningXpItemProjection.adoptionId(located.item());
                    if (pending != null) {
                        if (!pending.equals(state.adoption().adoptionId()) || state.account().revision() != 0)
                            return issue(id, State.CONFLICT, "Pending adoption marker conflicts with durable adoption", located.location());
                        var fence = new MiningXpItemProjection.AdoptionFence(pending, id, state.account().profileId(), state.account().progress());
                        return tasks.server(() -> projection.completeAdoption(located.item(), fence, state.account()))
                                .thenCompose(result -> result == MiningXpItemProjection.Result.APPLIED
                                        ? clear(new Report(id, State.ADOPTED, "Recovered durable adoption at revision 0"))
                                        : issue(id, State.CONFLICT, "Pending adoption metadata no longer matches", located.location()));
                    }
                    Long revision = MiningXpItemProjection.revision(located.item());
                    if (revision == null) return issue(id, State.CONFLICT, "Managed revision is missing or malformed", located.location());
                    if (revision > state.account().revision())
                        return issue(id, State.STALE, "Item revision is ahead of MariaDB authority", located.location());
                    if (revision == state.account().revision()) {
                        if (!MiningXpItemProjection.matchesAccount(located.item(), state.account()))
                            return issue(id, State.CONFLICT, "Item progress conflicts with its authoritative revision", located.location());
                        if (revision == 0) return clear(new Report(id, State.CURRENT, "Projection is current at revision 0"));
                        return tasks.database(() -> ledger.findProjection(id, revision)).thenCompose(last -> {
                            if (last.isEmpty()) return issue(id, State.CONFLICT,
                                    "Authoritative revision has no unique committed receipt", located.location());
                            return present(last.get()).thenCompose(ignored -> clear(new Report(id, State.CURRENT,
                                    "Projection is current at revision " + revision)));
                        });
                    }
                    return projectNext(id, revision + 1);
                });
    }

    private CompletableFuture<Report> projectNext(UUID id, long revision) {
        return tasks.database(() -> ledger.findProjection(id, revision)).thenCompose(found -> {
            if (found.isEmpty()) return issue(id, State.CONFLICT, "Committed revision " + revision + " has no unique receipt", "recovery");
            XpProjectionReceipt receipt = found.get();
            return tasks.server(() -> {
                XpItemCustody.Located current = custody.locate(id);
                if (current.copies() != 1 || current.quarantined()) return new Report(id,
                        current.copies() == 0 ? State.MISSING : current.copies() > 1 ? State.DUPLICATED : State.QUARANTINED,
                        "Custody changed during projection");
                var result = projection.apply(current.item(), receipt);
                if (result == MiningXpItemProjection.Result.CONFLICT) return new Report(id, State.CONFLICT,
                        "Absolute revision " + revision + " conflicts with item metadata");
                plugin.getGearManager().refreshPresentation(current.item());
                return new Report(id, State.PROJECTED, current.location());
            }).thenCompose(result -> {
                if (result.state() != State.PROJECTED) return issue(id, result.state(), result.detail(), "projection");
                return present(receipt).thenCompose(ignored -> recover(id));
            });
        });
    }

    public CompletableFuture<Report> addXp(CommandSender actor, Player owner, double amount) {
        return administer(actor, owner, AdministrativeAction.ADD_XP, amount);
    }
    public CompletableFuture<Report> setLevel(CommandSender actor, Player owner, int level) {
        return administer(actor, owner, AdministrativeAction.SET_LEVEL, level);
    }

    private CompletableFuture<Report> administer(CommandSender actor, Player owner, AdministrativeAction action, double value) {
        if (!Bukkit.isPrimaryThread()) return CompletableFuture.failedFuture(new IllegalStateException("Server thread required"));
        if (!Double.isFinite(value) || value < 0 || (action == AdministrativeAction.ADD_XP && value == 0))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Administrative value must be positive and finite"));
        ItemStack held = owner.getInventory().getItemInMainHand();
        UUID id = XpItemCustody.itemId(held);
        XpItemCustody.Located located = id == null ? new XpItemCustody.Located(null, null, "main-hand", 0, false) : custody.locate(id);
        if (id == null || !located.uniqueMainHand(owner, id) || located.quarantined()
                || plugin.getDuplicateService().isRestricted(id)
                || MiningXpItemProjection.revision(held) == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Target must uniquely hold one adopted, non-quarantined item"));
        var gear = plugin.getGearManager().inspect(held, false).orElseThrow();
        var profile = plugin.getGearProfiles().find(gear.profileId()).orElseThrow();
        if (profile.progressionMode() != GearProgressionMode.EXPERIENCE)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Profile does not use EXPERIENCE progression"));
        List<Double> requirements = new ArrayList<>(profile.maximumLevel());
        for (int i = 0; i < profile.maximumLevel(); i++) requirements.add(plugin.getLevelManager().getRequiredXp(i));
        UUID operationId = UUID.randomUUID();
        UUID actorId = actor instanceof Player player ? player.getUniqueId()
                : UUID.nameUUIDFromBytes(("console:" + actor.getName()).getBytes(StandardCharsets.UTF_8));
        return tasks.database(() -> {
                    var adoption = ledger.findAdoption(id).orElseThrow(() -> new IllegalStateException("XP account has not been explicitly adopted"));
                    return ledger.findAccount(id).filter(adoption.account()::equals)
                            .orElseThrow(() -> new IllegalStateException("Adoption/account state conflicts"));
                }).thenCompose(account -> tasks.server(() -> {
                    XpItemCustody.Located current = custody.locate(id);
                    ItemStack currentHeld = owner.getInventory().getItemInMainHand();
                    if (!current.uniqueMainHand(owner, id) || current.quarantined()
                            || plugin.getDuplicateService().isRestricted(id)
                            || !MiningXpItemProjection.matchesAccount(currentHeld, account))
                        throw new IllegalStateException("Item custody or projection changed before administrative commit");
                    return account;
                })).thenCompose(account -> tasks.database(() -> ledger.administer(operationId, actorId, actor.getName(), id,
                        account.profileId(), account.revision(), account.progress(), action, value, requirements)))
                .thenCompose(receipt -> tasks.server(() -> {
                    XpItemCustody.Located current = custody.locate(id);
                    if (!current.uniqueMainHand(owner, id) || current.quarantined()
                            || plugin.getDuplicateService().isRestricted(id))
                        return new Report(id, State.CONFLICT, "Custody changed after administrative commit");
                    ItemStack currentHeld = owner.getInventory().getItemInMainHand();
                    var result = projection.apply(currentHeld, receipt.projection());
                    if (result == MiningXpItemProjection.Result.CONFLICT)
                        return new Report(id, State.CONFLICT, "Administrative receipt conflicts with item metadata");
                    plugin.getGearManager().refreshPresentation(currentHeld);
                    return new Report(id, State.PROJECTED, "Administrative " + action + " committed at revision " + receipt.account().revision());
                }).thenCompose(report -> report.state() == State.PROJECTED
                        ? present(receipt.projection()).thenCompose(ignored -> clear(report))
                        : issue(id, report.state(), report.detail(), "administration")))
                .exceptionallyCompose(failure -> tasks.database(() -> ledger.findAdministrativeReceipt(operationId))
                        .thenCompose(saved -> saved.isPresent() ? recover(id) : issue(id, State.CONFLICT,
                                "Administrative operation has no committed receipt and was not replayed", "administration")));
    }

    /** Policy: only upward transitions, only while a unique copy has an online owner, at most once per revision. */
    private CompletableFuture<Void> present(XpProjectionReceipt receipt) {
        if (receipt.account().progress().level() <= receipt.before().level()) return CompletableFuture.completedFuture(null);
        return tasks.server(() -> custody.locate(receipt.pickaxeId())).thenCompose(ready -> {
            if (ready.copies() != 1 || ready.owner() == null || !ready.owner().isOnline())
                return CompletableFuture.completedFuture(null);
            UUID ownerId = ready.owner().getUniqueId();
            return tasks.database(() -> ledger.claimPresentation(receipt.pickaxeId(), receipt.account().revision(), "LEVEL_UP"))
                    .thenCompose(claimed -> claimed ? tasks.server(() -> {
                        XpItemCustody.Located current = custody.locate(receipt.pickaxeId());
                        if (current.copies() == 1 && current.owner() != null && current.owner().isOnline()
                                && ownerId.equals(current.owner().getUniqueId()))
                            plugin.getLevelManager().presentCommittedLevelUp(current.owner(), current.item(),
                                    receipt.before().level(), receipt.account().progress().level());
                        return null;
                    }) : CompletableFuture.completedFuture(null));
        });
    }

    private CompletableFuture<Report> issue(UUID id, State state, String detail, String location) {
        return tasks.database(() -> { ledger.recordReconciliation(id, state.name(), detail, location == null ? "unknown" : location); return null; })
                .handle((ignored, failure) -> new Report(id, state, detail));
    }
    private CompletableFuture<Report> clear(Report report) {
        return tasks.database(() -> { ledger.clearReconciliation(report.pickaxeId()); return null; }).thenApply(ignored -> report);
    }
    public CompletableFuture<List<MariaMiningXpLedger.Reconciliation>> issues() {
        return tasks.database(() -> ledger.reconciliations(100));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { schedule(event.getPlayer().getInventory(), event.getPlayer().getEnderChest()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) { schedule(event.getInventory()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) schedule(player.getInventory(), event.getView().getTopInventory());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) schedule(player.getInventory(), event.getView().getTopInventory());
    }
    public void recoverLoadedOnStart() {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(player ->
                scan(player.getInventory(), player.getEnderChest())));
    }
    private void schedule(Inventory... inventories) { Bukkit.getScheduler().runTask(plugin, () -> scan(inventories)); }
    private void scan(Inventory... inventories) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Inventory inventory : inventories) if (inventory != null) for (ItemStack item : inventory.getContents()) {
            UUID id = XpItemCustody.itemId(item); if (id != null && MiningXpItemProjection.isManaged(item)) ids.add(id);
        }
        ids.forEach(id -> recover(id).exceptionally(failure -> {
            plugin.getLogger().warning("XP projection recovery failed safely for " + id + " (" + failure.getClass().getSimpleName() + ")");
            return null;
        }));
    }

}
