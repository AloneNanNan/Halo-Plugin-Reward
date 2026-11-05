package cn.nanzhiy.reward.afdian;

import cn.nanzhiy.reward.extension.RewardRecord;
import cn.nanzhiy.reward.service.RewardNotificationService;
import cn.nanzhiy.reward.service.RewardRecordService;
import cn.nanzhiy.reward.service.RewardSettingService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

/**
 * 爱发电订单接入：Webhook 入库、手动同步、订单反查与打赏者信息补全。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AfdianService {

    /** 订单同步时最多拉取的页数，每页 50 条。 */
    private static final int SYNC_MAX_PAGES = 5;

    /** 打赏者反查最多翻页数，每页 20 条。 */
    private static final int SPONSOR_MAX_PAGES = 2;

    private static final Duration SPONSOR_CACHE_TTL = Duration.ofMinutes(10);

    private static final int MAX_SPONSOR_CACHE = 1024;

    private static final int MAX_NICKNAME_LENGTH = 30;

    private static final int MAX_MESSAGE_LENGTH = 200;

    /** 爱发电订单状态：2 表示已支付。 */
    private static final int ORDER_STATUS_PAID = 2;

    private final AfdianClient afdianClient;

    private final RewardSettingService settingService;

    private final RewardRecordService recordService;

    private final RewardNotificationService notificationService;

    private final ReactiveExtensionClient extensionClient;

    private final Map<String, CachedSponsor> sponsorCache = new ConcurrentHashMap<>();

    public record Sponsor(String name, String avatar) {
        static final Sponsor EMPTY = new Sponsor(null, null);
    }

    private record CachedSponsor(Sponsor sponsor, Instant cachedAt) {
    }

    /**
     * 从 Halo Secret 读取 API Token。
     */
    public Mono<String> resolveToken(AfdianSettings settings) {
        var secretName = settings.getSecretName();
        if (!StringUtils.hasText(secretName)) {
            return Mono.empty();
        }
        return extensionClient.fetch(Secret.class, secretName)
            .map(this::readToken)
            .filter(StringUtils::hasText)
            .doOnNext(token -> log.debug("afdian token resolved, length={}", token.length()));
    }

    /**
     * 兼容两种存储形式：写入时的 stringData，以及 Halo 落库后的 data（byte[]）。
     */
    private String readToken(Secret secret) {
        var stringData = secret.getStringData();
        if (stringData != null) {
            var value = stringData.get("token");
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        var data = secret.getData();
        if (data != null) {
            var bytes = data.get("token");
            if (bytes != null && bytes.length > 0) {
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * 处理一条订单：幂等检查 → 反查校验 → 入库。
     *
     * @param order 订单数据（Webhook 或 API 返回的结构）
     */
    public Mono<RewardRecord> handleOrder(Map<String, Object> order) {
        return handleOrder(order, true);
    }

    /**
     * @param verify 是否调用开放平台反查订单。手动同步的数据来自已签名的接口，可跳过反查省去一半请求。
     */
    private Mono<RewardRecord> handleOrder(Map<String, Object> order, boolean verify) {
        var tradeNo = str(order.get("out_trade_no"));
        if (!StringUtils.hasText(tradeNo)) {
            return Mono.empty();
        }
        return recordService.exists(recordName(tradeNo))
            .flatMap(exists -> {
                if (Boolean.TRUE.equals(exists)) {
                    return Mono.<RewardRecord>empty();
                }
                return settingService.afdian().flatMap(settings -> {
                    if (!settings.isEnable()) {
                        return Mono.<RewardRecord>empty();
                    }
                    return resolveToken(settings)
                        .flatMap(token -> verify
                            ? verifyOrder(tradeNo, settings.getUserId(), token)
                            : Mono.just(Map.<String, Object>of("order", order)))
                        .flatMap(verified -> applySponsor(verified, settings))
                        .flatMap(data -> saveRecord(verifiedOrder(data),
                            resolveSponsorFromData(data), settings));
                });
            });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verifiedOrder(Map<String, Object> data) {
        var order = data.get("order");
        return order instanceof Map<?, ?> ? (Map<String, Object>) order : Map.of();
    }

    /**
     * 用开放平台接口反查订单，确认订单真实存在且支付成功。
     */
    private Mono<Map<String, Object>> verifyOrder(String tradeNo, String userId, String token) {
        var params = "{\"page\":1,\"out_trade_no\":\"" + escapeJson(tradeNo) + "\"}";
        return afdianClient.call("query-order", params, userId, token)
            .flatMap(result -> {
                if (AfdianClient.toInt(result.get("ec")) != 200) {
                    log.warn("afdian query-order rejected: {}", result.get("em"));
                    return Mono.empty();
                }
                var order = firstOrder(result);
                if (order == null) {
                    return Mono.empty();
                }
                if (AfdianClient.toInt(order.get("status")) != ORDER_STATUS_PAID) {
                    return Mono.<Map<String, Object>>empty();
                }
                return Mono.just(Map.<String, Object>of("order", order));
            });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstOrder(Map<String, Object> result) {
        var data = result.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return null;
        }
        var list = dataMap.get("list");
        if (!(list instanceof List<?> items) || items.isEmpty()) {
            return null;
        }
        var first = items.get(0);
        return first instanceof Map<?, ?> ? (Map<String, Object>) first : null;
    }

    /**
     * 补全打赏者昵称与头像。查不到时保持为空，前台回退为匿名展示。
     */
    private Mono<Map<String, Object>> applySponsor(Map<String, Object> data,
        AfdianSettings settings) {
        var order = verifiedOrder(data);
        var afdianUserId = str(order.get("user_id"));
        if (!StringUtils.hasText(afdianUserId)) {
            return Mono.just(data);
        }
        return resolveToken(settings)
            .flatMap(token -> findSponsor(afdianUserId, settings.getUserId(), token, 1))
            .map(sponsor -> {
                var merged = new java.util.HashMap<>(data);
                merged.put("sponsor", sponsor);
                return (Map<String, Object>) merged;
            })
            .defaultIfEmpty(data);
    }

    private Sponsor resolveSponsorFromData(Map<String, Object> data) {
        var sponsor = data.get("sponsor");
        return sponsor instanceof Sponsor value ? value : Sponsor.EMPTY;
    }

    private Mono<Sponsor> findSponsor(String afdianUserId, String devUserId, String token,
        int page) {
        var cached = sponsorCache.get(afdianUserId);
        if (cached != null && Duration.between(cached.cachedAt(), Instant.now())
            .compareTo(SPONSOR_CACHE_TTL) < 0) {
            return Mono.just(cached.sponsor());
        }
        if (page > SPONSOR_MAX_PAGES) {
            return Mono.just(Sponsor.EMPTY);
        }
        var params = "{\"page\":" + page + "}";
        return afdianClient.call("query-sponsor", params, devUserId, token)
            .flatMap(result -> {
                var found = matchSponsor(result, afdianUserId);
                if (found != null) {
                    cacheSponsor(afdianUserId, found);
                    return Mono.just(found);
                }
                return findSponsor(afdianUserId, devUserId, token, page + 1);
            })
            .onErrorResume(error -> Mono.just(Sponsor.EMPTY));
    }

    private void cacheSponsor(String afdianUserId, Sponsor sponsor) {
        if (sponsorCache.size() >= MAX_SPONSOR_CACHE) {
            var now = Instant.now();
            sponsorCache.entrySet().removeIf(item ->
                Duration.between(item.getValue().cachedAt(), now).compareTo(SPONSOR_CACHE_TTL) >= 0);
            if (sponsorCache.size() >= MAX_SPONSOR_CACHE) {
                sponsorCache.clear();
            }
        }
        sponsorCache.put(afdianUserId, new CachedSponsor(sponsor, Instant.now()));
    }

    private Sponsor matchSponsor(Map<String, Object> result, String afdianUserId) {
        var data = result.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return null;
        }
        var list = dataMap.get("list");
        if (!(list instanceof List<?> items)) {
            return null;
        }
        for (var item : items) {
            if (!(item instanceof Map<?, ?> sponsorItem)) {
                continue;
            }
            var user = sponsorItem.get("user");
            if (!(user instanceof Map<?, ?> userMap)) {
                continue;
            }
            if (!afdianUserId.equals(str(userMap.get("user_id")))) {
                continue;
            }
            return new Sponsor(str(userMap.get("name")), str(userMap.get("avatar")));
        }
        return null;
    }

    private Mono<RewardRecord> saveRecord(Map<String, Object> order, Sponsor sponsor,
        AfdianSettings settings) {
        var tradeNo = str(order.get("out_trade_no"));
        if (!StringUtils.hasText(tradeNo)) {
            return Mono.empty();
        }
        var amount = parseAmount(order.get("total_amount"));
        if (amount == null) {
            return Mono.empty();
        }
        var spec = new RewardRecord.Spec();
        spec.setAmount(amount);
        spec.setMethod("afdian");
        spec.setNickname(truncate(sponsor.name(), MAX_NICKNAME_LENGTH));
        spec.setAvatar(StringUtils.hasText(sponsor.avatar()) ? sponsor.avatar().trim() : null);
        spec.setMessage(truncate(order.get("remark") == null ? null
            : String.valueOf(order.get("remark")), MAX_MESSAGE_LENGTH));
        spec.setTransactionNo(tradeNo);
        spec.setExternalId(tradeNo);
        spec.setSource("afdian");
        spec.setStatus(settings.isAutoApprove()
            ? RewardRecord.STATUS_CONFIRMED : RewardRecord.STATUS_PENDING);

        var record = new RewardRecord();
        record.setSpec(spec);
        var metadata = new Metadata();
        metadata.setName(recordName(tradeNo));
        record.setMetadata(metadata);

        return recordService.create(record)
            .doOnNext(saved -> {
                log.info("afdian order saved: {}", tradeNo);
                notificationService.notifyNewRecord(saved, "afdian");
            });
    }

    /**
     * 手动同步历史订单，返回新增数量。单条订单失败不会中断整批同步。
     */
    public Mono<Integer> syncOrders() {
        return settingService.afdian().flatMap(settings -> {
            if (!settings.isEnable()) {
                return Mono.error(new IllegalArgumentException("爱发电未启用"));
            }
            return resolveToken(settings)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("未配置爱发电 API 凭据")))
                .flatMap(token -> Flux.range(1, SYNC_MAX_PAGES)
                    .concatMap(page -> fetchOrders(settings, token, page))
                    .filter(order -> AfdianClient.toInt(order.get("status")) == ORDER_STATUS_PAID)
                    .concatMap(order -> handleOrder(order, false)
                        .onErrorResume(error -> {
                            log.warn("afdian order skipped: {}", error.getMessage());
                            return Mono.empty();
                        }))
                    .count()
                    .map(Long::intValue));
        });
    }

    @SuppressWarnings("unchecked")
    private Flux<Map<String, Object>> fetchOrders(AfdianSettings settings, String token, int page) {
        var params = "{\"page\":" + page + "}";
        return afdianClient.call(QUERY_ORDER, params, settings.getUserId(), token)
            .flatMapMany(result -> {
                var ec = AfdianClient.toInt(result.get("ec"));
                if (ec != 200) {
                    var em = String.valueOf(result.getOrDefault("em", "unknown"));
                    log.warn("afdian query-order failed: ec={}, em={}, debug={}",
                        ec, em, result.get("debug"));
                    return Flux.error(new IllegalArgumentException(
                        "爱发电接口错误（ec=" + ec + "，" + em + "）"));
                }
                var data = result.get("data");
                if (!(data instanceof Map<?, ?> dataMap)) {
                    return Flux.empty();
                }
                var list = dataMap.get("list");
                if (!(list instanceof List<?> items)) {
                    return Flux.empty();
                }
                return Flux.fromIterable(items)
                    .filter(item -> item instanceof Map)
                    .map(item -> (Map<String, Object>) item);
            });
    }

    /**
     * 校验爱发电配置是否可用。
     */
    public Mono<Boolean> ping() {
        return settingService.afdian()
            .flatMap(settings -> resolveToken(settings)
                .flatMap(token -> afdianClient.ping(settings.getUserId(), token)))
            .defaultIfEmpty(false);
    }

    private static String recordName(String tradeNo) {
        return "afdian-" + tradeNo;
    }

    private static String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static BigDecimal parseAmount(Object value) {
        var text = str(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final String QUERY_ORDER = "query-order";

    private static String escapeJson(String value) {
        var builder = new StringBuilder(value.length() + 8);
        for (var index = 0; index < value.length(); index++) {
            var ch = value.charAt(index);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }
}
