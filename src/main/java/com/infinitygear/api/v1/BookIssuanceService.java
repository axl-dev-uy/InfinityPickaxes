package com.infinitygear.api.v1;

import java.util.concurrent.CompletionStage;
import org.bukkit.inventory.ItemStack;

/** Call issue on the server thread. Completion occurs asynchronously; use Bukkit scheduler for inventory changes.
 * Retried receipts are recovery data, never new delivery grants. */
public interface BookIssuanceService {
    record IssuedBook(BookLedger.Receipt receipt, byte[] serializedItem) {
        public IssuedBook { serializedItem = serializedItem.clone(); }
        @Override public byte[] serializedItem() { return serializedItem.clone(); }
    }
    CompletionStage<IssuedBook> issue(BookLedger.Issue request);
    CompletionStage<Boolean> validate(ItemStack item);
    /** Register an authority through ServicesManager. Called off-thread; validate committed external provenance. */
    interface ProvenanceAuthority {
        boolean validate(BookLedger.Issue request) throws Exception;
    }
}
