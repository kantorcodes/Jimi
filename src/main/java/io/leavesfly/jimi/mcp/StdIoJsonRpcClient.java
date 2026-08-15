package io.leavesfly.jimi.mcp;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * STDIO JSON-RPC 客户端实现
 * 通过标准输入输出与外部MCP服务进程通信
 */
@Slf4j
public class StdIoJsonRpcClient extends AbstractJsonRpcClient {

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    private final Map<Object, CompletableFuture<JsonRpcMessage.Response>> pendingRequests = new ConcurrentHashMap<>();
    /** 写锁：仅保护 writer 写入，避免多线程写入交错；等待响应不持锁 */
    private final Object writeLock = new Object();
    private final Thread readerThread;
    private volatile boolean closed = false;

    private static final long REQUEST_TIMEOUT_SECONDS = 30;

    /**
     * 构造 STDIO JSON-RPC 客户端
     *
     * @param command 启动命令，如 "node"、"python" 等
     * @param args    命令参数列表
     * @param env     环境变量映射，传递给子进程
     * @throws IOException 进程启动失败时抛出
     */
    public StdIoJsonRpcClient(String command, List<String> args, Map<String, String> env) throws IOException {
        super();

        ProcessBuilder pb = new ProcessBuilder();
        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(command);
        if (args != null) {
            fullCommand.addAll(args);
        }
        pb.command(fullCommand);

        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }

        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process startedProcess = null;
        try {
            startedProcess = pb.start();
            // MCP 协议规定使用 UTF-8 编码，显式指定避免平台默认字符集（如 Windows GBK）导致乱码
            this.writer = new BufferedWriter(new OutputStreamWriter(startedProcess.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(startedProcess.getInputStream(), StandardCharsets.UTF_8));
            this.process = startedProcess;

            this.readerThread = new Thread(this::readLoop, "MCP-Reader-" + command);
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        } catch (IOException e) {
            log.error("Failed to start MCP process: {}", e.getMessage());
            // 清理已启动的进程
            if (startedProcess != null) {
                startedProcess.destroy();
            }
            throw e;
        }
    }

    @Override
    protected JsonRpcMessage.Response sendRequest(String method, Map<String, Object> params) throws Exception {
        Object requestId = requestIdCounter.getAndIncrement();

        JsonRpcMessage.Request request = JsonRpcMessage.Request.builder()
                .jsonrpc("2.0")
                .id(requestId)
                .method(method)
                .params(params)
                .build();

        String requestJson = objectMapper.writeValueAsString(request);
        log.debug("Sending MCP request: {}", requestJson);

        // 先登记等待句柄再发送，避免响应先于登记到达而丢失
        CompletableFuture<JsonRpcMessage.Response> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            writeLine(requestJson);
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw e;
        }

        // 等待响应不持锁，多个请求可并行在途（JSON-RPC 通过 id 匹配响应）
        try {
            return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            throw new RuntimeException("Request timeout: method=" + method + ", id=" + requestId);
        } catch (InterruptedException e) {
            pendingRequests.remove(requestId);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request interrupted: method=" + method + ", id=" + requestId);
        }
    }

    @Override
    protected void sendNotification(String method, Map<String, Object> params) throws Exception {
        JsonRpcMessage.Request notification = JsonRpcMessage.Request.builder()
                .jsonrpc("2.0")
                .method(method)
                .params(params)
                .build();

        String notificationJson = objectMapper.writeValueAsString(notification);
        log.debug("Sending MCP notification: {}", notificationJson);
        writeLine(notificationJson);
    }

    /**
     * 写一行 JSON 消息到底层进程标准输入
     * 多线程并发写入时通过写锁保证消息不被交错拆分
     */
    private void writeLine(String json) throws IOException {
        synchronized (writeLock) {
            writer.write(json);
            writer.write("\n");
            writer.flush();
        }
    }

    /**
     * 后台读取响应循环
     */
    private void readLoop() {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                log.debug("Received MCP response: {}", line);
                try {
                    JsonRpcMessage.Response response = objectMapper.readValue(line, JsonRpcMessage.Response.class);
                    if (response.getId() == null) {
                        // 服务端通知（无 id），忽略
                        continue;
                    }
                    if (response.getResult() == null && response.getError() == null) {
                        // 既无 result 也无 error 的消息不是合法响应（可能是服务端发起的请求），
                        // 忽略以避免用空响应错误地完成等待中的 future
                        log.warn("Ignoring non-response message with id={}: {}", response.getId(), line);
                        continue;
                    }
                    CompletableFuture<JsonRpcMessage.Response> future = pendingRequests.remove(response.getId());
                    if (future != null) {
                        future.complete(response);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse response: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!closed) {
                log.error("Error reading from MCP process: {}", e.getMessage());
            }
        } finally {
            // 进程退出或连接断开时，失败所有未完成的请求避免调用方阻塞到超时
            if (!closed) {
                pendingRequests.forEach((id, future) ->
                        future.completeExceptionally(new RuntimeException("MCP process stream closed")));
                pendingRequests.clear();
            }
        }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        pendingRequests.forEach((id, future) ->
                future.completeExceptionally(new RuntimeException("Client closed")));
        pendingRequests.clear();

        if (writer != null) {
            writer.close();
        }
        if (reader != null) {
            reader.close();
        }
        if (process != null) {
            process.destroy();
            // 限时等待，避免子进程不响应 destroy 时无限阻塞关闭流程
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
}
