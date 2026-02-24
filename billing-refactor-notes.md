# 计费模式整改说明（project_id + line_id 配额模式）

## 1. 改造目标

本次改造将系统计费模式从“金额/价格模板”切换为“项目线路配额（验证码个数）”。

核心规则：

- 计费维度：`user_id + project_id + line_id`
- 取号：先预扣 1 个配额
- 无验证码：退回 1 个配额
- 代理充值：必须扣代理同项目同线路配额，再加到下级
- 用户线路权限：仅当该线路配额 `> 0` 时有权限
- 用户余额查询：必须传 `project_id + line_id`，返回线路剩余配额

## 2. 新增内容

### 2.1 新增表模型

1. `user_project_quota`
- 作用：存储每个用户在每个项目线路下的可用配额
- 关键字段：`user_id`, `project_id`, `line_id`, `available_count`
- 关键约束：`(user_id, project_id, line_id)` 唯一

2. `user_project_quota_ledger`
- 作用：记录配额变动流水，支持业务幂等
- 关键字段：`biz_no`, `user_id`, `operator_id`, `project_id`, `line_id`, `change_count`, `ledger_type`, `count_before`, `count_after`
- 关键约束：`biz_no` 唯一

### 2.2 新增代码文件

1. `src/main/java/com/wzz/smscode/entity/UserProjectQuota.java`
2. `src/main/java/com/wzz/smscode/entity/UserProjectQuotaLedger.java`
3. `src/main/java/com/wzz/smscode/mapper/UserProjectQuotaMapper.java`
4. `src/main/java/com/wzz/smscode/mapper/UserProjectQuotaLedgerMapper.java`
5. `src/main/java/com/wzz/smscode/service/UserProjectQuotaService.java`
6. `src/main/java/com/wzz/smscode/service/impl/UserProjectQuotaServiceImpl.java`

### 2.3 新增服务能力

`UserProjectQuotaService` 新增能力：

1. 查询线路配额：`getAvailableCount`
2. 扣减配额（幂等）：`deductQuota`
3. 增加配额（幂等）：`addQuota`
4. 转移配额（代理扣自己给下级）：`transferQuota`
5. 查询有配额线路：`listAvailableLineIds`

## 3. 已改动内容

### 3.1 核心业务链路改动

1. `src/main/java/com/wzz/smscode/service/impl/NumberRecordServiceImpl.java`

改了什么：
- `getNumber`：去掉价格模板和余额判断，改为检查项目线路配额
- `createOrderTransaction`：改为写记录后按业务号 `NR_PRE_{recordId}` 预扣 1 个配额
- `updateRecordAfterRetrieval`：
  - 超时/失败：按 `NR_REFUND_{recordId}` 退回 1 个配额
  - 退款后获码成功：按 `NR_REDEDUCT_{recordId}` 再扣 1 个配额
- `batchRefundByQuery`：由金额退款改为批量退回配额

为什么改：
- 满足“先扣费、无码退费”的新配额模式
- 保证扣减与退款幂等，防重复扣退

改后作用：
- 取号/取码主流程完全脱离金额计费
- 以项目线路配额作为唯一计费依据

2. `src/main/java/com/wzz/smscode/service/impl/ProjectServiceImpl.java`

改了什么：
- `listLinesWithCamelCaseKeyFor` 不再依赖模板，改为按 `user_project_quota.available_count > 0` 返回线路

为什么改：
- 用户线路权限改为“是否有该线路配额”

改后作用：
- 无配额线路自然无权限

3. `src/main/java/com/wzz/smscode/service/impl/UserServiceImpl.java`

改了什么：
- `getBalance` 改为按 `project_id + line_id` 返回配额
- `chargeUser/rechargeUser/deductUser` 改为配额操作（后台）
- `rechargeUserFromAgentBalance/deductUserToAgentBalance` 改为代理与下级之间的同项目线路配额转移
- `createUser` 去除对模板和初始金额记账的强依赖
- `processRebates` 旧金额返点逻辑改为停用日志
- `batchFundOperation` 明确抛出“旧金额接口已弃用”

为什么改：
- 后台与代理侧充值/扣款必须统一到配额体系
- 完全停止旧金额模式副作用

改后作用：
- 管理端、代理端、用户端全链路统一配额口径

### 3.2 接口层改动

1. 用户接口：`src/main/java/com/wzz/smscode/controller/user/UserController.java`

- `GET/POST /api/user/getNumber`
  - 变更：`lineId` 参数改为 `String`
  - 作用：统一线路ID字符串化

- `GET/POST /api/user/getBalance`
  - 旧参数：`userName`, `password`
  - 新参数：`userName`, `password`, `projectId`, `lineId`
  - 新返回：线路配额数量（`Long`）
  - 作用：用户余额查询改为“项目线路配额查询”

2. 代理接口：`src/main/java/com/wzz/smscode/controller/agent/AgentController.java`

- `POST /api/agent/rechargeUser`
  - 旧参数：`targetUserId`, `amount`
  - 新参数：`targetUserId`, `projectId`, `lineId`, `count`

