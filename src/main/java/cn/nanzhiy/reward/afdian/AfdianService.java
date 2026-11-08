package cn.nanzhiy.reward.afdian;

import cn.nanzhiy.reward.extension.RewardRecord;
import cn.nanzhiy.reward.service.RewardNotificationService;
import cn.nanzhiy.reward.service.RewardRecordService;
import cn.nanzhiy.reward.service.RewardSettingService;
import cn.nanzhiy.reward.support.InputSanitizer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
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

    /** 爱发电订单状态：2 表示已支付。 */
    private static final int ORDER_STATUS_PAID = 2;

    /** metadata.name 长度上限，前缀 "afdian-" 占用 7 个字符。 */
    private static final int MAX_NAME_SUFFIX_LENGTH = 56;

    /** 规整后仍要放得下哈希后缀。 */
    private static final int MAX_NORMALIZED_NAME_LENGTH = 40;

    private static final Pattern VALID_NAME_SUFFIX =
        Pattern.compile("[a-z0-9]([-a-z0-9.]*[a-z0-9])?");

    private static final Pattern NON_NAME_CHARS = Pattern.compile("[^a-z0-9]+");

    private static final Pattern LEADING_TRAILING_DASH = Pattern.compile("(^-+)|(-+$)");

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
                return new String(bytes, StandardCharsets.UTF_8);
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
        return handleOrder(order, OrderOptions.webhook());
    }

    /**
     * 单条订单的处理选项：回调路径需要反查订单并通知；手动同步复用整批 Token 与预拉的打赏者表，
     * 避免逐单读 Secret、逐单反查。
     */
    private record OrderOptions(boolean verify, boolean notifyAdmin, String token,
        Map<String, Sponsor> sponsors) {

        static OrderOptions webhook() {
            return new OrderOptions(true, true, null, null);
        }

        static OrderOptions sync(String token, Map<String, Sponsor> sponsors) {
            return new OrderOptions(false, false, token, sponsors);
        }
    }

    private Mono<RewardRecord> handleOrder(Map<String, Object> order, OrderOptions options) {
        var tradeNo = str(order.get("out_trade_no"));
        if (!StringUtils.hasText(tradeNo)) {
            log.warn("afdian order skipped: out_trade_no is blank");
            return Mono.empty();
        }
        return recordService.exists(recordName(tradeNo))
            .flatMap(exists -> {
                if (Boolean.TRUE.equals(exists)) {
                    return Mono.<RewardRecord>empty();
                }
                return settingService.afdian().flatMap(settings -> {
                    if (!settings.isEnable()) {
                        log.warn("afdian order skipped: afdian not enabled, tradeNo={}", tradeNo);
                        return Mono.<RewardRecord>empty();
                    }
                    var tokenMono = StringUtils.hasText(options.token())
                        ? Mono.just(options.token()) : resolveToken(settings);
                    return tokenMono
                        .switchIfEmpty(Mono.<String>defer(() -> {
                            log.warn("afdian order skipped: api token not configured, tradeNo={}",
                                tradeNo);
                            return Mono.empty();
                        }))
                        .flatMap(resolved -> (options.verify()
                                ? verifyOrder(tradeNo, settings.getUserId(), resolved)
                                : Mono.just(Map.<String, Object>of("order", order)))
                            .flatMap(verified ->
                                applySponsor(verified, settings, resolved, options.sponsors()))
                            .flatMap(data -> saveRecord(verifiedOrder(data),
                                resolveSponsorFromData(data), settings, options.notifyAdmin())));
                });
            });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verifiedOrder(Map<String, Object> data) {
        var order = data.get("order");
        return order instanceof Map<?, ?> ? (Map<String, Object>) order : Map.of();
    }

    /**
     * 用开放平台接口反查订单，确认单号一致、真实存在且支付成功。
     * 每个失败分支都要留日志：平台已收到成功响应不会重推，静默丢弃将无从排查。
     */
    private Mono<Map<String, Object>> verifyOrder(String tradeNo, String userId, String token) {
        var params = "{\"page\":1,\"out_trade_no\":\"" + escapeJson(tradeNo) + "\"}";
        return afdianClient.call("query-order", params, userId, token)
            .flatMap(result -> {
                if (AfdianClient.toInt(result.get("ec")) != 200) {
                    log.warn("afdian query-order rejected: {}, tradeNo={}",
                        result.get("em"), tradeNo);
                    return Mono.empty();
                }
                var order = findOrder(result, tradeNo);
                if (order == null) {
                    // 刚支付就回调时查询接口可能还没可见，这里不能静默丢弃，否则平台已收到成功响应不会重推。
                    log.warn("afdian order not found by out_trade_no: {}", tradeNo);
                    return Mono.empty();
                }
                if (AfdianClient.toInt(order.get("status")) != ORDER_STATUS_PAID) {
                    log.warn("afdian order not paid: {}, status={}", tradeNo, order.get("status"));
                    return Mono.<Map<String, Object>>empty();
                }
                return Mono.just(Map.<String, Object>of("order", order));
            });
    }

    /**
     * 按单号在返回列表里精确匹配，不直接取第一条：
     * 查询参数若被平台忽略或返回多条，取第一条会把别的订单入到当前单号名下。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findOrder(Map<String, Object> result, String tradeNo) {
        var data = result.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return null;
        }
        var list = dataMap.get("list");
        if (!(list instanceof List<?> items)) {
            return null;
        }
        for (var item : items) {
            if (item instanceof Map<?, ?> order
                && tradeNo.equals(str(order.get("out_trade_no")))) {
                return (Map<String, Object>) order;
            }
        }
        return null;
    }

    /**
     * 补全打赏者昵称与头像。查不到时保持为空，前台回退为匿名展示。
     */
    private Mono<Map<String, Object>> applySponsor(Map<String, Object> data,
        AfdianSettings settings, String token, Map<String, Sponsor> preloaded) {
        var order = verifiedOrder(data);
        var afdianUserId = str(order.get("user_id"));
        if (!StringUtils.hasText(afdianUserId)) {
            return Mono.just(data);
        }
        if (preloaded != null && preloaded.containsKey(afdianUserId)) {
            return Mono.just(withSponsor(data, preloaded.get(afdianUserId)));
        }
        return findSponsor(afdianUserId, settings.getUserId(), token, 1)
            .map(sponsor -> withSponsor(data, sponsor))
            .defaultIfEmpty(data);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> withSponsor(Map<String, Object> data, Sponsor sponsor) {
        var merged = new java.util.HashMap<>(data);
        merged.put("sponsor", sponsor);
        return (Map<String, Object>) merged;
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
        return afdianClient.call("query-sponsor", pageParams(page), devUserId, token)
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
        // 与批量预拉共用同一套字段解析，避免两处对 user_id/name/avatar 的口径漂移
        for (var entry : parseSponsorEntries(result)) {
            if (entry.getKey().equals(afdianUserId)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Mono<RewardRecord> saveRecord(Map<String, Object> order, Sponsor sponsor,
        AfdianSettings settings, boolean notify) {
        var tradeNo = str(order.get("out_trade_no"));
        var amount = parseAmount(order.get("total_amount"));
        if (amount == null) {
            log.warn("afdian order skipped: invalid total_amount={}, tradeNo={}",
                order.get("total_amount"), tradeNo);
            return Mono.empty();
        }
        var spec = new RewardRecord.Spec();
        spec.setAmount(amount);
        spec.setMethod("afdian");
        spec.setNickname(InputSanitizer.clean(sponsor.name(),
            InputSanitizer.MAX_NICKNAME_LENGTH));
        spec.setAvatar(InputSanitizer.safeImageUrl(sponsor.avatar()));
        spec.setMessage(InputSanitizer.clean(order.get("remark") == null ? null
            : String.valueOf(order.get("remark")), InputSanitizer.MAX_MESSAGE_LENGTH));
        spec.setTransactionNo(tradeNo);
        spec.setExternalId(tradeNo);
        spec.setRewardTime(resolveOrderTime(order));
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
                if (notify) {
                    notificationService.notifyNewRecord(saved, "afdian");
                }
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
                .flatMap(token -> loadSponsorMap(settings, token)
                    .flatMap(sponsors -> syncPages(settings, token, sponsors)));
        });
    }

    /**
     * 分页拉取已支付订单并逐条入库。单条订单失败只记日志，不中断整批。
     */
    private Mono<Integer> syncPages(AfdianSettings settings, String token,
        Map<String, Sponsor> sponsors) {
        var options = OrderOptions.sync(token, sponsors);
        return Flux.range(1, SYNC_MAX_PAGES)
            .concatMap(page -> fetchOrders(settings, token, page))
            .filter(order -> AfdianClient.toInt(order.get("status")) == ORDER_STATUS_PAID)
            .concatMap(order -> handleOrder(order, options)
                .onErrorResume(error -> {
                    log.warn("afdian order skipped: {}", error.getMessage());
                    return Mono.empty();
                }))
            .count()
            .map(Long::intValue);
    }

    @SuppressWarnings("unchecked")
    private Flux<Map<String, Object>> fetchOrders(AfdianSettings settings, String token, int page) {
        return afdianClient.call(QUERY_ORDER, pageParams(page), settings.getUserId(), token)
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
     * 手动同步前一次性拉取打赏者名单，避免逐单反查；只覆盖前两页，未命中的用户仍回退到 findSponsor。
     */
    private Mono<Map<String, Sponsor>> loadSponsorMap(AfdianSettings settings, String token) {
        return Flux.range(1, SPONSOR_MAX_PAGES)
            .concatMap(page -> fetchSponsorEntries(settings.getUserId(), token, page))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue)
            // 预拉结果写回缓存，后续回调命中时不必再查
            .doOnNext(sponsors -> sponsors.forEach(this::cacheSponsor))
            .onErrorResume(error -> {
                log.warn("afdian query-sponsor preload failed: {}", error.getMessage());
                return Mono.just(Map.of());
            });
    }

    private Flux<Map.Entry<String, Sponsor>> fetchSponsorEntries(String devUserId, String token,
        int page) {
        return afdianClient.call("query-sponsor", pageParams(page), devUserId, token)
            .flatMapMany(result -> Flux.fromIterable(parseSponsorEntries(result)));
    }

    private static String pageParams(int page) {
        return "{\"page\":" + page + "}";
    }

    private List<Map.Entry<String, Sponsor>> parseSponsorEntries(Map<String, Object> result) {
        var data = result.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return List.of();
        }
        var list = dataMap.get("list");
        if (!(list instanceof List<?> items)) {
            return List.of();
        }
        var entries = new java.util.ArrayList<Map.Entry<String, Sponsor>>(items.size());
        for (var item : items) {
            if (!(item instanceof Map<?, ?> sponsorItem)) {
                continue;
            }
            if (!(sponsorItem.get("user") instanceof Map<?, ?> userMap)) {
                continue;
            }
            var userId = str(userMap.get("user_id"));
            if (!StringUtils.hasText(userId)) {
                continue;
            }
            entries.add(Map.entry(userId,
                new Sponsor(str(userMap.get("name")), str(userMap.get("avatar")))));
        }
        return entries;
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

    /**
     * 爱发电官方文档的订单字段表未承诺返回时间字段，实测响应里存在秒级 create_time。
     * 有则用，取不到就回落到订单到达时刻；不要在缺少该字段时中断入库。
     */
    private static Instant resolveOrderTime(Map<String, Object> order) {
        var raw = order.get("create_time");
        if (raw == null) {
            return Instant.now();
        }
        try {
            var value = Long.parseLong(String.valueOf(raw).trim());
            // 0 与负数不是有效时间戳，直接换算会得到 1970 年并排到列表最末，必须回退。
            if (value <= 0) {
                return Instant.now();
            }
            // 10 位为秒、13 位为毫秒，按量级区分，避免整批订单时间偏差 1000 倍。
            return value > 100_000_000_000L
                ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        } catch (NumberFormatException e) {
            return Instant.now();
        }
    }

    private static String recordName(String tradeNo) {
        var prefix = "afdian-";
        // 常规单号保持原样，避免历史记录因改名而重复入库
        if (isValidNameSuffix(tradeNo)) {
            return prefix + tradeNo;
        }
        // 非法字符或超长时规整后附短哈希：规整结果可能撞名，哈希保证一一对应
        var normalized = NON_NAME_CHARS.matcher(tradeNo.toLowerCase(Locale.ROOT))
            .replaceAll("-");
        normalized = LEADING_TRAILING_DASH.matcher(normalized).replaceAll("");
        if (normalized.length() > MAX_NORMALIZED_NAME_LENGTH) {
            normalized = normalized.substring(0, MAX_NORMALIZED_NAME_LENGTH);
        }
        var hash = sha256Hex(tradeNo).substring(0, 8);
        return prefix + (normalized.isEmpty() ? "" : normalized + "-") + hash;
    }

    /**
     * metadata.name 只允许小写字母、数字、'-' 与 '.'，且首尾必须是字母数字。
     */
    private static boolean isValidNameSuffix(String tradeNo) {
        return tradeNo != null
            && tradeNo.length() <= MAX_NAME_SUFFIX_LENGTH
            && VALID_NAME_SUFFIX.matcher(tradeNo).matches();
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("哈希计算失败", e);
        }
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
