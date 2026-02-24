package com.wzz.smscode.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.wzz.smscode.common.CommonResultDTO;
import com.wzz.smscode.dto.AddUserProjectPricesRequestDTO;
import com.wzz.smscode.dto.BatchChargeRequestDTO;
import com.wzz.smscode.dto.agent.AgentDashboardStatsDTO;
import com.wzz.smscode.dto.agent.AgentProjectLineUpdateDTO;
import com.wzz.smscode.dto.agent.AgentProjectPriceDTO;
import com.wzz.smscode.dto.CreatDTO.UserCreateDTO;
import com.wzz.smscode.dto.EntityDTO.UserDTO;
import com.wzz.smscode.dto.LoginDTO.UserLoginDto;
import com.wzz.smscode.dto.ResultDTO.UserResultDTO;
import com.wzz.smscode.dto.project.SubUserProjectPriceDTO;
import com.wzz.smscode.dto.update.UpdateUserDto;
import com.wzz.smscode.dto.update.UserUpdateDtoByUser;
import com.wzz.smscode.dto.update.UserUpdatePasswardDTO;
import com.wzz.smscode.entity.NumberRecord;
import com.wzz.smscode.entity.User;
import com.wzz.smscode.entity.UserProjectLine;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

public interface UserService extends IService<User> {
    User authenticate(Long userId, String password);

    User authenticateUserByUserName(String userId, String password);

    User authenticateUserByUserName(String userName, String password, boolean useCache);

    @Transactional(rollbackFor = Exception.class)
    void deleteSubUsersBatch(List<Long> userIds, Long agentId);


    CommonResultDTO<Long> getBalance(String userName, String password, String projectId, String lineId);

    @Transactional
    boolean createUser(UserCreateDTO dto, Long operatorId);



    IPage<User> listSubUsers(String userName, Long operatorId, IPage<User> page);

    @Transactional
    CommonResultDTO<?> chargeUser(Long targetUserId, String projectId, String lineId, Long count, Long operatorId, boolean isRecharge);

    CommonResultDTO<?> rechargeUser(Long targetUserId, String projectId, String lineId, Long count, Long operatorId);

    CommonResultDTO<?> deductUser(Long targetUserId, String projectId, String lineId, Long count, Long operatorId);


    @Scheduled(cron = "0 0 0 * * ?")
    void resetDailyStatsAllUsers();

    User getByUserName(String userName);

    Boolean login(UserLoginDto userLoginDto);

    Boolean regist(UserResultDTO userDTO);

    AgentDashboardStatsDTO getAgentDashboardStats(Long agentId);

    User findAndLockById(Long userId);

    User AgentLogin(String username, String password);


    boolean updateUserByAgent(UserUpdateDtoByUser userDTO, long l);

    Boolean updatePassWardByUserId(UpdateUserDto id);

    Boolean updatePassWardByUserName(UserUpdatePasswardDTO updateUserDto);

    @Transactional(rollbackFor = Exception.class)
    void rechargeUserFromAgentBalance(Long targetUserId, String projectId, String lineId, Long count, Long agentId);

    @Transactional(rollbackFor = Exception.class)
    void deductUserToAgentBalance(Long targetUserId, String projectId, String lineId, Long count, Long agentId);


    @Transactional
    void updateAgentProjectConfig(Long agentId, AgentProjectLineUpdateDTO updateDTO);

    IPage<SubUserProjectPriceDTO> getSubUsersProjectPrices(String userName,Long agentId, Page<User> page);

    List<Long> findUserIdsByUsernameLike(String username);

    boolean delectByuserId(Long userId);

    void updateUserStats(Long userId);

    /**
     * [新增] 处理多级代理业务返款
     * @param successfulRecord 成功扣费的号码记录
     */
    void processRebates(NumberRecord successfulRecord);

    @Transactional(rollbackFor = Exception.class)
    int batchFundOperation(BatchChargeRequestDTO dto, Long operatorId);
}
