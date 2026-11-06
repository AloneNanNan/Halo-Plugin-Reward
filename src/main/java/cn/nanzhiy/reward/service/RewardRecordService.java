package cn.nanzhiy.reward.service;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.empty;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.or;

import cn.nanzhiy.reward.extension.RewardRecord;
import cn.nanzhiy.reward.vo.RewardStatsVo;
import cn.nanzhiy.reward.vo.WallItem;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 打赏记录读写。
 */
@Service
@RequiredArgsConstructor
public class RewardRecordService {

    /**
     * 排序依赖 spec.rewardTime 的索引，索引值由 {@link RewardRecord#resolveRewardTime} 计算，
     * 因此未填打赏时间的记录会按入库时间参与排序，不会因为空值落到不确定的位置。
     */
    private static final Sort DEFAULT_SORT =
        Sort.by(Sort.Order.desc("spec.rewardTime"));

    private static final DateTimeFormatter DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private static final Duration STATS_TTL = Duration.ofSeconds(60);

    private static final int MAX_KEYWORD_LENGTH = 100;

    private final ReactiveExtensionClient client;

    private final AtomicReference<CachedStats> statsCache = new AtomicReference<>();

    private record CachedStats(RewardStatsVo stats, Instant cachedAt) {
    }

    /**
     * 统计结果有 60 秒缓存，写操作后立即失效：改完打赏时间还需要等一分钟才看到新数字，
     * 容易被当成没保存成功。
     */
    private void invalidateStats() {
        statsCache.set(null);
    }

    /**
     * 后台分页查询，status 为空表示全部。
     */
    public Mono<ListResult<RewardRecord>> list(String status, String keyword, int page, int size) {
        var statusCondition = status == null || status.isBlank()
            ? empty() : equal("spec.status", status);
        var safeKeyword = keyword == null ? "" : keyword.trim();
        if (safeKeyword.length() > MAX_KEYWORD_LENGTH) {
            safeKeyword = safeKeyword.substring(0, MAX_KEYWORD_LENGTH);
        }
        var keywordCondition = safeKeyword.isEmpty()
            ? empty()
            : or(
                contains("spec.nickname", safeKeyword),
                contains("spec.message", safeKeyword),
                contains("spec.transactionNo", safeKeyword)
            );
        var options = ListOptions.builder()
            .fieldQuery(and(statusCondition, keywordCondition))
            .build();
        return client.listBy(RewardRecord.class, options,
            PageRequestImpl.of(page, size, DEFAULT_SORT));
    }

    /**
     * 赞助者墙分页查询：仅已确认的记录。
     */
    public Mono<ListResult<RewardRecord>> listWall(int page, int size) {
        var options = ListOptions.builder()
            .fieldQuery(equal("spec.status", RewardRecord.STATUS_CONFIRMED))
            .build();
        return client.listBy(RewardRecord.class, options,
            PageRequestImpl.of(page, size, DEFAULT_SORT));
    }

    public Mono<RewardRecord> create(RewardRecord record) {
        if (record.getMetadata() == null) {
            var metadata = new Metadata();
            metadata.setName("reward-" + UUID.randomUUID());
            record.setMetadata(metadata);
        }
        return client.create(record)
            .doOnNext(saved -> invalidateStats());
    }

    /**
     * 更新记录中可由站长编辑的字段。仅覆盖请求中出现的字段，避免部分更新清空其它内容。
     * 打赏时间传 null 一律视为不修改，本方法不提供「清空回退」语义。
     */
    public Mono<RewardRecord> updateSpec(String name, RewardRecord.Spec patch) {
        return client.fetch(RewardRecord.class, name)
            .flatMap(record -> {
                var spec = record.getSpec();
                if (spec == null) {
                    spec = new RewardRecord.Spec();
                    record.setSpec(spec);
                }
                if (patch.getAmount() != null) {
                    spec.setAmount(patch.getAmount());
                }
                if (patch.getNickname() != null) {
                    spec.setNickname(patch.getNickname());
                }
                if (patch.getMessage() != null) {
                    spec.setMessage(patch.getMessage());
                }
                if (patch.getContact() != null) {
                    spec.setContact(patch.getContact());
                }
                if (patch.getTransactionNo() != null) {
                    spec.setTransactionNo(patch.getTransactionNo());
                }
                if (patch.getAvatar() != null) {
                    spec.setAvatar(patch.getAvatar());
                }
                if (patch.getRewardTime() != null) {
                    spec.setRewardTime(patch.getRewardTime());
                }
                if (patch.getMethod() != null) {
                    spec.setMethod(patch.getMethod());
                }
                if (patch.getStatus() != null) {
                    spec.setStatus(patch.getStatus());
                }
                return client.update(record)
                    .doOnNext(updated -> invalidateStats());
            });
    }

