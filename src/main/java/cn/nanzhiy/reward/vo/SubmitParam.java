package cn.nanzhiy.reward.vo;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 前台提交打赏记录的请求体。
 */
@Data
public class SubmitParam {

    private BigDecimal amount;

    private String method;

    private String nickname;

    private String message;

    private String contact;

    private String captchaId;

    private String captchaCode;

    private String transactionNo;

    private String avatar;
}
