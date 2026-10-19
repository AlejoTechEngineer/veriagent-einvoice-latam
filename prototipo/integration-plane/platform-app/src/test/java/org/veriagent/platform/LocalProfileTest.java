package org.veriagent.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** Perfil local: la aplicación escucha solo en 127.0.0.1 (no es alcanzable desde otras interfaces de red). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"veriagent.ingest.poller-enabled=false", "veriagent.responses.poller-enabled=false"})
@ActiveProfiles({"local", "test"})
class LocalProfileTest {
    @LocalServerPort int port;
    @Autowired Environment env;

    @Test
    @DisplayName("Escucha en 127.0.0.1 y no en las demás interfaces")
    void bindsLoopbackOnly() throws Exception {
        assertEquals("127.0.0.1", env.getProperty("server.address"));
        var r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (!(a instanceof Inet4Address)) continue;
                try (Socket s = new Socket()) {
                    s.connect(new java.net.InetSocketAddress(a, port), 300);
                    fail("La aplicación aceptó conexiones en " + a.getHostAddress());
                } catch (java.io.IOException expected) {
                    // Conexión rechazada: correcto.
                }
            }
        }
    }
}
