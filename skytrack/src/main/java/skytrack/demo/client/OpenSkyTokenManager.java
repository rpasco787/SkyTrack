package skytrack.demo.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

public class OpenSkyTokenManager {

    private static final Logger log = LoggerFactory.getLogger(OpenSkyTokenManager.class);

    private static final String TOKEN_URL =
            "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token";
    private static final int REFRESH_MARGIN_SECONDS = 30;

    private final String clientId;
    private final String clientSecret;
    private final RestClient authClient;
    private final ObjectMapper mapper;

    private volatile String token;
    private volatile Instant expiresAt;

    public OpenSkyTokenManager(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.authClient = RestClient.builder().build();
        this.mapper = new ObjectMapper();
    }

    public String getToken() {
        if (token != null && expiresAt != null && Instant.now().isBefore(expiresAt)) {
            return token;
        }
        return refresh();
    }

    private synchronized String refresh() {
        // Double-check after acquiring lock — another thread may have refreshed already
        if (token != null && expiresAt != null && Instant.now().isBefore(expiresAt)) {
            return token;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        String responseBody = authClient.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);

        try {
            JsonNode json = mapper.readTree(responseBody);
            this.token = json.get("access_token").asString();
            int expiresIn = json.get("expires_in").asInt(1800);
            this.expiresAt = Instant.now().plusSeconds(expiresIn - REFRESH_MARGIN_SECONDS);
            log.info("OAuth2 token obtained, expires in {}s", expiresIn);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OAuth2 token response", e);
        }

        return this.token;
    }
}
