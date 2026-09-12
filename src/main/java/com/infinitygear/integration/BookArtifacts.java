package com.infinitygear.integration;

import com.infinitygear.api.v1.BookLedger;
import java.util.Optional;
import java.util.UUID;

/** Blocking storage participant. Saved bytes are immutable and belong to an unconsumed ledger identity. */
public interface BookArtifacts {
    Optional<byte[]> load(UUID bookId) throws Exception;
    byte[] saveFirst(BookLedger.Receipt receipt, byte[] candidate) throws Exception;
}
