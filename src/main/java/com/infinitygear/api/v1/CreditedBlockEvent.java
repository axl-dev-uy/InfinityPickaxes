package com.infinitygear.api.v1;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.Objects;

/** Post-credit observation only. Never cancellable. Emitted on the server thread. */
public final class CreditedBlockEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MiningCredit credit;
    public CreditedBlockEvent(MiningCredit credit) {
        this.credit = Objects.requireNonNull(credit);
        if (!credit.legitimate() || !credit.successful()) throw new IllegalArgumentException("Only completed legitimate credits may be published");
    }
    public MiningCredit credit() { return credit; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
