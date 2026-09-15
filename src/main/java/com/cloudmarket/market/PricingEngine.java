package com.cloudmarket.market;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Bounded bonding curve.
 *
 * <p>The instantaneous price at stock level {@code s} is:
 *
 * <pre>
 *   raw(s)   = basePrice * 2 * E / (s + E)
 *   price(s) = clamp(raw(s), floorPrice, ceilingPrice)
 * </pre>
 *
 * <p>The naive closed-form integral of the curve is only correct on the unclamped
 * region. Integrating first and clamping afterwards leaks money at the boundaries:
 * a batch that starts inside the ceiling region and ends on the curve would be paid
 * out entirely at curve prices. So we integrate piecewise across up to three
 * regions - a flat stretch at the ceiling, the log curve in the middle, and a flat
 * stretch at the floor - and sum them.
 *
 * <p>Because {@code raw} is strictly decreasing in {@code s}, the boundaries are
 * single crossing points that can be solved for directly:
 *
 * <pre>
 *   raw(s) >= ceiling  for s &lt;= sCeil,   sCeil  = 2*base*E/ceiling - E
 *   raw(s) &lt;= floor    for s &gt;= sFloor,  sFloor = 2*base*E/floor    - E
 * </pre>
 *
 * <p>Every value here is money-in-total, never money-per-unit. Rounding happens
 * once, at the very end, on the total - rounding each marginal unit to two decimal
 * places and summing would drift by up to half a cent per item and is exactly the
 * kind of thing players farm with stacks of 64.
 */
public final class PricingEngine {

    private PricingEngine() {
    }

    /**
     * Instantaneous unit price at a given stock level. Used for display only -
     * never for computing a transaction total.
     */
    public static BigDecimal spotPrice(MarketItem item, long stock) {
        double base = item.getBasePrice();
        double e = Math.max(1L, item.getEquilibriumStock());
        double raw = (base * 2.0d * e) / (stock + e);
        double clamped = Math.min(Math.max(raw, item.getFloorPrice()), item.getCeilingPrice());
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Area under the clamped price curve between two stock levels. This is the
     * gross value of moving stock from {@code from} to {@code to}, in currency.
     */
    public static double integrate(MarketItem item, double from, double to) {
        if (to <= from) {
            return 0.0d;
        }
        double base = item.getBasePrice();
        double e = Math.max(1L, item.getEquilibriumStock());
        double floor = Math.max(0.0d, item.getFloorPrice());
        double ceiling = item.getCeilingPrice();

        if (base <= 0.0d) {
            // Degenerate item: the curve is flat zero, only the floor can pay out.
            return floor * (to - from);
        }
        if (ceiling < floor) {
            // Misconfigured. Treat it as a flat price rather than producing nonsense.
            return floor * (to - from);
        }

        double numerator = 2.0d * base * e;
        double sCeil = ceiling > 0.0d ? (numerator / ceiling) - e : Double.NEGATIVE_INFINITY;
        double sFloor = floor > 0.0d ? (numerator / floor) - e : Double.POSITIVE_INFINITY;

        double total = 0.0d;

        // Region 1: stock so low the curve is above the ceiling. Flat at ceiling.
        double ceilEnd = Math.min(to, sCeil);
        if (ceilEnd > from) {
            total += ceiling * (ceilEnd - from);
        }

        // Region 2: the curve itself.
        double curveStart = Math.max(from, sCeil);
        double curveEnd = Math.min(to, sFloor);
        if (curveEnd > curveStart) {
            total += numerator * Math.log((curveEnd + e) / (curveStart + e));
        }

        // Region 3: stock so high the curve is below the floor. Flat at floor.
        double floorStart = Math.max(from, sFloor);
        if (to > floorStart) {
            total += floor * (to - floorStart);
        }

        return total;
    }

    /**
     * Quote a sale of {@code quantity} units into a market currently holding
     * {@code stock}. Stock rises, so the seller is paid the area under the curve
     * from {@code stock} to {@code stock + quantity} - each successive unit is
     * worth slightly less than the one before it.
     */
    public static Quote quoteSell(MarketItem item, long stock, int quantity, double taxRate) {
        double gross = integrate(item, stock, (double) stock + quantity);
        BigDecimal grossMoney = money(gross);
        BigDecimal tax = grossMoney.multiply(BigDecimal.valueOf(taxRate)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = grossMoney.subtract(tax);
        if (net.signum() < 0) {
            net = BigDecimal.ZERO;
        }
        return new Quote(quantity, grossMoney, tax, net, stock + quantity);
    }

    /**
     * Quote a purchase of {@code quantity} units out of a market currently holding
     * {@code stock}. Stock falls, so the buyer pays the area under the curve from
     * {@code stock - quantity} back up to {@code stock} - the same span a seller
     * would have been paid for supplying it, which is what makes the tax the only
     * thing standing between a player and free round-trip arbitrage.
     *
     * <p>Callers must reject {@code quantity > stock} before calling; the cloud
     * cannot sell what nobody has deposited.
     */
    public static Quote quoteBuy(MarketItem item, long stock, int quantity, double taxRate) {
        double gross = integrate(item, (double) stock - quantity, stock);
        BigDecimal grossMoney = money(gross);
        BigDecimal tax = grossMoney.multiply(BigDecimal.valueOf(taxRate)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = grossMoney.add(tax);
        return new Quote(quantity, grossMoney, tax, total, stock - quantity);
    }

    private static BigDecimal money(double value) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The result of pricing a batch.
     *
     * @param quantity   units moved
     * @param gross      pre-tax total
     * @param tax        tax withheld (sell) or added (buy); burned either way
     * @param net        what the player actually receives (sell) or pays (buy)
     * @param stockAfter the market's stock level once this settles
     */
    public record Quote(int quantity, BigDecimal gross, BigDecimal tax, BigDecimal net, long stockAfter) {

        /** Average per-unit price, for display in confirmations. */
        public BigDecimal unitAverage() {
            if (quantity <= 0) {
                return BigDecimal.ZERO;
            }
            return gross.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
        }
    }
}
