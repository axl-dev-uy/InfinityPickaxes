package com.infinitygear.mining;

/** A trusted producer supplies these facts. AIR/generation snapshots alone cannot set authoritative.
 * Current AxMines diagnostics do not satisfy the authoritative capability and remain unavailable. */
public record MiningCompletion(boolean authoritative, boolean returnedSuccess, boolean acceptedReplacement,
                               boolean finalAir, boolean generationMatches, boolean placementMatches,
                               boolean mutationUnchanged, boolean producerException) {
    public boolean confirmed() {
        return authoritative && returnedSuccess && acceptedReplacement && finalAir && generationMatches
                && placementMatches && mutationUnchanged && !producerException;
    }
}
