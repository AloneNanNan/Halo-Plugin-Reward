package cn.nanzhiy.reward.support;

import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 访客提交与后台编辑共用的字段清洗规则与长度上限。
 *
 * <p>两侧走的是同一条存储通路，长度上限只能有一份定义，否则前台放行、后台截断会出现口径漂移。</p>
 */
public final class InputSanitizer {

    /** 展示昵称 */
    public static final int MAX_NICKNAME_LENGTH = 30;

    /** 留言 */
    public static final int MAX_MESSAGE_LENGTH = 200;

    /** 联系方式 */
    public static final int MAX_CONTACT_LENGTH = 100;

    /** 交易单号 */
    public static final int MAX_TRANSACTION_NO_LENGTH = 64;

    /** 头像地址 */
    public static final int MAX_AVATAR_LENGTH = 500;

    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private InputSanitizer() {
    }

    /**
     * 去除控制字符并 trim，不做长度截断。后台写入用它，超长由调用方先判长度再决定怎么处理。
     */
    public static String stripControlChars(String value) {
        if (value == null) {
            return null;
        }
        var cleaned = CONTROL_CHARS.matcher(value).replaceAll("").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * 去除控制字符并限制长度，超长部分直接截断。前台提交用它，访客超长时静默截断而不是报错。
     */
    public static String clean(String value, int maxLength) {
        var cleaned = stripControlChars(value);
        if (cleaned == null || cleaned.length() <= maxLength) {
            return cleaned;
        }
        // 截断点正好落在代理对中间时会留下孤立代理字符，落库后变成乱码；这时少截一个字符
        var end = maxLength;
        if (Character.isHighSurrogate(cleaned.charAt(end - 1))
            && Character.isLowSurrogate(cleaned.charAt(end))) {
            end -= 1;
        }
        // 上限为 1 且首字符就是高代理时没有完整字符可留，按空值处理
        return end <= 0 ? null : cleaned.substring(0, end);
    }

    /**
     * 只放行 http(s) 与站内绝对路径。协议相对地址（//host）会解析到外部域名，必须排除。
     */
    public static String safeImageUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        var trimmed = url.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
            return trimmed;
        }
        return null;
    }
}
