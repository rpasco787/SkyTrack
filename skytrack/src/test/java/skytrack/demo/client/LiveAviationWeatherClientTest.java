package skytrack.demo.client;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import skytrack.demo.config.WeatherProperties;
import skytrack.demo.model.FlightCategory;
import skytrack.demo.model.WeatherObservation;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class LiveAviationWeatherClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void shouldFetchMetarForSingleAirport() {
        wm.stubFor(get(urlPathEqualTo("/metar"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "icaoId": "KORD",
                                    "obsTime": 1714915200,
                                    "visib": "10+",
                                    "wdir": 270,
                                    "wspd": 18,
                                    "wgst": 25,
                                    "rawOb": "METAR KORD 051430Z 27018G25KT 10SM CLR",
                                    "clouds": [{"cover": "CLR"}]
                                  }
                                ]
                                """)));

        var props = new WeatherProperties("live", wm.baseUrl() + "/metar",
                null, 5000, 15, 30, List.of("KORD"));
        var client = new LiveAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD"));

        assertThat(result).hasSize(1);
        WeatherObservation obs = result.get(0);
        assertThat(obs.airportIcao()).isEqualTo("KORD");
        assertThat(obs.visibilityStatuteMiles()).isEqualTo(10.0);
        assertThat(obs.windSpeedKnots()).isEqualTo(18);
        assertThat(obs.windGustKnots()).isEqualTo(25);
        assertThat(obs.flightCategory()).isEqualTo(FlightCategory.VFR);
    }

    @Test
    void shouldDeriveIfrFromLowCeiling() {
        wm.stubFor(get(urlPathEqualTo("/metar"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "icaoId": "KORD",
                                    "obsTime": 1714915200,
                                    "visib": "2",
                                    "wdir": 90,
                                    "wspd": 12,
                                    "rawOb": "METAR KORD 051430Z 09012KT 2SM BR OVC008",
                                    "clouds": [{"cover": "OVC", "base": 800}]
                                  }
                                ]
                                """)));

        var props = new WeatherProperties("live", wm.baseUrl() + "/metar",
                null, 5000, 15, 30, List.of("KORD"));
        var client = new LiveAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).flightCategory()).isEqualTo(FlightCategory.IFR);
        assertThat(result.get(0).ceilingFeet()).isEqualTo(800);
    }

    @Test
    void shouldReturnEmptyOnHttpError() {
        wm.stubFor(get(urlPathEqualTo("/metar"))
                .willReturn(aResponse().withStatus(500)));

        var props = new WeatherProperties("live", wm.baseUrl() + "/metar",
                null, 5000, 15, 30, List.of("KORD"));
        var client = new LiveAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD"));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldIgnoreFewAndSctClouds() {
        wm.stubFor(get(urlPathEqualTo("/metar"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "icaoId": "KORD",
                                    "obsTime": 1714915200,
                                    "visib": "10",
                                    "wdir": 0,
                                    "wspd": 5,
                                    "rawOb": "METAR KORD 051430Z 00005KT 10SM FEW015 SCT025",
                                    "clouds": [
                                      {"cover": "FEW", "base": 1500},
                                      {"cover": "SCT", "base": 2500}
                                    ]
                                  }
                                ]
                                """)));

        var props = new WeatherProperties("live", wm.baseUrl() + "/metar",
                null, 5000, 15, 30, List.of("KORD"));
        var client = new LiveAviationWeatherClient(props, new ObjectMapper());

        List<WeatherObservation> result = client.fetchObservations(List.of("KORD"));

        // FEW/SCT do not constitute a ceiling, so ceilingFeet should be null and category VFR
        assertThat(result.get(0).ceilingFeet()).isNull();
        assertThat(result.get(0).flightCategory()).isEqualTo(FlightCategory.VFR);
    }
}
