package skytrack.demo.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import skytrack.demo.service.BtsScheduleRepository;

/**
 * Surfaces the one prediction failure mode that is otherwise invisible: a BTS repository holding
 * zero records while the feature is switched on.
 *
 * <p>Nothing downstream can distinguish that state from ordinary misses. An empty repository makes
 * {@link skytrack.demo.service.OutboundScheduleResolver} return empty for every arrival, so
 * {@code DelayPredictionService.predictNextDeparture} returns before it predicts anything — no
 * exception, no log line, no metric. The pipeline reports healthy while emitting nothing.</p>
 *
 * <p>A <em>missing</em> file is already loud: {@code BtsScheduleRepository.fromCsv} throws and the
 * context refuses to start. The gap this closes is the file that reads fine and parses to nothing —
 * an unexpected date format or renamed columns.</p>
 */
@Component("btsSchedule")
public class BtsScheduleHealthIndicator implements HealthIndicator {

    private final BtsScheduleRepository repository;
    private final PredictionProperties props;

    public BtsScheduleHealthIndicator(BtsScheduleRepository repository, PredictionProperties props) {
        this.repository = repository;
        this.props = props;
    }

    @Override
    public Health health() {
        // Switched off deliberately: an empty repository is the expected state, not a fault.
        // Reporting DOWN here would make the signal noise and nobody would act on it.
        if (!props.enabled()) {
            return Health.up().withDetail("prediction", "disabled").build();
        }

        int records = repository.size();
        if (records == 0) {
            return Health.down()
                    .withDetail("records", 0)
                    .withDetail("btsCsvPath", props.btsCsvPath())
                    .withDetail("reason", "BTS schedule loaded zero records — no predictions "
                            + "can be emitted. Check the CSV path resolves against the app's "
                            + "working directory and that its date format parses.")
                    .build();
        }
        return Health.up().withDetail("records", records).build();
    }
}
