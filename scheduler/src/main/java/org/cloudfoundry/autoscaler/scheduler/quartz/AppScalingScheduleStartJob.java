package org.cloudfoundry.autoscaler.scheduler.quartz;

import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cloudfoundry.autoscaler.scheduler.entity.ActiveScheduleEntity;
import org.cloudfoundry.autoscaler.scheduler.util.JobActionEnum;
import org.cloudfoundry.autoscaler.scheduler.util.ScheduleJobHelper;
import org.cloudfoundry.autoscaler.scheduler.util.error.DatabaseValidationException;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
abstract class AppScalingScheduleStartJob extends AppScalingScheduleJob {
	private Logger logger = LogManager.getLogger(this.getClass());

	@Autowired
	private Scheduler scheduler;

	abstract Date calculateEndJobStartTime(JobExecutionContext jobExecutionContext) throws JobExecutionException;

	boolean shouldExecuteStartJob(JobExecutionContext jobExecutionContext, Date startJobStartTime,
			Date endJobStartTime) {
		return true;
	}

	@Override
	public void executeInternal(JobExecutionContext jobExecutionContext) throws JobExecutionException {
		JobActionEnum jobStart = JobActionEnum.START;
		Date startJobStartTime = jobExecutionContext.getFireTime();
		Date endJobStartTime = calculateEndJobStartTime(jobExecutionContext);
		if (shouldExecuteStartJob(jobExecutionContext, startJobStartTime, endJobStartTime)) {

			JobDataMap jobDataMap = jobExecutionContext.getJobDetail().getJobDataMap();
			ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
			activeScheduleEntity.setStartJobIdentifier(jobExecutionContext.getFireTime().getTime());

			String executingMessage = messageBundleResourceHelper.lookupMessage("scheduler.job.start",
					jobExecutionContext.getJobDetail().getKey(), activeScheduleEntity.getAppId(),
					activeScheduleEntity.getId(), jobStart);
			logger.info(executingMessage);

			String appId = activeScheduleEntity.getAppId();

			deleteExistingActiveSchedule(jobExecutionContext, appId);
			// Save new active schedule
			saveActiveSchedule(jobExecutionContext, activeScheduleEntity);

			scheduleEndJob(jobExecutionContext, activeScheduleEntity.getStartJobIdentifier(), endJobStartTime);

			notifyScalingEngine(activeScheduleEntity, jobStart, jobExecutionContext);
		}
	}

	@Transactional
	private void deleteExistingActiveSchedule(JobExecutionContext jobExecutionContext, String appId)
			throws JobExecutionException {
		JobDataMap jobDataMap = jobExecutionContext.getJobDetail().getJobDataMap();
		boolean activeScheduleTableTaskDone = jobDataMap.getBoolean(ScheduleJobHelper.ACTIVE_SCHEDULE_TABLE_TASK_DONE);
		if (!activeScheduleTableTaskDone) {
			// Delete existing active schedules from database
			try {
				int activeScheduleDeleted = activeScheduleDao.deleteActiveSchedulesByAppId(appId);
				logger.info("Deleted " + activeScheduleDeleted + " existing active schedules for application id :"
						+ appId + " before creating new active schedule.");
			} catch (DatabaseValidationException dve) {
				String errorMessage = messageBundleResourceHelper
						.lookupMessage("database.error.delete.activeschedule.failed", dve.getMessage(), appId);
				logger.error(errorMessage, dve);

				handleJobRescheduling(jobExecutionContext, ScheduleJobHelper.RescheduleCount.ACTIVE_SCHEDULE,
						maxJobRescheduleCount);
				throw new JobExecutionException(errorMessage, dve);
			}
		}
	}

	@Transactional
	private void saveActiveSchedule(JobExecutionContext jobExecutionContext, ActiveScheduleEntity activeScheduleEntity)
			throws JobExecutionException {
		JobDataMap jobDataMap = jobExecutionContext.getJobDetail().getJobDataMap();
		boolean activeScheduleTableTaskDone = jobDataMap.getBoolean(ScheduleJobHelper.ACTIVE_SCHEDULE_TABLE_TASK_DONE);
		if (!activeScheduleTableTaskDone) {

			try {
				activeScheduleDao.create(activeScheduleEntity);
				jobDataMap.put(ScheduleJobHelper.ACTIVE_SCHEDULE_TABLE_TASK_DONE, true);
			} catch (DatabaseValidationException dve) {

				String errorMessage = messageBundleResourceHelper.lookupMessage(
						"database.error.create.activeschedule.failed", dve.getMessage(),
						activeScheduleEntity.getAppId(), activeScheduleEntity.getId());
				logger.error(errorMessage, dve);

				handleJobRescheduling(jobExecutionContext, ScheduleJobHelper.RescheduleCount.ACTIVE_SCHEDULE,
						maxJobRescheduleCount);
				throw new JobExecutionException(errorMessage, dve);
			}
		}
	}

	private void scheduleEndJob(JobExecutionContext jobExecutionContext, long startJobIdentifier,
			Date endJobStartTime) {
		JobDataMap jobDataMap = jobExecutionContext.getJobDetail().getJobDataMap();
		if (!jobDataMap.getBoolean(ScheduleJobHelper.CREATE_END_JOB_TASK_DONE)) {
			jobDataMap.put(ScheduleJobHelper.START_JOB_IDENTIFIER, startJobIdentifier);
			Long scheduleId = jobDataMap.getLong(ScheduleJobHelper.SCHEDULE_ID);
			String keyName = scheduleId + JobActionEnum.END.getJobIdSuffix() + "_" + startJobIdentifier;

			JobKey jobKey = new JobKey(keyName, "Schedule");
			TriggerKey triggerKey = new TriggerKey(keyName, "Schedule");

			JobDetail jobDetail = JobBuilder.newJob(AppScalingScheduleEndJob.class).withIdentity(jobKey).storeDurably()
					.setJobData(jobDataMap).build();
			Trigger trigger = ScheduleJobHelper.buildTrigger(triggerKey, jobKey, endJobStartTime);

			try {
				scheduler.scheduleJob(jobDetail, trigger);
				jobDataMap.put(ScheduleJobHelper.CREATE_END_JOB_TASK_DONE, true);
			} catch (SchedulerException se) {
				String errorMessage = messageBundleResourceHelper.lookupMessage("scheduler.job.end.schedule.failed",
						se.getMessage(), jobKey, jobDataMap.getString(ScheduleJobHelper.APP_ID),
						jobDataMap.getLong(ScheduleJobHelper.SCHEDULE_ID), startJobIdentifier);
				logger.error(errorMessage, se);
			}
		}
	}
}
