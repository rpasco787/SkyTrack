package skytrack.demo.client;

import skytrack.demo.model.FlightPosition;

import java.util.List;

public interface FlightDataSource {

    List<FlightPosition> fetchPositions();
}
