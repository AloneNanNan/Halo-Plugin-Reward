package cn.nanzhiy.reward.service;

import cn.nanzhiy.reward.afdian.AfdianSettings;
import cn.nanzhiy.reward.config.BasicSettings;
import cn.nanzhiy.reward.config.NotifySettings;
import cn.nanzhiy.reward.config.PaymentSettings;
import cn.nanzhiy.reward.config.RecordSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 统一读取插件配置，配置缺失时返回默认值，避免空指针与启动失败。
 */
@Service
@RequiredArgsConstructor
public class RewardSettingService {

    private final ReactiveSettingFetcher settingFetcher;

    public Mono<BasicSettings> basic() {
        return settingFetcher.fetch("basic", BasicSettings.class)
            .switchIfEmpty(Mono.fromSupplier(BasicSettings::new));
    }

    public Mono<PaymentSettings> payment() {
        return settingFetcher.fetch("payment", PaymentSettings.class)
            .switchIfEmpty(Mono.fromSupplier(PaymentSettings::new));
    }

    public Mono<RecordSettings> records() {
        return settingFetcher.fetch("records", RecordSettings.class)
            .switchIfEmpty(Mono.fromSupplier(RecordSettings::new));
    }

    public Mono<AfdianSettings> afdian() {
        return settingFetcher.fetch("afdian", AfdianSettings.class)
            .switchIfEmpty(Mono.fromSupplier(AfdianSettings::new));
    }

    public Mono<NotifySettings> notifySettings() {
        return settingFetcher.fetch("notify", NotifySettings.class)
            .switchIfEmpty(Mono.fromSupplier(NotifySettings::new));
    }

    public Mono<Settings> all() {
        return Mono.zip(basic(), payment(), records())
            .map(tuple -> new Settings(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    public record Settings(BasicSettings basic, PaymentSettings payment, RecordSettings records) {
    }
}
