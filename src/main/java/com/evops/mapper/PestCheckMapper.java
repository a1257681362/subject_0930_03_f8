package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.PestCheck;
import org.apache.ibatis.annotations.Select;

public interface PestCheckMapper extends BaseMapper<PestCheck> {

    /** 检查单编号取号（H2 序列），检查单号在插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_pest_check_no")
    Long nextCheckNo();
}
