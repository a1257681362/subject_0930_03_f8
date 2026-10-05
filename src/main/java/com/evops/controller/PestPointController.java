package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PestPointCloseRequest;
import com.evops.dto.PestPointCreateRequest;
import com.evops.dto.PestPointMoveRequest;
import com.evops.dto.PestStripReplaceRequest;
import com.evops.entity.PestPoint;
import com.evops.entity.PestPointMove;
import com.evops.entity.PestTrapStrip;
import com.evops.service.PestPointService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诱捕点位：展厅/库房布放、迁移（保留前后坐标）、停用（只置状态）、诱捕片更换（旧片留行）。
 * 点位编号、布放日期、坐标、责任区域与责任保管员全程留痕。
 */
@RestController
public class PestPointController {

    private final PestPointService pointService;

    public PestPointController(PestPointService pointService) {
        this.pointService = pointService;
    }

    /** 布放诱捕点位（HALL 指定 hallId / STORAGE 指定 storageAreaId） */
    @PostMapping("/api/pest-points")
    public ApiResponse<PestPoint> lay(@Valid @RequestBody PestPointCreateRequest request) {
        return ApiResponse.ok(pointService.lay(request));
    }

    /** 点位列表：可按区域类型/展厅/库区/状态过滤 */
    @GetMapping("/api/pest-points")
    public ApiResponse<List<PestPoint>> list(@RequestParam(required = false) String locationType,
                                             @RequestParam(required = false) Long hallId,
                                             @RequestParam(required = false) Long storageAreaId,
                                             @RequestParam(required = false) String status) {
        return ApiResponse.ok(pointService.listPoints(locationType, hallId, storageAreaId, status));
    }

    /** 点位详情：点位头 + 迁移轨迹（前后坐标）+ 诱捕片更换记录（含已换下旧片） */
    @GetMapping("/api/pest-points/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        PestPoint point = pointService.requirePoint(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("point", point);
        detail.put("moves", pointService.listMoves(id));
        detail.put("strips", pointService.listStrips(id));
        return ApiResponse.ok(detail);
    }

    /** 点位迁移：旧址置 MOVED 另立新点位行，迁移记录保留前后坐标 */
    @PostMapping("/api/pest-points/{id}/move")
    public ApiResponse<PestPoint> move(@PathVariable Long id,
                                       @Valid @RequestBody PestPointMoveRequest request) {
        return ApiResponse.ok(pointService.move(id, request));
    }

    /** 点位停用：只置 CLOSED，历史记录全部保留 */
    @PostMapping("/api/pest-points/{id}/close")
    public ApiResponse<PestPoint> close(@PathVariable Long id,
                                        @Valid @RequestBody PestPointCloseRequest request) {
        return ApiResponse.ok(pointService.close(id, request));
    }

    /** 首次安装 / 更换诱捕片（更换不删除旧片与旧监测记录） */
    @PostMapping("/api/pest-points/{id}/strips")
    public ApiResponse<PestTrapStrip> replaceStrip(@PathVariable Long id,
                                                   @Valid @RequestBody PestStripReplaceRequest request) {
        return ApiResponse.ok(pointService.replaceStrip(id, request));
    }
}
