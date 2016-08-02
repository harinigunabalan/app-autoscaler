package org.cloudfoundry.autoscaler.scheduler.service;

import java.util.Date;
import java.util.TimeZone;

import org.cloudfoundry.autoscaler.scheduler.entity.SpecificDateScheduleEntity;
import org.cloudfoundry.autoscaler.scheduler.quartz.AppScalingScheduleJob;
import org.cloudfoundry.autoscaler.scheduler.util.DateHelper;
import org.cloudfoundry.autoscaler.scheduler.util.JobActionEnum;
import org.cloudfoundry.autoscaler.scheduler.util.ScheduleJobHelper;
import org.cloudfoundry.autoscaler.scheduler.util.error.ValidationErrorResult;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service class to persist the schedule entity in the database and create
 * scheduled job.
 * 
 * 
 *
 */
@Service
public class ScheduleJobManager {
	@Autowired
	Scheduler scheduler;
	@Autowired
	ValidationErrorResult validationErrorResult;

	/**
	 * Creates simple job for specific date schedule for the application scaling using helper 
	 * methods. Here in two jobs are required, First job to tell the scaling decision maker
	 * scaling action needs to initiated Second job to tell the scaling decision maker scaling
	 * action needs to be ended.
	 * 
	 * @param specificDateScheduleEntity
	 * @throws SchedulerException 
	 * @throws Exception
	 */
	public void createSimpleJob(SpecificDateScheduleEntity specificDateScheduleEntity) {

		Long scheduleId = specificDateScheduleEntity.getId();
		String jobStartId = ScheduleJobHelper.generateJobKey(scheduleId, JobActionEnum.START);
		String jobEndId = ScheduleJobHelper.generateJobKey(scheduleId, JobActionEnum.END);

		// Build the job
		JobDetail jobStartDetail = ScheduleJobHelper.buildJob(jobStartId, AppScalingScheduleJob.class);
		JobDetail jobEndDetail = ScheduleJobHelper.buildJob(jobEndId, AppScalingScheduleJob.class);

		// Set the data in JobDetail for informing the scaling decision maker that scaling job needs to be started
		setupScalingScheduleJobData(jobStartDetail, specificDateScheduleEntity, JobActionEnum.START);
		// Set the data in JobDetail for informing the scaling decision maker that scaling job needs to be ended.
		setupScalingScheduleJobData(jobEndDetail, specificDateScheduleEntity, JobActionEnum.END);

		// Build the trigger
		TimeZone policyTimeZone = TimeZone.getTimeZone(specificDateScheduleEntity.getTimeZone());

		Date triggerStartDateTime = DateHelper.getDateWithZoneOffset(specificDateScheduleEntity.getStartDateTime(),
				policyTimeZone);
		Date triggerEndDateTime = DateHelper.getDateWithZoneOffset(specificDateScheduleEntity.getEndDateTime(),
				policyTimeZone);

		Trigger jobStartTrigger = ScheduleJobHelper.buildTrigger(jobStartId, jobStartDetail.getKey(),
				triggerStartDateTime);
		Trigger jobEndTrigger = ScheduleJobHelper.buildTrigger(jobEndId, jobEndDetail.getKey(), triggerEndDateTime);

		// Schedule the job
		try {
			scheduler.scheduleJob(jobStartDetail, jobStartTrigger);
			scheduler.scheduleJob(jobEndDetail, jobEndTrigger);

		} catch (SchedulerException se) {

			validationErrorResult.addErrorForQuartzSchedulerException(se, "scheduler.error.create.failed",
					"app_id=" + specificDateScheduleEntity.getAppId());
		}

	}

	/**
	 * Sets the data in the JobDetail object
	 * 
	 * @param jobDetail
	 * @param scheduleEntity
	 * @param jobAction 
	 */
	private void setupScalingScheduleJobData(JobDetail jobDetail, SpecificDateScheduleEntity scheduleEntity,
			JobActionEnum jobAction) {

		JobDataMap jobDataMap = jobDetail.getJobDataMap();
		jobDataMap.put("appId", scheduleEntity.getAppId());
		jobDataMap.put("scheduleId", scheduleEntity.getId());
		jobDataMap.put("scalingAction", jobAction);

		// The minimum and maximum instance count need to be set when the
		// scaling action has to be started.
		if (jobAction == JobActionEnum.START) {
			jobDataMap.put("instanceMinCount", scheduleEntity.getInstanceMinCount());
			jobDataMap.put("instanceMaxCount", scheduleEntity.getInstanceMaxCount());
		} else {
			jobDataMap.put("instanceMinCount", scheduleEntity.getDefaultInstanceMinCount());
			jobDataMap.put("instanceMaxCount", scheduleEntity.getDefaultInstanceMaxCount());
		}
	}
}
