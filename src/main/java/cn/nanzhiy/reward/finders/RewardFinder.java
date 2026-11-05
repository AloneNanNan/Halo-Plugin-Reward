package cn.nanzhiy.reward.finders;

import cn.nanzhiy.reward.vo.RewardConfigVo;
import cn.nanzhiy.reward.vo.RewardStatsVo;
import cn.nanzhiy.reward.vo.WallItem;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;

/**
 * 提供给主题模板的数据接口，在模板中以 {@code rewardFinder} 访问。
 */
public interface RewardFinder {

    /**
     * 打赏页公开配置，包含标题、简介与微信/支付宝收款码地址。
     */
    Mono<RewardConfigVo> getConfig();

    /**
     * 赞助者墙分页数据，仅包含已确认的记录。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，最大 50
     */
    Mono<ListResult<WallItem>> listWall(int page, int size);

    /**
     * 打赏汇总统计。
     */
    Mono<RewardStatsVo> getStats();
}
