package cn.nanzhiy.reward.config;

import lombok.Data;

/**
 * 对应设置表单中的 notify 分组。
 */
@Data
public class NotifySettings {

    private boolean notifyOnSubmit = true;

    private boolean notifyOnAfdianOrder = true;
}
