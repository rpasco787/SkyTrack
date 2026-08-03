package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import skytrack.demo.config.StateMachineProperties;
import skytrack.demo.model.AircraftTrack;
import skytrack.demo.model.FlightPosition;
import skytrack.demo.repository.AircraftTrackRepository;

import java.util.List;

@Service
@Primary
public class StatefulFlightPositionHandler implements FlightPositionHandler {

    private static final Logger log = LoggerFactory.getLogger(StatefulFlightPositionHandler.class);

    private final AircraftTrackRepository repository;
    private final AircraftStateMachine stateMachine;
    private final ScheduleResolver scheduleResolver;
    private final DelayEventProcessor delayEventProcessor;
    private final StateMachineProperties props;

    public StatefulFlightPositionHandler(AircraftTrackRepository repository,
                                         AircraftStateMachine stateMachine,
                                         ScheduleResolver scheduleResolver,
                                         DelayEventProcessor delayEventProcessor,
                                         StateMachineProperties props) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.scheduleResolver = scheduleResolver;
        this.delayEventProcessor = delayEventProcessor;
        this.props = props;
    }

    @Override
    public void handle(List<FlightPosition> positions) {
        int landings = 0;
        for (FlightPosition position : positions) {
            try {
                AircraftTrack track = repository.findByIcao24(position.icao24())
                        .orElseGet(() -> AircraftTrack.initial(position.icao24()));

                // process() mutates the track in place, so the previous value has to be read first.
                Long previousLastSeen = track.getLastSeen();

                var result = stateMachine.process(track, position);

                // Writing every position costs one putItem per aircraft per poll and dominates
                // both the DynamoDB bill and the consumer's per-message latency. Persist only when
                // the write carries information: a state transition, a landing, or a heartbeat to
                // keep lastSeen inside the stale window (see StateMachineProperties for why the
                // heartbeat is mandatory).
                boolean persist = result.stateChanged()
                        || result.landingEvent().isPresent()
                        || previousLastSeen == null
                        || (position.lastContact() - previousLastSeen) >= props.persistIntervalSeconds();
                if (persist) {
                    repository.save(result.updatedTrack());
                }

                if (result.landingEvent().isPresent()) {
                    landings++;
                    var resolved = scheduleResolver.resolve(result.landingEvent().get());
                    delayEventProcessor.process(resolved);
                }
            } catch (Exception e) {
                log.error("Error processing position for icao24={}: {}",
                        position.icao24(), e.getMessage(), e);
            }
        }
        log.debug("Processed {} positions, {} landings detected", positions.size(), landings);
    }
}
