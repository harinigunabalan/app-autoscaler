package org.cloudfoundry.autoscaler.scheduler.quartz;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.notNull;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.cloudfoundry.autoscaler.scheduler.dao.ActiveScheduleDao;
import org.cloudfoundry.autoscaler.scheduler.entity.ActiveScheduleEntity;
import org.cloudfoundry.autoscaler.scheduler.util.EmbeddedTomcatUtil;
import org.cloudfoundry.autoscaler.scheduler.util.JobActionEnum;
import org.cloudfoundry.autoscaler.scheduler.util.ScheduleJobHelper;
import org.cloudfoundry.autoscaler.scheduler.util.TestConfiguration;
import org.cloudfoundry.autoscaler.scheduler.util.TestDataCleanupHelper;
import org.cloudfoundry.autoscaler.scheduler.util.TestDataSetupHelper;
import org.cloudfoundry.autoscaler.scheduler.util.TestDataSetupHelper.JobInformation;
import org.cloudfoundry.autoscaler.scheduler.util.TestJobListener;
import org.cloudfoundry.autoscaler.scheduler.util.error.DatabaseValidationException;
import org.cloudfoundry.autoscaler.scheduler.util.error.MessageBundleResourceHelper;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.quartz.CronExpression;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@RunWith(SpringRunner.class)
@SpringBootTest
public class AppScalingScheduleJobTest extends TestConfiguration {

	@Mock
	private Appender mockAppender;

	@Captor
	private ArgumentCaptor<LogEvent> logCaptor;

	@Autowired
	private MessageBundleResourceHelper messageBundleResourceHelper;

	private Scheduler memScheduler;

	@MockBean
	private Scheduler scheduler;

	@MockBean
	private ActiveScheduleDao activeScheduleDao;

	@SpyBean
	private RestTemplate restTemplate;

	@Autowired
	private TestDataCleanupHelper testDataCleanupHelper;

	@Autowired
	private ApplicationContext applicationContext;

	@Value("${autoscaler.scalingengine.url}")
	private String scalingEngineUrl;

	private static EmbeddedTomcatUtil embeddedTomcatUtil;

	@BeforeClass
	public static void beforeClass() {
		embeddedTomcatUtil = new EmbeddedTomcatUtil();
		embeddedTomcatUtil.start();

	}

	@AfterClass
	public static void afterClass() {
		embeddedTomcatUtil.stop();
	}

	@Before
	public void before() throws SchedulerException {
		MockitoAnnotations.initMocks(this);
		memScheduler = createMemScheduler();
		testDataCleanupHelper.cleanupData(memScheduler);

		Mockito.reset(mockAppender);
		Mockito.reset(activeScheduleDao);
		Mockito.reset(restTemplate);
		Mockito.reset(scheduler);

		Mockito.when(mockAppender.getName()).thenReturn("MockAppender");
		Mockito.when(mockAppender.isStarted()).thenReturn(true);
		Mockito.when(mockAppender.isStopped()).thenReturn(false);

		setLogLevel(Level.INFO);
	}

	private Scheduler createMemScheduler() throws SchedulerException {
		Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();

		QuartzJobFactory jobFactory = new QuartzJobFactory();
		jobFactory.setApplicationContext(applicationContext);
		scheduler.setJobFactory(jobFactory);

		scheduler.start();
		return scheduler;
	}

