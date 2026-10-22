package io.github.houseelves.github.github;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** GitHub's GraphQL endpoint, called with the elf's token. */
public interface GitHubApi {

    JsonNode graphql(String authorization, Map<String, Object> request);

    /** GitHub answered with something other than 200. */
    class HttpFailed extends RuntimeException {
        public final int status;

        public HttpFailed(int status) {
            super("GitHub GraphQL answered HTTP " + status);
            this.status = status;
        }
    }
}
