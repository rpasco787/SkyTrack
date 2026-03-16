package skytrack.demo.client;

import skytrack.demo.model.FlightSchedule;
import java.util.List;
import java.util.Optional;

public interface FlightScheduleApiClient {
    Optional<FlightSchedule> getFlightSchedule(String callsign, String date);
    List<FlightSchedule> getDailyFlights(String date);
}
