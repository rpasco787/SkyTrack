package skytrack.demo.service;

import skytrack.demo.model.FlightPosition;

import java.util.List;

public interface FlightPositionHandler {

    void handle(List<FlightPosition> positions);
}
