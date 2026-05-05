package skytrack.demo.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelayClassificationTest {

    @Test
    void shouldClassifyOnTime() {
        assertThat(DelayClassification.fromDelaySeconds(0L)).isEqualTo(DelayClassification.ON_TIME);
        assertThat(DelayClassification.fromDelaySeconds(-300L)).isEqualTo(DelayClassification.ON_TIME);
    }

    @Test
    void shouldClassifyMinor() {
        assertThat(DelayClassification.fromDelaySeconds(60L)).isEqualTo(DelayClassification.MINOR);
        assertThat(DelayClassification.fromDelaySeconds(900L)).isEqualTo(DelayClassification.MINOR);
    }

    @Test
    void shouldClassifyModerate() {
        assertThat(DelayClassification.fromDelaySeconds(960L)).isEqualTo(DelayClassification.MODERATE);
        assertThat(DelayClassification.fromDelaySeconds(2700L)).isEqualTo(DelayClassification.MODERATE);
    }

    @Test
    void shouldClassifyMajor() {
        assertThat(DelayClassification.fromDelaySeconds(2760L)).isEqualTo(DelayClassification.MAJOR);
        assertThat(DelayClassification.fromDelaySeconds(7200L)).isEqualTo(DelayClassification.MAJOR);
    }

    @Test
    void shouldClassifySevere() {
        assertThat(DelayClassification.fromDelaySeconds(7260L)).isEqualTo(DelayClassification.SEVERE);
    }

    @Test
    void shouldClassifyUnknownForNull() {
        assertThat(DelayClassification.fromDelaySeconds(null)).isEqualTo(DelayClassification.UNKNOWN);
    }

    @Test
    void shouldIdentifyFaaDelayedFlights() {
        assertThat(DelayClassification.ON_TIME.isDelayed()).isFalse();
        assertThat(DelayClassification.MINOR.isDelayed()).isFalse();
        assertThat(DelayClassification.MODERATE.isDelayed()).isTrue();
        assertThat(DelayClassification.MAJOR.isDelayed()).isTrue();
        assertThat(DelayClassification.SEVERE.isDelayed()).isTrue();
        assertThat(DelayClassification.UNKNOWN.isDelayed()).isFalse();
    }
}
