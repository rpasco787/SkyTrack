package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlightCategoryTest {

    @Test
    void shouldClassifyVfrWhenVisHighAndCeilingHigh() {
        assertThat(FlightCategory.from(10.0, 5000)).isEqualTo(FlightCategory.VFR);
        assertThat(FlightCategory.from(5.0, 3000)).isEqualTo(FlightCategory.VFR);
    }

    @Test
    void shouldClassifyMvfrWhenVisOrCeilingMarginal() {
        assertThat(FlightCategory.from(4.0, 5000)).isEqualTo(FlightCategory.MVFR);
        assertThat(FlightCategory.from(10.0, 2000)).isEqualTo(FlightCategory.MVFR);
        assertThat(FlightCategory.from(3.0, 1500)).isEqualTo(FlightCategory.MVFR);
    }

    @Test
    void shouldClassifyIfrWhenVisOrCeilingLow() {
        assertThat(FlightCategory.from(2.0, 5000)).isEqualTo(FlightCategory.IFR);
        assertThat(FlightCategory.from(10.0, 800)).isEqualTo(FlightCategory.IFR);
        assertThat(FlightCategory.from(1.0, 600)).isEqualTo(FlightCategory.IFR);
    }

    @Test
    void shouldClassifyLifrWhenVisOrCeilingVeryLow() {
        assertThat(FlightCategory.from(0.5, 5000)).isEqualTo(FlightCategory.LIFR);
        assertThat(FlightCategory.from(10.0, 300)).isEqualTo(FlightCategory.LIFR);
    }

    @Test
    void shouldReturnUnknownForNullInputs() {
        assertThat(FlightCategory.from(null, 5000)).isEqualTo(FlightCategory.UNKNOWN);
        assertThat(FlightCategory.from(5.0, null)).isEqualTo(FlightCategory.UNKNOWN);
        assertThat(FlightCategory.from(null, null)).isEqualTo(FlightCategory.UNKNOWN);
    }

    @Test
    void shouldUseWorstOfVisOrCeiling() {
        // Vis is VFR but ceiling is IFR -> downgrade to IFR
        assertThat(FlightCategory.from(10.0, 800)).isEqualTo(FlightCategory.IFR);
        // Vis is LIFR but ceiling is VFR -> downgrade to LIFR
        assertThat(FlightCategory.from(0.5, 5000)).isEqualTo(FlightCategory.LIFR);
    }
}
