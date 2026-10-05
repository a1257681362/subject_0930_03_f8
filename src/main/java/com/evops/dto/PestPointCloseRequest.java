package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 诱捕点位停用：只置 CLOSED 状态与停用原因，历史检查/换片/任务全部保留。
 */
@Data
public class PestPointCloseRequest {

    @NotBlank(message = "停用原因不能为空")
    private String reason;

    @NotBlank(message = "操作人不能为空")
    private String operator;
}
