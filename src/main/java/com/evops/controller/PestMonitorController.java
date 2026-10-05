package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PestCheckSubmitRequest;
import com.evops.entity.PestCheck;
import com.evops.entity.PestCheckPhoto;
import com.evops.entity.PestDisposalTask;
import com.evops.entity.PestTaskPhoto;
import com.evops.service.PestFreezeScheduler;
import com.evops.service.PestLedgerService;
import com.evops.service.PestMonitorService;
import com.evops.entity.PestLedger;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 虫害现场监测：
 * 提交检查单（编号、布放日期随点位、虫种数量与现场照片），按提交时生效规则版本判定；
 * 超阈自动生成处置任务并关联责任区域；月度冻结后拒绝补录；
 * 外包人员（OUTSOURCER）可提交检查单与照片，但不能登记处置/关闭任务。
 */
@RestController
public class PestMonitorController {

    private final PestMonitorService monitorService;
    private final PestFreezeScheduler freezeScheduler;
    private final PestLedgerService ledgerService;

    public PestMonitorController(PestMonitorService monitorService,
                                 PestFreezeScheduler freezeScheduler,
                                 PestLedgerService ledgerService) {
        this.monitorService = monitorService;
        this.freezeScheduler = freezeScheduler;
        this.ledgerService = ledgerService;
    }

    /** 提交现场监测检查单（requestNo 幂等；外包人员 OUTSOURCER 可提交） */
    @PostMapping("/api/pest-points/{pointId}/checks")
    public ApiResponse<PestCheck> submitCheck(@PathVariable Long pointId,
                                              @Valid @RequestBody PestCheckSubmitRequest request) {
        return ApiResponse.ok(monitorService.submitCheck(pointId, request));
    }

    /** 监测记录列表：可按点位/月份/是否超阈过滤，结果标明所属月份与冻结状态 */
    @GetMapping("/api/pest-checks")
    public ApiResponse<List<PestCheck>> listChecks(@RequestParam(required = false) Long pointId,
                                                   @RequestParam(required = false) String periodMonth,
                                                   @RequestParam(required = false) Integer abnormal) {
        return ApiResponse.ok(monitorService.listChecks(pointId, periodMonth, abnormal));
    }

    /** 检查单报表：单据头（标明规则版本/所属月份）+ 虫种明细（阈值与结论固化）+ 照片 */
    @GetMapping("/api/pest-checks/{id}")
    public ApiResponse<Map<String, Object>> checkReport(@PathVariable Long id) {
        return ApiResponse.ok(monitorService.checkReport(id));
    }

    /** 监测现场照片上传（内容寻址去重；外包人员可上传；冻结月拒绝补传） */
    @PostMapping(value = "/api/pest-checks/{id}/photos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PestCheckPhoto> uploadCheckPhoto(@PathVariable Long id,
                                                        @RequestParam("file") MultipartFile file,
                                                        @RequestParam String uploadedBy,
                                                        @RequestParam String role,
                                                        @RequestParam(required = false) String remark) {
        return ApiResponse.ok(monitorService.uploadCheckPhoto(id, file, uploadedBy, role, remark));
    }

    /** 手动冻结指定历史月份（全馆口径，当月不可冻结，重复冻结幂等） */
    @PostMapping("/api/pest-freezes/month")
    public ApiResponse<Boolean> freezeMonth(@RequestParam String periodMonth,
                                            @RequestParam(defaultValue = "SYSTEM") String frozenBy,
                                            @RequestParam(required = false) String remark) {
        return ApiResponse.ok(freezeScheduler.freezeMonth(periodMonth, frozenBy, remark));
    }

    /** 立即扫描冻结所有已结束未冻结月份（定时任务的手动触发入口，幂等） */
    @PostMapping("/api/pest-freezes/freeze-due")
    public ApiResponse<Integer> freezeDue() {
        return ApiResponse.ok(freezeScheduler.freezeDueMonths());
    }

    /** 月度冻结记录列表 */
    @GetMapping("/api/pest-freezes")
    public ApiResponse<List<?>> listFreezes() {
        return ApiResponse.ok(freezeScheduler.listFreezes());
    }

    /**
     * 虫害台账（可追溯）：布点/迁移/换片/检查/任务开闭/冻结全留痕。
     * 可按点位/任务/展厅/库区过滤；不传条件返回全量台账。
     */
    @GetMapping("/api/pest-ledgers")
    public ApiResponse<List<PestLedger>> ledgers(@RequestParam(required = false) Long pointId,
                                                 @RequestParam(required = false) Long taskId,
                                                 @RequestParam(required = false) Long hallId,
                                                 @RequestParam(required = false) Long storageAreaId) {
        return ApiResponse.ok(ledgerService.list(pointId, taskId, hallId, storageAreaId));
    }
}
