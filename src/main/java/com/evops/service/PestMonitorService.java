package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.PestCheckSubmitRequest;
import com.evops.dto.PestTaskCloseRequest;
import com.evops.dto.PestTaskHandleRequest;
import com.evops.entity.PestCheck;
import com.evops.entity.PestCheckLine;
import com.evops.entity.PestCheckPhoto;
import com.evops.entity.PestDisposalTask;
import com.evops.entity.PestMonthFreeze;
import com.evops.entity.PestPoint;
import com.evops.entity.PestRuleItem;
import com.evops.entity.PestRuleVersion;
import com.evops.entity.PestTaskPhoto;
import com.evops.entity.PestTrapStrip;
import com.evops.entity.PhotoFile;
import com.evops.mapper.PestCheckLineMapper;
import com.evops.mapper.PestCheckMapper;
import com.evops.mapper.PestCheckPhotoMapper;
import com.evops.mapper.PestDisposalTaskMapper;
import com.evops.mapper.PestMonthFreezeMapper;
import com.evops.mapper.PestTaskPhotoMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.evops.constant.MuseumConstants.*;

/**
 * 虫害现场监测与处置任务核心服务：
 *
 * <ol>
 *   <li><b>现场提交</b>：一个点位一次检查一单（requestNo 幂等），逐虫种登记数量，
 *       按提交时生效规则版本判定 NORMAL/WARN/ALARM，阈值与结论随明细行固化；
 *       外包人员（OUTSOURCER）可提交现场数据。所属月份冻结后一律拒绝补录。</li>
 *   <li><b>超阈任务</b>：任一虫种数量达到处置阈值即按 (点位, 虫种) 自动开立/并入
 *       处置任务，并关联点位所在责任区域（展厅/库房）与责任保管员快照。</li>
 *   <li><b>岗位边界</b>：处置登记与关闭均拒绝外包人员；关闭前必须已处置且有现场照片。</li>
 *   <li><b>不可变</b>：检查单提交即封存（不提供修改/删除）；规则新版本不改旧结论。</li>
 * </ol>
 */
@Service
public class PestMonitorService {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PestCheckMapper checkMapper;
    private final PestCheckLineMapper lineMapper;
    private final PestCheckPhotoMapper checkPhotoMapper;
    private final PestDisposalTaskMapper taskMapper;
    private final PestTaskPhotoMapper taskPhotoMapper;
    private final PestMonthFreezeMapper freezeMapper;
    private final PestRuleService ruleService;
    private final PestPointService pointService;
    private final PestLedgerService ledgerService;
    private final PhotoFileStore photoFileStore;

    public PestMonitorService(PestCheckMapper checkMapper,
                              PestCheckLineMapper lineMapper,
                              PestCheckPhotoMapper checkPhotoMapper,
                              PestDisposalTaskMapper taskMapper,
                              PestTaskPhotoMapper taskPhotoMapper,
                              PestMonthFreezeMapper freezeMapper,
                              PestRuleService ruleService,
                              PestPointService pointService,
                              PestLedgerService ledgerService,
                              PhotoFileStore photoFileStore) {
        this.checkMapper = checkMapper;
        this.lineMapper = lineMapper;
        this.checkPhotoMapper = checkPhotoMapper;
        this.taskMapper = taskMapper;
        this.taskPhotoMapper = taskPhotoMapper;
        this.freezeMapper = freezeMapper;
        this.ruleService = ruleService;
        this.pointService = pointService;
        this.ledgerService = ledgerService;
        this.photoFileStore = photoFileStore;
    }

    // ================================================================
    // 现场监测提交
    // ================================================================

