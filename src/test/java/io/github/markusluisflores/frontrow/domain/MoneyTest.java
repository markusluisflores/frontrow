package io.github.markusluisflores.frontrow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void addsAmountsInTheSameCurrency() {
        assertThat(Money.ofCents(5000, "CAD").plus(Money.ofCents(2550, "CAD"))).isEqualTo(Money.ofCents(7550, "CAD"));
    }

    @Test
    void multipliesBySeatCount() {
        assertThat(Money.ofCents(5000, "CAD").times(3)).isEqualTo(Money.ofCents(15000, "CAD"));
    }

    @Test
    void rejectsMixedCurrencies() {
        Money cad = Money.ofCents(100, "CAD");
        Money usd = Money.ofCents(100, "USD");
        assertThatIllegalArgumentException().isThrownBy(() -> cad.plus(usd)).withMessageContaining("CAD");
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.ofCents(-1, "CAD"));
    }

    @Test
    void rejectsCurrencyThatIsNotThreeUppercaseLetters() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.ofCents(100, "cad"));
        assertThatIllegalArgumentException().isThrownBy(() -> Money.ofCents(100, "CANADA"));
    }

    @Test
    void zeroIsAllowed() {
        assertThat(Money.ofCents(0, "CAD").cents()).isZero();
    }
}
