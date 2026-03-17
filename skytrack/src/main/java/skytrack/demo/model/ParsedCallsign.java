package skytrack.demo.model;

public record ParsedCallsign(
        String icaoCarrierCode,
        String flightNumber,
        String iataCarrierCode) {}
