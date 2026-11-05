package cn.nanzhiy.reward.router;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import cn.nanzhiy.reward.RewardPlugin;
import cn.nanzhiy.reward.extension.RewardRecord;
import cn.nanzhiy.reward.service.RewardRecordService;
import cn.nanzhiy.reward.service.RewardSettingService;
import cn.nanzhiy.reward.vo.RewardConfigVo;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.app.theme.router.ModelConst;

/**
 * 注册前台打赏页面路由。
 *
 * <p>模板名称通过 {@link TemplateNameResolver} 解析：主题若提供 reward.html 则优先使用主题模板，
 * 否则回退到插件内置模板。</p>
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class RewardPageRouter {

    public static final String TEMPLATE_NAME = "reward";

    private static final int MAX_WALL_SIZE = 50;

    private final TemplateNameResolver templateNameResolver;

    private final RewardSettingService settingService;

    private final RewardRecordService recordService;

    @Bean
    RouterFunction<ServerResponse> rewardPageRouterFunction() {
        return route(GET("/reward"), this::renderPage);
    }

    private Mono<ServerResponse> renderPage(ServerRequest request) {
        return Mono.zip(settingService.all(), settingService.afdian()).flatMap(tuple -> {
            var settings = tuple.getT1();
            var afdian = tuple.getT2();
            var basic = settings.basic();
            if (!basic.isEnable()) {
                return ServerResponse.notFound().build();
            }
            var records = settings.records();
            var size = Math.min(Math.max(records.getWallPageSize(), 1), MAX_WALL_SIZE);
            var wall = records.isWallEnabled()
                ? recordService.listWall(1, size)
                : Mono.just(new ListResult<RewardRecord>(1, size, 0L, List.of()));
            return wall.flatMap(result -> {
                var page = new LinkedHashMap<String, Object>(
                    RewardConfigVo.from(basic, settings.payment(), records).toMap());
                page.put("showAmountOnWall", records.isShowAmountOnWall());
                page.put("wallPageSize", size);
                page.put("wallItems",
                    recordService.toWallItems(result.getItems(), records.isShowAmountOnWall()));
                page.put("wallTotal", result.getTotal());
                page.put("afdianPageUrl", afdian.isEnable() ? afdian.getPageUrl() : "");

                var model = new HashMap<String, Object>();
                model.put("reward", page);
                model.put(ModelConst.TEMPLATE_ID,
                    "plugin:" + RewardPlugin.NAME + ":" + TEMPLATE_NAME);
                return templateNameResolver
                    .resolveTemplateNameOrDefault(request.exchange(), TEMPLATE_NAME)
                    .flatMap(templateName -> ServerResponse.ok().render(templateName, model));
            });
        });
    }
}
