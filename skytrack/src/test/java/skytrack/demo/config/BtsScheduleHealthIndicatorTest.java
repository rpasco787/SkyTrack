package skytrack.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import skytrack.demo.model.BtsFlightRecord;
import skytrack.demo.service.BtsScheduleRepository;
import skytrack.demo.service.TestRepos;

import static org.assertj.core.api.Assertions.assertThat;

class BtsScheduleHealthIndicatorTest {

    private static Health healthOf(boolean enabled, BtsScheduleRepository repo) {
        var props = new PredictionProperties(enabled, "data/bts/btsdata.csv", 45, 15, 360);
        return new BtsScheduleHealthIndicator(repo, props).health();
    }

    @Test
    void reportsDownWhenPredictionIsEnabledButNoSchedulesLoaded() {
        // The silent-failure case this indicator exists for: the CSV was readable (a missing file
        // already throws in fromCsv) but parsed to nothing — wrong date format or wrong columns.
        Health health = healthOf(true, BtsScheduleRepository.empty());

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("records", 0)
                .containsEntry("btsCsvPath", "data/bts/btsdata.csv");
    }

    @Test
    void reportsUpWithRecordCountWhenSchedulesAreLoaded() {
        var repo = TestRepos.of(new BtsFlightRecord(
                "UA", "1234", "N123UA", "ORD", "LAX", 1773090000L, 1773097200L,
                540L, false, null, null));

        Health health = healthOf(true, repo);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("records", 1);
    }

    @Test
    void reportsUpWhenPredictionIsDeliberatelyDisabled() {
        // An empty repository is the expected state when the feature is switched off, so this
        // must not read as a fault — otherwise the signal is noise and nobody acts on it.
        Health health = healthOf(false, BtsScheduleRepository.empty());

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("prediction", "disabled");
    }
}
