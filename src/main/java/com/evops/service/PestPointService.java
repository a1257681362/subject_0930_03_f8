package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.PestPointCloseRequest;
import com.evops.dto.PestPointCreateRequest;
import com.evops.dto.PestPointMoveRequest;
import com.evops.dto.PestStripReplaceRequest;
import com.evops.entity.Hall;
import com.evops.entity.PestPoint;
import com.evops.entity.PestPointMove;
import com.evops.entity.PestTrapStrip;
import com.evops.entity.StorageArea;
import com.evops.mapper.HallMapper;
import com.evops.mapper.PestPointMapper;
import com.evops.mapper.PestPointMoveMapper;
import com.evops.mapper.PestTrapStripMapper;
import com.evops.mapper.StorageAreaMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 诱捕点位与诱捕片：
 *
 * <ol>
 *   <li>布放：展厅或库房库区二选一，固化责任区域编号/名称快照与责任保管员；</li>
 *   <li>迁移：旧址置 MOVED 并另立新点位行，迁移记录保留前后坐标，历史监测与任务留在旧址；</li>
 *   <li>停用：只置 CLOSED，不删除任何历史；</li>
 *   <li>换片：旧片置 REPLACED 留行，另立新片；旧片与旧监测记录永不删除。</li>
 * </ol>
 */
@Service
public class PestPointService {

    private final PestPointMapper pointMapper;
    private final PestPointMoveMapper moveMapper;
    private final PestTrapStripMapper stripMapper;
    private final HallMapper hallMapper;
    private final StorageAreaMapper areaMapper;
    private final PestLedgerService ledgerService;

    public PestPointService(PestPointMapper pointMapper,
                            PestPointMoveMapper moveMapper,
                            PestTrapStripMapper stripMapper,
                            HallMapper hallMapper,
                            StorageAreaMapper areaMapper,
                            PestLedgerService ledgerService) {
        this.pointMapper = pointMapper;
        this.moveMapper = moveMapper;
        this.stripMapper = stripMapper;
        this.hallMapper = hallMapper;
        this.areaMapper = areaMapper;
        this.ledgerService = ledgerService;
    }

    // ----------------------------------------------------------------
    // 布放
    // ----------------------------------------------------------------

    @Transactional
    public PestPoint lay(PestPointCreateRequest req) {
        String locationType = req.getLocationType().trim();
        if (!PEST_LOC_HALL.equals(locationType) && !PEST_LOC_STORAGE.equals(locationType)) {
            throw new IllegalArgumentException("点位区域类型只能为 HALL（展厅）或 STORAGE（库房）");
        }
        if (pointMapper.selectCount(new LambdaQueryWrapper<PestPoint>()
                .eq(PestPoint::getPointNo, req.getPointNo())) > 0) {
            throw new IllegalArgumentException("诱捕点位编号已存在: " + req.getPointNo());
        }

        PestPoint point = new PestPoint();
        point.setPointNo(req.getPointNo());
        point.setLocationType(locationType);
        point.setCoordX(req.getCoordX());
        point.setCoordY(req.getCoordY());
        point.setCoordDesc(req.getCoordDesc());
        point.setTrapType(req.getTrapType());
        point.setCustodian(req.getCustodian());
        point.setLaidAt(req.getLaidAt() == null ? LocalDateTime.now() : req.getLaidAt());
        point.setStatus(PEST_POINT_ACTIVE);

        String areaText;
        if (PEST_LOC_HALL.equals(locationType)) {
            if (req.getHallId() == null || req.getStorageAreaId() != null) {
                throw new IllegalArgumentException("展厅点位必须且只能指定 hallId");
            }
            Hall hall = hallMapper.selectById(req.getHallId());
            if (hall == null) {
                throw new IllegalArgumentException("展厅不存在: hallId=" + req.getHallId());
            }
            if (!HALL_ACTIVE.equals(hall.getStatus())) {
                throw new IllegalArgumentException("展厅已关闭，不能布放诱捕点位: " + hall.getHallNo());
            }
            point.setHallId(hall.getId());
            point.setAreaNoSnapshot(hall.getHallNo());
            point.setAreaNameSnapshot(hall.getName());
            if (point.getCustodian() == null || point.getCustodian().trim().isEmpty()) {
                point.setCustodian(hall.getCustodian());
            }
            areaText = "展厅 " + hall.getHallNo() + " " + hall.getName();
        } else {
            if (req.getStorageAreaId() == null || req.getHallId() != null) {
                throw new IllegalArgumentException("库房点位必须且只能指定 storageAreaId");
            }
            StorageArea area = areaMapper.selectById(req.getStorageAreaId());
            if (area == null) {
                throw new IllegalArgumentException("库房库区不存在: storageAreaId=" + req.getStorageAreaId());
            }
            if (!STORAGE_ACTIVE.equals(area.getStatus())) {
                throw new IllegalArgumentException("库区已关闭，不能布放诱捕点位: " + area.getAreaNo());
            }
            point.setStorageAreaId(area.getId());
            point.setAreaNoSnapshot(area.getAreaNo());
            point.setAreaNameSnapshot(area.getName());
            if (point.getCustodian() == null || point.getCustodian().trim().isEmpty()) {
                point.setCustodian(area.getManager());
            }
            areaText = "库房 " + area.getAreaNo() + " " + area.getName();
        }
        pointMapper.insert(point);

        ledgerService.append(point.getId(), null, null, point.getHallId(), point.getStorageAreaId(),
                null, null, ACTION_POINT_LAY, point.getPointNo(),
                "在" + areaText + "布放诱捕点位 " + point.getPointNo()
                        + "，坐标(" + point.getCoordX() + "," + point.getCoordY() + ")"
                        + (point.getCustodian() == null ? "" : "，责任保管员 " + point.getCustodian())
                        + "，布放日期 " + point.getLaidAt(), point.getLaidAt());
        return point;
    }

