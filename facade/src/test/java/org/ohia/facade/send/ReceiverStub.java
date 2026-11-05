package org.ohia.facade.send;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A tester's FHIR server stand-in: records what it receives and answers with a configurable status. */
final class ReceiverStub {

    record Received(String method, String path, String authorization, String contentType, String body) {
    }

    final List<Received> received = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String responseBody = "{\"resourceType\":\"Bundle\",\"type\":\"transaction-response\"}";
    private final HttpServer server;

    ReceiverStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/fhir", this::handle);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }

    void reset() {
        received.clear();
        status = 200;
        responseBody = "{\"resourceType\":\"Bundle\",\"type\":\"transaction-response\"}";
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("Content-Type"), body));
        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/fhir+json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }
}
