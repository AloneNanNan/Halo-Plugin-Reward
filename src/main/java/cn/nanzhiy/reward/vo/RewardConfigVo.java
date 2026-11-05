package cn.nanzhiy.reward.vo;

import cn.nanzhiy.reward.config.BasicSettings;
import cn.nanzhiy.reward.config.PaymentSettings;
import cn.nanzhiy.reward.config.RecordSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * 前台与主题共用的公开配置视图。公开接口、页面路由与 Finder 都从这里取值，避免同一份字段在多处维护。
 */
@Data
public class RewardConfigVo {

    private String title;

    private String intro;

    private String currencySymbol;

    private String wechatQr;

    private String alipayQr;

    private String paymentTip;

    private boolean enableRecordSubmit;

    private boolean requireLogin;

    private boolean allowMessage;

    private boolean wallEnabled;

    private boolean enableCaptcha;

    private boolean enableTransactionNo;

    private boolean enableAvatar;

    public static RewardConfigVo from(BasicSettings basic, PaymentSettings payment,
        RecordSettings records) {
        var vo = new RewardConfigVo();
        vo.setTitle(basic.getPageTitle());
        vo.setIntro(basic.getPageIntro());
        vo.setCurrencySymbol(basic.getCurrencySymbol());
        vo.setWechatQr(payment.getWechatQr());
        vo.setAlipayQr(payment.getAlipayQr());
        vo.setPaymentTip(payment.getPaymentTip());
        vo.setEnableRecordSubmit(records.isEnableRecordSubmit());
        vo.setRequireLogin(records.isRequireLogin());
        vo.setAllowMessage(records.isAllowMessage());
        vo.setWallEnabled(records.isWallEnabled());
        vo.setEnableCaptcha(records.isEnableCaptcha());
        vo.setEnableTransactionNo(records.isEnableTransactionNo());
        vo.setEnableAvatar(records.isEnableAvatar());
        return vo;
    }

    /**
     * 转为 {@code GET /public/config} 的响应体。
     */
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("title", title);
        map.put("intro", intro);
        map.put("currencySymbol", currencySymbol);
        map.put("wechatQr", wechatQr);
        map.put("alipayQr", alipayQr);
        map.put("paymentTip", paymentTip);
        map.put("enableRecordSubmit", enableRecordSubmit);
        map.put("requireLogin", requireLogin);
        map.put("allowMessage", allowMessage);
        map.put("wallEnabled", wallEnabled);
        map.put("enableCaptcha", enableCaptcha);
        map.put("enableTransactionNo", enableTransactionNo);
        map.put("enableAvatar", enableAvatar);
        return map;
    }
}
