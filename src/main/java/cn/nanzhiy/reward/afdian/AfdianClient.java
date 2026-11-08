package cn.nanzhiy.reward.afdian;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * 爱发电开放平台客户端。
 *
 * <p>全部请求通过 WebClient 发起，具备超时、重试上限与备用域名回退，
 * 不会阻塞 Halo 的请求线程。</p>
 */
@Slf4j
@Component
public class AfdianClient {

    private static final String[] DOMAINS = {"https://afdian.com", "https://ifdian.net"};

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient = WebClient.builder().build();

    /**
     * 调用开放平台接口。
     *
     * @param apiName    接口名，如 query-order
     * @param paramsJson 参数 JSON 字符串，必须与签名时完全一致
     * @param userId     开发者 user_id
     * @param token      API Token
     */
    public Mono<Map<String, Object>> call(String apiName, String paramsJson,
        String userId, String token) {
        return callWithDomain(apiName, paramsJson, userId, token, 0);
    }

    private Mono<Map<String, Object>> callWithDomain(String apiName, String paramsJson,
        String userId, String token, int domainIndex) {
        if (domainIndex >= DOMAINS.length) {
            return Mono.error(new IllegalStateException("爱发电接口不可用"));
        }
        var ts = Instant.now().getEpochSecond();
        var sign = sign(token, paramsJson, ts, userId);
        // 显式拼装 JSON，确保 params 一定是字符串而不是被序列化成对象
        var body = "{"
            + "\"user_id\":" + quote(userId)
            + ",\"params\":" + quote(paramsJson)
            + ",\"ts\":" + ts
            + ",\"sign\":" + quote(sign)
            + "}";
        return webClient.post()
            .uri(DOMAINS[domainIndex] + "/api/open/" + apiName)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
            })
            .timeout(TIMEOUT)
            // 4xx 是请求本身不合法，重试不会成功；超时与 5xx 才值得再试一次
            .retryWhen(Retry.backoff(1, Duration.ofMillis(600))
                .filter(error -> !isClientError(error)))
            .onErrorResume(error -> {
                log.warn("afdian api {} failed on {}: {}", apiName, DOMAINS[domainIndex],
                    error.getMessage());
                return callWithDomain(apiName, paramsJson, userId, token, domainIndex + 1);
            });
    }

    /**
     * 签名规则：md5(token + "params" + params + "ts" + ts + "user_id" + userId)。
     */
    private String sign(String token, String paramsJson, long ts, String userId) {
        var raw = token + "params" + paramsJson + "ts" + ts + "user_id" + userId;
        try {
            var digest = MessageDigest.getInstance("MD5")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isClientError(Throwable error) {
        return error instanceof WebClientResponseException response
            && response.getStatusCode().is4xxClientError();
    }

    /**
     * 校验签名是否配置正确，返回原始响应以便展示错误码。
     */
    public Mono<Map<String, Object>> pingResult(String userId, String token) {
        return call("ping", "{}", userId, token);
    }

    public Mono<Boolean> ping(String userId, String token) {
        return pingResult(userId, token)
            .map(result -> toInt(result.get("ec")) == 200)
            .defaultIfEmpty(false);
    }

    public static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }
}
