package cn.nanzhiy.reward.endpoint;

import cn.nanzhiy.reward.extension.RewardRecord;
import cn.nanzhiy.reward.service.RewardRecordService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    private static final int MAX_BATCH_SIZE = 200;

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
        if (spec.getAmount() == null || spec.getAmount().compareTo(BigDecimal.ZERO) <= 0
            || spec.getAmount().compareTo(MAX_AMOUNT) > 0) {
            return Mono.error(new IllegalArgumentException("金额必须大于 0 且不超过 1000000"));
        }
        if (spec.getNickname() == null || spec.getNickname().isBlank()) {
            return Mono.error(new IllegalArgumentException("昵称不能为空"));
        }
        if (spec.getStatus() == null || !ALLOWED_STATUS.contains(spec.getStatus())) {
            spec.setStatus(RewardRecord.STATUS_PENDING);
        }
        if (spec.getMethod() == null || spec.getMethod().isBlank()) {
            spec.setMethod("wechat");
        }
        spec.setSource("manual");
        var record = new RewardRecord();
        record.setSpec(spec);
        return recordService.create(record);
    }

    @PutMapping("/batch-status")
    public Mono<Map<String, Object>> batchStatus(@RequestBody BatchStatusParam param) {
        var status = normalizeStatus(param.getStatus());
        if (!ALLOWED_STATUS.contains(status)) {
            return Mono.error(new IllegalArgumentException("不支持的状态"));
        }
        var names = param.getNames() == null ? List.<String>of() : param.getNames();
        if (names.isEmpty()) {
            return Mono.just(Map.of("updated", 0));
        }
        if (names.size() > MAX_BATCH_SIZE) {
            return Mono.error(new IllegalArgumentException("单次最多处理 " + MAX_BATCH_SIZE + " 条"));
        }
        return Flux.fromIterable(names)
            .concatMap(name -> recordService.updateStatus(name, status))
            .count()
            .map(count -> Map.of("updated", count));
    }

    @PostMapping("/batch-delete")
    public Mono<Map<String, Object>> batchDelete(@RequestBody BatchDeleteParam param) {
        var names = param.getNames() == null ? List.<String>of() : param.getNames();
        if (names.isEmpty()) {
            return Mono.just(Map.of("deleted", 0));
        }
        if (names.size() > MAX_BATCH_SIZE) {
            return Mono.error(new IllegalArgumentException("单次最多处理 " + MAX_BATCH_SIZE + " 条"));
        }
        return Flux.fromIterable(names)
            .concatMap(recordService::delete)
            .count()
            .map(count -> Map.of("deleted", count));
    }

    @PutMapping("/{name}")
    public Mono<RewardRecord> update(@PathVariable("name") String name,
        @RequestBody RewardRecord.Spec spec) {
        if (spec.getAmount() != null && (spec.getAmount().compareTo(BigDecimal.ZERO) <= 0
            || spec.getAmount().compareTo(MAX_AMOUNT) > 0)) {
            return Mono.error(new IllegalArgumentException("金额必须大于 0 且不超过 1000000"));
        }
        if (spec.getStatus() != null && !ALLOWED_STATUS.contains(spec.getStatus())) {
            return Mono.error(new IllegalArgumentException("不支持的状态"));
        }
        return recordService.updateSpec(name, spec);
    }

    @PutMapping("/{name}/status")
    public Mono<RewardRecord> updateStatus(@PathVariable("name") String name,
        @RequestBody StatusParam param) {
        var status = normalizeStatus(param.getStatus());
        if (!ALLOWED_STATUS.contains(status)) {
            return Mono.error(new IllegalArgumentException("不支持的状态"));
        }
        return recordService.updateStatus(name, status);
    }

    @DeleteMapping("/{name}")
    public Mono<Void> delete(@PathVariable("name") String name) {
        return recordService.delete(name)
            .switchIfEmpty(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在")))
            .then();
    }

    @GetMapping("/export")
    public Mono<Void> export(ServerHttpResponse response) {
        return recordService.listAll().collectList().flatMap(records -> {
            var builder = new StringBuilder("\uFEFF");
            builder.append("提交时间,昵称,金额,支付方式,留言,联系方式,交易单号,状态\n");
            for (var record : records) {
                var spec = record.getSpec();
                if (spec == null) {
                    continue;
                }
                builder.append(escape(record.getMetadata() == null
                        ? null : record.getMetadata().getCreationTimestamp()))
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
            var bytes = builder.toString().getBytes(StandardCharsets.UTF_8);
            response.getHeaders().setContentType(
                MediaType.parseMediaType("text/csv;charset=UTF-8"));
            response.getHeaders().setContentDisposition(
                ContentDisposition.attachment()
                    .filename("reward-records.csv", StandardCharsets.UTF_8)
                    .build());
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        });
    }

    private String escape(Object value) {
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

    private static String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase();
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
