package cn.nanzhiy.reward.config;

import lombok.Data;

/**
 * 对应设置表单中的 payment 分组。存储的是附件库中的图片地址。
 */
@Data
public class PaymentSettings {

    private String wechatQr;

    private String alipayQr;

    private String paymentTip = "通过微信/支付宝打赏后可点击下方按钮进行提交打赏信息，审核通过后将展示在赞助者墙；通过爱发电打赏后无需提交，会将自动拉取信息。";
}
