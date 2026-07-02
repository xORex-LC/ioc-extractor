package com.iocextractor.adapter.out.transport.smb;

import org.junit.jupiter.api.Test;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectTimeoutSocketFactoryTest {

    @Test
    void appliesConfiguredTimeoutToTcpConnect() throws IOException {
        RecordingSocket socket = new RecordingSocket();
        ConnectTimeoutSocketFactory factory = new ConnectTimeoutSocketFactory(
                new SingleSocketFactory(socket), Duration.ofSeconds(7));

        Socket connected = factory.createSocket("files.example.test", 445);

        assertThat(connected).isSameAs(socket);
        assertThat(socket.timeoutMillis).isEqualTo(7_000);
        assertThat(socket.remote.toString()).contains("files.example.test").endsWith(":445");
    }

    private static final class SingleSocketFactory extends SocketFactory {
        private final Socket socket;

        private SingleSocketFactory(Socket socket) {
            this.socket = socket;
        }

        @Override
        public Socket createSocket() {
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingSocket extends Socket {
        private SocketAddress remote;
        private int timeoutMillis;

        @Override
        public void connect(SocketAddress endpoint, int timeout) {
            remote = endpoint;
            timeoutMillis = timeout;
        }
    }
}
