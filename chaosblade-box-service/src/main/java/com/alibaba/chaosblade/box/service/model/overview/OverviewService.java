package com.alibaba.chaosblade.box.service.model.overview;

import com.alibaba.chaosblade.box.common.common.domain.ChaosError;
import com.alibaba.chaosblade.box.common.common.domain.PageQueryResponse;
import com.alibaba.chaosblade.box.common.common.domain.Response;
import com.alibaba.chaosblade.box.common.common.domain.user.ChaosUser;
import com.alibaba.chaosblade.box.common.common.enums.CommonErrorCode;
import com.alibaba.chaosblade.box.common.common.enums.ExperimentStateEnum;
import com.alibaba.chaosblade.box.common.infrastructure.util.CollectionUtil;
import com.alibaba.chaosblade.box.dao.mapper.CloudManualMapper;
import com.alibaba.chaosblade.box.dao.model.*;
import com.alibaba.chaosblade.box.dao.query.ExperimentQuery;
import com.alibaba.chaosblade.box.dao.repository.*;
import com.alibaba.chaosblade.box.dao.query.CloudDeviceQuery;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author sunpeng
 * 
 *
 */
@Component
public class OverviewService {

    @Resource
    private DeviceRepository deviceRepository;

    @Resource
    private CloudManualMapper cloudManualMapper;

    @Autowired
    private SceneFunctionRepository sceneFunctionRepository;

    @Autowired
    private ExpertiseRepository expertiseRepository;

    @Autowired
    private ExperimentRepository experimentRepository;

    @Autowired
    private OverviewProductLoadFactory overviewProductLoadFactory;

    @Autowired
    private ExperimentTaskRepository experimentTaskRepository;

    private List<OverviewScene> defaultUserScene = new ArrayList<OverviewScene>() {{
        add(new OverviewScene("主机内Cpu满载", "chaos.cpu.fullload", 1, "系统资源", "CPU资源"));
        add(new OverviewScene("主机内网络延迟", "chaos.network.delay", 2, "系统资源", "网络延迟"));
        add(new OverviewScene("主机内Java抛出自定义异常", "chaos.jvm.throwCustomException", 3, "JAVA应用", "抛异常"));
        add(new OverviewScene("主机内Java脚本", "chaos.jvm.script", 4, "JAVA应用", "自定义故障"));
    }};

    private Cache<String, List<OverviewScene>> sceneCache = CacheBuilder.newBuilder().maximumSize(1000)
            .build();

    public Response<OverviewExperimentTask> getUserExperimentDayCount(ChaosUser chaosUser) {
        Date monthDate = getStartTimeByYear();
        Map<Date, OverviewExperimentTaskDayCount> result = initResult(monthDate, getEndTimeByYear());
        SimpleDateFormat fo = new SimpleDateFormat("yyyy-MM-dd");
        cloudManualMapper.countExperimentTaskByDate(chaosUser.getUserId(), monthDate)
                .stream().map(
                stringObjectMap -> {
                    OverviewExperimentTaskDayCount experimentTaskCount = new OverviewExperimentTaskDayCount();
                    try {
                        experimentTaskCount.setTime(fo.parse(stringObjectMap.get("date").toString()));
                    } catch (ParseException e) {
                    }
                    experimentTaskCount.setTotalCount(Integer.parseInt(stringObjectMap.get("total").toString()));
                    return experimentTaskCount;
                }).forEach(experimentTaskCount -> {
            if (result.containsKey(experimentTaskCount.getTime())) {
                result.put(experimentTaskCount.getTime(), experimentTaskCount);
            }
        });
        OverviewExperimentTask overviewExperimentTask = new OverviewExperimentTask();
        overviewExperimentTask.setExperimentTaskDayCountList(result.values().stream().sorted(Comparator.comparing(OverviewExperimentTaskDayCount::getTime)).collect(
                Collectors.toList()));
        ExperimentTaskDO experimentTaskDO = experimentTaskRepository.findUserLastFinishedExperimentTask(chaosUser.getUserId());
        overviewExperimentTask.setLastExperimentTaskTime(null != experimentTaskDO ? experimentTaskDO.getGmtCreate() : null);
        return Response.okWithData(overviewExperimentTask);
    }

