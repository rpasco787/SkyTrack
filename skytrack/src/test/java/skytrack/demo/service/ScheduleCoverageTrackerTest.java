package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.ScheduleCoverage;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleCoverageTrackerTest {

    @Test
    void shouldCountByMethodAndComputeVerifiedRate() {
        var tracker = new ScheduleCoverageTracker();
        tracker.record("AEROAPI");
        tracker.record("AEROAPI");
        tracker.record("AEROAPI");
        tracker.record("ROUTE_AVERAGE");
        tracker.record("UNRESOLVED");

        ScheduleCoverage coverage = tracker.snapshot();
        assertThat(coverage.total()).isEqualTo(5);
        assertThat(coverage.verified()).isEqualTo(3);
        assertThat(coverage.estimated()).isEqualTo(1);
        assertThat(coverage.unresolved()).isEqualTo(1);
        assertThat(coverage.verifiedRate()).isEqualTo(0.6);
    }

    @Test
    void shouldReportZeroRateWhenEmpty() {
        ScheduleCoverage coverage = new ScheduleCoverageTracker().snapshot();
        assertThat(coverage.total()).isZero();
        assertThat(coverage.verifiedRate()).isZero();
    }

    @Test
    void shouldTreatNullMethodAsUnresolved() {
        var tracker = new ScheduleCoverageTracker();
        tracker.record(null);
        assertThat(tracker.snapshot().unresolved()).isEqualTo(1);
    }
}
