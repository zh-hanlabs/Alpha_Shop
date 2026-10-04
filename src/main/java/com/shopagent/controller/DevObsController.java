package com.shopagent.controller;

import com.shopagent.infra.obs.TurnMetrics;
import com.shopagent.infra.obs.TurnMetricsRecorder;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 观测查询端点（W6D4）：轮级明细 + 汇总统计，dev/演示期排查与 W7 压测对照用。
 * Why dev-only：暴露内部指标快照，演示/生产运行（默认无 profile）不注册。
 */
@RestController
@RequestMapping("/api/dev/obs")
@Profile("dev")
public class DevObsController {

    private final TurnMetricsRecorder recorder;

    public DevObsController(TurnMetricsRecorder recorder) {
        this.recorder = recorder;
    }

    /** 轮级明细（环形缓冲最近 100 轮，?limit 取尾部 N 条） */
    @GetMapping("/turns")
    public List<TurnMetrics> turns(@RequestParam(defaultValue = "100") int limit) {
        List<TurnMetrics> all = recorder.turns();
        int bounded = Math.min(Math.max(limit, 1), 100);
        return all.size() <= bounded ? all : all.subList(all.size() - bounded, all.size());
    }

    /** 汇总：轮数 / outcome 分布 / 平均耗时 / token 合计与 usage 覆盖率 / 工具调用分布 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return recorder.stats();
    }
}
