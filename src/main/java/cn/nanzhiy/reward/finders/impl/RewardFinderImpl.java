package cn.nanzhiy.reward.finders.impl;

import cn.nanzhiy.reward.finders.RewardFinder;
import cn.nanzhiy.reward.service.RewardRecordService;
import cn.nanzhiy.reward.service.RewardSettingService;
import cn.nanzhiy.reward.vo.RewardConfigVo;
import cn.nanzhiy.reward.vo.RewardStatsVo;
import cn.nanzhiy.reward.vo.WallItem;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.theme.finders.Finder;

/**
 * Finder 实现，注册名为 {@code rewardFinder}，主题中可直接调用。
 */
@Finder("rewardFinder")
@Component
@RequiredArgsConstructor
public class RewardFinderImpl implements RewardFinder {

    private static final int MAX_WALL_SIZE = 50;

    private final RewardSettingService settingService;

    private final RewardRecordService recordService;

    @Override
    public Mono<RewardConfigVo> getConfig() {
        return settingService.all().map(settings -> RewardConfigVo.from(settings.basic(),
            settings.payment(), settings.records(), settings.afdian()));
    }

    @Override
    public Mono<ListResult<WallItem>> listWall(int page, int size) {
        var safePage = Math.max(page, 1);
        var safeSize = Math.min(Math.max(size, 1), MAX_WALL_SIZE);
        return settingService.records().flatMap(records -> {
            if (!records.isWallEnabled()) {
                return Mono.just(new ListResult<WallItem>(safePage, safeSize, 0L, List.of()));
            }
            return recordService.listWall(safePage, safeSize)
                .map(result -> new ListResult<>(
                    result.getPage(),
                    result.getSize(),
                    result.getTotal(),
                    recordService.toWallItems(result.getItems(), records.isShowAmountOnWall())
                ));
        });
    }

    @Override
    public Mono<RewardStatsVo> getStats() {
        return recordService.stats();
    }
}
