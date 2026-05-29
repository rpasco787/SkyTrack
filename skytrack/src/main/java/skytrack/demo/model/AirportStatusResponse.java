package skytrack.demo.model;

import java.util.List;

public record AirportStatusResponse(
        AirportDisruptionScore score,
        WeatherObservation weather,
        List<CascadeAlert> cascades
) {}
