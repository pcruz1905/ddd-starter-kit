package myfluxo.adapter.http;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpService;

/**
 * Tiny lifecycle wrapper around Helidon's {@link WebServer}. The composition
 * root builds this with whatever services it wants to mount; the rest of
 * the application doesn't import Helidon directly.
 *
 * <p>{@link ErrorHandlers} is installed automatically so that <em>no</em>
 * uncaught exception ever leaks raw to the client — every route inherits
 * the airlock for free.
 */
public final class HttpServer implements AutoCloseable {

    private final WebServer server;

    private HttpServer(WebServer server) {
        this.server = server;
    }

    public static HttpServer start(int port, HttpService... services) {
        var ws = WebServer.builder()
            .port(port)
            .routing(routing -> {
                for (var service : services) {
                    routing.register(service);
                }
                ErrorHandlers.install(routing);
            })
            .build()
            .start();
        return new HttpServer(ws);
    }

    public int port() {
        return server.port();
    }

    @Override
    public void close() {
        server.stop();
    }
}