    public Response<OverviewExperimentCount> getUserExperimentCount(OverviewRequest overviewRequest) {
        ExperimentQuery experimentQuery = new ExperimentQuery();
        experimentQuery.setUser(overviewRequest.getUser());
        if (!Strings.isNullOrEmpty(overviewRequest.getNamespace())) {
            experimentQuery.setNamespace(overviewRequest.getNamespace());
        }
        experimentQuery.setUser(overviewRequest.getUser());
        List<ExperimentDO> experiments = experimentRepository.find(experimentQuery);

        OverviewExperimentCount overviewExperimentCount = new OverviewExperimentCount();
        overviewExperimentCount.setTotal(experiments.size());

        long active = 0L, running = 0L, failed = 0L, success = 0L, idle = 0L, finishedCount = 0L;

        for (ExperimentDO experiment : experiments) {
            if (experiment.getExperimentTaskId() == null) {
                idle++;
            } else {
                active++;
                ExperimentStateEnum experimentStateEnum = experiment.getExperimentStateEnum();
                if (ExperimentStateEnum.RUNNING.equals(experimentStateEnum)) {
                    running++;
                } else {
                    finishedCount++;
                    if (experiment.isSuccess()) {
                        success++;
                    } else {
                        failed++;
                    }
                }
            }
        }

        overviewExperimentCount.setIdle(idle);
        overviewExperimentCount.setFinished(finishedCount);
        overviewExperimentCount.setRunning(running);
        overviewExperimentCount.setActive(active);
        overviewExperimentCount.setFailure(failed);
        overviewExperimentCount.setSuccess(success);
        return Response.okWithData(overviewExperimentCount);
    }

    public Response<OverviewAgent> getUserAgentCount(ChaosUser chaosUser) {
        CloudDeviceQuery cloudDeviceQuery = new CloudDeviceQuery();
        cloudDeviceQuery.setUserId(chaosUser.getUserId());
        List<DeviceDO> deviceDOS = deviceRepository.getAliveDevices(cloudDeviceQuery);
        int hostTotal = 0;
        int hostOnline = 0;
        int hostNormal = 0;
        int hostError = 0;
        int clusterTotal = 0;
        int clusterTotalOnline = 0;
        int clusterTotalNormal = 0;
        int clusterTotalError = 0;

        for (DeviceDO deviceDO : deviceDOS) {
            if (Strings.isNullOrEmpty(deviceDO.getClusterId())) {
                //主机
                hostTotal++;
                if (DeviceDO.STATUS_ENABLED == deviceDO.getStatus()) {
                    hostOnline++;
                }
            } else {
                //集群
                clusterTotal++;
                if (DeviceDO.STATUS_ENABLED == deviceDO.getStatus()) {
                    clusterTotalOnline++;
                }
            }
        }
        OverviewAgentInfo host = new OverviewAgentInfo(hostTotal, hostOnline, hostNormal, hostError);
        OverviewAgentInfo cluster = new OverviewAgentInfo(clusterTotal, clusterTotalOnline, clusterTotalNormal, clusterTotalError);
        OverviewAgent overviewAgent = new OverviewAgent();
        overviewAgent.setHost(host);
        overviewAgent.setCluster(cluster);
        return Response.okWithData(overviewAgent);
    }

    public Response<OverviewProduct> getProductMessage(ChaosUser chaosUser) {
        OverviewProduct overviewProduct = new OverviewProduct();
        overviewProduct.setMessage(overviewProductLoadFactory.getMessage());
        overviewProduct.setPractice(overviewProductLoadFactory.getPractice());
        return Response.okWithData(overviewProduct);
    }

    public Response<List<OverviewScene>> getUserScene(ChaosUser chaosUser) {
        try {
            List<OverviewScene> result = sceneCache.get(chaosUser.getUserId(), new Callable<List<OverviewScene>>() {
                @Override
                public List<OverviewScene> call() throws Exception {
                    return cloudManualMapper.countAppCodeByUserId(chaosUser.getUserId(), getStartTime())
                            .stream()
                            .sorted((o1, o2) -> Integer.parseInt(o2.get("total").toString()) - Integer.parseInt(o1.get("total").toString()))
                            .limit(4)
                            .map(stringObjectMap -> {
                                OverviewScene overviewScene = new OverviewScene();
                                String appCode = String.valueOf(stringObjectMap.get("app_code"));
                                overviewScene.setAppCode(appCode);
                                overviewScene.setSceneTarget(getSceneTarget(appCode));
                                overviewScene.setSceneType(getSceneType(appCode));
                                overviewScene.setName(sceneFunctionRepository.findByCode(appCode).map(
                                        new Function<SceneFunctionDO, String>() {
                                            @Override
                                            public String apply(SceneFunctionDO sceneFunctionDO) {
                                                return sceneFunctionDO.getName();
                                            }
                                        }).orElse(null));
                                return overviewScene;
                            })
                            .collect(Collectors.toList());
                }
            });
            return Response.okWithData(CollectionUtil.isNullOrEmpty(result) ? defaultUserScene : result);
        } catch (Exception e) {
            return Response.failedWith(ChaosError.withCode(CommonErrorCode.B_OVERVIEW_USER_SCENE_QUERY_ERROR));
        }
    }


