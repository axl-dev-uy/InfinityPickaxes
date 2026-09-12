package com.infinitypickaxes.api;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.core.enchant.EnchantManager;
import com.infinitypickaxes.core.duplicate.PickaxeDuplicateService;
import com.infinitypickaxes.core.level.LevelManager;
import com.infinitypickaxes.core.pickaxe.InfinityPickaxe;
import com.infinitypickaxes.core.pickaxe.PickaxeData;
import com.infinitypickaxes.core.pickaxe.PickaxeManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * @deprecated This legacy adapter and its {@code InfinityGearService} return
 * type are in-plugin compatibility APIs. External integrations must use the
 * Bukkit-registered {@code com.infinitygear.api.v1} services instead.
 */
@Deprecated
public final class InfinityPickaxesAPI {

    private InfinityPickaxesAPI() {}

    public static boolean isInfinityPickaxe(ItemStack item) {
        return PickaxeData.isInfinityPickaxe(item);
    }

    public static InfinityPickaxe getPickaxe(ItemStack item) {
        return PickaxeData.fromItemStack(item);
    }

    public static InfinityPickaxe getHeldPickaxe(Player player) {
        return InfinityPickaxes.getInstance().getPickaxeManager().getHeldPickaxe(player);
    }

    public static ItemStack createPickaxe(int startingLevel) {
        return InfinityPickaxes.getInstance().getPickaxeManager().createPickaxe(startingLevel);
    }

    public static LevelManager getLevelManager() {
        return InfinityPickaxes.getInstance().getLevelManager();
    }

    public static EnchantManager getEnchantManager() {
        return InfinityPickaxes.getInstance().getEnchantManager();
    }

    public static PickaxeManager getPickaxeManager() {
        return InfinityPickaxes.getInstance().getPickaxeManager();
    }

    public static PickaxeDuplicateService getDuplicateService() {
        return InfinityPickaxes.getInstance().getDuplicateService();
    }

    public static com.infinitygear.api.InfinityGearService getGearService() {
        return InfinityPickaxes.getInstance().getGearService();
    }
}
