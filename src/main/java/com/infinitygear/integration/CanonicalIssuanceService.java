package com.infinitygear.integration;

import com.infinitygear.api.v1.*;
import com.infinitygear.data.*;
import com.infinitygear.enchant.CanonicalBookFactory;
import com.infinitypickaxes.InfinityPickaxes;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;

public final class CanonicalIssuanceService implements BookIssuanceService {
    private final InfinityPickaxes plugin;
    private final BookLedger ledger;
    private final IntegrationTasks tasks;
    private final IssuanceRecovery recovery;
    public CanonicalIssuanceService(InfinityPickaxes plugin, BookLedger ledger, BookArtifacts artifacts, IntegrationTasks tasks) {
        this.plugin = plugin; this.ledger = ledger; this.tasks = tasks;
        this.recovery = new IssuanceRecovery(ledger, artifacts, tasks, new IssuanceRecovery.Materializer() {
            public void validateNew(BookLedger.Issue request) {
                var socket = plugin.getEnchantManager().getSocketByKey(request.enchantmentKey());
                var enchant = plugin.getEnchantManager().getEnchantment(request.enchantmentKey());
                var eco = enchant == null ? null : plugin.getEnchantManager().getEcoHook().findEcoEnchant(enchant);
                int maximum = enchant == null ? 0 : eco == null ? enchant.getMaxLevel() : eco.getMaximumLevel();
                if (socket == null || !socket.isEnabled() || request.level() > maximum)
                    throw new IllegalArgumentException("Disabled or invalid native enchantment level");
            }
            public byte[] create(BookLedger.Receipt receipt) {
                var enchant = plugin.getEnchantManager().getEnchantment(receipt.issue().enchantmentKey());
                if (enchant == null) throw new IllegalStateException("Native integration unavailable for unfinished artifact; retry after recovery");
                var item = new CanonicalBookFactory().create(enchant, receipt.issue().level());
                ArchiveBookIdentity.stamp(item, receipt);
                TrackedItemData.stamp(item, TrackedKind.ARCHIVE_BOOK, receipt.issue().enchantmentKey(), receipt.bookId());
                return stableNativeBytes(item);
            }
        });
    }

    /**
     * Paper may normalize a freshly constructed item's component encoding on its
     * first native deserialize/serialize cycle. Persist only a fixed-point image
     * so the bytes returned for physical delivery are exactly the bytes later
     * presented to lifecycle validation.
     */
    private static byte[] stableNativeBytes(ItemStack item) {
        byte[] current = item.serializeAsBytes();
        for (int attempt = 0; attempt < 4; attempt++) {
            byte[] normalized = ItemStack.deserializeBytes(current).serializeAsBytes();
            if (Arrays.equals(current, normalized)) return current;
            current = normalized;
        }
        throw new IllegalStateException("Native Archive-book serialization did not stabilize");
    }
    @Override public CompletionStage<IssuedBook> issue(BookLedger.Issue request) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Server thread required");
        if (!plugin.getDuplicateService().authorityReady()) return CompletableFuture.failedFuture(
                new IllegalStateException("Tracked-item quarantine authority is unavailable"));
        var authority = plugin.getServer().getServicesManager().load(ProvenanceAuthority.class);
        return recovery.issue(request, authority);
    }
    @Override public CompletionStage<Boolean> validate(ItemStack item) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Server thread required");
        ItemStack snapshot = item == null ? null : item.clone();
        if (!ArchiveBookIdentity.marked(snapshot)) return CompletableFuture.completedFuture(false);
        final UUID id;
        try { id = UUID.fromString(snapshot.getItemMeta().getPersistentDataContainer().get(ArchiveBookIdentity.ID, PersistentDataType.STRING)); }
        catch (RuntimeException invalid) { return CompletableFuture.completedFuture(false); }
        return tasks.database(() -> ledger.find(id)).thenCompose(receipt -> tasks.server(() -> receipt.isPresent()
                && ArchiveBookIdentity.matches(snapshot, receipt.get())
                && plugin.getDuplicateService().isUsable(snapshot)));
    }
}
