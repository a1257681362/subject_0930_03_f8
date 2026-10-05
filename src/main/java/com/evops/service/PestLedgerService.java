package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.PestLedger;
import com.evops.mapper.PestLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 虫害台账：只追加、不修改、不删除。
 * 规则发布/布点/停用/迁移/换片/检查/任务开闭/月度冻结全部留痕，
 * 每条尽量固化规则版本号与责任区域，虫害监测全程可追溯。
 */
@Service
public class PestLedgerService {

    private final PestLedgerMapper ledgerMapper;

    public PestLedgerService(PestLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void append(Long pointId, Long checkId, Long taskId,
                       Long hallId, Long storageAreaId,
                       Long ruleVersionId, String ruleVersionNo,
                       String action, String bizNo, String summary,
                       LocalDateTime occurredAt) {
        PestLedger entry = new PestLedger();
        entry.setPointId(pointId);
        entry.setCheckId(checkId);
        entry.setTaskId(taskId);
        entry.setHallId(hallId);
        entry.setStorageAreaId(storageAreaId);
        entry.setRuleVersionId(ruleVersionId);
        entry.setRuleVersionNo(ruleVersionNo);
        entry.setAction(action);
        entry.setBizNo(bizNo);
        entry.setSummary(summary);
        entry.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        ledgerMapper.insert(entry);
    }

    public List<PestLedger> list(Long pointId, Long taskId, Long hallId, Long storageAreaId) {
        return ledgerMapper.selectList(new LambdaQueryWrapper<PestLedger>()
                .eq(pointId != null, PestLedger::getPointId, pointId)
                .eq(taskId != null, PestLedger::getTaskId, taskId)
                .eq(hallId != null, PestLedger::getHallId, hallId)
                .eq(storageAreaId != null, PestLedger::getStorageAreaId, storageAreaId)
                .orderByAsc(PestLedger::getOccurredAt)
                .orderByAsc(PestLedger::getId));
    }
}
