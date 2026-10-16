package com.bytefuture.toolnest.features.http;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 探针专用的极小 HTTP 服务器。
 *
 * <p>为什么不用 {@code com.sun.net.httpserver}：它不在默认模块根集合里，
 * 要跑得给 java 命令加 {@code --add-modules jdk.httpserver}，会牵动 verify.cmd。
 * 这里手写 ServerSocket 反而更省事，也能精确控制回什么字节 ——
 * 比如「Content-Type 声明 GBK」这种真实接口常见、但标准库不好造的情况。
 *
 * <p>能力只覆盖探针需要的那几件事：按路径回固定字节 + 指定 Content-Type、
 * 302 跳转、记录收到的请求头。
 */
final class TinyHttpServer implements Closeable {

    private final ServerSocket server;
    private final Thread thread;
    private volatile boolean running = true;

    private final Map<String, byte[]> bodyByPath = new HashMap<>();
    private final Map<String, String> typeByPath = new HashMap<>();
    private final Map<String, String> redirectByPath = new HashMap<>();

    /** 最近一次请求的起始行与全部请求头（已按 ISO-8859-1 解成单字节字符）。 */
    private final List<String> lastRequest = Collections.synchronizedList(new ArrayList<>());
    /** 最近一次请求的请求体字节。 */
    private volatile byte[] lastBody = new byte[0];
    /**
     * 收到过的请求总数。
     *
     * <p>「摘要条上的『重发』按一下是不是真的又发了一次」只能靠计数回答 ——
     * 断言按钮被点过（{@code fire()} 返回值）证明不了请求发出去。
     */
    private final java.util.concurrent.atomic.AtomicInteger requestCount =
            new java.util.concurrent.atomic.AtomicInteger();

    TinyHttpServer() throws IOException {
        // 端口给 0 让系统分配，避免和开发机上别的东西撞端口
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(this::loop, "tiny-http");
        thread.setDaemon(true);
        thread.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /** 注册一个路径：固定字节 + Content-Type。 */
    void serve(String path, String contentType, byte[] body) {
        synchronized (bodyByPath) {
            bodyByPath.put(path, body);
            typeByPath.put(path, contentType);
        }
    }

    /** 注册一个 302 跳转。 */
    void redirect(String path, String location) {
        synchronized (redirectByPath) {
            redirectByPath.put(path, location);
        }
    }

    List<String> lastRequest() {
        synchronized (lastRequest) {
            return new ArrayList<>(lastRequest);
        }
    }

    byte[] lastBody() {
        return lastBody.clone();
    }

    /** 收到过的请求总数。 */
    int requestCount() {
        return requestCount.get();
    }

    private void loop() {
        while (running) {
            try (Socket socket = server.accept()) {
                handle(socket);
            } catch (IOException stop) {
                // 关闭时 accept 会抛，属正常退出路径
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String head = readHead(in);
        if (head.isEmpty()) {
            return;
        }
        requestCount.incrementAndGet();
        List<String> lines = new ArrayList<>();
        for (String line : head.split("\r\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        synchronized (lastRequest) {
            lastRequest.clear();
            lastRequest.addAll(lines);
        }
        readBody(in, lines);

        String requestLine = lines.isEmpty() ? "" : lines.get(0);
        String[] parts = requestLine.split(" ");
        String target = parts.length > 1 ? parts[1] : "/";
        int query = target.indexOf('?');
        String path = query >= 0 ? target.substring(0, query) : target;

        OutputStream out = socket.getOutputStream();
        String location = redirectByPath.get(path);
        if (location != null) {
            write(out, "302 Found", "text/plain; charset=utf-8",
                    location.getBytes(StandardCharsets.UTF_8), location);
            return;
        }
        byte[] body;
        String type;
        synchronized (bodyByPath) {
            body = bodyByPath.getOrDefault(path, new byte[0]);
            type = typeByPath.getOrDefault(path, "text/plain; charset=utf-8");
        }
        write(out, "200 OK", type, body, null);
    }

    /** 逐字节读到空行为止。不能用 BufferedReader —— 它会把请求体一起缓冲走。 */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            buffer.write(b);
            byte[] bytes = buffer.toByteArray();
            int n = bytes.length;
            if (n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n'
                    && bytes[n - 2] == '\r' && bytes[n - 1] == '\n') {
                break;
            }
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    /** 按 Content-Length 把请求体读干净，否则客户端写不完会收到 connection reset。 */
    private void readBody(InputStream in, List<String> lines) throws IOException {
        int length = 0;
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                } catch (NumberFormatException ignored) {
                    length = 0;
                }
            }
        }
        if (length <= 0) {
            lastBody = new byte[0];
            return;
        }
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        lastBody = body;
    }

    private static void write(OutputStream out, String status, String contentType,
                              byte[] body, String location) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        if (location != null) {
            head.append("Location: ").append(location).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
    }
}
