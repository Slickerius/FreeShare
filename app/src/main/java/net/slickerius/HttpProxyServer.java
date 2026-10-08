package net.slickerius;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.SocketFactory;

final class HttpProxyServer implements AutoCloseable {
    interface Listener {
        void onProxyError(String message);
    }

    private static final int MAX_HEADER_BYTES = 65_536;
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private final InetAddress bindAddress;
    private final int port;
    private final SocketFactory upstreamSocketFactory;
    private final Listener listener;
    private final AtomicLong transferredBytes = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "freeshare-proxy-worker");
        thread.setDaemon(true);
        return thread;
    });

    private ServerSocket serverSocket;
    private Thread acceptThread;

    HttpProxyServer(
            InetAddress bindAddress,
            int port,
            SocketFactory upstreamSocketFactory,
            Listener listener) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.upstreamSocketFactory = upstreamSocketFactory;
        this.listener = listener;
    }

    void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, port), 64);
        acceptThread = new Thread(this::acceptLoop, "freeshare-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    long getTransferredBytes() {
        return transferredBytes.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                sockets.add(client);
                workers.execute(() -> handleClient(client));
            } catch (IOException error) {
                if (running.get()) {
                    listener.onProxyError("Proxy accept failed: " + safeMessage(error));
                }
            }
        }
    }

    private void handleClient(Socket client) {
        Socket remote = null;
        try {
            client.setSoTimeout(20_000);
            byte[] headerBytes = readHeader(client.getInputStream());
            if (headerBytes == null) {
                return;
            }
            Request request = Request.parse(headerBytes);
            remote = openRemote(request.host, request.port);
            sockets.add(remote);
            client.setSoTimeout(0);
            remote.setSoTimeout(0);

            if (request.connectTunnel) {
                client.getOutputStream().write(
                        "HTTP/1.1 200 Connection Established\r\n\r\n"
                                .getBytes(StandardCharsets.ISO_8859_1));
                client.getOutputStream().flush();
            } else {
                remote.getOutputStream().write(request.forwardHeader);
                remote.getOutputStream().flush();
            }
            relay(client, remote);
        } catch (BadRequest error) {
            sendError(client, 400, "Bad Request", error.getMessage());
        } catch (IOException error) {
            sendError(client, 502, "Bad Gateway", safeMessage(error));
        } finally {
            closeSocket(client);
            closeSocket(remote);
        }
    }

    private Socket openRemote(String host, int port) throws IOException {
        Socket socket = upstreamSocketFactory.createSocket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        return socket;
    }

    private void relay(Socket client, Socket remote) throws IOException {
        Future<?> upload = workers.submit(() -> pump(client, remote));
        try {
            pump(remote, client);
        } finally {
            upload.cancel(true);
        }
    }

    private void pump(Socket source, Socket destination) {
        byte[] buffer = new byte[32 * 1_024];
        try {
            InputStream input = source.getInputStream();
            OutputStream output = destination.getOutputStream();
            int count;
            while (running.get() && (count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                transferredBytes.addAndGet(count);
            }
            output.flush();
            destination.shutdownOutput();
        } catch (IOException ignored) {
            // Either peer closing a tunnel is a normal end-of-stream condition.
        }
    }

    private static byte[] readHeader(InputStream input) throws IOException, BadRequest {
        ByteArrayOutputStream output = new ByteArrayOutputStream(1_024);
        int matched = 0;
        while (output.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value == -1) {
                return output.size() == 0 ? null : output.toByteArray();
            }
            output.write(value);
            if ((matched == 0 || matched == 2) && value == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && value == '\n') {
                matched++;
                if (matched == 4) {
                    return output.toByteArray();
                }
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        throw new BadRequest("Request headers are too large");
    }

    private static void sendError(Socket client, int code, String reason, String detail) {
        if (client == null || client.isClosed()) {
            return;
        }
        String body = reason + "\n" + (detail == null ? "" : detail) + "\n";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        try {
            OutputStream output = client.getOutputStream();
            output.write(header.getBytes(StandardCharsets.ISO_8859_1));
            output.write(bodyBytes);
            output.flush();
        } catch (IOException ignored) {
        }
    }

    private void closeSocket(Socket socket) {
        if (socket == null) {
            return;
        }
        sockets.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        for (Socket socket : sockets) {
            closeSocket(socket);
        }
        workers.shutdownNow();
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    private static final class Request {
        final boolean connectTunnel;
        final String host;
        final int port;
        final byte[] forwardHeader;

        Request(boolean connectTunnel, String host, int port, byte[] forwardHeader) {
            this.connectTunnel = connectTunnel;
            this.host = host;
            this.port = port;
            this.forwardHeader = forwardHeader;
        }

        static Request parse(byte[] headerBytes) throws BadRequest {
            String raw = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String[] lines = raw.split("\\r\\n");
            if (lines.length == 0) {
                throw new BadRequest("Missing request line");
            }
            String[] requestLine = lines[0].split(" ", 3);
            if (requestLine.length != 3) {
                throw new BadRequest("Malformed request line");
            }
            String method = requestLine[0].toUpperCase(Locale.ROOT);
            String target = requestLine[1];
            if ("CONNECT".equals(method)) {
                HostPort destination = HostPort.parse(target, 443);
                return new Request(true, destination.host, destination.port, null);
            }

            String hostHeader = null;
            List<String> forwardedHeaders = new ArrayList<>();
            for (int index = 1; index < lines.length; index++) {
                String line = lines[index];
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Host")) {
                    hostHeader = value;
                }
                if (!name.equalsIgnoreCase("Proxy-Connection")
                        && !name.equalsIgnoreCase("Connection")) {
                    forwardedHeaders.add(line);
                }
            }

            URI uri;
            try {
                uri = URI.create(target);
            } catch (IllegalArgumentException error) {
                throw new BadRequest("Invalid request target");
            }
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 80 : uri.getPort();
            String path = target;
            if (host != null) {
                path = uri.getRawPath();
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                if (uri.getRawQuery() != null) {
                    path += "?" + uri.getRawQuery();
                }
            } else {
                if (hostHeader == null) {
                    throw new BadRequest("Missing Host header");
                }
                HostPort destination = HostPort.parse(hostHeader, 80);
                host = destination.host;
                port = destination.port;
            }

            StringBuilder rebuilt = new StringBuilder();
            rebuilt.append(method).append(' ').append(path).append(' ')
                    .append(requestLine[2]).append("\r\n");
            for (String line : forwardedHeaders) {
                rebuilt.append(line).append("\r\n");
            }
            rebuilt.append("Connection: close\r\n\r\n");
            return new Request(false, host, port,
                    rebuilt.toString().getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    private static final class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static HostPort parse(String value, int defaultPort) throws BadRequest {
            String host = value.trim();
            int port = defaultPort;
            if (host.startsWith("[")) {
                int closing = host.indexOf(']');
                if (closing < 0) {
                    throw new BadRequest("Invalid IPv6 destination");
                }
                String remainder = host.substring(closing + 1);
                String ipv6 = host.substring(1, closing);
                if (remainder.startsWith(":")) {
                    port = parsePort(remainder.substring(1));
                }
                host = ipv6;
            } else {
                int colon = host.lastIndexOf(':');
                if (colon > 0 && host.indexOf(':') == colon) {
                    port = parsePort(host.substring(colon + 1));
                    host = host.substring(0, colon);
                }
            }
            if (host.isBlank()) {
                throw new BadRequest("Missing destination host");
            }
            return new HostPort(host, port);
        }

        private static int parsePort(String value) throws BadRequest {
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65_535) {
                    throw new NumberFormatException();
                }
                return port;
            } catch (NumberFormatException error) {
                throw new BadRequest("Invalid destination port");
            }
        }
    }

    private static final class BadRequest extends Exception {
        BadRequest(String message) {
            super(message);
        }
    }
}
