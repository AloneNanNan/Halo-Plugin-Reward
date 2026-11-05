package cn.nanzhiy.reward.support;

import cn.nanzhiy.reward.vo.CaptchaVo;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 图形验证码：内存存储，一次性使用，5 分钟过期。
 */
@Slf4j
@Component
public class RewardCaptchaService {

    /** 排除容易混淆的 I O 0 1。 */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private static final int CODE_LENGTH = 4;

    private static final int WIDTH = 132;

    private static final int HEIGHT = 44;

    private static final Duration TTL = Duration.ofMinutes(5);

    private static final int MAX_ENTRIES = 2048;

    private final SecureRandom random = new SecureRandom();

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(String code, Instant expireAt) {
    }

    /**
     * 生成一张新的验证码。图片绘制属于阻塞操作，调度到 boundedElastic 执行。
     */
    public Mono<CaptchaVo> generate() {
        return Mono.fromCallable(this::doGenerate)
            .subscribeOn(Schedulers.boundedElastic());
    }

    private CaptchaVo doGenerate() {
        cleanupIfNeeded();
        var code = randomCode();
        var id = UUID.randomUUID().toString();
        store.put(id, new Entry(code, Instant.now().plus(TTL)));
        return new CaptchaVo(id, render(code));
    }

    /**
     * 校验并立即失效，避免重复使用。
     */
    public boolean verify(String id, String code) {
        if (id == null || id.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        var entry = store.remove(id);
        if (entry == null) {
            return false;
        }
        if (Instant.now().isAfter(entry.expireAt())) {
            return false;
        }
        return entry.code().equalsIgnoreCase(code.trim());
    }

    private void cleanupIfNeeded() {
        if (store.size() < MAX_ENTRIES) {
            return;
        }
        var now = Instant.now();
        store.entrySet().removeIf(item -> now.isAfter(item.getValue().expireAt()));
        if (store.size() < MAX_ENTRIES) {
            return;
        }
        // 全部未过期时按到期时间淘汰最旧的一批，给内存占用一个硬上限
        store.entrySet().stream()
            .sorted(java.util.Comparator.comparing(item -> item.getValue().expireAt()))
            .limit(Math.max(MAX_ENTRIES / 4, 1))
            .map(Map.Entry::getKey)
            .toList()
            .forEach(store::remove);
    }

    private String randomCode() {
        var builder = new StringBuilder(CODE_LENGTH);
        for (var i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }

    private String render(String code) {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(248, 248, 250));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);

            graphics.setStroke(new BasicStroke(1.2f));
            for (var i = 0; i < 3; i++) {
                graphics.setColor(randomColor(170, 210));
                graphics.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                    random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }

            for (var i = 0; i < 40; i++) {
                graphics.setColor(randomColor(180, 215));
                graphics.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
            }

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            var step = WIDTH / (CODE_LENGTH + 1);
            for (var i = 0; i < code.length(); i++) {
                graphics.setColor(randomColor(25, 90));
                graphics.drawString(String.valueOf(code.charAt(i)),
                    step / 2 + i * step, HEIGHT / 2 + 9);
            }
        } finally {
            graphics.dispose();
        }

        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception e) {
            log.error("captcha render failed", e);
            throw new IllegalStateException("验证码渲染失败", e);
        }
    }

    private Color randomColor(int min, int max) {
        var span = Math.max(max - min, 1);
        return new Color(
            min + random.nextInt(span),
            min + random.nextInt(span),
            min + random.nextInt(span)
        );
    }
}
