package cn.nanzhiy.reward.afdian;

import lombok.Data;

/**
 * 对应设置表单中的 afdian 分组。
 */
@Data
public class AfdianSettings {

    private boolean enable = false;

    private String userId = "";

    /** 存放 API Token 的 Halo Secret 名称。 */
    private String secretName = "";

    /** 爱发电主页地址，启用并填写后前台才展示入口。 */
    private String pageUrl = "";

    /** 订单入库后是否直接确认并展示。 */
    private boolean autoApprove = true;
}
