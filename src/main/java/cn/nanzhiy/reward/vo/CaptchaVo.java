package cn.nanzhiy.reward.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 图形验证码，图片以 data URI 形式返回。
 */
@Data
@AllArgsConstructor
public class CaptchaVo {

    /** 验证码标识，提交时需一并回传。 */
    private String id;

    /** data:image/png;base64,... */
    private String image;
}
