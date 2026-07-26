package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.ParsedCallsign;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CallsignParserTest {

    private final CallsignParser parser = new CallsignParser();

    @Test
    void shouldParseUnitedCallsign() {
        Optional<ParsedCallsign> result = parser.parse("UAL1234");
        assertThat(result).isPresent();
        assertThat(result.get().icaoCarrierCode()).isEqualTo("UAL");
        assertThat(result.get().flightNumber()).isEqualTo("1234");
        assertThat(result.get().iataCarrierCode()).isEqualTo("UA");
    }

    @Test
    void shouldParseDeltaCallsign() {
        Optional<ParsedCallsign> result = parser.parse("DAL567");
        assertThat(result).isPresent();
        assertThat(result.get().icaoCarrierCode()).isEqualTo("DAL");
        assertThat(result.get().iataCarrierCode()).isEqualTo("DL");
    }

    @Test
    void shouldParseAmericanCallsign() {
        Optional<ParsedCallsign> result = parser.parse("AAL100");
        assertThat(result).isPresent();
        assertThat(result.get().icaoCarrierCode()).isEqualTo("AAL");
        assertThat(result.get().iataCarrierCode()).isEqualTo("AA");
    }

    @Test
    void shouldHandleCallsignWithWhitespace() {
        Optional<ParsedCallsign> result = parser.parse("  UAL1234  ");
        assertThat(result).isPresent();
        assertThat(result.get().icaoCarrierCode()).isEqualTo("UAL");
    }

    @Test
    void shouldReturnEmptyForNull() {
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void shouldReturnEmptyForBlank() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
    }

    @Test
    void shouldReturnEmptyForInvalidFormat() {
        assertThat(parser.parse("UA1234")).isEmpty();  // only 2 letters
        assertThat(parser.parse("UALA")).isEmpty();    // no digits
        assertThat(parser.parse("12345")).isEmpty();   // all digits
    }

    @Test
    void shouldReturnEmptyForUnknownCarrier() {
        assertThat(parser.parse("ZZZ999")).isEmpty();
    }

    @Test
    void shouldParsePsaAirlinesCallsign() {
        Optional<ParsedCallsign> result = parser.parse("JIA5480");
        assertThat(result).isPresent();
        assertThat(result.get().icaoCarrierCode()).isEqualTo("JIA");
        assertThat(result.get().iataCarrierCode()).isEqualTo("OH");
    }

    @Test
    void shouldParseAllegiantCallsign() {
        Optional<ParsedCallsign> result = parser.parse("AAY3227");
        assertThat(result).isPresent();
        assertThat(result.get().icaoCarrierCode()).isEqualTo("AAY");
        assertThat(result.get().iataCarrierCode()).isEqualTo("G4");
    }
}
