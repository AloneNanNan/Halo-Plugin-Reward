package cn.nanzhiy.reward.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 内存滑动窗口限流，供前台提交、验证码获取与爱发电回调共用。
 *
 * <p>窗口与配额由调用方按场景传入，因此同一个实例可以服务多个接口。键数量达到上限时先惰性清理
 * 超过最长窗口未访问的条目，仍超出时拒绝新键，避免被刷时内存无界增长。
 */
@Component
public class RateLimiter {

    /** 清理阈值使用的最长窗口，调用方传入的窗口都不应超过该值。 */
    private static final Duration MAX_WINDOW = Duration.ofMinutes(10);

    private static final int EVICT_THRESHOLD = 2048;

    private static final int MAX_TRACKED_KEYS = 16384;

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /**
     * 尝试获取一次许可。
     *
     * @param key         限流维度，通常是「场景:来源 IP」
     * @param maxRequests 窗口内允许的最大次数
     * @param window      统计窗口
     * @return true 表示放行
     */
    public boolean tryAcquire(String key, int maxRequests, Duration window) {
        var now = Instant.now();
        evictIfNecessary(now);
        if (!hits.containsKey(key) && hits.size() >= MAX_TRACKED_KEYS) {
            return false;
        }
        var records = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (records) {
            while (!records.isEmpty()
                && Duration.between(records.peekFirst(), now).compareTo(window) >= 0) {
                records.pollFirst();
            }
            if (records.size() >= maxRequests) {
                return false;
            }
            records.addLast(now);
            return true;
        }
    }

    private void evictIfNecessary(Instant now) {
        if (hits.size() < EVICT_THRESHOLD) {
            return;
        }
        hits.entrySet().removeIf(entry -> {
            var records = entry.getValue();
            synchronized (records) {
                if (records.isEmpty()) {
                    return true;
                }
                return Duration.between(records.peekLast(), now).compareTo(MAX_WINDOW) >= 0;
            }
        });
    }
}
