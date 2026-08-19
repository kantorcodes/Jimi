package io.leavesfly.jimi.wire;

import io.leavesfly.jimi.wire.message.WireMessage;
import io.leavesfly.jimi.wire.message.WireRequest;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Wire 消息总线实现
 * <p>
 * 使用 Reactor Sinks 实现双向异步通信：
 * - 下行通道（Engine → Client）：通过 messageSink 实现
 * - 上行通道（Client → Engine）：通过 requestSink 实现请求-响应模式
 * <p>
 * reset() 仅做语义标记：Sink 在生命周期内保持不变，避免替换 Sink 后
 * 已建立的订阅（WireRequestHandler / ShellUI）仍绑定旧 Sink 导致通道失联
 */
@Slf4j
public class WireImpl implements Wire {

    /** 下行消息通道（Engine → Client） */
    private final Sinks.Many<WireMessage> messageSink;

    /** 上行请求通道（Client → Engine） */
    private final Sinks.Many<WireRequest<?>> requestSink;

    public WireImpl() {
        this.messageSink = createMessageSink();
        this.requestSink = createRequestSink();
    }

    private Sinks.Many<WireMessage> createMessageSink() {
        return Sinks.many().multicast().onBackpressureBuffer();
    }

    private Sinks.Many<WireRequest<?>> createRequestSink() {
        return Sinks.many().multicast().onBackpressureBuffer();
    }

    // ==================== 下行通道 ====================

    @Override
    public void send(WireMessage message) {
        messageSink.tryEmitNext(message);
    }

    @Override
    public Flux<WireMessage> asFlux() {
        return messageSink.asFlux();
    }

    // ==================== 上行通道 ====================

    @Override
    public <R> Mono<R> request(WireRequest<R> request) {
        log.debug("Sending wire request: {}", request.getMessageType());
        requestSink.tryEmitNext(request);
        return request.getResponseMono();
    }

    @Override
    public Flux<WireRequest<?>> requests() {
        return requestSink.asFlux();
    }

    // ==================== 生命周期 ====================

    @Override
    public void complete() {
        messageSink.tryEmitComplete();
    }

    @Override
    public void reset() {
        // 不替换 Sink：multicast().onBackpressureBuffer() 支持持续 emit，
        // 而订阅者（WireRequestHandler、ShellUI）在启动时只订阅一次，
        // 替换 Sink 会导致已有订阅绑定旧实例，reset 后双向通道全部失联
        log.debug("Wire reset: sinks kept, session state managed by upper layers");
    }
}
