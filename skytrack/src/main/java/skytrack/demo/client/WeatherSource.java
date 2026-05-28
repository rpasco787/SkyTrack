package skytrack.demo.client;

import skytrack.demo.model.WeatherObservation;

import java.util.List;

public interface WeatherSource {
    List<WeatherObservation> fetchObservations(List<String> airportIcaoCodes);
}