    // ----------------------------------------------------------------
    // 迁移：旧址 MOVED + 新点位行 + 迁移记录（前后坐标都保留）
    // ----------------------------------------------------------------

    @Transactional
    public PestPoint move(Long pointId, PestPointMoveRequest req) {
        PestPoint old = requirePoint(pointId);
        if (!PEST_POINT_ACTIVE.equals(old.getStatus())) {
            throw new IllegalArgumentException("点位" + old.getPointNo() + "已"
                    + (PEST_POINT_MOVED.equals(old.getStatus()) ? "迁移" : "停用")
                    + "，不能再次迁移");
        }
        if (pointMapper.selectCount(new LambdaQueryWrapper<PestPoint>()
                .eq(PestPoint::getPointNo, req.getNewPointNo())) > 0) {
            throw new IllegalArgumentException("新点位编号已存在: " + req.getNewPointNo());
        }
        LocalDateTime movedAt = req.getMovedAt() == null ? LocalDateTime.now() : req.getMovedAt();

        // 新点位行：承继区域与责任人，坐标用迁移目标
        PestPoint fresh = new PestPoint();
        fresh.setPointNo(req.getNewPointNo());
        fresh.setLocationType(old.getLocationType());
        fresh.setHallId(old.getHallId());
        fresh.setStorageAreaId(old.getStorageAreaId());
        fresh.setAreaNoSnapshot(old.getAreaNoSnapshot());
        fresh.setAreaNameSnapshot(old.getAreaNameSnapshot());
        fresh.setCustodian(old.getCustodian());
        fresh.setCoordX(req.getToCoordX());
        fresh.setCoordY(req.getToCoordY());
        fresh.setCoordDesc(req.getCoordDesc());
        fresh.setTrapType(old.getTrapType());
        fresh.setLaidAt(movedAt);
        fresh.setStatus(PEST_POINT_ACTIVE);
        pointMapper.insert(fresh);

        // 旧址置 MOVED 指向新点位（历史坐标保留在旧址行与迁移记录）
        old.setStatus(PEST_POINT_MOVED);
        old.setMovedToId(fresh.getId());
        pointMapper.updateById(old);

        PestPointMove move = new PestPointMove();
        move.setPointId(old.getId());
        move.setNewPointId(fresh.getId());
        move.setFromCoordX(old.getCoordX());
        move.setFromCoordY(old.getCoordY());
        move.setToCoordX(req.getToCoordX());
        move.setToCoordY(req.getToCoordY());
        move.setReason(req.getReason());
        move.setMovedBy(req.getMovedBy());
        move.setMovedAt(movedAt);
        moveMapper.insert(move);

        ledgerService.append(old.getId(), null, null, old.getHallId(), old.getStorageAreaId(),
                null, null, ACTION_POINT_MOVE, fresh.getPointNo(),
                "点位 " + old.getPointNo() + " 由(" + old.getCoordX() + "," + old.getCoordY()
                        + ")迁移至新点位 " + fresh.getPointNo() + "("
                        + req.getToCoordX() + "," + req.getToCoordY() + ")，操作人 " + req.getMovedBy()
                        + (req.getReason() == null ? "" : "，原因：" + req.getReason())
                        + "；旧址监测/换片/任务记录全部保留", movedAt);
        return fresh;
    }

    // ----------------------------------------------------------------
    // 停用：只置状态
    // ----------------------------------------------------------------

    @Transactional
    public PestPoint close(Long pointId, PestPointCloseRequest req) {
        PestPoint point = requirePoint(pointId);
        if (!PEST_POINT_ACTIVE.equals(point.getStatus())) {
            throw new IllegalArgumentException("点位" + point.getPointNo() + "非在测状态，不能停用");
        }
        LocalDateTime now = LocalDateTime.now();
        point.setStatus(PEST_POINT_CLOSED);
        point.setClosedReason(req.getReason());
        point.setClosedAt(now);
        pointMapper.updateById(point);
        ledgerService.append(point.getId(), null, null, point.getHallId(), point.getStorageAreaId(),
                null, null, ACTION_POINT_CLOSE, point.getPointNo(),
                "诱捕点位 " + point.getPointNo() + " 停用：" + req.getReason()
                        + "，操作人 " + req.getOperator() + "；历史记录全部保留", now);
        return point;
    }

