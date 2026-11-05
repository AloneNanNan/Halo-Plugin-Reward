package cn.nanzhiy.reward.vo;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 赞助者墙对外展示的条目，已做脱敏处理，不包含联系方式等隐私信息。
 */
@Data
public class WallItem {

    private String nickname;

    /** 昵称首个码点，供没有头像的记录展示占位字，避免代理对被截断。 */
    private String initial;

    private BigDecimal amount;

    private String avatar;

    private String message;

    private String createdAt;
}
