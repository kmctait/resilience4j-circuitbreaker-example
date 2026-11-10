# Resilience4j Circuit Breaker demo

A small, runnable demonstration of the Circuit Breaker pattern using
[Resilience4j](https://resilience4j.readme.io/docs/circuitbreaker) 2.3.0.

A local HTTP server simulates a remote service whose behaviour can change at
runtime. The demo walks through the full circuit breaker lifecycle:

```text
CLOSED --(too many failures)--> OPEN --(wait)--> HALF_OPEN --(successes)--> CLOSED
```

It also shows basic network timeouts (connect timeout plus a per-request
timeout), and that a timeout is recorded as a failure by the breaker.

## Requirements

- JDK 21+
- Internet access on the first build so Gradle can download dependencies
  (Gradle itself is provided by the included wrapper)

## Run

```bash
./gradlew run
```

Verified output:

```text
Circuit breaker configuration
  sliding window : COUNT_BASED, size=6, minimum calls=4
  failure rate   : 50%
  open state     : wait=2000 ms, half-open trials=2
  timeouts       : connect=1000 ms, request=400 ms

--- 1. Healthy service: calls succeed, breaker stays CLOSED ---
call 1: OK in 105 ms -> {"message":"hello from the remote service","mode":"HEALTHY","request":1}

--- 2. Service becomes slow: request timeout (400 ms) fires ---
[breaker] recorded failure: HttpTimeoutException
call 2: FAILED in 402 ms -> HttpTimeoutException: request timed out

--- 3. Service returns HTTP 503: failures accumulate ---
[breaker] recorded failure: IOException
call 3: FAILED in 4 ms -> IOException: transient HTTP 503
[breaker] recorded failure: IOException
[breaker] state: CLOSED -> OPEN
call 4: FAILED in 49 ms -> IOException: transient HTTP 503

--- 4. Circuit is OPEN: calls fail fast without touching the server ---
[breaker] call not permitted while OPEN
call 5: REJECTED in 0 ms (state=OPEN)
server requests before/after rejected call: 4 / 4

--- 5. Wait 2000 ms in OPEN state, then recover the service ---
Two permitted trial calls should close the circuit:
[breaker] state: OPEN -> HALF_OPEN
call 6: OK in 9 ms -> {"message":"hello from the remote service","mode":"HEALTHY","request":5}
[breaker] state: HALF_OPEN -> CLOSED
call 7: OK in 50 ms -> {"message":"hello from the remote service","mode":"HEALTHY","request":6}

--- 6. Circuit CLOSED again: normal traffic resumes ---
call 8: OK in 45 ms -> {"message":"hello from the remote service","mode":"HEALTHY","request":7}

Final state: CLOSED, server requests: 7
```

Timings vary slightly per run.

## What to look for

1. **CLOSED** - every request goes to the remote service.
2. **Timeout** - the slow endpoint sleeps 1.5 s while the client allows 400 ms.
   `HttpTimeoutException` is an `IOException`, so the breaker records it as a
   failure.
3. **OPEN** - after the 4th recorded call the failure rate is 75% (3 of 4), so
   the breaker opens. The next call is rejected immediately with
   `CallNotPermittedException`; the server request counter does not change.
4. **HALF_OPEN** - after the 2 s wait, the next calls are trial calls. Two
   successes bring the failure rate below 50% and close the circuit.
5. **CLOSED** - normal traffic resumes.

## Configuration

| Setting | Value |
| --- | --- |
| Sliding window | `COUNT_BASED`, size 6, minimum 4 calls |
| Failure rate threshold | 50% |
| Wait in open state | 2 s |
| Permitted calls in half-open | 2 |
| Recorded exceptions | `IOException` (includes timeouts and 5xx) |
| Ignored exceptions | `InterruptedException` |
| Connect timeout | 1 s |
| Request timeout | 400 ms |

`RemoteServer` switches between `HEALTHY`, `FAILING` (HTTP 503) and `SLOW`
(1.5 s delay) modes to drive the scenario deterministically.

## Test

```bash
./gradlew test
```

Three tests verify:

- the breaker opens after the failure-rate threshold is crossed and then rejects
  calls without invoking the remote resource,
- successful half-open trial calls close the breaker again,
- the slow endpoint produces an `HttpTimeoutException`.

## Build a standalone distribution

```bash
./gradlew installDist
./build/install/resilience4j-circuitbreaker-example/bin/resilience4j-circuitbreaker-example
```

## Layout

```text
src/main/java/com/example/circuitbreaker/CircuitBreakerDemo.java  breaker config + demo script
src/main/java/com/example/circuitbreaker/RemoteServer.java        controllable local HTTP server
src/test/java/com/example/circuitbreaker/CircuitBreakerDemoTest.java
```
