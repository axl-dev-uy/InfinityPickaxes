package com.infinitygear.integration;

import com.infinitygear.api.v1.*;
import com.infinitygear.data.*;
import com.infinitygear.enchant.CanonicalBookFactory;
import com.infinitypickaxes.InfinityPickaxes;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.UUID;
import java.util.concurrent.*;

public final class CanonicalIssuanceService implements BookIssuanceService {
    private final InfinityPickaxes plugin;
    private final BookLedger ledger;
    private final Executor executor;
    public CanonicalIssuanceService(InfinityPickaxes plugin, BookLedger ledger, Executor executor) {
        this.plugin = plugin; this.ledger = ledger; this.executor = executor;
    }
    @Override public CompletionStage<IssuedBook> issue(BookLedger.Issue request) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Server thread required");
        var authority = plugin.getServer().getServicesManager().load(ProvenanceAuthority.class);
        if (authority == null) return CompletableFuture.failedFuture(new IllegalStateException("Provenance authority unavailable"));
        var socket = plugin.getEnchantManager().getSocketByKey(request.enchantmentKey());
        var enchant = plugin.getEnchantManager().getEnchantment(request.enchantmentKey());
        var eco = enchant == null ? null : plugin.getEnchantManager().getEcoHook().findEcoEnchant(enchant);
        int maximum = enchant == null ? 0 : eco == null ? enchant.getMaxLevel() : eco.getMaximumLevel();
        if (enchant == null) return CompletableFuture.failedFuture(new IllegalStateException("Native enchantment unavailable; persisted issuance can be retried after integration recovery"));
        boolean eligible = socket != null && socket.isEnabled() && request.level() <= maximum;
        ItemStack template = new CanonicalBookFactory().create(enchant, request.level());
        return CompletableFuture.supplyAsync(() -> {
            try {
                var existing = ledger.find(request.operationId(), request.rewardId());
                if (existing.isPresent()) {
                    if (!existing.get().issue().equals(request)) throw new IllegalArgumentException("Operation/reward payload mismatch");
                    return existing.get();
                }
                if (!eligible) throw new IllegalArgumentException("Disabled or invalid native enchantment level");
                if (!authority.validate(request)) throw new IllegalArgumentException("Provenance rejected");
                return ledger.issue(request);
            } catch (Exception failure) { throw new CompletionException(failure); }
        }, executor).thenCompose(receipt -> onServer(() -> {
            if (receipt.consumed()) throw new IllegalStateException("Issued source already consumed");
            ArchiveBookIdentity.stamp(template, receipt);
            TrackedItemData.stamp(template, TrackedKind.ARCHIVE_BOOK, request.enchantmentKey(), receipt.bookId());
            return new IssuedBook(receipt, template.serializeAsBytes());
        }));
    }
    @Override public CompletionStage<Boolean> validate(ItemStack item) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Server thread required");
        ItemStack snapshot = item == null ? null : item.clone();
        if (!ArchiveBookIdentity.marked(snapshot)) return CompletableFuture.completedFuture(false);
        final UUID id;
        try { id = UUID.fromString(snapshot.getItemMeta().getPersistentDataContainer().get(ArchiveBookIdentity.ID, PersistentDataType.STRING)); }
        catch (RuntimeException invalid) { return CompletableFuture.completedFuture(false); }
        return CompletableFuture.supplyAsync(() -> {
            try { return ledger.find(id); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, executor).thenCompose(receipt -> onServer(() -> receipt.isPresent()
                && ArchiveBookIdentity.matches(snapshot, receipt.get())
                && plugin.getDuplicateService().isUsable(snapshot)));
    }
    private <T> CompletionStage<T> onServer(Callable<T> task) {
        var result = new CompletableFuture<T>();
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try { result.complete(task.call()); } catch (Exception failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException disabled) { result.completeExceptionally(disabled); }
        return result;
    }
}
