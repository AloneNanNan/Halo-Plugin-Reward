package cn.nanzhiy.reward.endpoint;

import cn.nanzhiy.reward.extension.RewardRecord;
import cn.nanzhiy.reward.service.RewardRecordService;
import cn.nanzhiy.reward.support.InputSanitizer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.plugin.ApiVersion;

/**
 * 后台管理接口：记录列表、状态变更、删除与导出。
 */
@ApiVersion("console.api." + RewardRecord.GROUP + "/v1alpha1")
@RestController
@RequiredArgsConstructor
@RequestMapping("/records")
public class RewardConsoleEndpoint {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final Set<String> ALLOWED_STATUS = Set.of(
        RewardRecord.STATUS_PENDING,
        RewardRecord.STATUS_CONFIRMED,
        RewardRecord.STATUS_REJECTED
    );

    private static final Set<String> ALLOWED_METHOD = Set.of("wechat", "alipay", "afdian");

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    private static final int MAX_BATCH_SIZE = 200;

    /**
     * 导出上限。超过这个量级时让站长先按状态或关键词筛选，
     * 避免一次性把全表读进内存拼 CSV。
     */
    private static final int MAX_EXPORT_ROWS = 20000;

    /** 浏览器与服务端时钟可能有偏差，留出少量容差，避免刚选「此刻」就被判为未来时间。 */
    private static final Duration REWARD_TIME_TOLERANCE = Duration.ofMinutes(5);

    /** 以这些字符开头的单元格会被表格软件当作公式执行。 */
    private static final String FORMULA_PREFIXES = "=+-@\t\r";

    private final RewardRecordService recordService;

