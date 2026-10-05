package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 现场监测提交：一个点位一次检查一单，逐虫种登记数量。
 *
 * <ul>
 *   <li>requestNo 请求流水号全库唯一：现场弱网补传/重试复用同一流水号，只形成一单；</li>
 *   <li>inspectorRole=OUTSOURCER（外包人员）允许提交本单与现场照片，但不能处置/关闭任务；</li>
 *   <li>按提交时生效规则版本逐虫种判定并固化阈值/结论；所属月份冻结后拒绝补录。</li>
 * </ul>
 */
@Data
public class PestCheckSubmitRequest {

    @NotBlank(message = "请求流水号不能为空")
    private String requestNo;

    @NotBlank(message = "检查人不能为空")
    private String inspector;

    /** CURATOR / WAREHOUSE_KEEPER / RESTORER / OUTSOURCER 等岗位码 */
    @NotBlank(message = "检查人岗位不能为空")
    private String inspectorRole;

    /** 检查时间，缺省取当前时间（决定所属月份） */
    private LocalDateTime checkedAt;

    private String remark;

    @NotEmpty(message = "虫种数量明细不能为空")
    @Valid
    private List<PestCountItem> items;

    @Data
    public static class PestCountItem {

        @NotBlank(message = "虫种不能为空")
        private String pestSpecies;

        @NotNull(message = "虫种数量不能为空")
        @Min(value = 0, message = "虫种数量不能为负")
        private Integer count;
    }
}
