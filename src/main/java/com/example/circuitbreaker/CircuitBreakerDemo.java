package com.example.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Runnable circuit breaker demo using Resilience4j.
 *
 * <p>A local HTTP server simulates a remote service that is healthy, slow and
 * failing. The demo shows the CLOSED -> OPEN -> HALF_OPEN -> CLOSED lifecycle,
 * including fast-fail behaviour while the circuit is open and the effect of
 * connect/request timeouts.</p>
 */
public final class CircuitBreakerDemo {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(400);
    private static final Duration WAIT_IN_OPEN_STATE = Duration.ofSeconds(2);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public static void main(String[] args) throws Exception {
        try (RemoteServer server = RemoteServer.start()) {
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(6)
                    .minimumNumberOfCalls(4)
                    .failureRateThreshold(50.0f)
                    .waitDurationInOpenState(WAIT_IN_OPEN_STATE)
                    .permittedNumberOfCallsInHalfOpenState(2)
                    .recordExceptions(IOException.class)          // timeouts + 5xx
                    .ignoreExceptions(InterruptedException.class)
                    .build();

            CircuitBreaker breaker = CircuitBreakerRegistry.of(config)
                    .circuitBreaker("remoteService");

            printConfig(config);
            registerLogging(breaker);

            Callable<String> guarded = CircuitBreaker.decorateCallable(
                    breaker, () -> fetch(server.uri()));

            System.out.println("--- 1. Healthy service: calls succeed, breaker stays CLOSED ---");
            run("call 1", guarded, breaker);

            System.out.println("\n--- 2. Service becomes slow: request timeout (400 ms) fires ---");
            server.setMode(RemoteServer.Mode.SLOW);
            run("call 2", guarded, breaker);

            System.out.println("\n--- 3. Service returns HTTP 503: failures accumulate ---");
            server.setMode(RemoteServer.Mode.FAILING);
            run("call 3", guarded, breaker);
            run("call 4", guarded, breaker);   // 4th call in window: 75% failures -> OPEN

            System.out.println("\n--- 4. Circuit is OPEN: calls fail fast without touching the server ---");
            int requestsBeforeRejection = server.requestCount();
            run("call 5", guarded, breaker);
            System.out.printf("server requests before/after rejected call: %d / %d%n",
                    requestsBeforeRejection, server.requestCount());

            System.out.printf("%n--- 5. Wait %d ms in OPEN state, then recover the service ---%n",
                    WAIT_IN_OPEN_STATE.toMillis());
            Thread.sleep(WAIT_IN_OPEN_STATE.toMillis() + 250);
            server.setMode(RemoteServer.Mode.HEALTHY);

            System.out.println("Two permitted trial calls should close the circuit:");
            run("call 6", guarded, breaker);
            run("call 7", guarded, breaker);

            System.out.println("\n--- 6. Circuit CLOSED again: normal traffic resumes ---");
            run("call 8", guarded, breaker);

            System.out.printf("%nFinal state: %s, server requests: %d%n",
                    breaker.getState(), server.requestCount());
        }
    }

    private static void registerLogging(CircuitBreaker breaker) {
        breaker.getEventPublisher()
                .onStateTransition(e -> System.out.printf(
                        "[breaker] state: %s -> %s%n",
                        e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState()))
                .onError(e -> System.out.printf(
                        "[breaker] recorded failure: %s%n",
                        e.getThrowable().getClass().getSimpleName()))
                .onCallNotPermitted(e ->
                        System.out.println("[breaker] call not permitted while OPEN"));
    }

    private static void printConfig(CircuitBreakerConfig config) {
        System.out.println("Circuit breaker configuration");
        System.out.printf("  sliding window : %s, size=%d, minimum calls=%d%n",
                config.getSlidingWindowType(),
                config.getSlidingWindowSize(),
                config.getMinimumNumberOfCalls());
        System.out.printf("  failure rate   : %.0f%%%n", config.getFailureRateThreshold());
        System.out.printf("  open state     : wait=%d ms, half-open trials=%d%n",
                WAIT_IN_OPEN_STATE.toMillis(),
                config.getPermittedNumberOfCallsInHalfOpenState());
        System.out.printf("  timeouts       : connect=%d ms, request=%d ms%n",
                CONNECT_TIMEOUT.toMillis(), REQUEST_TIMEOUT.toMillis());
        System.out.println();
    }

    private static void run(String label, Callable<String> call, CircuitBreaker breaker) {
        long start = System.nanoTime();
        try {
            String body = call.call();
            System.out.printf("%s: OK in %d ms -> %s%n", label, elapsedMillis(start), body);
        } catch (CallNotPermittedException e) {
            System.out.printf("%s: REJECTED in %d ms (state=%s)%n",
                    label, elapsedMillis(start), breaker.getState());
        } catch (Exception e) {
            System.out.printf("%s: FAILED in %d ms -> %s%n",
                    label, elapsedMillis(start), describe(e));
        }
    }

    /**
     * Performs the remote GET and classifies failures:
     * 429/5xx become retryable IOExceptions, other 4xx errors are permanent.
     * Timeouts from the HTTP client are IOExceptions too and are recorded as
     * failures by the circuit breaker.
     */
    static String fetch(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response =
                CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429 || response.statusCode() >= 500) {
            throw new IOException("transient HTTP " + response.statusCode());
        }
        if (response.statusCode() >= 400) {
            throw new IllegalArgumentException("permanent HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private CircuitBreakerDemo() {
    }
}
