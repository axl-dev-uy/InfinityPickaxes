package com.infinitygear.api;

import com.infinitygear.data.TrackedKind;
import com.infinitygear.gear.GearProfile;
import com.infinitygear.enchant.ResolvedEnchantmentPolicy;
import com.infinitypickaxes.core.enchant.EnchantSocket;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Optional;

/**
 * Legacy in-plugin compatibility service. Its signatures intentionally retain
 * implementation types and it is not part of the published external consumer
 * contract. External integrations must compile only against
 * {@code com.infinitygear.api.v1}, in particular
 * {@link com.infinitygear.api.v1.ArchiveIntegrationService}.
 * Every mutation method requires Bukkit's primary thread.
 */
@Deprecated(since = "2.0.0", forRemoval = false)
public interface InfinityGearService {
    boolean isGear(ItemStack item);
    Optional<GearSnapshot> inspect(ItemStack item);
    Optional<GearProfile> resolveProfile(ItemStack item);
    OperationResult<ItemStack> createGear(String profileId, int startingLevel);
    OperationResult<Integer> validateEnchantmentApplication(ItemStack gear, ItemStack book, String enchantmentKey);
    OperationResult<Integer> applyEnchantment(ItemStack gear, ItemStack book, String enchantmentKey);
    int usedSockets(ItemStack gear);
    int socketLimit(ItemStack gear);
    boolean quarantined(ItemStack item);
    OperationResult<ItemStack> createTrackedArtifact(TrackedKind kind, String type);
    Collection<EnchantSocket> eligibleEnchantments(String profileId);

    /** Effective global + profile policy at a gear level; default preserves third-party implementor compatibility. */
    default Optional<ResolvedEnchantmentPolicy> resolveEnchantmentPolicy(
            String profileId, String enchantmentKey, int gearLevel) {
        return Optional.empty();
    }
}
