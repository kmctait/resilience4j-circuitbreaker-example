package com.example.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CircuitBreakerDemoTest {

    private static CircuitBreakerConfig smallWindow(Duration waitInOpenState) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(waitInOpenState)
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(IOException.class)
                .build();
    }

    @Test
    void opensWhenFailureRateExceedsThresholdAndRejectsCalls() {
        CircuitBreaker breaker = CircuitBreaker.of("opens", smallWindow(Duration.ofSeconds(30)));
        AtomicInteger calls = new AtomicInteger();

        Callable<String> call = CircuitBreaker.decorateCallable(breaker, () -> {
            calls.incrementAndGet();
            throw new IOException("remote failure");
        });

        for (int i = 0; i < 4; i++) {
            assertThrows(IOException.class, call::call);
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        assertThrows(CallNotPermittedException.class, call::call);
        assertEquals(4, calls.get(), "a rejected call must not reach the remote resource");
    }

    @Test
    void closesAgainAfterSuccessfulHalfOpenTrials() throws Exception {
        CircuitBreaker breaker = CircuitBreaker.of("half-open", smallWindow(Duration.ofMillis(50)));
        AtomicInteger calls = new AtomicInteger();

        Callable<String> call = CircuitBreaker.decorateCallable(breaker, () -> {
            calls.incrementAndGet();
            return "ok";
        });

        breaker.transitionToOpenState();
        Thread.sleep(100);

        assertEquals("ok", call.call());
        assertEquals("ok", call.call());

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(2, calls.get());
    }

    @Test
    void slowResponseIsReportedAsTimeout() throws Exception {
        try (RemoteServer server = RemoteServer.start()) {
            server.setMode(RemoteServer.Mode.SLOW);

            assertThrows(HttpTimeoutException.class,
                    () -> CircuitBreakerDemo.fetch(server.uri()));
        }
    }
}
