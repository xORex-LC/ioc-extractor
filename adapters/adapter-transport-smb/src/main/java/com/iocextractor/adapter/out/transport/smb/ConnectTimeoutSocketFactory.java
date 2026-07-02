package com.iocextractor.adapter.out.transport.smb;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;

/** Socket factory that applies a bounded TCP connect timeout to SMB connections. */
final class ConnectTimeoutSocketFactory extends SocketFactory {

    private final SocketFactory delegate;
    private final int connectTimeoutMillis;

    ConnectTimeoutSocketFactory(Duration connectTimeout) {
        this(SocketFactory.getDefault(), connectTimeout);
    }

    ConnectTimeoutSocketFactory(SocketFactory delegate, Duration connectTimeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        long millis = connectTimeout.toMillis();
        if (millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("connectTimeout must be between 1ms and "
                    + Integer.MAX_VALUE + "ms");
        }
        this.connectTimeoutMillis = (int) millis;
    }

    @Override
    public Socket createSocket() throws IOException {
        return delegate.createSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return connect(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(InetAddress address,
                               int port,
                               InetAddress localAddress,
                               int localPort) throws IOException {
        return connect(new InetSocketAddress(address, port), new InetSocketAddress(localAddress, localPort));
    }

    private Socket connect(InetSocketAddress remote, InetSocketAddress local) throws IOException {
        Socket socket = delegate.createSocket();
        try {
            if (local != null) {
                socket.bind(local);
            }
            socket.connect(remote, connectTimeoutMillis);
            return socket;
        } catch (IOException | RuntimeException failure) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
