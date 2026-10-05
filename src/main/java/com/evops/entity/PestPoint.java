package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 诱捕点位：展厅（HALL，hall_id）或库房库区（STORAGE，storage_area_id）二选一布设。
 *
 * <ul>
 *   <li>ACTIVE：在测；</li>
 *   <li>MOVED：已迁移，moved_to_id 指向新点位行（迁移另立新行、旧址坐标保留）；</li>
 *   <li>CLOSED：已停用，历史检查/换片/任务全部保留。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_point")
public class PestPoint extends BaseEntity {

    private String pointNo;

    /** HALL / STORAGE */
    private String locationType;

    private Long hallId;
    private Long storageAreaId;

    /** 责任区域编号/名称在布点时快照，区域后续改名不影响历史点位口径 */
    private String areaNoSnapshot;
    private String areaNameSnapshot;

    /** 责任保管员（处置任务默认关联责任人） */
    private String custodian;

    private BigDecimal coordX;
    private BigDecimal coordY;
    private String coordDesc;

    private LocalDateTime laidAt;

    /** 诱捕器类型（粘虫板/信息素诱捕器等） */
    private String trapType;

    /** ACTIVE / MOVED / CLOSED */
    private String status;

    private String closedReason;
    private LocalDateTime closedAt;

    /** 迁移后指向的新点位 id */
    private Long movedToId;
}
