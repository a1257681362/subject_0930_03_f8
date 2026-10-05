package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 处置任务登记处置：OPEN -> HANDLING。
 * 外包人员（OUTSOURCER）禁止登记处置；处置后任务仍接受连续超阈检查并入。
 */
@Data
public class PestTaskHandleRequest {

    @NotBlank(message = "处置人不能为空")
    private String handler;

    /** 处置人岗位，OUTSOURCER 将被拒绝 */
    @NotBlank(message = "处置人岗位不能为空")
    private String handlerRole;

    @NotBlank(message = "处置说明不能为空")
    private String handleRemark;
}
