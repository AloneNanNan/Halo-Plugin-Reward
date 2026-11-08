package cn.nanzhiy.reward.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 一条打赏记录。由访客在前台提交，或由站长在后台补录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = RewardRecord.GROUP,
    version = "v1alpha1",
    kind = "RewardRecord",
    plural = "records",
    singular = "record")
public class RewardRecord extends AbstractExtension {

    public static final String GROUP = "reward.nanzhiy.cn";

    /** 单笔金额上限，公开提交与后台补录共用，避免两处口径漂移。 */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "RewardRecordSpec")
    public static class Spec {

        @Schema(description = "打赏金额", requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "0.01", maximum = "1000000")
        private BigDecimal amount;

        @Schema(description = "支付方式", allowableValues = {"wechat", "alipay", "afdian"},
            defaultValue = "wechat")
        private String method;

        @Schema(description = "展示昵称", maxLength = 30)
        private String nickname;

        @Schema(description = "留言", maxLength = 200)
        private String message;

        @Schema(description = "联系方式，仅后台可见", maxLength = 100)
        private String contact;

        @Schema(description = "交易单号，用于站长核对收款", maxLength = 64)
        private String transactionNo;

        @Schema(description = "头像图片地址", maxLength = 500)
        private String avatar;

        @Schema(description = "打赏实际发生时间，留空时按记录入库时间展示、排序与统计")
        private Instant rewardTime;

        @Schema(description = "状态", allowableValues = {"PENDING", "CONFIRMED", "REJECTED"},
            defaultValue = STATUS_PENDING)
        private String status;

        @Schema(description = "来源", allowableValues = {"page", "manual", "afdian"},
            defaultValue = "page")
        private String source;

        @Schema(description = "外部渠道单号，留档用；去重依赖 metadata.name", maxLength = 64)
        private String externalId;
    }

    /**
     * 打赏时间：优先取站长填写的值，未填写时回落到 Halo 托管的入库时间。
     * 入库时间不可写，因此所有对外展示、统计与排序都必须经过这里，不能直接读 metadata。
     * 用于排序索引时同样按此口径计算，避免空值记录排到不确定的位置。
     */
    public static Instant resolveRewardTime(RewardRecord record) {
        if (record == null) {
            return null;
        }
        if (record.getSpec() != null && record.getSpec().getRewardTime() != null) {
            return record.getSpec().getRewardTime();
        }
        return record.getMetadata() == null ? null : record.getMetadata().getCreationTimestamp();
    }
}