	@Test
	public void testNotifyStartOfActiveScheduleToScalingEngine_with_SpecificDateSchedule() throws Exception {
		// Build the job and trigger
		JobInformation jobInformation = new JobInformation<>(AppScalingSpecificDateScheduleStartJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 200, null);

		TestJobListener testJobListener = new TestJobListener(1);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(1)).deleteActiveSchedulesByAppId(appId);
		Mockito.verify(activeScheduleDao, Mockito.times(1)).create(Mockito.anyObject());
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());

		String expectedMessage = messageBundleResourceHelper.lookupMessage(
				"scalingengine.notification.activeschedule.start", appId, scheduleId);
		assertThat("Log level should be INFO", logCaptor.getValue().getLevel(), is(Level.INFO));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For end job
		ArgumentCaptor<JobDetail> jobDetailArgumentCaptor = ArgumentCaptor.forClass(JobDetail.class);
		ArgumentCaptor<Trigger> triggerArgumentCaptor = ArgumentCaptor.forClass(Trigger.class);

		Mockito.verify(scheduler, Mockito.times(1)).scheduleJob(jobDetailArgumentCaptor.capture(),
				triggerArgumentCaptor.capture());

		Long startJobIdentifier = jobDetailArgumentCaptor.getValue().getJobDataMap()
				.getLong(ScheduleJobHelper.START_JOB_IDENTIFIER);

		assertEndJobArgument(triggerArgumentCaptor.getValue(), endJobStartTime, scheduleId, startJobIdentifier);

		// For notify to Scaling Engine
		assertNotifyScalingEngineForStartJob(activeScheduleEntity, startJobIdentifier);
	}

	@Test
	public void testNotifyStartOfActiveScheduleToScalingEngine_with_SpecificDateSchedule_starting_after_endTime()
			throws Exception {
		// Build the job and trigger
		JobInformation jobInformation = new JobInformation<>(AppScalingSpecificDateScheduleStartJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1));
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 200, null);

		TestJobListener testJobListener = new TestJobListener(1);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.never()).deleteActiveSchedulesByAppId(Mockito.anyString());
		Mockito.verify(activeScheduleDao, Mockito.never()).create(Mockito.anyObject());
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());

		String expectedMessage = messageBundleResourceHelper.lookupMessage(
				"scheduler.job.start.specificdate.schedule.skipped", endJobStartTime,
				jobInformation.getJobDetail().getKey(), appId, scheduleId);
		assertThat("Log level should be WARN", logCaptor.getValue().getLevel(), is(Level.WARN));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For end job
		Mockito.verify(scheduler, Mockito.never()).scheduleJob(Mockito.anyObject(), Mockito.anyObject());

		// For notify to Scaling Engine
		Mockito.verify(restTemplate, Mockito.never()).put(Mockito.anyString(), notNull());
	}

	@Test
	public void testNotifyStartOfActiveScheduleToScalingEngine_with_RecurringSchedule() throws Exception {
		// Build the job and trigger
		JobInformation jobInformation = new JobInformation<>(AppScalingRecurringScheduleStartJob.class);
		CronExpression endJobCronExpression = new CronExpression("00 00 00 1 * ? 2099");
		JobDataMap jobDataMap = setupJobDataForRecurringSchedule(jobInformation.getJobDetail(),
				endJobCronExpression.getCronExpression());

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 200, null);

		TestJobListener testJobListener = new TestJobListener(1);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(1)).deleteActiveSchedulesByAppId(appId);
		Mockito.verify(activeScheduleDao, Mockito.times(1)).create(Mockito.anyObject());
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());

		String expectedMessage = messageBundleResourceHelper.lookupMessage(
				"scalingengine.notification.activeschedule.start", appId, scheduleId);
		assertThat("Log level should be INFO", logCaptor.getValue().getLevel(), is(Level.INFO));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For end job
		ArgumentCaptor<JobDetail> jobDetailArgumentCaptor = ArgumentCaptor.forClass(JobDetail.class);
		ArgumentCaptor<Trigger> triggerArgumentCaptor = ArgumentCaptor.forClass(Trigger.class);

		Mockito.verify(scheduler, Mockito.times(1)).scheduleJob(jobDetailArgumentCaptor.capture(),
				triggerArgumentCaptor.capture());

		Long startJobIdentifier = jobDetailArgumentCaptor.getValue().getJobDataMap()
				.getLong(ScheduleJobHelper.START_JOB_IDENTIFIER);

		assertEndJobArgument(triggerArgumentCaptor.getValue(), endJobCronExpression.getNextValidTimeAfter(new Date()),
				scheduleId, startJobIdentifier);

		// For notify to Scaling Engine
		assertNotifyScalingEngineForStartJob(activeScheduleEntity, startJobIdentifier);
	}

	@Test
	public void testNotifyStartOfActiveScheduleToScalingEngine_with_RecurringSchedule_throw_ParseException()
			throws Exception {
		setLogLevel(Level.ERROR);

		// Build the job and trigger
		JobInformation jobInformation = new JobInformation<>(AppScalingRecurringScheduleStartJob.class);
		JobDataMap jobDataMap = setupJobDataForRecurringSchedule(jobInformation.getJobDetail(), null);

		jobDataMap.put(ScheduleJobHelper.END_JOB_CRON_EXPRESSION, "Invalid cron expression");

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 200, null);

		TestJobListener testJobListener = new TestJobListener(1);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.never()).deleteActiveSchedulesByAppId(Mockito.anyString());
		Mockito.verify(activeScheduleDao, Mockito.never()).create(Mockito.anyObject());
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());

		String expectedMessage = messageBundleResourceHelper.lookupMessage("scheduler.job.cronexpression.parse.failed",
				"Illegal characters for this position: 'INV'", "Invalid cron expression",
				jobInformation.getJobDetail().getKey(), appId, scheduleId);
		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For end job
		Mockito.verify(scheduler, Mockito.never()).scheduleJob(Mockito.anyObject(), Mockito.anyObject());

		// For notify to Scaling Engine
		Mockito.verify(restTemplate, Mockito.never()).put(Mockito.anyString(), Mockito.notNull());
	}

	@Test
	public void testNotifyStartOfActiveScheduleToScalingEngine_with_existing_ActiveSchedule() throws Exception {
		// Build the job and trigger
		JobInformation jobInformation = new JobInformation<>(AppScalingSpecificDateScheduleStartJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);
		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 200, null);
		TestJobListener testJobListener = new TestJobListener(1);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		Mockito.when(activeScheduleDao.deleteActiveSchedulesByAppId(Mockito.anyString())).thenReturn(1);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(1)).deleteActiveSchedulesByAppId(appId);
		Mockito.verify(activeScheduleDao, Mockito.times(1)).create(Mockito.anyObject());
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());

		String expectedMessage = messageBundleResourceHelper.lookupMessage(
				"scalingengine.notification.activeschedule.start", appId, scheduleId);
		assertThat("Log level should be INFO", logCaptor.getValue().getLevel(), is(Level.INFO));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		expectedMessage = "Deleted " + 1 + " existing active schedules for application id :" + appId
				+ " before creating new active schedule.";
		assertLogHasMessageCount(Level.INFO, expectedMessage, 1);

		// For end job
		ArgumentCaptor<JobDetail> jobDetailArgumentCaptor = ArgumentCaptor.forClass(JobDetail.class);
		ArgumentCaptor<Trigger> triggerArgumentCaptor = ArgumentCaptor.forClass(Trigger.class);

		Mockito.verify(scheduler, Mockito.times(1)).scheduleJob(jobDetailArgumentCaptor.capture(),
				triggerArgumentCaptor.capture());

		Long startJobIdentifier = jobDetailArgumentCaptor.getValue().getJobDataMap()
				.getLong(ScheduleJobHelper.START_JOB_IDENTIFIER);

		assertEndJobArgument(triggerArgumentCaptor.getValue(), endJobStartTime, scheduleId, startJobIdentifier);

		// For notify to Scaling Engine
		assertNotifyScalingEngineForStartJob(activeScheduleEntity, startJobIdentifier);
	}

	@Test
	public void testNotifyStartOfActiveScheduleToScalingEngine_with_existing_ActiveSchedule_throw_DatabaseValidationException()
			throws Exception {
		setLogLevel(Level.ERROR);

		// Build the job and trigger
		JobInformation jobInformation = new JobInformation<>(AppScalingSpecificDateScheduleStartJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));

		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 200, null);
		Mockito.when(activeScheduleDao.deleteActiveSchedulesByAppId(Mockito.anyString()))
				.thenThrow(new DatabaseValidationException("test exception"));

		TestJobListener testJobListener = new TestJobListener(2);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(2)).deleteActiveSchedulesByAppId(appId);
		Mockito.verify(activeScheduleDao, Mockito.never()).create(Mockito.anyObject());
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());

		String expectedMessage = messageBundleResourceHelper
				.lookupMessage("database.error.delete.activeschedule.failed", "test exception", appId);
		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For end job
		Mockito.verify(scheduler, Mockito.never()).scheduleJob(Mockito.anyObject(), Mockito.anyObject());

		// For notify to Scaling Engine
		Mockito.verify(restTemplate, Mockito.never()).put(Mockito.anyString(), notNull());
	}

	@Test
	public void testNotifyEndOfActiveScheduleToScalingEngine() throws Exception {
		// Build the job
		JobInformation jobInformation = new JobInformation<>(AppScalingScheduleEndJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));

		long startJobIdentifier = 10L;
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);
		jobDataMap.put(ScheduleJobHelper.START_JOB_IDENTIFIER, startJobIdentifier);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 204, null);

		TestJobListener testJobListener = new TestJobListener(1);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(1)).delete(activeScheduleEntity.getId(), startJobIdentifier);
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());

		String expectedMessage = messageBundleResourceHelper.lookupMessage(
				"scalingengine.notification.activeschedule.remove", appId, scheduleId);
		assertThat("Log level should be INFO", logCaptor.getValue().getLevel(), is(Level.INFO));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For notify to Scaling Engine
		assertNotifyScalingEngineForEndJob(activeScheduleEntity);
	}

	@Test
	public void testCreateActiveSchedules_throw_DatabaseValidationException() throws Exception {
		setLogLevel(Level.ERROR);

		int expectedNumOfTimesJobRescheduled = 2;

		// Build the job
		JobInformation jobInformation = new JobInformation<>(AppScalingSpecificDateScheduleStartJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 200, null);

		Mockito.doThrow(new DatabaseValidationException("test exception")).doNothing().when(activeScheduleDao)
				.create(Mockito.anyObject());

		TestJobListener testJobListener = new TestJobListener(expectedNumOfTimesJobRescheduled);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(expectedNumOfTimesJobRescheduled))
				.deleteActiveSchedulesByAppId(appId);
		Mockito.verify(activeScheduleDao, Mockito.times(expectedNumOfTimesJobRescheduled)).create(Mockito.anyObject());

		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());
		String expectedMessage = messageBundleResourceHelper
				.lookupMessage("database.error.create.activeschedule.failed", "test exception", appId, scheduleId);

		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For end job
		ArgumentCaptor<JobDetail> jobDetailArgumentCaptor = ArgumentCaptor.forClass(JobDetail.class);
		ArgumentCaptor<Trigger> triggerArgumentCaptor = ArgumentCaptor.forClass(Trigger.class);

		Mockito.verify(scheduler, Mockito.times(1)).scheduleJob(jobDetailArgumentCaptor.capture(),
				triggerArgumentCaptor.capture());

		Long startJobIdentifier = jobDetailArgumentCaptor.getValue().getJobDataMap()
				.getLong(ScheduleJobHelper.START_JOB_IDENTIFIER);

		assertEndJobArgument(triggerArgumentCaptor.getValue(), endJobStartTime, scheduleId, startJobIdentifier);

		// For notify to Scaling Engine
		assertNotifyScalingEngineForStartJob(activeScheduleEntity, startJobIdentifier);
	}

	@Test
	public void testRemoveActiveSchedules_throw_DatabaseValidationException() throws Exception {
		setLogLevel(Level.ERROR);
		int expectedNumOfTimesJobRescheduled = 2;

		// Build the job
		JobInformation jobInformation = new JobInformation<>(AppScalingScheduleEndJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));

		long startJobIdentifier = 10L;
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);
		jobDataMap.put(ScheduleJobHelper.START_JOB_IDENTIFIER, startJobIdentifier);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 204, null);

		Mockito.doThrow(new DatabaseValidationException("test exception")).doReturn(1).when(activeScheduleDao)
				.delete(eq(scheduleId), Mockito.anyObject());

		TestJobListener testJobListener = new TestJobListener(expectedNumOfTimesJobRescheduled);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(expectedNumOfTimesJobRescheduled)).delete(scheduleId,
				startJobIdentifier);
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());
		String expectedMessage = messageBundleResourceHelper
				.lookupMessage("database.error.delete.activeschedule.failed", "test exception", appId, scheduleId);

		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For notify to Scaling Engine
		assertNotifyScalingEngineForEndJob(activeScheduleEntity);
	}

	@Test
	public void testCreateActiveSchedules_when_JobRescheduleMaxCountReached() throws Exception {
		setLogLevel(Level.ERROR);

		int expectedNumOfTimesJobRescheduled = 5;

		// Build the job
		JobInformation jobInformation = new JobInformation<>(AppScalingSpecificDateScheduleStartJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 200, null);

		Mockito.doThrow(new DatabaseValidationException("test exception")).when(activeScheduleDao)
				.create(Mockito.anyObject());

		TestJobListener testJobListener = new TestJobListener(expectedNumOfTimesJobRescheduled);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		// 5 times because in case of failure quartz will reschedule job which will call create again
		Mockito.verify(activeScheduleDao, Mockito.times(expectedNumOfTimesJobRescheduled))
				.deleteActiveSchedulesByAppId(appId);
		Mockito.verify(activeScheduleDao, Mockito.times(expectedNumOfTimesJobRescheduled)).create(Mockito.anyObject());

		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());
		String expectedMessage = messageBundleResourceHelper.lookupMessage(
				"scheduler.job.reschedule.failed.max.reached", jobInformation.getTrigger().getKey(), appId, scheduleId,
				expectedNumOfTimesJobRescheduled, ScheduleJobHelper.RescheduleCount.ACTIVE_SCHEDULE.name());

		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For end job
		Mockito.verify(scheduler, Mockito.never()).scheduleJob(Mockito.anyObject(), Mockito.anyObject());

		// For notify to Scaling Engine
		Mockito.verify(restTemplate, Mockito.never()).put(Mockito.anyString(), notNull());
	}

	@Test
	public void testRemoveActiveSchedules_when_JobRescheduleMaxCountReached() throws Exception {
		setLogLevel(Level.ERROR);

		int expectedNumOfTimesJobRescheduled = 5;

		// Build the job
		JobInformation jobInformation = new JobInformation<>(AppScalingScheduleEndJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));

		long startJobIdentifier = 10L;
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);
		jobDataMap.put(ScheduleJobHelper.START_JOB_IDENTIFIER, startJobIdentifier);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 204, null);

		Mockito.doThrow(new DatabaseValidationException("test exception")).when(activeScheduleDao)
				.delete(eq(scheduleId), eq(startJobIdentifier));

		TestJobListener testJobListener = new TestJobListener(expectedNumOfTimesJobRescheduled);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(expectedNumOfTimesJobRescheduled)).delete(scheduleId,
				startJobIdentifier);
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());
		String expectedMessage = messageBundleResourceHelper.lookupMessage(
				"scheduler.job.reschedule.failed.max.reached", jobInformation.getTrigger().getKey(), appId, scheduleId,
				expectedNumOfTimesJobRescheduled, ScheduleJobHelper.RescheduleCount.ACTIVE_SCHEDULE.name());

		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For notify to Scaling Engine
		Mockito.verify(restTemplate, Mockito.never()).delete(Mockito.anyString(), notNull());
	}

	@Test
	public void testNotifyStartOfActiveScheduleToScalingEngine_when_invalidRequest() throws Exception {
		setLogLevel(Level.ERROR);
		// Build the job and trigger
		JobInformation jobInformation = new JobInformation<>(AppScalingSpecificDateScheduleStartJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);
		// Min_Count > Max_Count (Invalid data)
		jobDataMap.put(ScheduleJobHelper.INSTANCE_MIN_COUNT, 5);
		jobDataMap.put(ScheduleJobHelper.INSTANCE_MAX_COUNT, 4);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 400, "test error message");

		TestJobListener testJobListener = new TestJobListener(1);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(1)).deleteActiveSchedulesByAppId(appId);
		Mockito.verify(activeScheduleDao, Mockito.times(1)).create(Mockito.anyObject());

		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());
		String expectedMessage = messageBundleResourceHelper.lookupMessage("scalingengine.notification.client.error",
				400, "test error message", appId, scheduleId, JobActionEnum.START);

		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For end job
		ArgumentCaptor<JobDetail> jobDetailArgumentCaptor = ArgumentCaptor.forClass(JobDetail.class);
		ArgumentCaptor<Trigger> triggerArgumentCaptor = ArgumentCaptor.forClass(Trigger.class);

		Mockito.verify(scheduler, Mockito.times(1)).scheduleJob(jobDetailArgumentCaptor.capture(),
				triggerArgumentCaptor.capture());

		Long startJobIdentifier = jobDetailArgumentCaptor.getValue().getJobDataMap()
				.getLong(ScheduleJobHelper.START_JOB_IDENTIFIER);

		assertEndJobArgument(triggerArgumentCaptor.getValue(), endJobStartTime, scheduleId, startJobIdentifier);

		// For notify to Scaling Engine
		assertNotifyScalingEngineForStartJob(activeScheduleEntity, startJobIdentifier);
	}

	@Test
	public void testNotifyEndOfActiveScheduleToScalingEngine_when_invalidRequest() throws Exception {
		setLogLevel(Level.ERROR);

		// Build the job and trigger
		JobInformation jobInformation = new JobInformation<>(AppScalingScheduleEndJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));

		long startJobIdentifier = 10L;
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);
		jobDataMap.put(ScheduleJobHelper.START_JOB_IDENTIFIER, startJobIdentifier);
		// Min_Count > Max_Count (Invalid data)
		jobDataMap.put(ScheduleJobHelper.INSTANCE_MIN_COUNT, 5);
		jobDataMap.put(ScheduleJobHelper.INSTANCE_MAX_COUNT, 4);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 400, "test error message");

		TestJobListener testJobListener = new TestJobListener(1);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(1)).delete(scheduleId, startJobIdentifier);

		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());
		String expectedMessage = messageBundleResourceHelper.lookupMessage("scalingengine.notification.client.error",
				400, "test error message", appId, scheduleId, JobActionEnum.END);

		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For notify to Scaling Engine
		assertNotifyScalingEngineForEndJob(activeScheduleEntity);
	}

	@Test
	public void testNotifyStartOfActiveScheduleToScalingEngine_when_responseError() throws Exception {
		setLogLevel(Level.ERROR);
		// Build the job and trigger
		JobInformation jobInformation = new JobInformation<>(AppScalingSpecificDateScheduleStartJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 500, "test error message");

		TestJobListener testJobListener = new TestJobListener(1);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(1)).deleteActiveSchedulesByAppId(appId);
		Mockito.verify(activeScheduleDao, Mockito.times(1)).create(anyObject());
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());
		String expectedMessage = messageBundleResourceHelper.lookupMessage("scalingengine.notification.failed", 500,
				"test error message", appId, scheduleId, JobActionEnum.START);

		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For end job
		ArgumentCaptor<JobDetail> jobDetailArgumentCaptor = ArgumentCaptor.forClass(JobDetail.class);
		ArgumentCaptor<Trigger> triggerArgumentCaptor = ArgumentCaptor.forClass(Trigger.class);

		Mockito.verify(scheduler, Mockito.times(1)).scheduleJob(jobDetailArgumentCaptor.capture(),
				triggerArgumentCaptor.capture());

		Long startJobIdentifier = jobDetailArgumentCaptor.getValue().getJobDataMap()
				.getLong(ScheduleJobHelper.START_JOB_IDENTIFIER);

		assertEndJobArgument(triggerArgumentCaptor.getValue(), endJobStartTime, scheduleId, startJobIdentifier);

		// For notify to Scaling Engine
		assertNotifyScalingEngineForStartJob(activeScheduleEntity, startJobIdentifier);
	}

	@Test
	public void testNotifyEndOfActiveScheduleToScalingEngine_when_responseError() throws Exception {
		setLogLevel(Level.ERROR);

		// Build the job and trigger
		JobInformation jobInformation = new JobInformation<>(AppScalingScheduleEndJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));

		long startJobIdentifier = 10L;
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);
		jobDataMap.put(ScheduleJobHelper.START_JOB_IDENTIFIER, startJobIdentifier);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 500, "test error message");

		TestJobListener testJobListener = new TestJobListener(1);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(1)).delete(scheduleId, startJobIdentifier);
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());
		String expectedMessage = messageBundleResourceHelper.lookupMessage("scalingengine.notification.failed", 500,
				"test error message", appId, scheduleId, JobActionEnum.END);

		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For notify to Scaling Engine
		assertNotifyScalingEngineForEndJob(activeScheduleEntity);
	}

	@Test
	public void testNotifyScalingEngine_when_invalidURL() throws Exception {
		setLogLevel(Level.ERROR);

		// Build the job and trigger
		JobInformation jobInformation = new JobInformation<>(AppScalingSpecificDateScheduleStartJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		Mockito.doThrow(new ResourceAccessException("test exception")).when(restTemplate).put(
				eq(scalingEngineUrl + "/v1/apps/" + appId + "/active_schedules/" + scheduleId), Mockito.anyObject());

		TestJobListener testJobListener = new TestJobListener(2);
		memScheduler.getListenerManager().addJobListener(testJobListener);

		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());

		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(1)).deleteActiveSchedulesByAppId(appId);
		Mockito.verify(activeScheduleDao, Mockito.times(1)).create(Mockito.anyObject());
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());
		String expectedMessage = messageBundleResourceHelper.lookupMessage("scalingengine.notification.error",
				"test exception", appId, scheduleId, JobActionEnum.START);

		assertLogHasMessageCount(Level.ERROR, expectedMessage, 2);

		expectedMessage = messageBundleResourceHelper.lookupMessage("scheduler.job.reschedule.failed.max.reached",
				jobInformation.getTrigger().getKey(), appId, scheduleId, 2,
				ScheduleJobHelper.RescheduleCount.SCALING_ENGINE_NOTIFICATION.name());

		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertThat(logCaptor.getValue().getMessage().getFormattedMessage(), is(expectedMessage));

		// For end job
		ArgumentCaptor<JobDetail> jobDetailArgumentCaptor = ArgumentCaptor.forClass(JobDetail.class);
		ArgumentCaptor<Trigger> triggerArgumentCaptor = ArgumentCaptor.forClass(Trigger.class);

		Mockito.verify(scheduler, Mockito.times(1)).scheduleJob(jobDetailArgumentCaptor.capture(),
				triggerArgumentCaptor.capture());

		Long startJobIdentifier = jobDetailArgumentCaptor.getValue().getJobDataMap()
				.getLong(ScheduleJobHelper.START_JOB_IDENTIFIER);

		assertEndJobArgument(triggerArgumentCaptor.getValue(), endJobStartTime, scheduleId, startJobIdentifier);

		// For notify to Scaling Engine
		assertNotifyScalingEngineForStartJob(activeScheduleEntity, startJobIdentifier);
	}

	@Test
	public void testNotifyScalingEngine_when_failed_to_schedule_endJob() throws Exception {
		setLogLevel(Level.ERROR);
		// Build the job
		JobInformation jobInformation = new JobInformation<>(AppScalingSpecificDateScheduleStartJob.class);
		Date endJobStartTime = new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
		JobDataMap jobDataMap = setupJobDataForSpecificDateSchedule(jobInformation.getJobDetail(), endJobStartTime);

		ActiveScheduleEntity activeScheduleEntity = ScheduleJobHelper.setupActiveSchedule(jobDataMap);
		String appId = activeScheduleEntity.getAppId();
		Long scheduleId = activeScheduleEntity.getId();

		embeddedTomcatUtil.setup(appId, scheduleId, 204, null);

		Mockito.doThrow(new SchedulerException("test exception")).when(scheduler).scheduleJob(Mockito.anyObject(),
				Mockito.anyObject());

		TestJobListener testJobListener = new TestJobListener(1);
		memScheduler.getListenerManager().addJobListener(testJobListener);
		memScheduler.scheduleJob(jobInformation.getJobDetail(), jobInformation.getTrigger());
		testJobListener.waitForJobToFinish(TimeUnit.MINUTES.toMillis(1));

		Mockito.verify(activeScheduleDao, Mockito.times(1)).deleteActiveSchedulesByAppId(appId);
		Mockito.verify(activeScheduleDao, Mockito.times(1)).create(Mockito.anyObject());
		Mockito.verify(mockAppender, Mockito.atLeastOnce()).append(logCaptor.capture());

		String expectedMessage = messageBundleResourceHelper.lookupMessage("scheduler.job.end.schedule.failed",
				"test exception", "\\w.*", appId, scheduleId, "\\w.*");
		assertThat("Log level should be ERROR", logCaptor.getValue().getLevel(), is(Level.ERROR));
		assertTrue(logCaptor.getValue().getMessage().getFormattedMessage().matches(expectedMessage));

		// For end job
		ArgumentCaptor<JobDetail> jobDetailArgumentCaptor = ArgumentCaptor.forClass(JobDetail.class);
		ArgumentCaptor<Trigger> triggerArgumentCaptor = ArgumentCaptor.forClass(Trigger.class);

		Mockito.verify(scheduler, Mockito.times(1)).scheduleJob(jobDetailArgumentCaptor.capture(),
				triggerArgumentCaptor.capture());

		Long startJobIdentifier = jobDetailArgumentCaptor.getValue().getJobDataMap()
				.getLong(ScheduleJobHelper.START_JOB_IDENTIFIER);

		assertEndJobArgument(triggerArgumentCaptor.getValue(), endJobStartTime, scheduleId, startJobIdentifier);

		// For notify to Scaling Engine
		assertNotifyScalingEngineForStartJob(activeScheduleEntity, startJobIdentifier);
	}

	private void assertLogHasMessageCount(Level logLevel, String expectedMessage, int expectedCount) {
		int messageCount = 0;
		List<LogEvent> logEvents = logCaptor.getAllValues();
		for (LogEvent logEvent : logEvents) {
			if (logEvent.getLevel() == logLevel
					&& logEvent.getMessage().getFormattedMessage().equals(expectedMessage)) {
				++messageCount;
			}
		}
		assertThat("Log should have message", messageCount, is(expectedCount));
	}

	private void assertEndJobArgument(Trigger trigger, Date expectedEndJobStartTime, long scheduleId,
			long startJobIdentifier) {
		String name = scheduleId + JobActionEnum.END.getJobIdSuffix() + "_" + startJobIdentifier;
		JobKey endJobKey = new JobKey(name, "Schedule");
		TriggerKey endTriggerKey = new TriggerKey(name, "Schedule");
		assertThat(trigger.getJobKey(), is(endJobKey));
		assertThat(trigger.getKey(), is(endTriggerKey));
		assertThat(trigger.getStartTime(), is(expectedEndJobStartTime));
		assertThat(trigger.getMisfireInstruction(), is(SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW));
	}

	private void assertNotifyScalingEngineForStartJob(ActiveScheduleEntity activeScheduleEntity,
			long startJobIdentifier) {
		activeScheduleEntity.setStartJobIdentifier(startJobIdentifier);
		String scalingEnginePath = scalingEngineUrl + "/v1/apps/" + activeScheduleEntity.getAppId()
				+ "/active_schedules/" + activeScheduleEntity.getId();
		HttpEntity<ActiveScheduleEntity> requestEntity = new HttpEntity<>(activeScheduleEntity);
		Mockito.verify(restTemplate, Mockito.times(1)).put(scalingEnginePath, requestEntity);
	}

	private void assertNotifyScalingEngineForEndJob(ActiveScheduleEntity activeScheduleEntity) {
		String scalingEnginePath = scalingEngineUrl + "/v1/apps/" + activeScheduleEntity.getAppId()
				+ "/active_schedules/" + activeScheduleEntity.getId();
		HttpEntity<ActiveScheduleEntity> requestEntity = new HttpEntity<>(activeScheduleEntity);
		Mockito.verify(restTemplate, Mockito.times(1)).delete(scalingEnginePath, requestEntity);
	}

	private void setLogLevel(Level level) {
		LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
		Configuration config = ctx.getConfiguration();

		LoggerConfig loggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
		loggerConfig.removeAppender("MockAppender");

		loggerConfig.setLevel(level);
		loggerConfig.addAppender(mockAppender, level, null);
		ctx.updateLoggers();

	}

	private JobDataMap setupJobDataForSpecificDateSchedule(JobDetail jobDetail, Date endJobStartTime) {
		JobDataMap jobDataMap = TestDataSetupHelper.setupJobDataMap(jobDetail);

		jobDataMap.put(ScheduleJobHelper.END_JOB_START_TIME, endJobStartTime.getTime());

		return jobDataMap;
	}

	private JobDataMap setupJobDataForRecurringSchedule(JobDetail jobDetail, String cronExpression) {
		JobDataMap jobDataMap = TestDataSetupHelper.setupJobDataMap(jobDetail);

		jobDataMap.put(ScheduleJobHelper.END_JOB_CRON_EXPRESSION, cronExpression);

		return jobDataMap;
	}

}