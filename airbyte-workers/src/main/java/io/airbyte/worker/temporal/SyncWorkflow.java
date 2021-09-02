/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.worker.temporal;

import com.google.common.annotations.VisibleForTesting;
import io.airbyte.commons.functional.CheckedSupplier;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.AirbyteConfigValidator;
import io.airbyte.config.ConfigSchema;
import io.airbyte.config.EnvConfigs;
import io.airbyte.config.NormalizationInput;
import io.airbyte.config.OperatorDbtInput;
import io.airbyte.config.ReplicationOutput;
import io.airbyte.config.ResourceRequirements;
import io.airbyte.config.StandardSyncInput;
import io.airbyte.config.StandardSyncOperation;
import io.airbyte.config.StandardSyncOperation.OperatorType;
import io.airbyte.config.StandardSyncOutput;
import io.airbyte.config.StandardSyncSummary;
import io.airbyte.scheduler.models.IntegrationLauncherConfig;
import io.airbyte.scheduler.models.JobRunConfig;
import io.airbyte.worker.DbtTransformationRunner;
import io.airbyte.worker.DbtTransformationWorker;
import io.airbyte.worker.DefaultNormalizationWorker;
import io.airbyte.worker.DefaultReplicationWorker;
import io.airbyte.worker.Worker;
import io.airbyte.worker.WorkerConstants;
import io.airbyte.worker.normalization.NormalizationRunnerFactory;
import io.airbyte.worker.process.AirbyteIntegrationLauncher;
import io.airbyte.worker.process.IntegrationLauncher;
import io.airbyte.worker.process.ProcessFactory;
import io.airbyte.worker.protocols.airbyte.AirbyteMessageTracker;
import io.airbyte.worker.protocols.airbyte.AirbyteSource;
import io.airbyte.worker.protocols.airbyte.DefaultAirbyteDestination;
import io.airbyte.worker.protocols.airbyte.DefaultAirbyteSource;
import io.airbyte.worker.protocols.airbyte.EmptyAirbyteSource;
import io.airbyte.worker.protocols.airbyte.NamespacingMapper;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WorkflowInterface
public interface SyncWorkflow {

  @WorkflowMethod
  StandardSyncOutput run(JobRunConfig jobRunConfig,
                         IntegrationLauncherConfig sourceLauncherConfig,
                         IntegrationLauncherConfig destinationLauncherConfig,
                         StandardSyncInput syncInput);

  class WorkflowImpl implements SyncWorkflow {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowImpl.class);

    private static final int MAX_SYNC_TIMEOUT_DAYS = new EnvConfigs().getMaxSyncTimeoutDays();

