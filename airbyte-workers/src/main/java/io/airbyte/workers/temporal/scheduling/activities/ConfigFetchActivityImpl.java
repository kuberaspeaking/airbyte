/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.scheduling.activities;

import static io.airbyte.metrics.lib.ApmTraceConstants.ACTIVITY_TRACE_OPERATION_NAME;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.CONNECTION_ID_KEY;

import com.google.common.annotations.VisibleForTesting;
import datadog.trace.api.Trace;
import io.airbyte.api.client.generated.ConnectionApi;
import io.airbyte.api.client.invoker.generated.ApiException;
import io.airbyte.api.client.model.generated.ConnectionIdRequestBody;
import io.airbyte.api.client.model.generated.ConnectionRead;
import io.airbyte.api.client.model.generated.ConnectionSchedule;
import io.airbyte.api.client.model.generated.ConnectionScheduleDataBasicSchedule;
import io.airbyte.api.client.model.generated.ConnectionScheduleDataBasicSchedule.TimeUnitEnum;
import io.airbyte.api.client.model.generated.ConnectionScheduleDataCron;
import io.airbyte.api.client.model.generated.ConnectionScheduleType;
import io.airbyte.api.client.model.generated.ConnectionStatus;
import io.airbyte.commons.temporal.config.WorkerMode;
import io.airbyte.commons.temporal.exception.RetryableException;
import io.airbyte.config.StandardSync;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.metrics.lib.ApmTraceUtils;
import io.airbyte.persistence.job.JobPersistence;
import io.airbyte.persistence.job.WorkspaceHelper;
import io.airbyte.persistence.job.models.Job;
import io.airbyte.validation.json.JsonValidationException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.text.ParseException;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTimeZone;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Slf4j
@Singleton
@Requires(env = WorkerMode.CONTROL_PLANE)
public class ConfigFetchActivityImpl implements ConfigFetchActivity {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigFetchActivityImpl.class);
  private final static long MS_PER_SECOND = 1000L;
  private final static long MIN_CRON_INTERVAL_SECONDS = 60;
  private static final Set<UUID> SCHEDULING_NOISE_WORKSPACE_IDS = Set.of(
      // Testing
      UUID.fromString("0ace5e1f-4787-43df-8919-456f5f4d03d1"),
      UUID.fromString("20810d92-41a4-4cfd-85db-fb50e77cf36b"),
      // Prod
      UUID.fromString("226edbc1-4a9c-4401-95a9-90435d667d9d"));
  private static final long SCHEDULING_NOISE_CONSTANT = 15;

  private final ConfigRepository configRepository;
  private final JobPersistence jobPersistence;
  private final WorkspaceHelper workspaceHelper;
  private final Integer syncJobMaxAttempts;
  private final Supplier<Long> currentSecondsSupplier;
  private final ConnectionApi connectionApi;

  public ConfigFetchActivityImpl(final ConfigRepository configRepository,
                                 final JobPersistence jobPersistence,
                                 @Value("${airbyte.worker.sync.max-attempts}") final Integer syncJobMaxAttempts,
                                 @Named("currentSecondsSupplier") final Supplier<Long> currentSecondsSupplier,
                                 final ConnectionApi connectionApi) {
    this(configRepository, jobPersistence, new WorkspaceHelper(configRepository, jobPersistence), syncJobMaxAttempts, currentSecondsSupplier,
        connectionApi);
  }

  @VisibleForTesting
  protected ConfigFetchActivityImpl(final ConfigRepository configRepository,
                                    final JobPersistence jobPersistence,
                                    final WorkspaceHelper workspaceHelper,
                                    @Value("${airbyte.worker.sync.max-attempts}") final Integer syncJobMaxAttempts,
                                    @Named("currentSecondsSupplier") final Supplier<Long> currentSecondsSupplier,
                                    final ConnectionApi connectionApi) {
    this.configRepository = configRepository;
    this.jobPersistence = jobPersistence;
    this.workspaceHelper = workspaceHelper;
    this.syncJobMaxAttempts = syncJobMaxAttempts;
    this.currentSecondsSupplier = currentSecondsSupplier;
    this.connectionApi = connectionApi;
  }

  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  @Override
  public StandardSync getStandardSync(final UUID connectionId) throws JsonValidationException, ConfigNotFoundException, IOException {
    return configRepository.getStandardSync(connectionId);
  }

  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  @Override
  public ScheduleRetrieverOutput getTimeToWait(final ScheduleRetrieverInput input) {
    try {
      ApmTraceUtils.addTagsToTrace(Map.of(CONNECTION_ID_KEY, input.getConnectionId()));
      final ConnectionIdRequestBody connectionIdRequestBody = new ConnectionIdRequestBody().connectionId(input.getConnectionId());
      final ConnectionRead connectionRead = connectionApi.getConnection(connectionIdRequestBody);
      if (connectionRead.getScheduleType() != null) {
        return this.getTimeToWaitFromScheduleType(connectionRead, input.getConnectionId());
      }
      return this.getTimeToWaitFromLegacy(connectionRead, input.getConnectionId());
    } catch (final IOException | ApiException e) {
      throw new RetryableException(e);
    }
  }

  /**
   * @param connectionRead
   * @param connectionId
   * @return
   * @throws IOException
   *
   *         This method consumes the `scheduleType` and `scheduleData` fields.
   */
  private ScheduleRetrieverOutput getTimeToWaitFromScheduleType(final ConnectionRead connectionRead, final UUID connectionId) throws IOException {
    if (connectionRead.getScheduleType() == ConnectionScheduleType.MANUAL || connectionRead.getStatus() != ConnectionStatus.ACTIVE) {
      // Manual syncs wait for their first run
      return new ScheduleRetrieverOutput(Duration.ofDays(100 * 365));
    }

    final Optional<Job> previousJobOptional = jobPersistence.getLastReplicationJob(connectionId);

    if (connectionRead.getScheduleType() == ConnectionScheduleType.BASIC) {
      if (previousJobOptional.isEmpty()) {
        // Basic schedules don't wait for their first run.
        return new ScheduleRetrieverOutput(Duration.ZERO);
      }
      final Job previousJob = previousJobOptional.get();
      final long prevRunStart = previousJob.getStartedAtInSecond().orElse(previousJob.getCreatedAtInSecond());
      final long nextRunStart = prevRunStart + getIntervalInSecond(connectionRead.getScheduleData().getBasicSchedule());
      final Duration timeToWait = Duration.ofSeconds(
          Math.max(0, nextRunStart - currentSecondsSupplier.get()));
      return new ScheduleRetrieverOutput(timeToWait);
    }

    else { // connectionRead.getScheduleType() == ConnectionScheduleType.CRON
      final ConnectionScheduleDataCron scheduleCron = connectionRead.getScheduleData().getCron();
      final TimeZone timeZone = DateTimeZone.forID(scheduleCron.getCronTimeZone()).toTimeZone();
      try {
        final CronExpression cronExpression = new CronExpression(scheduleCron.getCronExpression());
        cronExpression.setTimeZone(timeZone);
        // Ensure that at least a minimum interval -- one minute -- passes between executions. This prevents
        // us from multiple executions for the same scheduled time, since cron only has a 1-minute
        // resolution.
        final long earliestNextRun = Math.max(currentSecondsSupplier.get() * MS_PER_SECOND,
            (previousJobOptional.isPresent()
                ? previousJobOptional.get().getStartedAtInSecond().orElse(previousJobOptional.get().getCreatedAtInSecond())
                    + MIN_CRON_INTERVAL_SECONDS
                : currentSecondsSupplier.get()) * MS_PER_SECOND);
        final Date nextRunStart = cronExpression.getNextValidTimeAfter(new Date(earliestNextRun));
        Duration timeToWait = Duration.ofSeconds(
            Math.max(0, nextRunStart.getTime() / MS_PER_SECOND - currentSecondsSupplier.get()));

        timeToWait = addSchedulingNoiseForAllowListedWorkspace(timeToWait, connectionRead);
        return new ScheduleRetrieverOutput(timeToWait);
      } catch (final ParseException e) {
        throw (DateTimeException) new DateTimeException(e.getMessage()).initCause(e);
      }
    }
  }

  private Duration addSchedulingNoiseForAllowListedWorkspace(Duration timeToWait, ConnectionRead connectionRead) {
    final UUID workspaceId;
    try {
      workspaceId = workspaceHelper.getWorkspaceForConnectionId(connectionRead.getConnectionId());
    } catch (JsonValidationException | ConfigNotFoundException e) {
      // We tolerate exceptions and fail open by doing nothing.
      return timeToWait;
    }
    if (!SCHEDULING_NOISE_WORKSPACE_IDS.contains(workspaceId)) {
      // Only apply to a specific set of workspaces.
      return timeToWait;
    }
    if (!connectionRead.getScheduleType().equals(ConnectionScheduleType.CRON)) {
      // Only apply noise to cron connections.
      return timeToWait;
    }

    // We really do want to add some scheduling noise for this connection.
    final long minutesToWait = (long) (Math.random() * SCHEDULING_NOISE_CONSTANT);
    LOGGER.debug("Adding {} minutes noise to wait", minutesToWait);
    // Note: we add an extra second to make the unit tests pass in case `minutesToWait` was 0.
    return timeToWait.plusMinutes(minutesToWait).plusSeconds(1);
  }

  /**
   * @param connectionRead
   * @param connectionId
   * @return
   * @throws IOException
   *
   *         This method consumes the `schedule` field.
   */
  private ScheduleRetrieverOutput getTimeToWaitFromLegacy(final ConnectionRead connectionRead, final UUID connectionId) throws IOException {
    if (connectionRead.getSchedule() == null || connectionRead.getStatus() != ConnectionStatus.ACTIVE) {
      // Manual syncs wait for their first run
      return new ScheduleRetrieverOutput(Duration.ofDays(100 * 365));
    }

    final Optional<Job> previousJobOptional = jobPersistence.getLastReplicationJob(connectionId);

    if (previousJobOptional.isEmpty() && connectionRead.getSchedule() != null) {
      // Non-manual syncs don't wait for their first run
      return new ScheduleRetrieverOutput(Duration.ZERO);
    }

    final Job previousJob = previousJobOptional.get();
    final long prevRunStart = previousJob.getStartedAtInSecond().orElse(previousJob.getCreatedAtInSecond());

    final long nextRunStart = prevRunStart + getIntervalInSecond(connectionRead.getSchedule());

    final Duration timeToWait = Duration.ofSeconds(
        Math.max(0, nextRunStart - currentSecondsSupplier.get()));

    return new ScheduleRetrieverOutput(timeToWait);

  }

  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  @Override
  public GetMaxAttemptOutput getMaxAttempt() {
    return new GetMaxAttemptOutput(syncJobMaxAttempts);
  }

  @Override
  public Optional<UUID> getSourceId(final UUID connectionId) {
    try {
      final io.airbyte.api.client.model.generated.ConnectionIdRequestBody requestBody =
          new io.airbyte.api.client.model.generated.ConnectionIdRequestBody().connectionId(connectionId);
      final ConnectionRead connectionRead = connectionApi.getConnection(requestBody);
      return Optional.ofNullable(connectionRead.getSourceId());
    } catch (ApiException e) {
      log.info("Encountered an error fetching the connection's Source ID: ", e);
      return Optional.empty();
    }
  }

  @Override
  public Optional<ConnectionStatus> getStatus(final UUID connectionId) {
    try {
      final io.airbyte.api.client.model.generated.ConnectionIdRequestBody requestBody =
          new io.airbyte.api.client.model.generated.ConnectionIdRequestBody().connectionId(connectionId);
      final ConnectionRead connectionRead = connectionApi.getConnection(requestBody);
      return Optional.ofNullable(connectionRead.getStatus());
    } catch (ApiException e) {
      log.info("Encountered an error fetching the connection's status: ", e);
      return Optional.empty();
    }
  }

  private Long getIntervalInSecond(final ConnectionScheduleDataBasicSchedule schedule) {
    return getSecondsInUnit(schedule.getTimeUnit()) * schedule.getUnits();
  }

  private Long getIntervalInSecond(final ConnectionSchedule schedule) {
    return getSecondsInUnit(schedule.getTimeUnit()) * schedule.getUnits();
  }

  private Long getSecondsInUnit(final TimeUnitEnum timeUnitEnum) {
    switch (timeUnitEnum) {
      case MINUTES:
        return TimeUnit.MINUTES.toSeconds(1);
      case HOURS:
        return TimeUnit.HOURS.toSeconds(1);
      case DAYS:
        return TimeUnit.DAYS.toSeconds(1);
      case WEEKS:
        return TimeUnit.DAYS.toSeconds(1) * 7;
      case MONTHS:
        return TimeUnit.DAYS.toSeconds(1) * 30;
      default:
        throw new RuntimeException("Unhandled TimeUnitEnum: " + timeUnitEnum);
    }
  }

  private Long getSecondsInUnit(final ConnectionSchedule.TimeUnitEnum timeUnitEnum) {
    switch (timeUnitEnum) {
      case MINUTES:
        return TimeUnit.MINUTES.toSeconds(1);
      case HOURS:
        return TimeUnit.HOURS.toSeconds(1);
      case DAYS:
        return TimeUnit.DAYS.toSeconds(1);
      case WEEKS:
        return TimeUnit.DAYS.toSeconds(1) * 7;
      case MONTHS:
        return TimeUnit.DAYS.toSeconds(1) * 30;
      default:
        throw new RuntimeException("Unhandled TimeUnitEnum: " + timeUnitEnum);
    }
  }

}
