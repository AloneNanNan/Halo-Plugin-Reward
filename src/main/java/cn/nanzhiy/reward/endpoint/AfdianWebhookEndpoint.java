package cn.nanzhiy.reward.endpoint;

import cn.nanzhiy.reward.afdian.AfdianService;
import cn.nanzhiy.reward.extension.RewardRecord;
import cn.nanzhiy.reward.support.RateLimiter;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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
     * 回调来自爱发电的固定出口 IP，配额必须远高于其它接口，否则并发订单会被丢弃。
     */
    private static final int WEBHOOK_MAX_REQUESTS = 120;

    private static final Duration WEBHOOK_WINDOW = Duration.ofMinutes(1);

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
            log.warn("afdian webhook dropped by rate limit, ip={}", ip);
            return ServerResponse.ok().bodyValue(OK);
        }
        return request.bodyToMono(Map.class)
            .doOnNext(this::processAsync)
            .then(ServerResponse.ok().bodyValue(OK))
            .onErrorResume(error -> {
                log.warn("afdian webhook parse failed: {}", error.getMessage());
                return ServerResponse.ok().bodyValue(OK);
            });
    }

    @SuppressWarnings("unchecked")
    private void processAsync(Map<String, Object> body) {
        var data = body.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return;
        }
        var order = dataMap.get("order");
        if (!(order instanceof Map<?, ?> orderMap)) {
            return;
        }
        afdianService.handleOrder((Map<String, Object>) orderMap)
            .subscribe(
                saved -> log.info("afdian order handled: {}", saved.getMetadata().getName()),
                error -> log.warn("afdian order handle failed: {}", error.getMessage())
            );
    }
}
