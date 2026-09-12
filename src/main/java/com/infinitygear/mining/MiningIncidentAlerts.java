package com.infinitygear.mining;

import com.infinitygear.persistence.MariaMiningJournal.Incident;
import com.infinitygear.persistence.MariaMiningJournal.AttemptState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.function.*;

/** Server-thread, constant-memory, best-effort alerts. No timers, player compensation or replay. */
public final class MiningIncidentAlerts {
    public record Settings(boolean enabled, String permission, long intervalMillis, String message) {
        public Settings {
            if (permission == null || permission.isBlank() || intervalMillis < 1000 || message == null)
                throw new IllegalArgumentException("Invalid mining alert settings");
        }
        public static Settings read(ConfigurationSection config) {
            String base = "mining-incidents.alerts.";
            return new Settings(config.getBoolean(base + "enabled", false),
                    config.getString(base + "permission", "noxwardarchives.alerts.mining"),
                    Math.max(1, Math.min(86400, config.getLong(base + "interval-seconds", 60))) * 1000,
                    config.getString(base + "message", "[Mining] {count} voided operation(s). Latest: {operation}. Inspect the mining incident ledger."));
        }
    }
    private final Supplier<Settings> settings;
    private final Supplier<? extends Collection<? extends Player>> online;
    private final LongSupplier clock;
    private long nextAt = Long.MIN_VALUE, count;
    public MiningIncidentAlerts(Supplier<Settings> settings, Supplier<? extends Collection<? extends Player>> online, LongSupplier clock) {
        this.settings = Objects.requireNonNull(settings); this.online = Objects.requireNonNull(online); this.clock = Objects.requireNonNull(clock);
    }
    /** Called only for a durably stored terminal incident, after worker-side persistence and alert claim. */
    public void persisted(Incident incident) {
        if (!Set.of(AttemptState.FAILED_UNCONFIRMED, AttemptState.AMBIGUOUS, AttemptState.COMMIT_FAILED).contains(incident.state())) return;
        try {
            var config = settings.get();
            if (!config.enabled()) { count = 0; return; }
            if (count != Long.MAX_VALUE) count++;
            long now = clock.getAsLong();
            if (now < nextAt) return;
            nextAt = now + config.intervalMillis();
            String message = config.message().replace("{count}", Long.toString(count))
                    .replace("{operation}", incident.evidence().operationId().toString());
            boolean delivered = false;
            for (var player : online.get()) {
                try { if (player.hasPermission(config.permission())) { player.sendMessage(message); delivered = true; } }
                catch (RuntimeException ignored) { /* Staff delivery is never a reward participant. */ }
            }
            if (delivered) count = 0;
        } catch (RuntimeException ignored) { /* Invalid reload or unavailable audience leaves SQL inspection intact. */ }
    }
}
