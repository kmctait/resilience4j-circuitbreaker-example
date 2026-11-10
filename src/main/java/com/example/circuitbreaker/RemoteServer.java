package com.example.circuitbreaker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local HTTP server used by the demo. Its behaviour can be switched at runtime
 * between HEALTHY, FAILING (HTTP 503) and SLOW (1.5 s delay).
 */
final class RemoteServer implements AutoCloseable {

    enum Mode {
        HEALTHY,
        FAILING,
        SLOW
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.HEALTHY);

    private RemoteServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    static RemoteServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        RemoteServer remote = new RemoteServer(server, Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "remote-server");
            thread.setDaemon(true);
            return thread;
        }));

        server.createContext("/api/data", remote::handle);
        server.setExecutor(remote.executor);
        server.start();
        return remote;
    }

    private void handle(HttpExchange exchange) {
        int request = requests.incrementAndGet();
        Mode current = mode.get();

        if (current == Mode.SLOW) {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean failing = current == Mode.FAILING;
        String json = failing
                ? "{\"error\":\"service unavailable\",\"request\":" + request + "}"
                : "{\"message\":\"hello from the remote service\",\"mode\":\"" + current
                        + "\",\"request\":" + request + "}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        try {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(failing ? 503 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException ignored) {
            // The client may have timed out and closed the connection.
        }
    }

    void setMode(Mode newMode) {
        mode.set(newMode);
    }

    int requestCount() {
        return requests.get();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/data");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
