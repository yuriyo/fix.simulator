package com.fix.simulator.engine;

import java.math.BigDecimal;

/**
 * Best bid and best offer for one symbol (BBO). A side with no resting orders is null.
 *
 * @param bidPx  best bid price, or null if no bids
 * @param bidQty total open quantity at the best bid (0 when there is no bid)
 * @param askPx  best offer price, or null if no offers
 * @param askQty total open quantity at the best offer (0 when there is no offer)
 */
public record TopOfBook(BigDecimal bidPx, long bidQty, BigDecimal askPx, long askQty) {

    /** Same prices and quantities; used to detect changes for market data updates. */
    public boolean sameAs(TopOfBook other) {
        return other != null
                && java.util.Objects.equals(bidPx, other.bidPx) && bidQty == other.bidQty
                && java.util.Objects.equals(askPx, other.askPx) && askQty == other.askQty;
    }
}