    public Response<PageQueryResponse<OverviewExpertise>> getUserExpertise(ChaosUser chaosUser, OverviewRequest overviewRequest) {
        IPage<ExpertiseDO> expertiseDOIPage = expertiseRepository.userFindPageable(
                chaosUser,
                null,
                null,
                null,
                overviewRequest.getPage(),
                overviewRequest.getSize());
        PageQueryResponse<OverviewExpertise> pageQueryResponse = new PageQueryResponse<>();
        pageQueryResponse.setPageSize(expertiseDOIPage.getSize());
        pageQueryResponse.setPages(expertiseDOIPage.getPages());
        pageQueryResponse.setTotal(expertiseDOIPage.getTotal());
        pageQueryResponse.setCurrentPage(expertiseDOIPage.getCurrent());
        pageQueryResponse.setContent(
                expertiseDOIPage.getRecords().stream().map(new Function<ExpertiseDO, OverviewExpertise>() {
                    @Override
                    public OverviewExpertise apply(ExpertiseDO expertiseDO) {
                        OverviewExpertise overviewExpertise = new OverviewExpertise();
                        overviewExpertise.setExpertiseId(expertiseDO.getExpertiseId());
                        overviewExpertise.setExpertiseName(expertiseDO.getName());
                        overviewExpertise.setExpertiseDescription(expertiseDO.getDesignConcept());
                        overviewExpertise.setExpertiseTargetType(OverviewExpertiseTargetType.CPU.name);
                        overviewExpertise.setExpertiseTargetIcon(OverviewExpertiseTargetType.CPU.icon);
                        return overviewExpertise;
                    }
                }).collect(
                        Collectors.toList()));
        return Response.okWithData(pageQueryResponse);
    }

    /**
     * 三个月
     *
     * @return
     */
    private Date getStartTime() {
        Date monthDate = DateUtils.truncate(DateUtils.addMonths(new Date(), -2),
                Calendar.MONTH);
        return monthDate;
    }

    private String getSceneTarget(String appCode) {
        //todo 整理个工具类
        if (Strings.isNullOrEmpty(appCode)) {
            return "";
        }
        if (appCode.contains("jvm")) {
            return "JAVA应用";
        }
        return "系统资源";
    }

    private String getSceneType(String appCode) {
        if (Strings.isNullOrEmpty(appCode)) {
            return "";
        }
        if (appCode.contains("cpu")) {
            return "CPU资源";
        }
        if (appCode.contains("disk")) {
            return "磁盘资源";
        }
        if (appCode.contains("delay")) {
            return "延迟";
        }
        if (appCode.contains("network")) {
            return "网络资源";
        }
        if (appCode.contains("mem")) {
            return "内存资源";
        }
        if (appCode.contains("script")) {
            return "自定义故障";
        }
        if (appCode.contains("sql")) {
            return "数据库";
        }
        if (appCode.contains("cloud")) {
            return "云服务";
        }
        if (appCode.contains("node")) {
            return "集群资源";
        }
        if (appCode.contains("pod")) {
            return "集群资源";
        }
        if (appCode.contains("container")) {
            return "集群资源";
        }
        return "";
    }

    private Date getStartTimeByYear() {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date());
        c.add(Calendar.MONTH, -11);
        c.set(Calendar.DAY_OF_MONTH,
                c.getActualMinimum(Calendar.DAY_OF_MONTH));
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    private Date getEndTimeByYear() {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date());
        c.set(Calendar.DAY_OF_MONTH,
                c.getActualMaximum(Calendar.DAY_OF_MONTH));
        return c.getTime();
    }

    private Map<Date, OverviewExperimentTaskDayCount> initResult(Date startTime, Date endTime) {
        Map<Date, OverviewExperimentTaskDayCount> result = new HashMap<>();
        int i = 0;
        while (true) {
            Date tempDate = DateUtils.addDays(startTime, i);
            if (tempDate.compareTo(endTime) > 0) {
                break;
            }
            OverviewExperimentTaskDayCount experimentTaskCount = new OverviewExperimentTaskDayCount();
            experimentTaskCount.setTotalCount(0);
            experimentTaskCount.setTime(tempDate);
            result.put(tempDate, experimentTaskCount);
            i++;
        }
        return result;
    }


}
