package cn.nanzhiy.reward.vo;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 打赏汇总统计，仅统计已确认的记录。
 */
@Data
public class RewardStatsVo {

    /** 累计支持人数。 */
    private int totalCount;

    /** 累计金额。 */
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** 本月支持人数。 */
    private int monthCount;

    /** 本月金额。 */
    private BigDecimal monthAmount = BigDecimal.ZERO;
}
