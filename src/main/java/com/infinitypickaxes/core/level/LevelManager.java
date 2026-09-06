package com.infinitypickaxes.core.level;

import com.infinitypickaxes.InfinityPickaxes;
import com.infinitypickaxes.api.events.PickaxeLevelUpEvent;
import com.infinitypickaxes.core.pickaxe.InfinityPickaxe;
import com.infinitypickaxes.utils.SoundUtil;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class LevelManager {

    private final InfinityPickaxes plugin;
    private int maxLevel = 100;
    private double baseExp = 100.0;
    private double exponent = 1.35;
    private double linear = 50.0;

    private boolean soundEnabled = true;
    private Sound levelupSound = Sound.ENTITY_PLAYER_LEVELUP;
    private float soundVolume = 1.0f;
    private float soundPitch = 1.2f;

    private boolean particlesEnabled = true;
    private Particle levelupParticle = Particle.TOTEM_OF_UNDYING;
    private int particleCount = 30;

    public LevelManager(InfinityPickaxes plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        FileConfiguration config = plugin.getConfigManager().getConfig();
        this.maxLevel = config.getInt("settings.max-level", 100);
        this.baseExp = config.getDouble("leveling.formula.base-xp", 100.0);
        this.exponent = config.getDouble("leveling.formula.exponent", 1.35);
        this.linear = config.getDouble("leveling.formula.linear", 50.0);

        this.soundEnabled = config.getBoolean("leveling.levelup-sound.enabled", true);
        this.levelupSound = SoundUtil.resolve(config.getString(
                "leveling.levelup-sound.sound", "ENTITY_PLAYER_LEVELUP"), Sound.ENTITY_PLAYER_LEVELUP);
        this.soundVolume = (float) config.getDouble("leveling.levelup-sound.volume", 1.0);
        this.soundPitch = (float) config.getDouble("leveling.levelup-sound.pitch", 1.2);

        this.particlesEnabled = config.getBoolean("leveling.levelup-particles.enabled", true);
        try {
            this.levelupParticle = Particle.valueOf(config.getString("leveling.levelup-particles.particle", "TOTEM_OF_UNDYING"));
        } catch (Exception e) {
            this.levelupParticle = Particle.TOTEM_OF_UNDYING;
        }
        this.particleCount = config.getInt("leveling.levelup-particles.count", 30);

    }

    /**
     * Calculates the required XP to advance from current level to next level (level + 1).
     */
    public double getRequiredXp(int currentLevel) {
        if (currentLevel >= maxLevel) {
            return 0.0;
        }
        int nextLevel = currentLevel + 1;
        return Math.round(baseExp * Math.pow(nextLevel, exponent) + (nextLevel * linear));
    }

    /**
     * Adds XP to a pickaxe, checks for level up(s), and triggers all visual/sound events.
     */
    public void addXp(InfinityPickaxe pickaxe, double xpToAdd, Player player) {
        if (pickaxe == null || xpToAdd <= 0) return;
        if (com.infinitygear.mining.MiningXpItemProjection.isManaged(pickaxe.getItemStack()))
            throw new IllegalStateException("Database-managed mining XP requires its durable participant");
        if (pickaxe.getLevel() >= maxLevel) {
            pickaxe.saveAndSync();
            return;
        }

        pickaxe.addXp(xpToAdd);

        boolean leveledUp = false;
        int oldLevel = pickaxe.getLevel();

        while (pickaxe.getLevel() < maxLevel) {
            double required = getRequiredXp(pickaxe.getLevel());
            if (pickaxe.getXp() >= required) {
                pickaxe.setXp(pickaxe.getXp() - required);
                pickaxe.setLevel(pickaxe.getLevel() + 1);
                leveledUp = true;
            } else {
                break;
            }
        }

        pickaxe.saveAndSync();

        if (leveledUp && player != null) {
            int newLevel = pickaxe.getLevel();
            // Call Custom Event
            PickaxeLevelUpEvent event = new PickaxeLevelUpEvent(player, pickaxe, oldLevel, newLevel);
            Bukkit.getPluginManager().callEvent(event);

            // Play Sound
            if (soundEnabled) {
                player.playSound(player.getLocation(), levelupSound, soundVolume, soundPitch);
            }

            // Play Particles
            if (particlesEnabled) {
                player.getWorld().spawnParticle(levelupParticle, player.getLocation().add(0, 1, 0), particleCount, 0.5, 0.5, 0.5, 0.1);
            }

            // Send Messages / Titles / Actionbars
            plugin.getMessageManager().sendLevelUp(player, pickaxe, oldLevel, newLevel);
        }
    }

    public int getMaxLevel() {
        return maxLevel;
    }
}
