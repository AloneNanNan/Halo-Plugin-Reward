package cn.nanzhiy.reward.service;

import cn.nanzhiy.reward.extension.RewardRecord;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.RoleBinding;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;

/**
 * 打赏通知：新记录提交、爱发电订单入库时提醒管理员。
 *
 * <p>订阅目标为所有拥有 super-role 的用户，避免因管理员用户名不是 admin 而收不到通知。
 * 通知失败不影响主流程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardNotificationService {

    public static final String REASON_NEW_RECORD = "reward-new-record";

    private static final String SUPER_ROLE = "super-role";

    /** 后台打赏记录页面路径。 */
    private static final String MANAGE_PATH = "/console/reward";

    /** 管理员列表变化不频繁，缓存以减少每次通知都全量扫描 RoleBinding。 */
    private static final Duration ADMIN_CACHE_TTL = Duration.ofMinutes(5);

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final NotificationReasonEmitter notificationReasonEmitter;

    private final NotificationCenter notificationCenter;

    private final RewardSettingService settingService;

    private final ReactiveExtensionClient extensionClient;

    private final ExternalLinkProcessor externalLinkProcessor;

    private final AtomicReference<CachedAdmins> adminCache = new AtomicReference<>();

    /** 已成功订阅的管理员，避免每条新记录都把全部管理员重新写一遍订阅。 */
    private final Set<String> subscribedAdmins = ConcurrentHashMap.newKeySet();

    private record CachedAdmins(List<String> names, Instant cachedAt) {
    }

    public void notifyNewRecord(RewardRecord record, String source) {
        var spec = record.getSpec();
        if (spec == null || record.getMetadata() == null) {
            return;
        }
        settingService.notifySettings()
            .flatMap(settings -> {
                var enabled = "afdian".equals(source)
                    ? settings.isNotifyOnAfdianOrder() : settings.isNotifyOnSubmit();
                if (!enabled) {
                    return Mono.empty();
                }
                return adminUserNames().collectList()
                    .flatMap(names -> subscribeAll(names).then(emit(record, source,
                        names.isEmpty() ? "admin" : names.get(0))));
            })
            .subscribe(
                ignored -> {
                },
                error -> log.error("reward notification failed", error)
            );
    }

    /**
     * 为所有超级管理员订阅该通知类型，已订阅时 Halo 会自行去重。
     */
    private Mono<Void> subscribeAll(List<String> names) {
        return Flux.fromIterable(names)
            .filter(name -> !subscribedAdmins.contains(name))
            .flatMap(this::subscribe)
            .then();
    }

    private Mono<Void> subscribe(String name) {
        var interestReason = new Subscription.InterestReason();
        interestReason.setReasonType(REASON_NEW_RECORD);
        interestReason.setExpression("true");

        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.of(name).name());
        return notificationCenter.subscribe(subscriber, interestReason)
            .doOnSuccess(ignored -> subscribedAdmins.add(name))
            .onErrorResume(error -> {
                log.debug("subscribe notification for {} failed: {}", name, error.getMessage());
                return Mono.empty();
            })
            .then();
    }

    private Flux<String> adminUserNames() {
        var cached = adminCache.get();
        if (cached != null
            && Duration.between(cached.cachedAt(), Instant.now()).compareTo(ADMIN_CACHE_TTL) < 0) {
            return Flux.fromIterable(cached.names());
        }
        return extensionClient.listAll(RoleBinding.class, ListOptions.builder().build(),
                Sort.unsorted())
            .filter(binding -> binding.getRoleRef() != null
                && SUPER_ROLE.equals(binding.getRoleRef().getName()))
            .flatMapIterable(RoleBinding::getSubjects)
            .filter(subject -> "User".equals(subject.getKind()))
            .map(subject -> subject.getName())
            .distinct()
            .collectList()
            .doOnNext(names -> adminCache.set(new CachedAdmins(names, Instant.now())))
            .flatMapIterable(names -> names);
    }

    private Mono<Void> emit(RewardRecord record, String source, String author) {
        var spec = record.getSpec();
        var manageUrl = externalLinkProcessor.processLink(MANAGE_PATH);

        var attributes = new HashMap<String, String>();
        attributes.put("nickname", spec.getNickname() == null ? "匿名读者" : spec.getNickname());
        attributes.put("amount",
            spec.getAmount() == null ? "-" : spec.getAmount().toPlainString());
        var rewardTime = RewardRecord.resolveRewardTime(record);
        attributes.put("time", rewardTime == null ? "-" : DATE_TIME_FORMATTER.format(rewardTime));
        attributes.put("source", "afdian".equals(source) ? "爱发电" : "表单提交");
        attributes.put("message",
            spec.getMessage() == null || spec.getMessage().isBlank()
                ? "无" : spec.getMessage());
        attributes.put("manageUrl", manageUrl);

        var subject = Reason.Subject.builder()
            .apiVersion(RewardRecord.GROUP + "/v1alpha1")
            .kind("RewardRecord")
            .name(record.getMetadata().getName())
            .title("新的打赏记录")
            .url(manageUrl)
            .build();

        return notificationReasonEmitter.emit(REASON_NEW_RECORD,
            builder -> builder
                .attributes(Map.copyOf(attributes))
                .author(UserIdentity.of(author))
                .subject(subject));
    }
}
