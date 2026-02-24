package com.wzz.smscode.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzz.smscode.entity.UserProjectQuotaLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserProjectQuotaLedgerMapper extends BaseMapper<UserProjectQuotaLedger> {

    /**
     * 通过业务号查询流水，用于幂等判断
     */
    @Select("SELECT * FROM user_project_quota_ledger WHERE biz_no = #{bizNo} LIMIT 1")
    UserProjectQuotaLedger selectByBizNo(@Param("bizNo") String bizNo);
}
