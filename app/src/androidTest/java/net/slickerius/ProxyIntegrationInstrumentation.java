package net.slickerius;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class ProxyIntegrationInstrumentation extends Instrumentation {
    private static final String TAG = "FreeShareTest";
    private final AtomicBoolean finished = new AtomicBoolean();

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        new Thread(() -> {
            SystemClock.sleep(60_000L);
            complete(Activity.RESULT_CANCELED, "FAIL: integration test exceeded 60 seconds");
        }, "freeshare-test-watchdog").start();
        new Thread(() -> {
            Context context = getTargetContext();
            try {
                report("Starting FreeShare service");
                context.getSharedPreferences(ShareService.PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(ShareService.KEY_PHASE, ShareService.PHASE_STOPPED)
                        .commit();
                context.startForegroundService(new Intent(context, ShareService.class)
                        .setAction(ShareService.ACTION_START));
                waitUntilActive(context);
                report("Wi-Fi Direct group and proxy are active");
                String summary = runHttpsTunnelTest();
                Log.i(TAG, summary);
                context.startService(new Intent(context, ShareService.class)
                        .setAction(ShareService.ACTION_STOP));
                waitUntilStopped(context);
                complete(Activity.RESULT_OK, summary);
            } catch (Throwable error) {
                String message = error.getClass().getSimpleName() + ": " + error.getMessage();
                Log.e(TAG, message, error);
                context.startService(new Intent(context, ShareService.class)
                        .setAction(ShareService.ACTION_STOP));
                waitUntilStopped(context);
                complete(Activity.RESULT_CANCELED, "FAIL: " + message);
            }
        }, "freeshare-integration-test").start();
    }

    private static void waitUntilStopped(Context context) {
        SharedPreferences state = context.getSharedPreferences(ShareService.PREFS, Context.MODE_PRIVATE);
        long deadline = SystemClock.elapsedRealtime() + 5_000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ShareService.PHASE_STOPPED.equals(
                    state.getString(ShareService.KEY_PHASE, ShareService.PHASE_STOPPED))) {
                return;
            }
            SystemClock.sleep(100L);
        }
    }

    private void report(String message) {
        Bundle status = new Bundle();
        status.putString("stream", message + "\n");
        sendStatus(0, status);
        Log.i(TAG, message);
    }

    private void complete(int resultCode, String message) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        Bundle result = new Bundle();
        result.putString("stream", message + "\n");
        result.putString("result", resultCode == Activity.RESULT_OK ? "PASS" : "FAIL");
        finish(resultCode, result);
    }

    private static void waitUntilActive(Context context) throws Exception {
        SharedPreferences state = context.getSharedPreferences(ShareService.PREFS, Context.MODE_PRIVATE);
        long deadline = SystemClock.elapsedRealtime() + 20_000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            String phase = state.getString(ShareService.KEY_PHASE, ShareService.PHASE_STOPPED);
            if (ShareService.PHASE_ACTIVE.equals(phase)) {
                return;
            }
            if (ShareService.PHASE_ERROR.equals(phase)) {
                throw new IllegalStateException(state.getString(
                        ShareService.KEY_DETAIL, "FreeShare failed to start"));
            }
            SystemClock.sleep(250L);
        }
        throw new IllegalStateException("FreeShare did not become active within 20 seconds");
    }

    private static String runHttpsTunnelTest() throws Exception {
        Socket proxy = new Socket();
        proxy.connect(new InetSocketAddress("192.168.49.1", ShareService.PROXY_PORT), 5_000);
        proxy.setSoTimeout(20_000);

        OutputStream proxyOutput = proxy.getOutputStream();
        proxyOutput.write(("CONNECT speed.cloudflare.com:443 HTTP/1.1\r\n"
                + "Host: speed.cloudflare.com:443\r\n"
                + "Proxy-Connection: keep-alive\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        proxyOutput.flush();

        String connectResponse = readHeader(proxy.getInputStream());
        require(connectResponse.startsWith("HTTP/1.1 200"),
                "CONNECT failed: " + connectResponse);

        SSLSocketFactory tlsFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket tls = (SSLSocket) tlsFactory
                .createSocket(proxy, "speed.cloudflare.com", 443, true);
        tls.setSoTimeout(30_000);
        tls.startHandshake();

        int expectedBytes = 5_000_000;
        OutputStream tlsOutput = tls.getOutputStream();
        tlsOutput.write(("GET /__down?bytes=" + expectedBytes + " HTTP/1.1\r\n"
                + "Host: speed.cloudflare.com\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        tlsOutput.flush();

        InputStream tlsInput = tls.getInputStream();
        String responseHeader = readHeader(tlsInput);
        require(responseHeader.startsWith("HTTP/1.1 200"),
                "Download failed: " + responseHeader);

        byte[] buffer = new byte[64 * 1_024];
        long started = System.nanoTime();
        long received = 0L;
        int count;
        while (received < expectedBytes && (count = tlsInput.read(buffer)) != -1) {
            received += count;
        }
        long elapsedNanos = System.nanoTime() - started;
        proxy.close();

        require(received >= expectedBytes,
                "Expected a multi-megabyte response, got " + received);
        double seconds = elapsedNanos / 1_000_000_000.0;
        double megabitsPerSecond = received * 8.0 / seconds / 1_000_000.0;
        return String.format(
                "PASS: HTTPS tunnel received %d bytes in %.3f seconds (%.2f Mbps)",
                received,
                seconds,
                megabitsPerSecond);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String readHeader(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        while (output.size() < 65_536) {
            int value = input.read();
            if (value == -1) {
                break;
            }
            output.write(value);
            if ((matched == 0 || matched == 2) && value == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && value == '\n') {
                matched++;
                if (matched == 4) {
                    break;
                }
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        return output.toString(StandardCharsets.ISO_8859_1.name());
    }
}
