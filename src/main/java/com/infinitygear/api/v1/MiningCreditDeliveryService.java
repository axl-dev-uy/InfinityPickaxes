package com.infinitygear.api.v1;

import java.util.concurrent.CompletionStage;

/** Provider-owned, receipt-backed delivery for the single Archive v1 subscription. */
public interface MiningCreditDeliveryService {
    /** Called on the Bukkit server thread. Return promptly; complete true only after a durable
     * credit-ID decision has committed. False, failure, or timeout leaves the credit pending. */
    @FunctionalInterface interface Consumer {
        CompletionStage<Boolean> accept(MiningCredit credit);
    }

    /** Register the live callback. Registration alone never activates historical delivery. */
    Registration register(Consumer consumer);

    /** Nonblocking, including on the server thread. Database activation runs on InfinityGear's
     * worker and completes only after commit. New receipts enroll after the activation lock
     * boundary; older credits are never backfilled. */
    CompletionStage<Void> activate();

    /** Previously enrolled credits remain pending. */
    interface Registration {
        /** Immediately stops new callbacks and queues a database-worker fence. Before this
         * stage completes, an acknowledgement already executing may commit. After it completes,
         * no acknowledgement from this registration can commit. Never wait for it on the server thread. */
        CompletionStage<Void> unregister();
    }
}
