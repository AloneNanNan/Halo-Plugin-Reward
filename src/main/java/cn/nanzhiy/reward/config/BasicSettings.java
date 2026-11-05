package cn.nanzhiy.reward.config;

import lombok.Data;

/**
 * 对应设置表单中的 basic 分组。
 */
@Data
public class BasicSettings {

    private boolean enable = true;

    private String pageTitle = "打赏支持";

    private String pageIntro = "您的打赏将用于服务器维护、内容创作和功能开发，帮助我持续提供优质内容。";

    private String currencySymbol = "¥";
}
