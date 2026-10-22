package io.github.houseelves.github.github;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Semaphore;

/**
 * Every call to GitHub, one at a time. GitHub asks for a user's requests to be made serially: several at once (the
 * board's searches ran in parallel) trip its secondary rate limit, which answers 403 for minutes.
 */
public final class OneAtATime {

    private static final Semaphore GATE = new Semaphore(1, true);

    private OneAtATime() {
    }

    public static <T> HttpResponse<T> send(HttpClient http, HttpRequest request, HttpResponse.BodyHandler<T> body)
            throws IOException, InterruptedException {
        GATE.acquire();
        try {
            return http.send(request, body);
        } finally {
            GATE.release();
        }
    }
}