    // ----------------------------------------------------------------
    // 诱捕片安装/更换：旧片置 REPLACED 留行，不删除旧片与旧监测记录
    // ----------------------------------------------------------------

    @Transactional
    public PestTrapStrip replaceStrip(Long pointId, PestStripReplaceRequest req) {
        PestPoint point = requirePoint(pointId);
        if (!PEST_POINT_ACTIVE.equals(point.getStatus())) {
            throw new IllegalArgumentException("点位" + point.getPointNo() + "非在测状态，不能更换诱捕片");
        }
        if (stripMapper.selectCount(new LambdaQueryWrapper<PestTrapStrip>()
                .eq(PestTrapStrip::getStripNo, req.getNewStripNo())) > 0) {
            throw new IllegalArgumentException("诱捕片编号已存在: " + req.getNewStripNo());
        }
        LocalDateTime now = req.getOperatedAt() == null ? LocalDateTime.now() : req.getOperatedAt();

        PestTrapStrip current = currentStrip(pointId);
        String action;
        String summary;
        if (current == null) {
            action = ACTION_STRIP_INSTALL;
            summary = "点位 " + point.getPointNo() + " 首次安装诱捕片 " + req.getNewStripNo()
                    + "，操作人 " + req.getOperator();
        } else {
            if (req.getReplaceReason() == null || req.getReplaceReason().trim().isEmpty()) {
                throw new IllegalArgumentException("更换诱捕片必须填写更换原因");
            }
            // 旧片只置 REPLACED，不删除；旧片期间监测记录全部保留
            current.setStatus(STRIP_REPLACED);
            current.setReplacedAt(now);
            current.setReplacedBy(req.getOperator());
            current.setReplaceReason(req.getReplaceReason());
            stripMapper.updateById(current);
            action = ACTION_STRIP_REPLACE;
            summary = "点位 " + point.getPointNo() + " 诱捕片 " + current.getStripNo()
                    + " 更换为 " + req.getNewStripNo() + "，操作人 " + req.getOperator()
                    + "，原因：" + req.getReplaceReason() + "；旧片记录与旧监测记录保留不删";
        }

        PestTrapStrip strip = new PestTrapStrip();
        strip.setPointId(pointId);
        strip.setStripNo(req.getNewStripNo());
        strip.setInstalledAt(now);
        strip.setInstalledBy(req.getOperator());
        strip.setStatus(STRIP_IN_USE);
        stripMapper.insert(strip);

        ledgerService.append(pointId, null, null, point.getHallId(), point.getStorageAreaId(),
                null, null, action, strip.getStripNo(), summary, now);
        return strip;
    }

    // ----------------------------------------------------------------
    // 查询
    // ----------------------------------------------------------------

    public PestPoint requirePoint(Long pointId) {
        PestPoint point = pointMapper.selectById(pointId);
        if (point == null) {
            throw new IllegalArgumentException("诱捕点位不存在: id=" + pointId);
        }
        return point;
    }

    public List<PestPoint> listPoints(String locationType, Long hallId, Long storageAreaId,
                                      String status) {
        return pointMapper.selectList(new LambdaQueryWrapper<PestPoint>()
                .eq(locationType != null && !locationType.isEmpty(),
                        PestPoint::getLocationType, locationType)
                .eq(hallId != null, PestPoint::getHallId, hallId)
                .eq(storageAreaId != null, PestPoint::getStorageAreaId, storageAreaId)
                .eq(status != null && !status.isEmpty(), PestPoint::getStatus, status)
                .orderByAsc(PestPoint::getId));
    }

    public List<PestPointMove> listMoves(Long pointId) {
        return moveMapper.selectList(new LambdaQueryWrapper<PestPointMove>()
                .eq(PestPointMove::getPointId, pointId)
                .orderByAsc(PestPointMove::getMovedAt));
    }

    public List<PestTrapStrip> listStrips(Long pointId) {
        return stripMapper.selectList(new LambdaQueryWrapper<PestTrapStrip>()
                .eq(PestTrapStrip::getPointId, pointId)
                .orderByAsc(PestTrapStrip::getInstalledAt));
    }

    public PestTrapStrip currentStrip(Long pointId) {
        return stripMapper.selectOne(new LambdaQueryWrapper<PestTrapStrip>()
                .eq(PestTrapStrip::getPointId, pointId)
                .eq(PestTrapStrip::getStatus, STRIP_IN_USE)
                .last("LIMIT 1"));
    }
}
