package com.infinitygear.api.v1;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.Objects;

/** Post-credit observation only. Never cancellable. Emitted on the server thread.
 * Recovery may replay the same creditId; consumers must durably deduplicate it before granting rewards.
 * Event dispatch alone does not prove a consumer persisted the credit. */
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