- `POST /api/agent/deductUser`
  - 旧参数：`targetUserId`, `amount`
  - 新参数：`targetUserId`, `projectId`, `lineId`, `count`

作用：
- 强制代理按“同项目同线路配额”对下级进行充值/扣减

3. 管理员接口：`src/main/java/com/wzz/smscode/controller/admin/AdminController.java`

- `POST /api/admin/rechargeUser`
  - 旧参数：`targetUserId`, `amount`
  - 新参数：`targetUserId`, `projectId`, `lineId`, `count`

- `POST /api/admin/deductUser`
  - 旧参数：`targetUserId`, `amount`
  - 新参数：`targetUserId`, `projectId`, `lineId`, `count`

作用：
- 后台资金调整改为后台配额调整

### 3.3 线路ID类型统一改动

改了什么：
- `NumberRecord.lineId` 改为 `String`
- 查询/统计 DTO 的 `lineId` 统一改为 `String`
- `ProjectService.getProject`、缓存组件 `NumberRecordCacheManager` 的线路参数改为 `String`

涉及文件：

1. `src/main/java/com/wzz/smscode/entity/NumberRecord.java`
2. `src/main/java/com/wzz/smscode/dto/number/NumberDTO.java`
3. `src/main/java/com/wzz/smscode/dto/StatisticsQueryDTO.java`
4. `src/main/java/com/wzz/smscode/dto/SubordinateNumberRecordQueryDTO.java`
5. `src/main/java/com/wzz/smscode/dto/UserLineStatsDTO.java`
6. `src/main/java/com/wzz/smscode/dto/LineStatisticsDTO.java`
7. `src/main/java/com/wzz/smscode/service/ProjectService.java`
8. `src/main/java/com/wzz/smscode/service/impl/ProjectServiceImpl.java`
9. `src/main/java/com/wzz/smscode/cacheManager/NumberRecordCacheManager.java`
10. `src/main/java/com/wzz/smscode/moduleService/SmsApiService.java`
11. `src/main/java/com/wzz/smscode/service/NumberRecordService.java`

## 4. 弃用内容（接口与逻辑）

以下为“已明确弃用”的旧金额能力：

1. 价格模板参与取号计费
- 已不再用于 `getNumber` 的扣费依据

2. 旧金额返点流程
- `UserServiceImpl.processRebates` 已停用（仅记录日志）

3. 旧金额批量资金接口
- `UserServiceImpl.batchFundOperation` 已明确抛出“已弃用”异常

4. 用户创建时模板/初始金额入账逻辑
- 创建用户不再依赖模板和金额充值逻辑

5. 旧金额账本相关接口已下线（保留路由，统一返回“已下线”）
- 管理端：
  - `GET/POST /api/admin/viewUserLedger`
  - `GET/POST /api/admin/viewAllLedger`
  - `POST /api/admin/ledger/clear-physical`
  - `GET /api/admin/get/user-id/leader/`
- 代理端：
  - `GET /api/agent/viewUserLedger`
  - `GET /api/agent/subordinate-ledgers`
  - `GET /api/agent/my-ledger`
  - `POST /api/agent/ledger/clear`
  - `GET /api/agent/by-user/totalProfit`
- 用户端：
  - `GET/POST /api/user/ledger/list`
  - `GET/POST /api/user/ledger/clear`

为什么改：
- 这些接口全部依赖旧金额账本模型，不符合“按项目线路配额计费”的新规则。

改后作用：
- 从接口层彻底阻断旧金额逻辑继续被调用，避免新旧计费混用。

## 5. 本轮物理删除的旧金额代码文件

以下文件已从代码库删除：

1. `src/main/java/com/wzz/smscode/service/UserLedgerService.java`
2. `src/main/java/com/wzz/smscode/service/impl/UserLedgerServiceImpl.java`
3. `src/main/java/com/wzz/smscode/mapper/UserLedgerMapper.java`
4. `src/main/java/com/wzz/smscode/entity/UserLedger.java`
5. `src/main/java/com/wzz/smscode/dto/CreatDTO/LedgerCreationDTO.java`
6. `src/main/java/com/wzz/smscode/dto/EntityDTO/LedgerDTO.java`
7. `src/main/java/com/wzz/smscode/enums/FundType.java`
8. `src/main/java/com/wzz/smscode/util/BalanceUtil.java`

为什么改：
- 这些文件属于旧金额账本核心模型与工具，继续保留会引入误用风险。

改后作用：
- 编译期即可阻断任何新代码继续依赖旧金额体系。

## 6. 兼容性说明

1. 旧金额账本服务、实体、Mapper、DTO、工具类已物理删除。
2. 对外账本接口已下线并返回明确错误提示，防止旧前端误调用。
3. 价格模板管理接口仍在（用于历史管理功能），但不再参与取号扣费链路。
4. `User.balance` 等历史字段尚未做数据库结构删除（如需删库字段，建议单独执行DDL变更窗口）。

## 7. 验证结果

已执行：

- `sh mvnw -q -DskipTests compile`

结果：

- 编译通过。
