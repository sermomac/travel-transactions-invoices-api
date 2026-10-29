
package com.navan.travel;

import java.math.BigDecimal;

public final class TransactionLine {

    private TransactionLine() {
    }

    public record TaxLine(
            String name,
            BigDecimal rate,
            BigDecimal amount
    ) {
    }

    public record FeeLine(
            String name,
            BigDecimal amount
    ) {
    }
}
