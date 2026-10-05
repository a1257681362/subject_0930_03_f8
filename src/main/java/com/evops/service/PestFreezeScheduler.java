package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.config.MuseumPestProperties;
import com.evops.entity.PestCheck;
import com.evops.entity.PestMonthFreeze;
import com.evops.mapper.PestCheckMapper;
import com.evops.mapper.PestMonthFreezeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static com.evops.constant.MuseumConstants.*;

/**
 * 监测结果月度冻结：
 *
 * <p>定时扫描在每月（及重启补扫）把<b>已结束的上月及更早</b>未冻结月份按全馆冻结。
 * 当月永不冻结。冻结记录 (period_month, scope_type, scope_id) 唯一，天然幂等：
 * 重启后重复扫描同一月份不会产生第二条冻结记录；冻结后该月现场监测不可补录/修改。</p>
 */
@Component
public class PestFreezeScheduler {

    private static final Logger log = LoggerFactory.getLogger(PestFreezeScheduler.class);
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PestMonthFreezeMapper freezeMapper;
    private final PestCheckMapper checkMapper;
    private final PestLedgerService ledgerService;
    private final MuseumPestProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock scanLock = new ReentrantLock();
    private final AtomicBoolean startedUp = new AtomicBoolean(false);

    public PestFreezeScheduler(PestMonthFreezeMapper freezeMapper,
                               PestCheckMapper checkMapper,
                               PestLedgerService ledgerService,
                               MuseumPestProperties properties,
                               PlatformTransactionManager transactionManager) {
        this.freezeMapper = freezeMapper;
        this.checkMapper = checkMapper;
        this.ledgerService = ledgerService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void freezeOnStartup() {
        if (properties.isFreezeOnStartup() && startedUp.compareAndSet(false, true)) {
            try {
                log.info("虫害监测：启动补冻结上月及更早未冻结月份");
                freezeDueMonths();
            } catch (Exception e) {
                log.warn("启动补冻结虫害月份失败（将由周期扫描重试）: {}", e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelayString = "${museum.pest.freeze-scan-interval-ms:3600000}")
    public void freezeOnSchedule() {
        freezeDueMonths();
    }

    /**
     * 冻结所有已结束（早于当月）且尚无全馆冻结记录的月份。公开供接口/测试手动触发。
     *
     * @return 本轮新冻结的月份数
     */
    public int freezeDueMonths() {
        if (!scanLock.tryLock()) {
            log.debug("虫害月度冻结上一轮尚未结束，本轮跳过");
            return 0;
        }
        try {
            YearMonth current = YearMonth.now();
            // 取库中最早的检查月份，逐月冻结到上月
            PestCheck earliest = checkMapper.selectOne(new LambdaQueryWrapper<PestCheck>()
                    .orderByAsc(PestCheck::getPeriodMonth)
                    .last("LIMIT 1"));
            if (earliest == null) {
                return 0;
            }
            int created = 0;
            YearMonth cursor = YearMonth.parse(earliest.getPeriodMonth());
            YearMonth lastMonth = current.minusMonths(1);
            while (!cursor.isAfter(lastMonth)) {
                String month = cursor.format(MONTH_FORMAT);
                if (freezeMonth(month, "SYSTEM", null)) {
                    created++;
                }
                cursor = cursor.plusMonths(1);
            }
            if (created > 0) {
                log.info("虫害监测月度冻结：新冻结 {} 个月份", created);
            }
            return created;
        } finally {
            scanLock.unlock();
        }
    }

    /**
     * 冻结指定月份的全馆监测结果（幂等：已冻结返回 false）。
     * 每月独立事务：并发/重启撞唯一约束时整月回滚由调用方跳过，不产生半成品记录。
     */
    public boolean freezeMonth(String periodMonth, String frozenBy, String remark) {
        YearMonth target = YearMonth.parse(periodMonth);
        if (!target.isBefore(YearMonth.now())) {
            throw new IllegalArgumentException("当月监测结果不能冻结，只能冻结已结束的历史月份: " + periodMonth);
        }
        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                doFreezeMonth(periodMonth, frozenBy, remark)));
    }

    private boolean doFreezeMonth(String periodMonth, String frozenBy, String remark) {
        boolean exists = freezeMapper.selectCount(new LambdaQueryWrapper<PestMonthFreeze>()
                .eq(PestMonthFreeze::getPeriodMonth, periodMonth)
                .eq(PestMonthFreeze::getScopeType, PEST_SCOPE_ALL)
                .isNull(PestMonthFreeze::getScopeId)) > 0;
        if (exists) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        long checkCount = checkMapper.selectCount(new LambdaQueryWrapper<PestCheck>()
                .eq(PestCheck::getPeriodMonth, periodMonth));

        PestMonthFreeze freeze = new PestMonthFreeze();
        freeze.setPeriodMonth(periodMonth);
        freeze.setScopeType(PEST_SCOPE_ALL);
        freeze.setScopeId(null);
        freeze.setFrozenBy(frozenBy);
        freeze.setFrozenAt(now);
        freeze.setCheckCount((int) checkCount);
        freeze.setRemark(remark);
        try {
            freezeMapper.insert(freeze);
        } catch (DuplicateKeyException dup) {
            // 并发/重启下已由另一方冻结：不重复写记录
            return false;
        }
        ledgerService.append(null, null, null, null, null, null, null,
                ACTION_MONTH_FREEZE, periodMonth,
                "虫害监测结果 " + periodMonth + " 全馆按月冻结，纳入检查单 " + checkCount
                        + " 份；冻结后该月现场数据不可补录/修改，操作人 " + frozenBy, now);
        return true;
    }

    public java.util.List<PestMonthFreeze> listFreezes() {
        return freezeMapper.selectList(new LambdaQueryWrapper<PestMonthFreeze>()
                .orderByDesc(PestMonthFreeze::getPeriodMonth)
                .orderByAsc(PestMonthFreeze::getScopeType));
    }
}
