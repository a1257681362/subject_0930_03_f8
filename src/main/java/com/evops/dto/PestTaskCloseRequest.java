package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 处置任务关闭：馆内责任岗位复核关闭，关闭即封存。
 * 关闭前必须已登记处置并有至少一张现场照片；外包人员（OUTSOURCER）禁止关闭。
 */
@Data
public class PestTaskCloseRequest {

    @NotBlank(message = "关闭人不能为空")
    private String closedBy;

    /** 关闭人岗位，OUTSOURCER 将被拒绝 */
    @NotBlank(message = "关闭人岗位不能为空")
    private String closedByRole;

    private String closeRemark;
}