    @Transactional
    public PestCheck submitCheck(Long pointId, PestCheckSubmitRequest req) {
        PestPoint point = pointService.requirePoint(pointId);
        if (!PEST_POINT_ACTIVE.equals(point.getStatus())) {
            throw new IllegalArgumentException("点位" + point.getPointNo() + "已"
                    + (PEST_POINT_MOVED.equals(point.getStatus()) ? "迁移" : "停用")
                    + "，不能再提交现场监测数据");
        }

        // 请求流水号幂等：现场补传/重试复用同一 requestNo，只形成一单
        PestCheck duplicated = checkMapper.selectOne(new LambdaQueryWrapper<PestCheck>()
                .eq(PestCheck::getRequestNo, req.getRequestNo()).last("LIMIT 1"));
        if (duplicated != null) {
            return duplicated;
        }

        LocalDateTime checkedAt = req.getCheckedAt() == null ? LocalDateTime.now() : req.getCheckedAt();
        String periodMonth = YearMonth.from(checkedAt).format(MONTH_FORMAT);
        if (isFrozen(point, periodMonth)) {
            throw new IllegalArgumentException("点位 " + point.getPointNo() + " 所属 " + periodMonth
                    + " 监测结果已按月冻结，不能补录/修改现场数据");
        }

        PestRuleVersion version = ruleService.requireActiveVersion();
        List<PestRuleItem> items = ruleService.listItems(version.getId());
        Map<String, PestRuleItem> itemBySpecies = items.stream()
                .collect(Collectors.toMap(PestRuleItem::getPestSpecies, i -> i, (a, b) -> a));

        Set<String> speciesSet = new HashSet<>();
        for (PestCheckSubmitRequest.PestCountItem p : req.getItems()) {
            if (!speciesSet.add(p.getPestSpecies().trim())) {
                throw new IllegalArgumentException("同一检查单内虫种不能重复: " + p.getPestSpecies());
            }
            if (!itemBySpecies.containsKey(p.getPestSpecies().trim())) {
                throw new IllegalArgumentException("虫种【" + p.getPestSpecies()
                        + "】在生效规则版本 " + version.getVersionNo() + " 中无阈值条目，无法判定");
            }
        }

        // 诱捕片快照（可空点位无片也允许检查）
        PestTrapStrip strip = pointService.currentStrip(pointId);

        PestCheck check = new PestCheck();
        check.setRequestNo(req.getRequestNo());
        check.setCheckNo("PC-" + String.format("%06d", checkMapper.nextCheckNo()));
        check.setPointId(pointId);
        check.setRuleVersionId(version.getId());
        check.setRuleVersionNo(version.getVersionNo());
        check.setPeriodMonth(periodMonth);
        check.setCheckedAt(checkedAt);
        check.setInspector(req.getInspector().trim());
        check.setInspectorRole(req.getInspectorRole().trim());
        check.setStripId(strip == null ? null : strip.getId());
        check.setStripNo(strip == null ? null : strip.getStripNo());
        check.setAbnormal(0);
        check.setRemark(req.getRemark());

        List<PestCheckLine> lines = new ArrayList<>();
        List<PestCheckLine> alarms = new ArrayList<>();
        for (PestCheckSubmitRequest.PestCountItem p : req.getItems()) {
            PestRuleItem item = itemBySpecies.get(p.getPestSpecies().trim());
            int count = p.getCount();
            String level;
            if (count >= item.getAlarmThreshold()) {
                level = PEST_LEVEL_ALARM;
            } else if (count >= item.getWarnThreshold()) {
                level = PEST_LEVEL_WARN;
            } else {
                level = PEST_LEVEL_NORMAL;
            }
            PestCheckLine line = new PestCheckLine();
            line.setPestSpecies(item.getPestSpecies());
            line.setCount(count);
            line.setWarnThreshold(item.getWarnThreshold());
            line.setAlarmThreshold(item.getAlarmThreshold());
            line.setLevel(level);
            lines.add(line);
            if (PEST_LEVEL_ALARM.equals(level)) {
                alarms.add(line);
            }
        }

        try {
            checkMapper.insert(check);
        } catch (DuplicateKeyException dup) {
            // 并发补传撞 request_no：回查既有单返回，绝不形成第二条
            PestCheck raced = checkMapper.selectOne(new LambdaQueryWrapper<PestCheck>()
                    .eq(PestCheck::getRequestNo, req.getRequestNo()).last("LIMIT 1"));
            return raced;
        }
        for (PestCheckLine line : lines) {
            line.setCheckId(check.getId());
            lineMapper.insert(line);
        }

        // 达到处置阈值：逐虫种开立/并入处置任务
        for (PestCheckLine alarm : alarms) {
            reconcileTask(point, check, version, alarm, checkedAt);
        }

        if (!alarms.isEmpty()) {
            check.setAbnormal(1);
            checkMapper.updateById(check);
            String alarmText = alarms.stream()
                    .map(a -> a.getPestSpecies() + " " + a.getCount() + " 只(阈值 "
                            + a.getAlarmThreshold() + ")")
                    .collect(Collectors.joining("、"));
            ledgerService.append(pointId, check.getId(), null, point.getHallId(),
                    point.getStorageAreaId(), version.getId(), version.getVersionNo(),
                    ACTION_CHECK_ALARM, check.getCheckNo(),
                    "点位 " + point.getPointNo() + " 检查 " + check.getCheckNo() + " 发现超阈虫种："
                            + alarmText + "，已自动生成/并入处置任务，提交岗位 "
                            + check.getInspectorRole() + " " + check.getInspector()
                            + "，规则版本 " + version.getVersionNo(), checkedAt);
        } else {
            ledgerService.append(pointId, check.getId(), null, point.getHallId(),
                    point.getStorageAreaId(), version.getId(), version.getVersionNo(),
                    ACTION_CHECK_OK, check.getCheckNo(),
                    "点位 " + point.getPointNo() + " 检查 " + check.getCheckNo()
                            + " 各虫种数量未达处置阈值，提交岗位 " + check.getInspectorRole()
                            + " " + check.getInspector() + "，规则版本 " + version.getVersionNo(),
                    checkedAt);
        }
        return check;
    }

