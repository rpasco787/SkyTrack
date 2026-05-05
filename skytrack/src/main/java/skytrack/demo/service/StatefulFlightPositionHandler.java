package skytrack.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
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

    public StatefulFlightPositionHandler(AircraftTrackRepository repository,
                                         AircraftStateMachine stateMachine,
                                         ScheduleResolver scheduleResolver,
                                         DelayEventProcessor delayEventProcessor) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.scheduleResolver = scheduleResolver;
        this.delayEventProcessor = delayEventProcessor;
    }

    @Override
    public void handle(List<FlightPosition> positions) {
        int landings = 0;
        for (FlightPosition position : positions) {
            try {
                AircraftTrack track = repository.findByIcao24(position.icao24())
                        .orElseGet(() -> AircraftTrack.initial(position.icao24()));

                var result = stateMachine.process(track, position);
                repository.save(result.updatedTrack());

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
