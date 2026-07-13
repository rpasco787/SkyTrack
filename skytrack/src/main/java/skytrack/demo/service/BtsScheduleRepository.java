package skytrack.demo.service;

import skytrack.demo.model.BtsFlightRecord;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class BtsScheduleRepository {

    private final List<BtsFlightRecord> all;
    private final Map<String, List<BtsFlightRecord>> byTail;

    // Package-private: used by tests and by the CSV-loading @Component constructor (Task 4).
    BtsScheduleRepository(List<BtsFlightRecord> records) {
        this.all = List.copyOf(records);
        this.byTail = all.stream()
                .filter(r -> r.tailNumber() != null && !r.tailNumber().isBlank())
                .collect(Collectors.groupingBy(BtsFlightRecord::tailNumber));
    }

    public Optional<BtsFlightRecord> findInboundLeg(String carrierIata, String flightNumber,
                                                    String destIata, long nearArrivalEpoch) {
        return all.stream()
                .filter(r -> r.carrierIata().equals(carrierIata)
                        && r.flightNumber().equals(flightNumber)
                        && r.dest().equals(destIata))
                .min(Comparator.comparingLong(r -> Math.abs(r.scheduledDepEpoch() - nearArrivalEpoch)));
    }

    public Optional<BtsFlightRecord> findNextDeparture(String tailNumber, String fromAirportIata,
                                                       long afterEpoch) {
        return byTail.getOrDefault(tailNumber, List.of()).stream()
                .filter(r -> r.origin().equals(fromAirportIata) && r.scheduledDepEpoch() > afterEpoch)
                .min(Comparator.comparingLong(BtsFlightRecord::scheduledDepEpoch));
    }

    public int size() { return all.size(); }
}