    // ================================================================
    // 处置任务：开立 / 并入 / 处置 / 关闭
    // ================================================================

    private void reconcileTask(PestPoint point, PestCheck check, PestRuleVersion version,
                               PestCheckLine alarm, LocalDateTime checkedAt) {
        String mergeKey = mergeKey(point.getId(), alarm.getPestSpecies());
        PestDisposalTask active = taskMapper.selectOne(new LambdaQueryWrapper<PestDisposalTask>()
                .eq(PestDisposalTask::getMergeKey, mergeKey).last("LIMIT 1"));
        if (active != null) {
            mergeIntoTask(active, check, alarm, checkedAt);
            return;
        }
        openTask(point, check, version, alarm, mergeKey, checkedAt);
    }

    private void mergeIntoTask(PestDisposalTask task, PestCheck check, PestCheckLine alarm,
                               LocalDateTime checkedAt) {
        task.setLastCount(alarm.getCount());
        task.setPeakCount(Math.max(task.getPeakCount(), alarm.getCount()));
        task.setCheckCount(task.getCheckCount() + 1);
        task.setLastCheckId(check.getId());
        task.setLastAt(checkedAt);
        taskMapper.updateById(task);
        ledgerService.append(task.getPointId(), check.getId(), task.getId(), task.getHallId(),
                task.getStorageAreaId(), task.getRuleVersionId(), task.getRuleVersionNo(),
                ACTION_PEST_TASK_MERGE, task.getTaskNo(),
                "点位 " + task.getPointNo() + " 虫种【" + alarm.getPestSpecies() + "】连续超阈"
                        + "（本次 " + alarm.getCount() + " 只）并入处置任务 " + task.getTaskNo()
                        + "，累计 " + task.getCheckCount() + " 次检查超阈"
                        + (PEST_TASK_HANDLING.equals(task.getStatus()) ? "，任务处置中" : "")
                        + "，责任区域 " + task.getAreaNoSnapshot(), checkedAt);
    }

