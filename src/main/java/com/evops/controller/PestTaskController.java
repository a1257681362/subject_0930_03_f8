package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PestTaskCloseRequest;
import com.evops.dto.PestTaskHandleRequest;
import com.evops.entity.PestDisposalTask;
import com.evops.entity.PestTaskPhoto;
import com.evops.service.PestMonitorService;
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
 * 虫害处置任务：监测超阈自动生成、关联责任区域（展厅/库房）与责任保管员。
 * 外包人员（OUTSOURCER）只能提交现场数据/照片；登记处置与复核关闭仅限馆内责任岗位。
 */
@RestController
public class PestTaskController {

    private final PestMonitorService monitorService;

    public PestTaskController(PestMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /** 处置任务列表：可按状态/展厅/库区/点位/虫种过滤 */
    @GetMapping("/api/pest-tasks")
    public ApiResponse<List<PestDisposalTask>> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Long hallId,
                                                    @RequestParam(required = false) Long storageAreaId,
                                                    @RequestParam(required = false) Long pointId,
                                                    @RequestParam(required = false) String pestSpecies) {
        return ApiResponse.ok(monitorService.listTasks(status, hallId, storageAreaId, pointId, pestSpecies));
    }

    /** 任务报表：任务头（标明责任区域、首警规则版本）+ 现场照片 */
    @GetMapping("/api/pest-tasks/{id}")
    public ApiResponse<Map<String, Object>> report(@PathVariable Long id) {
        return ApiResponse.ok(monitorService.taskReport(id));
    }

    /** 登记处置（OPEN -> HANDLING；外包人员 OUTSOURCER 拒绝） */
    @PostMapping("/api/pest-tasks/{id}/handle")
    public ApiResponse<PestDisposalTask> handle(@PathVariable Long id,
                                                @Valid @RequestBody PestTaskHandleRequest request) {
        return ApiResponse.ok(monitorService.handleTask(id, request));
    }

    /** 馆内责任岗位复核关闭（须已处置且有现场照片；外包人员 OUTSOURCER 拒绝） */
    @PostMapping("/api/pest-tasks/{id}/close")
    public ApiResponse<PestDisposalTask> close(@PathVariable Long id,
                                               @Valid @RequestBody PestTaskCloseRequest request) {
        return ApiResponse.ok(monitorService.closeTask(id, request));
    }

    /** 任务现场照片上传（外包人员可上传；关闭后封存拒绝补传） */
    @PostMapping(value = "/api/pest-tasks/{id}/photos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PestTaskPhoto> uploadPhoto(@PathVariable Long id,
                                                  @RequestParam("file") MultipartFile file,
                                                  @RequestParam String uploadedBy,
                                                  @RequestParam String role,
                                                  @RequestParam(required = false) String remark) {
        return ApiResponse.ok(monitorService.uploadTaskPhoto(id, file, uploadedBy, role, remark));
    }
}
