package cn.nanzhiy.reward.endpoint;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import cn.nanzhiy.reward.config.RecordSettings;
import cn.nanzhiy.reward.extension.RewardRecord;
import cn.nanzhiy.reward.service.RewardNotificationService;
import cn.nanzhiy.reward.service.RewardRecordService;
import cn.nanzhiy.reward.service.RewardSettingService;
import cn.nanzhiy.reward.support.RateLimiter;
import cn.nanzhiy.reward.support.RewardCaptchaService;
import cn.nanzhiy.reward.vo.RewardConfigVo;
import cn.nanzhiy.reward.vo.SubmitParam;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.infra.AnonymousUserConst;

/**
 * 前台使用的公开接口。仅暴露打赏页所需的只读配置、赞助者墙与记录提交能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RewardPublicEndpoint implements CustomEndpoint {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    private static final int MAX_NICKNAME_LENGTH = 30;

    private static final int MAX_MESSAGE_LENGTH = 200;

    private static final int MAX_CONTACT_LENGTH = 100;

    private static final int MAX_TRANSACTION_NO_LENGTH = 64;

    private static final int MAX_AVATAR_LENGTH = 500;

    private static final int MAX_WALL_SIZE = 50;

    private static final int SUBMIT_MAX_REQUESTS = 5;

    private static final Duration SUBMIT_WINDOW = Duration.ofMinutes(10);

    private static final int CAPTCHA_MAX_REQUESTS = 10;

    private static final Duration CAPTCHA_WINDOW = Duration.ofMinutes(1);

    private static final int STATS_MAX_REQUESTS = 60;

    private static final Duration STATS_WINDOW = Duration.ofMinutes(1);

    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private final RewardRecordService recordService;

    private final RewardSettingService settingService;

    private final RateLimiter rateLimiter;

    private final RewardCaptchaService captchaService;

    private final RewardNotificationService notificationService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("/public/config", this::getConfig)
            .GET("/public/wall", this::listWall)
            .GET("/public/stats", this::getStats)
            .GET("/public/captcha", this::getCaptcha)
            .POST("/records", this::submit)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api." + RewardRecord.GROUP, "v1alpha1");
    }

    private Mono<ServerResponse> getCaptcha(ServerRequest request) {
        var ip = request.remoteAddress()
            .map(address -> address.getAddress().getHostAddress())
            .orElse("unknown");
        if (!rateLimiter.tryAcquire("captcha:" + ip, CAPTCHA_MAX_REQUESTS, CAPTCHA_WINDOW)) {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .bodyValue(Map.of("message", "验证码获取过于频繁，请稍后再试"));
        }
        return captchaService.generate()
            .flatMap(captcha -> ServerResponse.ok().bodyValue(captcha))
            .onErrorResume(error -> {
                log.error("generate captcha failed", error);
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .bodyValue(Map.of("message", "验证码生成失败，请稍后再试"));
            });
    }

    private Mono<ServerResponse> getStats(ServerRequest request) {
        var ip = request.remoteAddress()
            .map(address -> address.getAddress().getHostAddress())
            .orElse("unknown");
        if (!rateLimiter.tryAcquire("stats:" + ip, STATS_MAX_REQUESTS, STATS_WINDOW)) {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .bodyValue(Map.of("message", "请求过于频繁，请稍后再试"));
        }
        return recordService.stats()
            .flatMap(stats -> ServerResponse.ok().bodyValue(stats));
    }

    private Mono<ServerResponse> getConfig(ServerRequest request) {
        return settingService.all().flatMap(settings -> {
            if (!settings.basic().isEnable()) {
                return ServerResponse.status(HttpStatus.NOT_FOUND)
                    .bodyValue(Map.of("message", "打赏页面未启用"));
            }
            var config = RewardConfigVo.from(settings.basic(), settings.payment(),
                settings.records());
            return isLoggedIn().flatMap(loggedIn -> {
                var body = config.toMap();
                body.put("loggedIn", loggedIn);
                return ServerResponse.ok().bodyValue(body);
            });
        });
    }

    private Mono<ServerResponse> listWall(ServerRequest request) {
        var page = Math.max(parseInt(request.queryParam("page").orElse("1"), 1), 1);
        return settingService.records().flatMap(records -> {
            var size = request.queryParam("size")
                .map(value -> parseInt(value, records.getWallPageSize()))
                .orElse(records.getWallPageSize());
            var safeSize = Math.min(Math.max(size, 1), MAX_WALL_SIZE);
            if (!records.isWallEnabled()) {
                return ServerResponse.ok().bodyValue(Map.of(
                    "items", java.util.List.of(), "page", page,
                    "size", safeSize, "total", 0));
            }
            return recordService.listWall(page, safeSize).map(result -> {
                var body = new java.util.LinkedHashMap<String, Object>();
                body.put("items",
                    recordService.toWallItems(result.getItems(), records.isShowAmountOnWall()));
                body.put("page", result.getPage());
                body.put("size", result.getSize());
                body.put("total", result.getTotal());
                return body;
            }).flatMap(body -> ServerResponse.ok().bodyValue(body));
        });
    }

    private Mono<ServerResponse> submit(ServerRequest request) {
        var ip = request.remoteAddress()
            .map(address -> address.getAddress().getHostAddress())
            .orElse("unknown");
        if (!rateLimiter.tryAcquire("submit:" + ip, SUBMIT_MAX_REQUESTS, SUBMIT_WINDOW)) {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .bodyValue(Map.of("message", "提交过于频繁，请稍后再试"));
        }
        return request.bodyToMono(SubmitParam.class)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("请求内容不能为空")))
            .flatMap(param -> settingService.all().flatMap(settings -> {
                var recordSettings = settings.records();
                var basic = settings.basic();
                if (!basic.isEnable()) {
                    throw new IllegalArgumentException("打赏页面未启用");
                }
                if (!recordSettings.isEnableRecordSubmit()) {
                    throw new IllegalArgumentException("当前未开放打赏记录提交");
                }
                if (recordSettings.isEnableCaptcha()
                    && !captchaService.verify(param.getCaptchaId(), param.getCaptchaCode())) {
                    throw new IllegalArgumentException("验证码错误或已过期");
                }
                return isLoggedIn().flatMap(loggedIn -> {
                    if (recordSettings.isRequireLogin() && !loggedIn) {
                        throw new IllegalArgumentException("请先登录后再提交打赏记录");
                    }
                    var record = buildRecord(param, recordSettings);
                    return recordService.create(record)
                        .doOnNext(saved -> notificationService.notifyNewRecord(saved, "page"))
                        .flatMap(saved -> ServerResponse.ok().bodyValue(Map.of(
                            "message", recordSettings.isAutoApprove()
                                ? "感谢你的支持" : "已提交，等待站长确认后展示",
                            "status", saved.getSpec().getStatus())));
                });
            }))
            .onErrorResume(IllegalArgumentException.class,
                e -> ServerResponse.status(HttpStatus.BAD_REQUEST)
                    .bodyValue(Map.of("message", e.getMessage())))
            .onErrorResume(error -> {
                log.error("submit reward record failed", error);
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .bodyValue(Map.of("message", "提交失败，请稍后再试"));
            });
    }

    private RewardRecord buildRecord(SubmitParam param, RecordSettings settings) {
        var amount = param.getAmount();
        if (amount == null) {
            throw new IllegalArgumentException("请填写打赏金额");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("打赏金额不合法");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("打赏金额最多保留两位小数");
        }
        var spec = new RewardRecord.Spec();
        spec.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        spec.setMethod("alipay".equalsIgnoreCase(param.getMethod()) ? "alipay" : "wechat");

        var nickname = clean(param.getNickname(), MAX_NICKNAME_LENGTH);
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("请填写昵称");
        }
        spec.setNickname(nickname);
        spec.setMessage(
            settings.isAllowMessage() ? clean(param.getMessage(), MAX_MESSAGE_LENGTH) : null);
        spec.setContact(clean(param.getContact(), MAX_CONTACT_LENGTH));

        var transactionNo = clean(param.getTransactionNo(), MAX_TRANSACTION_NO_LENGTH);
        if (settings.isEnableTransactionNo() && (transactionNo == null || transactionNo.isBlank())) {
            throw new IllegalArgumentException("请填写交易单号");
        }
        spec.setTransactionNo(settings.isEnableTransactionNo() ? transactionNo : null);
        spec.setAvatar(
            settings.isEnableAvatar() ? clean(param.getAvatar(), MAX_AVATAR_LENGTH) : null);
        spec.setStatus(settings.isAutoApprove()
            ? RewardRecord.STATUS_CONFIRMED : RewardRecord.STATUS_PENDING);
        // 访客不提供时间，避免把打赏时间伪造成任意历史日期。
        spec.setRewardTime(Instant.now());
        spec.setSource("page");

        var record = new RewardRecord();
        record.setSpec(spec);
        return record;
    }

    /**
     * 去除控制字符并限制长度，防止异常内容进入存储与页面。
     */
    private String clean(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        var cleaned = CONTROL_CHARS.matcher(value).replaceAll("").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    private Mono<Boolean> isLoggedIn() {
        return ReactiveSecurityContextHolder.getContext()
            .map(context -> context.getAuthentication())
            .map(authentication -> authentication != null
                && authentication.isAuthenticated()
                && !AnonymousUserConst.PRINCIPAL.equals(authentication.getName()))
            .defaultIfEmpty(false);
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