    private void openTask(PestPoint point, PestCheck check, PestRuleVersion version,
                          PestCheckLine alarm, String mergeKey, LocalDateTime checkedAt) {
        PestDisposalTask task = new PestDisposalTask();
        task.setTaskNo("PDT-" + String.format("%06d", taskMapper.nextTaskNo()));
        task.setPointId(point.getId());
        task.setPointNo(point.getPointNo());
        task.setPestSpecies(alarm.getPestSpecies());
        task.setMergeKey(mergeKey);
        task.setLocationType(point.getLocationType());
        task.setHallId(point.getHallId());
        task.setStorageAreaId(point.getStorageAreaId());
        task.setAreaNoSnapshot(point.getAreaNoSnapshot());
        task.setAreaNameSnapshot(point.getAreaNameSnapshot());
        task.setCustodian(point.getCustodian());
        task.setRuleVersionId(version.getId());
        task.setRuleVersionNo(version.getVersionNo());
        task.setAlarmThreshold(alarm.getAlarmThreshold());
        task.setFirstCount(alarm.getCount());
        task.setLastCount(alarm.getCount());
        task.setPeakCount(alarm.getCount());
        task.setCheckCount(1);
        task.setFirstCheckId(check.getId());
        task.setLastCheckId(check.getId());
        task.setFirstAt(checkedAt);
        task.setLastAt(checkedAt);
        task.setStatus(PEST_TASK_OPEN);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException dup) {
            // 并发提交同 key 首警：回查并入，保证一个点位一个虫种只有一个在途任务
            PestDisposalTask raced = taskMapper.selectOne(new LambdaQueryWrapper<PestDisposalTask>()
                    .eq(PestDisposalTask::getMergeKey, mergeKey).last("LIMIT 1"));
            if (raced != null) {
                mergeIntoTask(raced, check, alarm, checkedAt);
                return;
            }
            throw dup;
        }
        ledgerService.append(point.getId(), check.getId(), task.getId(), point.getHallId(),
                point.getStorageAreaId(), version.getId(), version.getVersionNo(),
                ACTION_PEST_TASK_OPEN, task.getTaskNo(),
                "点位 " + point.getPointNo() + " 虫种【" + alarm.getPestSpecies() + "】捕获 "
                        + alarm.getCount() + " 只达到处置阈值 " + alarm.getAlarmThreshold()
                        + "，自动开立处置任务 " + task.getTaskNo() + "，责任区域："
                        + point.getAreaNoSnapshot() + " " + point.getAreaNameSnapshot()
                        + "，责任保管员 " + (point.getCustodian() == null ? "（待指派）" : point.getCustodian())
                        + "，规则版本 " + version.getVersionNo(), checkedAt);
    }

    /** 处置登记：OPEN -> HANDLING；外包人员禁止；已在处置中/已关闭拒绝重复登记。 */
    @Transactional
    public PestDisposalTask handleTask(Long taskId, PestTaskHandleRequest req) {
        PestDisposalTask task = requireTask(taskId);
        rejectOutsourced(req.getHandlerRole(), "登记处置");
        if (PEST_TASK_CLOSED.equals(task.getStatus())) {
            throw new IllegalArgumentException("处置任务" + task.getTaskNo() + "已关闭，处置记录封存");
        }
        if (PEST_TASK_HANDLING.equals(task.getStatus())) {
            throw new IllegalArgumentException("处置任务" + task.getTaskNo() + "已在处置中，请勿重复登记");
        }
        LocalDateTime now = LocalDateTime.now();
        task.setHandler(req.getHandler().trim());
        task.setHandleRemark(req.getHandleRemark());
        task.setHandledAt(now);
        task.setStatus(PEST_TASK_HANDLING);
        taskMapper.updateById(task);
        ledgerService.append(task.getPointId(), null, task.getId(), task.getHallId(),
                task.getStorageAreaId(), task.getRuleVersionId(), task.getRuleVersionNo(),
                ACTION_PEST_TASK_HANDLE, task.getTaskNo(),
                "处置任务 " + task.getTaskNo() + "（" + task.getAreaNoSnapshot() + " 点位 "
                        + task.getPointNo() + " 虫种 " + task.getPestSpecies() + "）由 "
                        + req.getHandler() + "（" + req.getHandlerRole() + "）登记处置："
                        + req.getHandleRemark(), now);
        return task;
    }

    /** 馆内责任岗位复核关闭：已处置且至少一张现场照片；外包人员禁止；关闭即封存。 */
    @Transactional
    public PestDisposalTask closeTask(Long taskId, PestTaskCloseRequest req) {
        PestDisposalTask task = requireTask(taskId);
        rejectOutsourced(req.getClosedByRole(), "关闭处置任务");
        if (PEST_TASK_CLOSED.equals(task.getStatus())) {
            throw new IllegalArgumentException("处置任务" + task.getTaskNo() + "已关闭，不能重复关闭");
        }
        if (!PEST_TASK_HANDLING.equals(task.getStatus())) {
            throw new IllegalArgumentException("处置任务" + task.getTaskNo()
                    + "尚未登记处置，不能直接关闭");
        }
        long photoCount = taskPhotoMapper.selectCount(new LambdaQueryWrapper<PestTaskPhoto>()
                .eq(PestTaskPhoto::getTaskId, taskId));
        if (photoCount <= 0) {
            throw new IllegalArgumentException("处置任务" + task.getTaskNo()
                    + "未关联现场照片，不能关闭");
        }
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(PEST_TASK_CLOSED);
        task.setClosedBy(req.getClosedBy().trim());
        task.setCloseRemark(req.getCloseRemark());
        task.setClosedAt(now);
        // 归并键清空：关闭后再次超阈另开新任务（历史任务永久保留）
        task.setMergeKey(null);
        taskMapper.updateById(task);
        ledgerService.append(task.getPointId(), null, task.getId(), task.getHallId(),
                task.getStorageAreaId(), task.getRuleVersionId(), task.getRuleVersionNo(),
                ACTION_PEST_TASK_CLOSE, task.getTaskNo(),
                "处置任务 " + task.getTaskNo() + " 由 " + req.getClosedBy() + "（"
                        + req.getClosedByRole() + "）复核关闭；处置人 " + task.getHandler()
                        + "，现场照片 " + photoCount + " 张，责任区域 " + task.getAreaNoSnapshot()
                        + "，首警规则版本 " + task.getRuleVersionNo()
                        + (req.getCloseRemark() == null ? "" : "；备注：" + req.getCloseRemark()), now);
        return task;
    }

    // ================================================================
    // 现场照片（外包人员可上传；检查单与任务均只追加）
    // ================================================================

    @Transactional
    public PestCheckPhoto uploadCheckPhoto(Long checkId, MultipartFile file,
                                           String uploadedBy, String role, String remark) {
        PestCheck check = requireCheck(checkId);
        PestPoint point = pointService.requirePoint(check.getPointId());
        if (isFrozen(point, check.getPeriodMonth())) {
            throw new IllegalArgumentException("检查单 " + check.getCheckNo() + " 所属月份已冻结，不能补传照片");
        }
        PhotoFile photoFile = storePhoto(file, uploadedBy);
        if (checkPhotoMapper.selectCount(new LambdaQueryWrapper<PestCheckPhoto>()
                .eq(PestCheckPhoto::getCheckId, checkId)
                .eq(PestCheckPhoto::getContentHash, photoFile.getContentHash())) > 0) {
            throw new IllegalArgumentException("该照片已关联检查单 " + check.getCheckNo() + "（内容一致）");
        }
        PestCheckPhoto photo = new PestCheckPhoto();
        photo.setCheckId(checkId);
        photo.setFileId(photoFile.getId());
        photo.setContentHash(photoFile.getContentHash());
        photo.setPhotoName(photoFile.getFileName());
        photo.setUploadedBy(uploadedBy.trim());
        photo.setUploadedRole(role.trim());
        photo.setRemark(remark);
        checkPhotoMapper.insert(photo);
        return photo;
    }

    @Transactional
    public PestTaskPhoto uploadTaskPhoto(Long taskId, MultipartFile file,
                                         String uploadedBy, String role, String remark) {
        PestDisposalTask task = requireTask(taskId);
        if (PEST_TASK_CLOSED.equals(task.getStatus())) {
            throw new IllegalArgumentException("处置任务" + task.getTaskNo() + "已关闭，不能再补现场照片");
        }
        PhotoFile photoFile = storePhoto(file, uploadedBy);
        if (taskPhotoMapper.selectCount(new LambdaQueryWrapper<PestTaskPhoto>()
                .eq(PestTaskPhoto::getTaskId, taskId)
                .eq(PestTaskPhoto::getContentHash, photoFile.getContentHash())) > 0) {
            throw new IllegalArgumentException("该照片已关联处置任务 " + task.getTaskNo() + "（内容一致）");
        }
        PestTaskPhoto photo = new PestTaskPhoto();
        photo.setTaskId(taskId);
        photo.setFileId(photoFile.getId());
        photo.setContentHash(photoFile.getContentHash());
        photo.setPhotoName(photoFile.getFileName());
        photo.setUploadedBy(uploadedBy.trim());
        photo.setUploadedRole(role.trim());
        photo.setRemark(remark);
        taskPhotoMapper.insert(photo);
        return photo;
    }

    private PhotoFile storePhoto(MultipartFile file, String uploadedBy) {
        if (uploadedBy == null || uploadedBy.trim().isEmpty()) {
            throw new IllegalArgumentException("上传人不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("现场照片不能为空");
        }
        try {
            return photoFileStore.store(file.getBytes(), file.getOriginalFilename(),
                    file.getContentType(), uploadedBy.trim());
        } catch (IOException e) {
            throw new UncheckedIOException("现场照片读取失败", e);
        }
    }

    // ================================================================
    // 月度冻结判定
    // ================================================================

    /** 该点位该月是否已冻结：全馆冻结或对应展厅/库区单独冻结命中即冻结。 */
    public boolean isFrozen(PestPoint point, String periodMonth) {
        if (freezeMapper.selectCount(new LambdaQueryWrapper<PestMonthFreeze>()
                .eq(PestMonthFreeze::getPeriodMonth, periodMonth)
                .eq(PestMonthFreeze::getScopeType, PEST_SCOPE_ALL)
                .isNull(PestMonthFreeze::getScopeId)) > 0) {
            return true;
        }
        String scopeType = PEST_LOC_HALL.equals(point.getLocationType())
                ? PEST_SCOPE_HALL : PEST_SCOPE_STORAGE;
        Long scopeId = PEST_LOC_HALL.equals(point.getLocationType())
                ? point.getHallId() : point.getStorageAreaId();
        return freezeMapper.selectCount(new LambdaQueryWrapper<PestMonthFreeze>()
                .eq(PestMonthFreeze::getPeriodMonth, periodMonth)
                .eq(PestMonthFreeze::getScopeType, scopeType)
                .eq(scopeId != null, PestMonthFreeze::getScopeId, scopeId)) > 0;
    }

    // ================================================================
    // 查询 / 报表
    // ================================================================

    public PestCheck requireCheck(Long checkId) {
        PestCheck check = checkMapper.selectById(checkId);
        if (check == null) {
            throw new IllegalArgumentException("监测检查单不存在: id=" + checkId);
        }
        return check;
    }

    public PestDisposalTask requireTask(Long taskId) {
        PestDisposalTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("虫害处置任务不存在: id=" + taskId);
        }
        return task;
    }

    public List<PestCheck> listChecks(Long pointId, String periodMonth, Integer abnormal) {
        return checkMapper.selectList(new LambdaQueryWrapper<PestCheck>()
                .eq(pointId != null, PestCheck::getPointId, pointId)
                .eq(periodMonth != null && !periodMonth.isEmpty(),
                        PestCheck::getPeriodMonth, periodMonth)
                .eq(abnormal != null, PestCheck::getAbnormal, abnormal)
                .orderByDesc(PestCheck::getCheckedAt)
                .orderByDesc(PestCheck::getId));
    }

    public List<PestCheckLine> listLines(Long checkId) {
        return lineMapper.selectList(new LambdaQueryWrapper<PestCheckLine>()
                .eq(PestCheckLine::getCheckId, checkId)
                .orderByAsc(PestCheckLine::getPestSpecies));
    }

    public List<PestCheckPhoto> listCheckPhotos(Long checkId) {
        return checkPhotoMapper.selectList(new LambdaQueryWrapper<PestCheckPhoto>()
                .eq(PestCheckPhoto::getCheckId, checkId)
                .orderByAsc(PestCheckPhoto::getId));
    }

    public List<PestDisposalTask> listTasks(String status, Long hallId, Long storageAreaId,
                                            Long pointId, String pestSpecies) {
        return taskMapper.selectList(new LambdaQueryWrapper<PestDisposalTask>()
                .eq(status != null && !status.isEmpty(), PestDisposalTask::getStatus, status)
                .eq(hallId != null, PestDisposalTask::getHallId, hallId)
                .eq(storageAreaId != null, PestDisposalTask::getStorageAreaId, storageAreaId)
                .eq(pointId != null, PestDisposalTask::getPointId, pointId)
                .eq(pestSpecies != null && !pestSpecies.isEmpty(),
                        PestDisposalTask::getPestSpecies, pestSpecies)
                .orderByDesc(PestDisposalTask::getFirstAt)
                .orderByDesc(PestDisposalTask::getId));
    }

    public List<PestTaskPhoto> listTaskPhotos(Long taskId) {
        return taskPhotoMapper.selectList(new LambdaQueryWrapper<PestTaskPhoto>()
                .eq(PestTaskPhoto::getTaskId, taskId)
                .orderByAsc(PestTaskPhoto::getId));
    }

    /** 检查单报表：单据头（标明规则版本）+ 虫种明细（阈值/结论快照）+ 现场照片 */
    public Map<String, Object> checkReport(Long checkId) {
        PestCheck check = requireCheck(checkId);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("check", check);
        report.put("ruleVersionId", check.getRuleVersionId());
        report.put("ruleVersionNo", check.getRuleVersionNo());
        report.put("periodMonth", check.getPeriodMonth());
        report.put("lines", listLines(checkId));
        report.put("photos", listCheckPhotos(checkId));
        return report;
    }

    /** 处置任务报表：任务头（标明责任区域与首警规则版本）+ 现场照片 */
    public Map<String, Object> taskReport(Long taskId) {
        PestDisposalTask task = requireTask(taskId);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("task", task);
        report.put("ruleVersionId", task.getRuleVersionId());
        report.put("ruleVersionNo", task.getRuleVersionNo());
        report.put("photos", listTaskPhotos(taskId));
        return report;
    }

    // ================================================================
    // 辅助
    // ================================================================

    private void rejectOutsourced(String role, String action) {
        if (ROLE_OUTSOURCER.equals(role == null ? null : role.trim())) {
            throw new IllegalArgumentException("外包人员只能提交现场监测数据与现场照片，不能"
                    + action + "处置任务");
        }
    }

    static String mergeKey(Long pointId, String species) {
        return pointId + "|" + species;
    }
}
