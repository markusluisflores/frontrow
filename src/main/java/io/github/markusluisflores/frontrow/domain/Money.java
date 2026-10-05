package io.github.markusluisflores.frontrow.domain;

/**
 * An amount in minor units plus an ISO-4217 currency code. Integer cents, never double (precision) and never
 * BigDecimal by default (spec §4).
 */
public record Money(long cents, String currency) {

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + cents);
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be three uppercase letters: " + currency);
        }
    }

    public static Money ofCents(long cents, String currency) {
        return new Money(cents, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(this.cents, other.cents), this.currency);
    }

    public Money times(int factor) {
        return new Money(Math.multiplyExact(this.cents, factor), this.currency);
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("cannot combine " + this.currency + " with " + other.currency);
        }
    }
}
