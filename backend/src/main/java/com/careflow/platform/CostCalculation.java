package com.careflow.platform;

import java.math.*;
import java.util.*;

/** Decimal arithmetic with explicit unknown quantities and missing unit prices. */
public final class CostCalculation {
  private CostCalculation() {}

  public record Quantity(Long units, boolean uncertain) {
    public Quantity {
      if (units != null && units < 0) throw new IllegalArgumentException();
    }

    static Quantity known(long units) {
      return new Quantity(units, false);
    }
  }

  public static Map<String, Object> calculate(
      Map<String, BigDecimal> rates, Map<String, Quantity> quantities, boolean configured) {
    BigDecimal known = BigDecimal.ZERO;
    var lines = new ArrayList<Map<String, Object>>();
    boolean complete = configured;
    for (var entry : quantities.entrySet()) {
      String resource = entry.getKey();
      Quantity quantity = entry.getValue();
      BigDecimal rate = rates.get(resource), amount = null;
      String state;
      if (quantity.units() != null && quantity.units() == 0 && !quantity.uncertain()) {
        amount = BigDecimal.ZERO;
        state = "NOT_USED";
      } else if (rate == null) {
        state = "UNPRICED";
        complete = false;
      } else if (quantity.units() == null) {
        state = "UNKNOWN_USAGE";
        complete = false;
      } else {
        amount = rate.multiply(BigDecimal.valueOf(quantity.units()));
        state = quantity.uncertain() ? "PARTIAL" : "KNOWN";
        if (quantity.uncertain()) complete = false;
      }
      if (amount != null) known = known.add(amount);
      var line = new LinkedHashMap<String, Object>();
      line.put("resource", resource);
      line.put("units", quantity.units());
      line.put("unit_price", rate);
      line.put("known_amount", amount == null ? null : amount.toPlainString());
      line.put("state", state);
      lines.add(line);
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("state", !configured ? "UNCONFIGURED" : complete ? "COMPLETE" : "PARTIAL");
    result.put("amount", complete ? known.toPlainString() : null);
    result.put("known_subtotal", configured ? known.toPlainString() : null);
    result.put("lines", lines);
    return result;
  }
}