    private static final ActivityOptions options = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofDays(MAX_SYNC_TIMEOUT_DAYS))
        .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
        .setRetryOptions(TemporalUtils.NO_RETRY)
        .build();

    private final ReplicationActivity replicationActivity = Workflow.newActivityStub(ReplicationActivity.class, options);
    private final NormalizationActivity normalizationActivity = Workflow.newActivityStub(NormalizationActivity.class, options);
    private final DbtTransformationActivity dbtTransformationActivity = Workflow.newActivityStub(DbtTransformationActivity.class, options);

    @Override
    public StandardSyncOutput run(JobRunConfig jobRunConfig,
                                  IntegrationLauncherConfig sourceLauncherConfig,
                                  IntegrationLauncherConfig destinationLauncherConfig,
                                  StandardSyncInput syncInput) {
      final StandardSyncOutput run = replicationActivity.replicate(jobRunConfig, sourceLauncherConfig, destinationLauncherConfig, syncInput);

      if (syncInput.getOperationSequence() != null && !syncInput.getOperationSequence().isEmpty()) {
        for (StandardSyncOperation standardSyncOperation : syncInput.getOperationSequence()) {
          if (standardSyncOperation.getOperatorType() == OperatorType.NORMALIZATION) {
            final NormalizationInput normalizationInput = new NormalizationInput()
                .withDestinationConfiguration(syncInput.getDestinationConfiguration())
                .withCatalog(run.getOutputCatalog())
                .withResourceRequirements(syncInput.getResourceRequirements());

            normalizationActivity.normalize(jobRunConfig, destinationLauncherConfig, normalizationInput);
          } else if (standardSyncOperation.getOperatorType() == OperatorType.DBT) {
            final OperatorDbtInput operatorDbtInput = new OperatorDbtInput()
                .withDestinationConfiguration(syncInput.getDestinationConfiguration())
                .withOperatorDbt(standardSyncOperation.getOperatorDbt());

            dbtTransformationActivity.run(jobRunConfig, destinationLauncherConfig, syncInput.getResourceRequirements(), operatorDbtInput);
          } else {
            final String message = String.format("Unsupported operation type: %s", standardSyncOperation.getOperatorType());
            LOGGER.error(message);
            throw new IllegalArgumentException(message);
          }
        }
      }

      return run;
    }

  }

  @ActivityInterface
  interface ReplicationActivity {

    @ActivityMethod
    StandardSyncOutput replicate(JobRunConfig jobRunConfig,
                                 IntegrationLauncherConfig sourceLauncherConfig,
                                 IntegrationLauncherConfig destinationLauncherConfig,
                                 StandardSyncInput syncInput);

  }

  class ReplicationActivityImpl implements ReplicationActivity {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplicationActivityImpl.class);

    private final ProcessFactory processFactory;
    private final Path workspaceRoot;
    private final AirbyteConfigValidator validator;

    public ReplicationActivityImpl(ProcessFactory processFactory, Path workspaceRoot) {
      this(processFactory, workspaceRoot, new AirbyteConfigValidator());
    }

    @VisibleForTesting
    ReplicationActivityImpl(ProcessFactory processFactory, Path workspaceRoot, AirbyteConfigValidator validator) {
      this.processFactory = processFactory;
      this.workspaceRoot = workspaceRoot;
      this.validator = validator;
    }

    @Override
    public StandardSyncOutput replicate(JobRunConfig jobRunConfig,
                                        IntegrationLauncherConfig sourceLauncherConfig,
                                        IntegrationLauncherConfig destinationLauncherConfig,
                                        StandardSyncInput syncInput) {

      final Supplier<StandardSyncInput> inputSupplier = () -> {
        validator.ensureAsRuntime(ConfigSchema.STANDARD_SYNC_INPUT, Jsons.jsonNode(syncInput));
        return syncInput;
      };

      final TemporalAttemptExecution<StandardSyncInput, ReplicationOutput> temporalAttempt = new TemporalAttemptExecution<>(
          workspaceRoot,
          jobRunConfig,
          getWorkerFactory(sourceLauncherConfig, destinationLauncherConfig, jobRunConfig, syncInput),
          inputSupplier,
          new CancellationHandler.TemporalCancellationHandler());

      final ReplicationOutput attemptOutput = temporalAttempt.get();
      final StandardSyncOutput standardSyncOutput = reduceReplicationOutput(attemptOutput);

      LOGGER.info("sync summary: {}", standardSyncOutput);

      return standardSyncOutput;
    }

    private static StandardSyncOutput reduceReplicationOutput(ReplicationOutput output) {
      final long totalBytesReplicated = output.getReplicationAttemptSummary().getBytesSynced();
      final long totalRecordsReplicated = output.getReplicationAttemptSummary().getRecordsSynced();

      final StandardSyncSummary syncSummary = new StandardSyncSummary();
      syncSummary.setBytesSynced(totalBytesReplicated);
      syncSummary.setRecordsSynced(totalRecordsReplicated);
      syncSummary.setStartTime(output.getReplicationAttemptSummary().getStartTime());
      syncSummary.setEndTime(output.getReplicationAttemptSummary().getEndTime());
      syncSummary.setStatus(output.getReplicationAttemptSummary().getStatus());

      final StandardSyncOutput standardSyncOutput = new StandardSyncOutput();
      standardSyncOutput.setState(output.getState());
      standardSyncOutput.setOutputCatalog(output.getOutputCatalog());
      standardSyncOutput.setStandardSyncSummary(syncSummary);

      return standardSyncOutput;
    }

    private CheckedSupplier<Worker<StandardSyncInput, ReplicationOutput>, Exception> getWorkerFactory(
                                                                                                      IntegrationLauncherConfig sourceLauncherConfig,
                                                                                                      IntegrationLauncherConfig destinationLauncherConfig,
                                                                                                      JobRunConfig jobRunConfig,
                                                                                                      StandardSyncInput syncInput) {
      return () -> {
        final IntegrationLauncher sourceLauncher = new AirbyteIntegrationLauncher(
            sourceLauncherConfig.getJobId(),
            Math.toIntExact(sourceLauncherConfig.getAttemptId()),
            sourceLauncherConfig.getDockerImage(),
            processFactory,
            syncInput.getResourceRequirements());
        final IntegrationLauncher destinationLauncher = new AirbyteIntegrationLauncher(
            destinationLauncherConfig.getJobId(),
            Math.toIntExact(destinationLauncherConfig.getAttemptId()),
            destinationLauncherConfig.getDockerImage(),
            processFactory,
            syncInput.getResourceRequirements());

        // reset jobs use an empty source to induce resetting all data in destination.
        final AirbyteSource airbyteSource =
            sourceLauncherConfig.getDockerImage().equals(WorkerConstants.RESET_JOB_SOURCE_DOCKER_IMAGE_STUB) ? new EmptyAirbyteSource()
                : new DefaultAirbyteSource(sourceLauncher);

        return new DefaultReplicationWorker(
            jobRunConfig.getJobId(),
            Math.toIntExact(jobRunConfig.getAttemptId()),
            airbyteSource,
            new NamespacingMapper(syncInput.getNamespaceDefinition(), syncInput.getNamespaceFormat(), syncInput.getPrefix()),
            new DefaultAirbyteDestination(destinationLauncher),
            new AirbyteMessageTracker(),
            new AirbyteMessageTracker());
      };
    }

  }

  @ActivityInterface
  interface NormalizationActivity {

    @ActivityMethod
    Void normalize(JobRunConfig jobRunConfig,
                   IntegrationLauncherConfig destinationLauncherConfig,
                   NormalizationInput input);

  }

  class NormalizationActivityImpl implements NormalizationActivity {

    private static final Logger LOGGER = LoggerFactory.getLogger(NormalizationActivityImpl.class);

    private final ProcessFactory processFactory;
    private final Path workspaceRoot;
    private final AirbyteConfigValidator validator;

    public NormalizationActivityImpl(ProcessFactory processFactory, Path workspaceRoot) {
      this(processFactory, workspaceRoot, new AirbyteConfigValidator());
    }

    @VisibleForTesting
    NormalizationActivityImpl(ProcessFactory processFactory, Path workspaceRoot, AirbyteConfigValidator validator) {
      this.processFactory = processFactory;
      this.workspaceRoot = workspaceRoot;
      this.validator = validator;
    }

    @Override
    public Void normalize(JobRunConfig jobRunConfig,
                          IntegrationLauncherConfig destinationLauncherConfig,
                          NormalizationInput input) {

      final Supplier<NormalizationInput> inputSupplier = () -> {
        validator.ensureAsRuntime(ConfigSchema.NORMALIZATION_INPUT, Jsons.jsonNode(input));
        return input;
      };

      final TemporalAttemptExecution<NormalizationInput, Void> temporalAttemptExecution = new TemporalAttemptExecution<>(
          workspaceRoot,
          jobRunConfig,
          getWorkerFactory(destinationLauncherConfig, jobRunConfig),
          inputSupplier,
          new CancellationHandler.TemporalCancellationHandler());

      return temporalAttemptExecution.get();
    }

    private CheckedSupplier<Worker<NormalizationInput, Void>, Exception> getWorkerFactory(IntegrationLauncherConfig destinationLauncherConfig,
                                                                                          JobRunConfig jobRunConfig) {
      return () -> new DefaultNormalizationWorker(
          jobRunConfig.getJobId(),
          Math.toIntExact(jobRunConfig.getAttemptId()),
          NormalizationRunnerFactory.create(
              destinationLauncherConfig.getDockerImage(),
              processFactory));
    }

  }

  @ActivityInterface
  interface DbtTransformationActivity {

    @ActivityMethod
    Void run(JobRunConfig jobRunConfig,
             IntegrationLauncherConfig destinationLauncherConfig,
             ResourceRequirements resourceRequirements,
             OperatorDbtInput input);

  }

  class DbtTransformationActivityImpl implements DbtTransformationActivity {

    private static final Logger LOGGER = LoggerFactory.getLogger(DbtTransformationActivityImpl.class);

    private final ProcessFactory processFactory;
    private final Path workspaceRoot;
    private final AirbyteConfigValidator validator;

    public DbtTransformationActivityImpl(ProcessFactory processFactory, Path workspaceRoot) {
      this(processFactory, workspaceRoot, new AirbyteConfigValidator());
    }

    @VisibleForTesting
    DbtTransformationActivityImpl(ProcessFactory processFactory, Path workspaceRoot, AirbyteConfigValidator validator) {
      this.processFactory = processFactory;
      this.workspaceRoot = workspaceRoot;
      this.validator = validator;
    }

    @Override
    public Void run(JobRunConfig jobRunConfig,
                    IntegrationLauncherConfig destinationLauncherConfig,
                    ResourceRequirements resourceRequirements,
                    OperatorDbtInput input) {

      final Supplier<OperatorDbtInput> inputSupplier = () -> {
        validator.ensureAsRuntime(ConfigSchema.OPERATOR_DBT_INPUT, Jsons.jsonNode(input));
        return input;
      };

      final TemporalAttemptExecution<OperatorDbtInput, Void> temporalAttemptExecution = new TemporalAttemptExecution<>(
          workspaceRoot,
          jobRunConfig,
          getWorkerFactory(destinationLauncherConfig, jobRunConfig, resourceRequirements),
          inputSupplier,
          new CancellationHandler.TemporalCancellationHandler());

      return temporalAttemptExecution.get();
    }

    private CheckedSupplier<Worker<OperatorDbtInput, Void>, Exception> getWorkerFactory(IntegrationLauncherConfig destinationLauncherConfig,
                                                                                        JobRunConfig jobRunConfig,
                                                                                        ResourceRequirements resourceRequirements) {
      return () -> new DbtTransformationWorker(
          jobRunConfig.getJobId(),
          Math.toIntExact(jobRunConfig.getAttemptId()),
          resourceRequirements,
          new DbtTransformationRunner(
              processFactory, NormalizationRunnerFactory.create(
                  destinationLauncherConfig.getDockerImage(),
                  processFactory)));
    }

  }

}