    public Mono<Boolean> exists(String name) {
        return client.fetch(RewardRecord.class, name).hasElement();
    }

    public Mono<RewardRecord> updateStatus(String name, String status) {
        return client.fetch(RewardRecord.class, name)
            .flatMap(record -> {
                var spec = record.getSpec();
                if (spec == null) {
                    spec = new RewardRecord.Spec();
                    record.setSpec(spec);
                }
                spec.setStatus(status);
                return client.update(record)
                    .doOnNext(updated -> invalidateStats());
            });
    }

    public Mono<RewardRecord> delete(String name) {
        return client.fetch(RewardRecord.class, name)
            .flatMap(client::delete)
            .doOnNext(deleted -> invalidateStats());
    }

    public Flux<RewardRecord> listAll() {
        return client.listAll(RewardRecord.class, ListOptions.builder().build(), DEFAULT_SORT);
    }

    public Mono<Long> countByStatus(String status) {
        return client.countBy(RewardRecord.class, ListOptions.builder()
            .fieldQuery(equal("spec.status", status))
            .build());
    }

    /**
     * 汇总已确认记录的金额与数量。统计需要遍历全部记录，因此结果缓存 60 秒，
     * 避免公开接口被高频请求时反复全表扫描。
     */
    public Mono<RewardStatsVo> stats() {
        var cached = statsCache.get();
        if (cached != null
            && Duration.between(cached.cachedAt(), Instant.now()).compareTo(STATS_TTL) < 0) {
            return Mono.just(cached.stats());
        }
        var options = ListOptions.builder()
            .fieldQuery(equal("spec.status", RewardRecord.STATUS_CONFIRMED))
            .build();
        return client.listAll(RewardRecord.class, options, Sort.unsorted())
            .collectList()
            .map(this::toStats)
            .doOnNext(stats -> statsCache.set(new CachedStats(stats, Instant.now())));
    }

    private RewardStatsVo toStats(List<RewardRecord> records) {
        var stats = new RewardStatsVo();
        var monthStart = YearMonth.now()
            .atDay(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant();
        var total = BigDecimal.ZERO;
        var month = BigDecimal.ZERO;
        var monthCount = 0;
        for (var record : records) {
            var spec = record.getSpec();
            if (spec == null || spec.getAmount() == null) {
                continue;
            }
            total = total.add(spec.getAmount());
            // 本月口径按打赏时间：补录历史记录时不应把金额算进当月。
            var rewardTime = RewardRecord.resolveRewardTime(record);
            if (rewardTime != null && !rewardTime.isBefore(monthStart)) {
                month = month.add(spec.getAmount());
                monthCount++;
            }
        }
        stats.setTotalCount(records.size());
        stats.setTotalAmount(total);
        stats.setMonthCount(monthCount);
        stats.setMonthAmount(month);
        return stats;
    }

    /**
     * 转换为前台可见条目：不输出联系方式、交易单号等隐私字段，头像只放行 http(s) 或站内路径。
     */
    public List<WallItem> toWallItems(List<RewardRecord> records, boolean showAmount) {
        var items = new ArrayList<WallItem>(records.size());
        for (var record : records) {
            var spec = record.getSpec();
            if (spec == null) {
                continue;
            }
            var nickname = displayNickname(spec.getNickname());
            var item = new WallItem();
            item.setNickname(nickname);
            item.setInitial(initial(nickname));
            item.setAmount(showAmount ? spec.getAmount() : null);
            item.setAvatar(safeImageUrl(spec.getAvatar()));
            item.setMessage(StringUtils.hasText(spec.getMessage()) ? spec.getMessage() : null);
            var rewardTime = RewardRecord.resolveRewardTime(record);
            if (rewardTime != null) {
                item.setCreatedAt(DATE_FORMATTER.format(rewardTime));
            }
            items.add(item);
        }
        return items;
    }

    private String displayNickname(String nickname) {
        return StringUtils.hasText(nickname) ? nickname.trim() : "匿名读者";
    }

    private static String initial(String nickname) {
        if (nickname.isEmpty()) {
            return "匿";
        }
        return new String(Character.toChars(nickname.codePointAt(0)));
    }

    private static String safeImageUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        var trimmed = url.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")
            || trimmed.startsWith("/")) {
            return trimmed;
        }
        return null;
    }
}
