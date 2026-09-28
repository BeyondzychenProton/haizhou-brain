package com.haizhuo.brain.testsupport.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 仅在本机使用的无状态 Streamable HTTP MCP 模拟器。
 * 它是测试夹具，不是生产环境的身份提供方或业务服务。
 */
public class SimulatedMcpServer implements AutoCloseable {
    private static final String WRITER = "+15550000001";
    private static final String READER = "+15550000002";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    private static final Object RESPONSE_LOST = new Object();
    private final HttpServer server;
    private final byte[] secret;
    private final String audience;
    private final Clock clock;
    private final String writerMobile;
    private final Map<String, Note> notes = new ConcurrentHashMap<>();
    private final Map<String, WriteReceipt> writes = new ConcurrentHashMap<>();
    private volatile boolean readSchemaChanged;
    private final AtomicBoolean dropNextWriteResponse = new AtomicBoolean();

    public SimulatedMcpServer(int port, String secret, String audience, Clock clock) throws IOException {
        this(port, secret, audience, clock, WRITER, READER);
    }

    public SimulatedMcpServer(int port, String secret, String audience, Clock clock,
                              String writerMobile, String readerMobile) throws IOException {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("A test secret is required");
        if (writerMobile == null || writerMobile.isBlank() || readerMobile == null || readerMobile.isBlank()
                || writerMobile.equals(readerMobile))
            throw new IllegalArgumentException("Distinct test user mobiles are required");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.audience = audience;
        this.clock = clock;
        this.writerMobile = writerMobile;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/mcp", this::handle);
        notes.put("note-a", new Note(writerMobile, "writer's private note"));
        notes.put("note-b", new Note(readerMobile, "reader's private note"));
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public void changeReadSchemaForTest() { readSchemaChanged = true; }
    public void dropNextWriteResponseForTest() { dropNextWriteResponse.set(true); }
    @Override public void close() { server.stop(0); }

    /** 测试夹具辅助方法，不通过 HTTP 暴露。 */
    public String issueToken(String mobile) {
        return issueToken(mobile, clock.instant().plusSeconds(300));
    }

    /** 供认证失败测试显式指定过期时间。 */
    public String issueToken(String mobile, Instant expiresAt) {
        String payload = mobile + "|" + audience + "|" + expiresAt.getEpochSecond();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(bytes) + "." + encoder.encodeToString(mac.doFinal(bytes));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String mobile = verify(exchange.getRequestHeaders().getFirst("Authorization"));
            if (mobile == null) { send(exchange, 401, Map.of("error", "unauthorized")); return; }
            Map<String, Object> request = JSON.readValue(exchange.getRequestBody(), OBJECT);
            String method = String.valueOf(request.get("method"));
            Object id = request.get("id");
            if ("notifications/initialized".equals(method)) { exchange.sendResponseHeaders(202, -1); return; }
            if (id == null) { send(exchange, 400, Map.of("error", "id required")); return; }
            Object result;
            if ("initialize".equals(method)) {
                result = Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "haizhuo-simulator", "version", "1.0.0"));
            } else if ("tools/list".equals(method)) {
                result = list(mobile, cast(request.get("params")));
            } else if ("tools/call".equals(method)) {
                result = call(mobile, cast(request.get("params")));
            } else if ("ping".equals(method)) {
                result = Map.of();
            } else {
                send(exchange, 200, Map.of("jsonrpc", "2.0", "id", id,
                        "error", Map.of("code", -32601, "message", "Method not found")));
                return;
            }
            if (result == RESPONSE_LOST) return;
            send(exchange, 200, Map.of("jsonrpc", "2.0", "id", id, "result", result));
        } catch (Exception error) {
            send(exchange, 400, Map.of("error", "invalid request"));
        } finally {
            exchange.close();
        }
    }

    private Object list(String mobile, Map<String, Object> params) {
        String cursor = String.valueOf(params.getOrDefault("cursor", ""));
        if ("next".equals(cursor) && writerMobile.equals(mobile))
            return Map.of("tools", List.of(writeTool(), statusTool()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", List.of(readTool()));
        if (writerMobile.equals(mobile)) result.put("nextCursor", "next");
        return result;
    }

    private Object call(String mobile, Map<String, Object> params) {
        String name = String.valueOf(params.get("name"));
        Map<String, Object> args = cast(params.get("arguments"));
        if (!"private_note_read".equals(name) && !writerMobile.equals(mobile)) {
            return denied();
        }
        if ("private_note_read".equals(name)) {
            Note note = notes.get(String.valueOf(args.get("noteId")));
            if (note == null || !note.owner().equals(mobile)) {
                return denied();
            }
            return text(note.text());
        }
        String operation = String.valueOf(cast(params.get("_meta")).get("operationId"));
        if ("private_note_write".equals(name)) {
            String noteId = String.valueOf(args.get("noteId"));
            Note note = notes.get(noteId);
            if (note == null || !note.owner().equals(mobile) || operation.equals("null")) {
                return denied();
            }
            String value = String.valueOf(args.get("text"));
            String key = mobile + "|" + operation;
            WriteReceipt existing = writes.putIfAbsent(key, new WriteReceipt(noteId, value));
            if (existing != null && (!existing.noteId().equals(noteId) || !existing.text().equals(value)))
                return Map.of("content", List.of(Map.of("type", "text", "text", "idempotency conflict")), "isError", true);
            if (existing == null) notes.put(noteId, new Note(mobile, value));
            if (dropNextWriteResponse.getAndSet(false)) return RESPONSE_LOST;
            return text("saved:" + operation);
        }
        if ("private_note_write_status".equals(name)) {
            WriteReceipt receipt = writes.get(mobile + "|" + args.get("operationId"));
            return text(receipt == null ? "not_found" : "saved:" + args.get("operationId"));
        }
        return Map.of("content", List.of(Map.of("type", "text", "text", "unknown tool")), "isError", true);
    }

    private static Map<String, Object> text(String value) {
        return Map.of("content", List.of(Map.of("type", "text", "text", value)), "isError", false);
    }

    private static Map<String, Object> denied() {
        return Map.of("content", List.of(Map.of("type", "text", "text", "permission denied")),
                "isError", true, "structuredContent",
                Map.of("code", "PERMISSION_DENIED", "effect", "NOT_EXECUTED"));
    }

    private Map<String, Object> readTool() {
        return tool("private_note_read", "Read a private note owned by this user", true,
                readSchemaChanged ? Map.of("noteId", Map.of("type", "string"),
                        "format", Map.of("type", "string")) : Map.of("noteId", Map.of("type", "string")),
                List.of("noteId"));
    }
    private static Map<String, Object> writeTool() {
        return tool("private_note_write", "Update a private note; idempotent by operationId", false,
                Map.of("noteId", Map.of("type", "string"), "text", Map.of("type", "string")),
                List.of("noteId", "text"));
    }
    private static Map<String, Object> statusTool() {
        return tool("private_note_write_status", "Query a prior write by operationId", true,
                Map.of("operationId", Map.of("type", "string")), List.of("operationId"));
    }
    private static Map<String, Object> tool(String name, String description, boolean readOnly,
                                            Map<String, Object> properties, List<String> required) {
        return Map.of("name", name, "description", description,
                "inputSchema", Map.of("type", "object", "properties", properties,
                        "required", required, "additionalProperties", false),
                "annotations", Map.of("readOnlyHint", readOnly));
    }

    private String verify(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        String[] parts = header.substring(7).split("[.]", -1);
        if (parts.length != 2) return null;
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] payload = decoder.decode(parts[0]);
            byte[] signature = decoder.decode(parts[1]);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            if (!MessageDigest.isEqual(signature, mac.doFinal(payload))) return null;
            String[] claims = new String(payload, StandardCharsets.UTF_8).split("[|]", -1);
            if (claims.length != 3 || !audience.equals(claims[1])
                    || Instant.ofEpochSecond(Long.parseLong(claims[2])).isBefore(clock.instant())) return null;
            return claims[0];
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            return null;
        }
    }

    private static Map<String, Object> cast(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            return copy;
        }
        return Map.of();
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private record Note(String owner, String text) { }
    private record WriteReceipt(String noteId, String text) { }

    public static void main(String[] args) throws Exception {
        String secret = System.getenv("MCP_SIMULATOR_SECRET");
        int port = Integer.parseInt(System.getenv().getOrDefault("MCP_SIMULATOR_PORT", "8765"));
        String writerMobile = System.getenv().getOrDefault("MCP_SIMULATOR_WRITER_MOBILE", WRITER);
        String readerMobile = System.getenv().getOrDefault("MCP_SIMULATOR_READER_MOBILE", READER);
        SimulatedMcpServer simulator = new SimulatedMcpServer(port, secret, "demo-mcp", Clock.systemUTC(),
                writerMobile, readerMobile);
        simulator.start();
        System.out.println("MCP simulator listening on 127.0.0.1:" + simulator.port());
        Thread.currentThread().join();
    }
}
