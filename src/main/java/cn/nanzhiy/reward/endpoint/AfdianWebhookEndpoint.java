package cn.nanzhiy.reward.endpoint;

import cn.nanzhiy.reward.afdian.AfdianService;
import cn.nanzhiy.reward.extension.RewardRecord;
import cn.nanzhiy.reward.support.RateLimiter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 接收爱发电订单回调。
 *
 * <p>平台要求立即返回 {"ec":200,"em":""}，因此校验与入库在响应之后异步执行。
 * 由于爱发电的回调没有签名，实际入库前会再通过开放平台接口反查订单以排除伪造请求。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AfdianWebhookEndpoint implements CustomEndpoint {

    private static final Map<String, Object> OK = Map.of("ec", 200, "em", "");

    /**
     * 平台未约定回调的 Content-Type，因此按原始字节解析。
     * 不能用 bodyToMono(Map.class)：它只认 application/json，其它类型会 415 后被吞成 200，订单静默丢失。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** 回调来源 IP 不固定，配额按来源 IP 独立计算。 */
    private static final int WEBHOOK_MAX_REQUESTS = 120;

    private static final Duration WEBHOOK_WINDOW = Duration.ofMinutes(1);

    /**
     * 回调体只有几百字节，限制上限避免伪造的超大请求把请求体整个拼进内存。
     * 超限由 join 抛异常，走下面的 onErrorResume 记日志并返回成功。
     */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final AfdianService afdianService;

    private final RateLimiter rateLimiter;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return org.springframework.web.reactive.function.server.RouterFunctions.route()
            .POST("/afdian/webhook", this::webhook)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api." + RewardRecord.GROUP, "v1alpha1");
    }

    private Mono<ServerResponse> webhook(ServerRequest request) {
        var ip = request.remoteAddress()
            .map(address -> address.getAddress().getHostAddress())
            .orElse("unknown");
        if (!rateLimiter.tryAcquire("afdian:" + ip, WEBHOOK_MAX_REQUESTS, WEBHOOK_WINDOW)) {
            // 超限也返回成功，避免平台把失败当异常反复重推；漏掉的订单可由后台手动同步补齐。
            log.warn("afdian webhook dropped by rate limit, ip={}", ip);
            return ServerResponse.ok().bodyValue(OK);
        }
        return DataBufferUtils.join(request.body(BodyExtractors.toDataBuffers()), MAX_BODY_BYTES)
            .map(this::readBody)
            .defaultIfEmpty("")
            .doOnNext(this::processAsync)
            .then(ServerResponse.ok().bodyValue(OK))
            .onErrorResume(error -> {
                log.warn("afdian webhook read failed: {}", error.getMessage());
                return ServerResponse.ok().bodyValue(OK);
            });
    }

    private String readBody(DataBuffer buffer) {
        try {
            var bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    /**
     * 结构异常一律记日志：平台已收到成功响应不会重推，静默丢弃将无从排查。
     */
    @SuppressWarnings("unchecked")
    private void processAsync(String body) {
        if (body.isBlank()) {
            log.warn("afdian webhook received empty body");
            return;
        }
        Map<String, Object> root;
        try {
            root = MAPPER.readValue(body, MAP_TYPE);
        } catch (Exception e) {
            log.warn("afdian webhook body is not valid json: {}", e.getMessage());
            return;
        }
        if (!(root.get("data") instanceof Map<?, ?> dataMap)) {
            log.warn("afdian webhook missing data field");
            return;
        }
        var type = dataMap.get("type");
        if (type != null && !"order".equals(String.valueOf(type))) {
            log.info("afdian webhook ignored: unsupported data type {}", type);
            return;
        }
        if (!(dataMap.get("order") instanceof Map<?, ?> orderMap)) {
            log.warn("afdian webhook missing data.order field");
            return;
        }
        afdianService.handleOrder((Map<String, Object>) orderMap)
            .subscribe(
                saved -> log.info("afdian order handled: {}", saved.getMetadata().getName()),
                error -> log.warn("afdian order handle failed: {}", error.getMessage())
            );
    }
}
