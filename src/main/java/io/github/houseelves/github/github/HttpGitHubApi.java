package io.github.houseelves.github.github;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.houseelves.github.elf.Json;

/** {@link GitHubApi} over java.net.http: what the desk's Quarkus REST client did. */
public class HttpGitHubApi implements GitHubApi {

    static final URI GRAPHQL = URI.create("https://api.github.com/graphql");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public JsonNode graphql(String authorization, Map<String, Object> request) {
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(GRAPHQL).timeout(Duration.ofSeconds(30))
                    .header("Authorization", authorization).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("User-Agent", "github-elf")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(request))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new HttpFailed(r.statusCode());
            return Json.MAPPER.readTree(r.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't reach GitHub: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while asking GitHub", e);
        }
    }
}
