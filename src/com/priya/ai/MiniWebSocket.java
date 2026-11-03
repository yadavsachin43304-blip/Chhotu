package com.priya.ai;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal RFC 6455 WebSocket client (no external libraries, pure java.*).
 * Handles text/binary frames (both delivered as UTF-8 text), fragmentation, ping/pong, close.
 */
public class MiniWebSocket {
    public interface Listener {
        void onOpen();
        void onMessage(String text);
        void onClosed(int code, String reason);
    }

    private final String host;
    private final int port;
    private final String path;
    private final boolean tls;
    private final Listener listener;

    private volatile Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean open = false;
    private final AtomicBoolean done = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private final SecureRandom rnd = new SecureRandom();

    public MiniWebSocket(String host, int port, String path, boolean tls, Listener listener) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.tls = tls;
        this.listener = listener;
    }

    public void connect() {
        Thread t = new Thread(this::run, "ws-reader");
        t.setDaemon(true);
        t.start();
    }

    public boolean isOpen() { return open; }

    private void run() {
        try {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 15000);
            raw.setTcpNoDelay(true);
            raw.setKeepAlive(true);
            if (tls) {
                SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket s = (SSLSocket) f.createSocket(raw, host, port, true);
                s.startHandshake();
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
                    s.close();
                    throw new IOException("TLS hostname mismatch");
                }
                socket = s;
            } else {
                socket = raw;
            }
            in = new BufferedInputStream(socket.getInputStream(), 16384);
            out = socket.getOutputStream();
            handshake();
            open = true;
            startPinger();
            listener.onOpen();
            readLoop();
        } catch (Throwable t) {
            finish(1006, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void handshake() throws IOException {
        byte[] kb = new byte[16];
        rnd.nextBytes(kb);
        String key = Base64.getEncoder().encodeToString(kb);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String status = readLine();
        if (status == null || !status.contains(" 101")) {
            StringBuilder msg = new StringBuilder(status == null ? "no response from server" : status);
            int contentLength = 0;
            String l;
            while ((l = readLine()) != null && !l.isEmpty()) {
                if (l.toLowerCase().startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(l.substring(15).trim()); } catch (Exception ignored) { }
                }
            }
            if (contentLength > 0) {
                byte[] b = new byte[Math.min(contentLength, 600)];
                readFully(b, b.length);
                msg.append(" | ").append(new String(b, StandardCharsets.UTF_8));
            }
            throw new IOException(msg.toString());
        }
        String l;
        while ((l = readLine()) != null && !l.isEmpty()) { /* skip headers */ }
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private void readFully(byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(b, off, len - off);
            if (r < 0) throw new IOException("connection closed by peer");
            off += r;
        }
    }

    private void readLoop() throws IOException {
        ByteArrayOutputStream frag = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read();
            if (b0 < 0) throw new IOException("connection closed by peer");
            int b1 = in.read();
            if (b1 < 0) throw new IOException("connection closed by peer");
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) {
                len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xff);
            }
            if (len > 64L * 1024 * 1024) throw new IOException("frame too large");
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(mask, 4);
            }
            byte[] payload = new byte[(int) len];
            readFully(payload, payload.length);
            if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            switch (op) {
                case 0x0: // continuation
                    frag.write(payload, 0, payload.length);
                    if (fin) {
                        deliver(frag.toByteArray());
                        frag.reset();
                    }
                    break;
                case 0x1: // text
                case 0x2: // binary (Gemini sends JSON in binary frames)
                    if (fin) {
                        deliver(payload);
                    } else {
                        frag.reset();
                        frag.write(payload, 0, payload.length);
                    }
                    break;
                case 0x8: { // close
                    int code = payload.length >= 2 ? (((payload[0] & 0xff) << 8) | (payload[1] & 0xff)) : 1005;
                    String reason = payload.length > 2
                            ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
                    try { sendFrame(0x8, payload.length >= 2 ? new byte[]{payload[0], payload[1]} : new byte[0]); } catch (Exception ignored) { }
                    finish(code, reason);
                    return;
                }
                case 0x9: // ping
                    sendFrame(0xA, payload);
                    break;
                default: // pong / unknown
                    break;
            }
        }
    }

    private void deliver(byte[] data) {
        listener.onMessage(new String(data, StandardCharsets.UTF_8));
    }

    private void startPinger() {
        Thread t = new Thread(() -> {
            while (open) {
                try { Thread.sleep(25000); } catch (InterruptedException e) { return; }
                if (!open) return;
                try { sendFrame(0x9, new byte[0]); } catch (IOException e) { finish(1006, "ping failed: " + e.getMessage()); return; }
            }
        }, "ws-ping");
        t.setDaemon(true);
        t.start();
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        int len = payload.length;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(len + 14);
        bo.write(0x80 | opcode);
        if (len < 126) {
            bo.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            bo.write(0x80 | 126);
            bo.write((len >> 8) & 0xff);
            bo.write(len & 0xff);
        } else {
            bo.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) bo.write((int) (((long) len >> (8 * i)) & 0xff));
        }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        bo.write(mask, 0, 4);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        bo.write(masked, 0, len);
        synchronized (writeLock) {
            out.write(bo.toByteArray());
            out.flush();
        }
    }

    /** Thread-safe. Returns false if the socket is not open or the write failed. */
    public boolean send(String text) {
        if (!open) return false;
        try {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            finish(1006, "write failed: " + e.getMessage());
            return false;
        }
    }

    public void close() {
        if (open) {
            try { sendFrame(0x8, new byte[]{0x03, (byte) 0xE8}); } catch (Exception ignored) { }
        }
        finish(1000, "client closed");
    }

    private void finish(int code, String reason) {
        open = false;
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (IOException ignored) { }
        if (done.compareAndSet(false, true)) {
            listener.onClosed(code, reason == null ? "" : reason);
        }
    }
}
