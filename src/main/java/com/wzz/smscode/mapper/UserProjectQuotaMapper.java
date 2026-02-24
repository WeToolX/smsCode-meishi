package com.wzz.smscode.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzz.smscode.entity.UserProjectQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserProjectQuotaMapper extends BaseMapper<UserProjectQuota> {

    /**
     * 按用户+项目+线路查询并加锁
     */
    @Select("SELECT * FROM user_project_quota WHERE user_id = #{userId} AND project_id = #{projectId} AND line_id = #{lineId} FOR UPDATE")
    UserProjectQuota selectByUserProjectLineForUpdate(@Param("userId") Long userId,
                                                      @Param("projectId") String projectId,
                                                      @Param("lineId") String lineId);
}
