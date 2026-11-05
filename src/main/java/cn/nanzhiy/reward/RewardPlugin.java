package cn.nanzhiy.reward;

import cn.nanzhiy.reward.extension.RewardRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 插件生命周期入口。只做自定义模型的注册与注销，不启动任何后台任务或定时线程。
 */
@Slf4j
@Component
public class RewardPlugin extends BasePlugin {

    /** 与 plugin.yaml 中的 metadata.name 保持一致，用于拼接模板与资源路径。 */
    public static final String NAME = "plugin-reward";

    private final SchemeManager schemeManager;

    public RewardPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(RewardRecord.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<RewardRecord, String>single("spec.status", String.class)
                .indexFunc(record -> record.getSpec() == null ? null : record.getSpec().getStatus()));

            indexSpecs.add(IndexSpecs.<RewardRecord, String>single("spec.nickname", String.class)
                .indexFunc(record -> record.getSpec() == null ? null : record.getSpec().getNickname()));

            indexSpecs.add(IndexSpecs.<RewardRecord, String>single("spec.message", String.class)
                .indexFunc(record -> record.getSpec() == null ? null : record.getSpec().getMessage()));

            indexSpecs.add(IndexSpecs.<RewardRecord, String>single("spec.transactionNo", String.class)
                .indexFunc(record -> record.getSpec() == null ? null : record.getSpec().getTransactionNo()));
        });
        log.info("plugin-reward started");
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(RewardRecord.class));
        log.info("plugin-reward stopped");
    }
}
