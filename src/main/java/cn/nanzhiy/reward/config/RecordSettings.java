package cn.nanzhiy.reward.config;

import lombok.Data;

/**
 * 对应设置表单中的 records 分组。
 */
@Data
public class RecordSettings {

    private boolean enableRecordSubmit = true;

    private boolean requireLogin = false;

    private boolean allowMessage = true;

    /** 提交时是否校验图形验证码。 */
    private boolean enableCaptcha = true;

    /** 开启后前台显示交易单号，且为必填。 */
    private boolean enableTransactionNo = false;

    /** 开启后前台显示头像链接，选填。 */
    private boolean enableAvatar = false;

    private boolean autoApprove = false;

    private boolean wallEnabled = true;

    private int wallPageSize = 10;

    private boolean showAmountOnWall = true;
}
