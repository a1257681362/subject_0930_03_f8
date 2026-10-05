package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 现场监测记录（检查单）：一个点位一次检查一单。
 *
 * <ul>
 *   <li>request_no 请求流水号唯一：现场弱网补传/重试只形成一单；</li>
 *   <li>rule_version_* 为提交时生效规则版本快照，后续版本发布不改写本单结论；</li>
 *   <li>period_month 为检查所属月份（yyyy-MM），该月冻结后不可补录/修改。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_check")
public class PestCheck extends BaseEntity {

    private String requestNo;
    private String checkNo;

    private Long pointId;

    private Long ruleVersionId;
    private String ruleVersionNo;

    /** 检查所属月 yyyy-MM */
    private String periodMonth;

    private LocalDateTime checkedAt;

    /** 现场提交人 */
    private String inspector;

    /** 提交时岗位快照（外包人员标识随单固化） */
    private String inspectorRole;

    /** 检查时点位上在用的诱捕片（可空） */
    private Long stripId;
    private String stripNo;

    /** 0 正常（至多预警）/ 1 有虫种达到处置阈值 */
    private Integer abnormal;

    private String remark;
}
