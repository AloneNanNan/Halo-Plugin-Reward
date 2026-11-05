package cn.nanzhiy.reward.endpoint;

import cn.nanzhiy.reward.afdian.AfdianService;
import cn.nanzhiy.reward.extension.RewardRecord;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

/**
 * 爱发电后台接口：连通性检测与手动同步。
 */
@ApiVersion("console.api." + RewardRecord.GROUP + "/v1alpha1")
@RestController
@RequiredArgsConstructor
@RequestMapping("/afdian")
public class AfdianConsoleEndpoint {

    private final AfdianService afdianService;

    @GetMapping("/ping")
    public Mono<Map<String, Object>> ping() {
        return afdianService.ping()
            .map(ok -> Map.of("ok", ok));
    }

    @PostMapping("/sync")
    public Mono<Map<String, Object>> sync() {
        return afdianService.syncOrders()
            .map(count -> {
                Map<String, Object> body = Map.of("synced", count);
                return body;
            })
            .onErrorResume(error -> {
                Map<String, Object> body = Map.of(
                    "synced", 0,
                    "message", error.getMessage() == null ? "同步失败" : error.getMessage()
                );
                return Mono.just(body);
            });
    }
}