    @GetMapping
    public Mono<ListResult<RewardRecord>> list(
        @RequestParam(name = "page", defaultValue = "1") int page,
        @RequestParam(name = "size", defaultValue = "20") int size,
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "keyword", required = false) String keyword) {
        return recordService.list(status, keyword, Math.max(page, 1),
            Math.min(Math.max(size, 1), 100));
    }

    @PostMapping
    public Mono<RewardRecord> create(@RequestBody RewardRecord.Spec spec) {
        validateRequiredFields(spec);
        validateCommonFields(spec);
        sanitizeSpec(spec);
        validateImageUrl(spec.getAvatar());
        // 状态统一存大写，赞助者墙按 spec.status = CONFIRMED 精确匹配
        var status = normalizeStatus(spec.getStatus());
        spec.setStatus(status.isEmpty() ? RewardRecord.STATUS_PENDING : status);
        spec.setMethod(normalizeMethod(spec.getMethod()));
        var rewardTime = spec.getRewardTime() == null ? Instant.now() : spec.getRewardTime();
        var timeError = rewardTimeError(rewardTime);
        if (timeError != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, timeError);
        }
        spec.setRewardTime(rewardTime);
        spec.setSource("manual");
        var record = new RewardRecord();
        record.setSpec(spec);
        return recordService.create(record);
    }

    @PutMapping("/batch-status")
    public Mono<Map<String, Object>> batchStatus(@RequestBody BatchStatusParam param) {
        var status = requireStatus(param.getStatus());
        var names = requireNames(param.getNames());
        return Flux.fromIterable(names)
            .concatMap(name -> recordService.updateStatus(name, status))
            .count()
            .map(count -> Map.of("updated", count));
    }

    /**
     * 批量删除走 PUT 而不是 POST：带 name 段的 POST 会被 Halo 按非资源请求处理，
     * 资源型权限规则对它不生效。机制说明见 extensions/role-templates.yaml。
     */
    @PutMapping("/batch-delete")
    public Mono<Map<String, Object>> batchDelete(@RequestBody BatchDeleteParam param) {
        var names = requireNames(param.getNames());
        return Flux.fromIterable(names)
            .concatMap(recordService::delete)
            .count()
            .map(count -> Map.of("deleted", count));
    }

    @PutMapping("/{name}")
    public Mono<RewardRecord> update(@PathVariable("name") String name,
        @RequestBody RewardRecord.Spec spec) {
        validateCommonFields(spec);
        sanitizeSpec(spec);
        validateImageUrl(spec.getAvatar());
        if (spec.getRewardTime() != null) {
            var timeError = rewardTimeError(spec.getRewardTime());
            if (timeError != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, timeError);
            }
        }
        return recordService.updateSpec(name, spec);
    }

    @PutMapping("/{name}/status")
    public Mono<RewardRecord> updateStatus(@PathVariable("name") String name,
        @RequestBody StatusParam param) {
        var status = requireStatus(param.getStatus());
        return recordService.updateStatus(name, status);
    }

    @DeleteMapping("/{name}")
    public Mono<Void> delete(@PathVariable("name") String name) {
        return recordService.delete(name)
            .switchIfEmpty(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在")))
            .then();
    }

    /**
     * 导出 CSV。站长通常已经在列表里筛过一遍，这里复用同一组筛选条件，避免导出无关数据。
     */
    @GetMapping("/export")
    public Mono<Void> export(@RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "keyword", required = false) String keyword,
        ServerHttpResponse response) {
        var normalizedStatus = normalizeStatus(status);
        if (!normalizedStatus.isEmpty() && !ALLOWED_STATUS.contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的状态");
        }
        return recordService.listAll(normalizedStatus, keyword)
            .take(MAX_EXPORT_ROWS + 1L)
            .collectList()
            .flatMap(records -> {
                if (records.size() > MAX_EXPORT_ROWS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "记录数量超过导出上限（" + MAX_EXPORT_ROWS + " 条），"
                            + "请先按状态或关键词筛选后再导出");
                }
                var bytes = buildCsv(records).getBytes(StandardCharsets.UTF_8);
                response.getHeaders().setContentType(
                    MediaType.parseMediaType("text/csv;charset=UTF-8"));
                response.getHeaders().setContentDisposition(
                    ContentDisposition.attachment()
                        .filename("reward-records.csv", StandardCharsets.UTF_8)
                        .build());
                return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
            });
    }

    /**
     * 拼 CSV。表头带 BOM，Excel 打开中文不乱码。
     */
    private static String buildCsv(List<RewardRecord> records) {
        var builder = new StringBuilder("\uFEFF");
        builder.append("打赏时间,昵称,金额,支付方式,留言,联系方式,交易单号,状态\n");
        for (var record : records) {
            var spec = record.getSpec();
            if (spec == null) {
                continue;
            }
            builder.append(escape(RewardRecord.resolveRewardTime(record)))
                .append(',')
                .append(escape(spec.getNickname())).append(',')
                .append(spec.getAmount() == null ? ""
                    : spec.getAmount().toPlainString()).append(',')
                .append(escape(spec.getMethod())).append(',')
                .append(escape(spec.getMessage())).append(',')
                .append(escape(spec.getContact())).append(',')
                .append(escape(spec.getTransactionNo())).append(',')
                .append(escape(spec.getStatus()))
                .append('\n');
        }
        return builder.toString();
    }

    /**
     * 去除控制字符。空串保持原样：后台允许把某个字段清空，
     * 而 null 在编辑接口里表示「不修改」。
     */
    private static void sanitizeSpec(RewardRecord.Spec spec) {
        if (spec == null) {
            return;
        }
        spec.setNickname(cleanIfPresent(spec.getNickname()));
        spec.setMessage(cleanIfPresent(spec.getMessage()));
        spec.setContact(cleanIfPresent(spec.getContact()));
        spec.setTransactionNo(cleanIfPresent(spec.getTransactionNo()));
        spec.setAvatar(cleanIfPresent(spec.getAvatar()));
    }

    private static String cleanIfPresent(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return InputSanitizer.stripControlChars(value);
    }

    /**
     * 新增时的必填项校验。
     */
    private static void validateRequiredFields(RewardRecord.Spec spec) {
        if (spec == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求内容不能为空");
        }
        if (spec.getAmount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额不能为空");
        }
        if (spec.getNickname() == null || spec.getNickname().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "昵称不能为空");
        }
    }

    /**
     * 新增与编辑共用的字段校验。长度按调用方传入的原始值判断：
     * 清洗只去控制字符、不截断，超长必须报错而不是被悄悄截掉。
     */
    private static void validateCommonFields(RewardRecord.Spec spec) {
        if (spec == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求内容不能为空");
        }
        if (spec.getAmount() != null
            && (spec.getAmount().compareTo(BigDecimal.ZERO) <= 0
            || spec.getAmount().compareTo(MAX_AMOUNT) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "金额必须大于 0 且不超过 1000000");
        }
        checkLength(spec.getNickname(), InputSanitizer.MAX_NICKNAME_LENGTH, "昵称");
        checkLength(spec.getMessage(), InputSanitizer.MAX_MESSAGE_LENGTH, "留言");
        checkLength(spec.getContact(), InputSanitizer.MAX_CONTACT_LENGTH, "联系方式");
        checkLength(spec.getTransactionNo(), InputSanitizer.MAX_TRANSACTION_NO_LENGTH, "交易单号");
        checkLength(spec.getAvatar(), InputSanitizer.MAX_AVATAR_LENGTH, "头像地址");
        if (spec.getMethod() != null && !spec.getMethod().isBlank()
            && !ALLOWED_METHOD.contains(spec.getMethod().toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的支付方式");
        }
        if (spec.getStatus() != null && !spec.getStatus().isBlank()
            && !ALLOWED_STATUS.contains(spec.getStatus().toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的状态");
        }
    }

    private static void checkLength(String value, int maxLength, String label) {
        if (value != null && value.trim().length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                label + "长度不能超过 " + maxLength);
        }
    }

    /**
     * 图片地址白名单。放在清洗之后校验：控制字符可能把非法协议拆开，
     * 例如 java\nscript:，清洗掉换行后才会暴露出来。
     */
    private static void validateImageUrl(String avatar) {
        if (avatar != null && !avatar.isBlank()
            && InputSanitizer.safeImageUrl(avatar) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "头像地址不合法，仅支持 http(s) 或站内路径");
        }
    }

    private static String requireStatus(String status) {
        var normalized = normalizeStatus(status);
        if (!ALLOWED_STATUS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的状态");
        }
        return normalized;
    }

    private static List<String> requireNames(List<String> names) {
        var resolved = names == null ? List.<String>of() : names;
        if (resolved.size() > MAX_BATCH_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "单次最多处理 " + MAX_BATCH_SIZE + " 条");
        }
        return resolved;
    }

    private static String escape(Object value) {
        if (value == null) {
            return "";
        }
        var text = value instanceof java.time.Instant instant
            ? DATE_TIME_FORMATTER.format(instant)
            : value.toString();
        if (!text.isEmpty() && FORMULA_PREFIXES.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n")
            || text.contains("\r")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    /**
     * 只拦未来时间。历史补录需要能把时间往前填到任意早，因此不设下限。
     */
    private static String rewardTimeError(Instant rewardTime) {
        if (rewardTime.isAfter(Instant.now().plus(REWARD_TIME_TOLERANCE))) {
            return "打赏时间不能晚于当前时间";
        }
        return null;
    }

    private static String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            return "wechat";
        }
        return method.trim().toLowerCase(Locale.ROOT);
    }

    @Data
    public static class BatchStatusParam {
        private List<String> names = List.of();
        private String status;
    }

    @Data
    public static class BatchDeleteParam {
        private List<String> names = List.of();
    }

    @Data
    public static class StatusParam {
        private String status;
    }
}
