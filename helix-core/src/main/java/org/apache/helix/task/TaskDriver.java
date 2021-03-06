package org.apache.helix.task;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.I0Itec.zkclient.DataUpdater;
import org.apache.helix.AccessOption;
import org.apache.helix.ConfigAccessor;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixException;
import org.apache.helix.HelixManager;
import org.apache.helix.PropertyKey;
import org.apache.helix.PropertyPathBuilder;
import org.apache.helix.ZNRecord;
import org.apache.helix.controller.rebalancer.util.RebalanceScheduler;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.manager.zk.ZKHelixDataAccessor;
import org.apache.helix.manager.zk.ZkBaseDataAccessor;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.ResourceConfig;
import org.apache.helix.model.builder.CustomModeISBuilder;
import org.apache.helix.store.HelixPropertyStore;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.apache.log4j.Logger;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * CLI for scheduling/canceling workflows
 */
public class TaskDriver {
  public enum DriverCommand {
    start,
    stop,
    delete,
    resume,
    list,
    flush,
    clean
  }

  /** For logging */
  private static final Logger LOG = Logger.getLogger(TaskDriver.class);

  /** Default time out for monitoring workflow or job state */
  private final static int _defaultTimeout = 3 * 60 * 1000; /* 3 mins */

  private final HelixDataAccessor _accessor;
  private final HelixPropertyStore<ZNRecord> _propertyStore;
  private final HelixAdmin _admin;
  private final String _clusterName;

  public TaskDriver(HelixManager manager) {
    this(manager.getClusterManagmentTool(), manager.getHelixDataAccessor(),
        manager.getHelixPropertyStore(), manager.getClusterName());
  }

  public TaskDriver(ZkClient client, String clusterName) {
    this(client, new ZkBaseDataAccessor<ZNRecord>(client), clusterName);
  }

  public TaskDriver(ZkClient client, ZkBaseDataAccessor<ZNRecord> baseAccessor, String clusterName) {
    this(new ZKHelixAdmin(client), new ZKHelixDataAccessor(clusterName, baseAccessor),
        new ZkHelixPropertyStore<ZNRecord>(baseAccessor,
            PropertyPathBuilder.propertyStore(clusterName), null), clusterName);
  }

  @Deprecated
  public TaskDriver(HelixAdmin admin, HelixDataAccessor accessor, ConfigAccessor cfgAccessor,
      HelixPropertyStore<ZNRecord> propertyStore, String clusterName) {
    this(admin, accessor, propertyStore, clusterName);
  }

  public TaskDriver(HelixAdmin admin, HelixDataAccessor accessor,
      HelixPropertyStore<ZNRecord> propertyStore, String clusterName) {
    _admin = admin;
    _accessor = accessor;
    _propertyStore = propertyStore;
    _clusterName = clusterName;
  }


  /** Schedules a new workflow
   *
   * @param flow
   */
  public void start(Workflow flow) {
    // TODO: check that namespace for workflow is available
    LOG.info("Starting workflow " + flow.getName());
    flow.validate();

    WorkflowConfig newWorkflowConfig =
        new WorkflowConfig.Builder().setConfigMap(flow.getResourceConfigMap())
            .setWorkflowId(flow.getName()).build();

    Map<String, String> jobTypes = new HashMap<String, String>();
    // add all job configs.
    for (String job : flow.getJobConfigs().keySet()) {
      JobConfig.Builder jobCfgBuilder = JobConfig.Builder.fromMap(flow.getJobConfigs().get(job));
      if (flow.getTaskConfigs() != null && flow.getTaskConfigs().containsKey(job)) {
        jobCfgBuilder.addTaskConfigs(flow.getTaskConfigs().get(job));
      }
      JobConfig jobCfg = jobCfgBuilder.build();
      if (jobCfg.getJobType() != null) {
        jobTypes.put(job, jobCfg.getJobType());
      }
      addJobConfig(job, jobCfg);
    }
    newWorkflowConfig.setJobTypes(jobTypes);

    // add workflow config.
    if (!TaskUtil.setResourceConfig(_accessor, flow.getName(), newWorkflowConfig)) {
      LOG.error("Failed to add workflow configuration for workflow " + flow.getName());
    }

    // Finally add workflow resource.
    addWorkflowResource(flow.getName());
  }

  /**
   * Update the configuration of a non-terminable workflow (queue).
   * The terminable workflow's configuration is not allowed
   * to change once created.
   * Note:
   * For recurrent workflow, the current running schedule will not be effected,
   * the new configuration will be applied to the next scheduled runs of the workflow.
   * For non-recurrent workflow, the new configuration may (or may not) be applied
   * on the current running jobs, but it will be applied on the following unscheduled jobs.
   *
   * Example:
   *
   * _driver = new TaskDriver ...
   * WorkflowConfig currentWorkflowConfig = _driver.getWorkflowCfg(_manager, workflow);
   * WorkflowConfig.Builder configBuilder = new WorkflowConfig.Builder(currentWorkflowConfig);

   * // make needed changes to the config here
   * configBuilder.setXXX();
   *
   * // update workflow configuration
   * _driver.updateWorkflow(workflow, configBuilder.build());
   *
   * @param workflow
   * @param newWorkflowConfig
   */
  public void updateWorkflow(String workflow, WorkflowConfig newWorkflowConfig) {
    WorkflowConfig currentConfig =
        TaskUtil.getWorkflowCfg(_accessor, workflow);
    if (currentConfig == null) {
      throw new HelixException("Workflow " + workflow + " does not exist!");
    }

    if (currentConfig.isTerminable()) {
      throw new HelixException(
          "Workflow " + workflow + " is terminable, not allow to change its configuration!");
    }

    if (!TaskUtil.setResourceConfig(_accessor, workflow, newWorkflowConfig)) {
      LOG.error("Failed to update workflow configuration for workflow " + workflow);
    }

    RebalanceScheduler.invokeRebalance(_accessor, workflow);
  }

  /**
   * Creates a new named job queue (workflow)
   *
   * @param queue
   */
  public void createQueue(JobQueue queue) {
    start(queue);
  }

  /**
   * Remove all jobs in a job queue
   *
   * @param queueName
   * @throws Exception
   */
  // TODO: need to make sure the queue is stopped or completed before flush the queue.
  public void flushQueue(String queueName) {
    WorkflowConfig config =
        TaskUtil.getWorkflowCfg(_accessor, queueName);
    if (config == null) {
      throw new IllegalArgumentException("Queue does not exist!");
    }

    // Remove all ideal states and resource configs to trigger a drop event
    PropertyKey.Builder keyBuilder = _accessor.keyBuilder();
    final Set<String> toRemove = Sets.newHashSet(config.getJobDag().getAllNodes());
    for (String resourceName : toRemove) {
      _accessor.removeProperty(keyBuilder.idealStates(resourceName));
      _accessor.removeProperty(keyBuilder.resourceConfig(resourceName));
      // Delete context
      String contextKey = Joiner.on("/").join(TaskConstants.REBALANCER_CONTEXT_ROOT, resourceName);
      _propertyStore.remove(contextKey, AccessOption.PERSISTENT);
    }

    // Now atomically clear the DAG
    String path = keyBuilder.resourceConfig(queueName).getPath();
    DataUpdater<ZNRecord> updater = new DataUpdater<ZNRecord>() {
      @Override
      public ZNRecord update(ZNRecord currentData) {
        JobDag jobDag = JobDag.fromJson(
            currentData.getSimpleField(WorkflowConfig.WorkflowConfigProperty.Dag.name()));
        for (String resourceName : toRemove) {
          for (String child : jobDag.getDirectChildren(resourceName)) {
            jobDag.getChildrenToParents().get(child).remove(resourceName);
          }
          for (String parent : jobDag.getDirectParents(resourceName)) {
            jobDag.getParentsToChildren().get(parent).remove(resourceName);
          }
          jobDag.getChildrenToParents().remove(resourceName);
          jobDag.getParentsToChildren().remove(resourceName);
          jobDag.getAllNodes().remove(resourceName);
        }
        try {
          currentData
              .setSimpleField(WorkflowConfig.WorkflowConfigProperty.Dag.name(), jobDag.toJson());
        } catch (Exception e) {
          throw new IllegalArgumentException(e);
        }
        return currentData;
      }
    };
    _accessor.getBaseDataAccessor().update(path, updater, AccessOption.PERSISTENT);

    // Now atomically clear the results
    path = Joiner.on("/")
        .join(TaskConstants.REBALANCER_CONTEXT_ROOT, queueName, TaskUtil.CONTEXT_NODE);
    updater = new DataUpdater<ZNRecord>() {
      @Override public ZNRecord update(ZNRecord currentData) {
        Map<String, String> states =
            currentData.getMapField(WorkflowContext.WorkflowContextProperties.JOB_STATES.name());
        if (states != null) {
          states.keySet().removeAll(toRemove);
        }
        return currentData;
      }
    };
    _propertyStore.update(path, updater, AccessOption.PERSISTENT);
  }

  /**
   * Delete a job from an existing named queue,
   * the queue has to be stopped prior to this call
   *
   * @param queueName
   * @param jobName
   */
  public void deleteJob(final String queueName, final String jobName) {
    WorkflowConfig workflowCfg =
        TaskUtil.getWorkflowCfg(_accessor, queueName);

    if (workflowCfg == null) {
      throw new IllegalArgumentException("Queue " + queueName + " does not yet exist!");
    }
    if (workflowCfg.isTerminable()) {
      throw new IllegalArgumentException(queueName + " is not a queue!");
    }

    boolean isRecurringWorkflow =
        (workflowCfg.getScheduleConfig() != null && workflowCfg.getScheduleConfig().isRecurring());

    if (isRecurringWorkflow) {
      WorkflowContext wCtx = TaskUtil.getWorkflowContext(_propertyStore, queueName);

      String lastScheduledQueue = wCtx.getLastScheduledSingleWorkflow();

      // delete the current scheduled one
      deleteJobFromScheduledQueue(lastScheduledQueue, jobName, true);

      // Remove the job from the original queue template's DAG
      removeJobFromDag(queueName, jobName);

      // delete the ideal state and resource config for the template job
      final String namespacedJobName = TaskUtil.getNamespacedJobName(queueName, jobName);
      _admin.dropResource(_clusterName, namespacedJobName);

      // Delete the job template from property store
      String jobPropertyPath =
          Joiner.on("/")
              .join(TaskConstants.REBALANCER_CONTEXT_ROOT, namespacedJobName);
      _propertyStore.remove(jobPropertyPath, AccessOption.PERSISTENT);
    } else {
      deleteJobFromScheduledQueue(queueName, jobName, false);
    }
  }

  /**
   * delete a job from a scheduled (non-recurrent) queue.
   *
   * @param queueName
   * @param jobName
   */
  private void deleteJobFromScheduledQueue(final String queueName, final String jobName,
      boolean isRecurrent) {
    WorkflowConfig workflowCfg = TaskUtil.getWorkflowCfg(_accessor, queueName);

    if (workflowCfg == null) {
      // When try to delete recurrent job, it could be either not started or finished. So
      // there may not be a workflow config.
      if (isRecurrent) {
        return;
      } else {
        throw new IllegalArgumentException("Queue " + queueName + " does not yet exist!");
      }
    }

    WorkflowContext wCtx = TaskUtil.getWorkflowContext(_propertyStore, queueName);
    if (wCtx != null && wCtx.getWorkflowState() == null) {
      throw new IllegalStateException("Queue " + queueName + " does not have a valid work state!");
    }

    String workflowState =
        (wCtx != null) ? wCtx.getWorkflowState().name() : TaskState.NOT_STARTED.name();

    if (workflowState.equals(TaskState.IN_PROGRESS.name())) {
      throw new IllegalStateException("Queue " + queueName + " is still in progress!");
    }

    removeJob(queueName, jobName);
  }

  private void removeJob(String queueName, String jobName) {
    // Remove the job from the queue in the DAG
    removeJobFromDag(queueName, jobName);

    // delete the ideal state and resource config for the job
    final String namespacedJobName = TaskUtil.getNamespacedJobName(queueName, jobName);
    _admin.dropResource(_clusterName, namespacedJobName);

    // update queue's property to remove job from JOB_STATES if it is already started.
    removeJobStateFromQueue(queueName, jobName);

    // Delete the job from property store
    TaskUtil.removeJobContext(_propertyStore, jobName);
  }

  /** Remove the job name from the DAG from the queue configuration */
  private void removeJobFromDag(final String queueName, final String jobName) {
    final String namespacedJobName = TaskUtil.getNamespacedJobName(queueName, jobName);

    DataUpdater<ZNRecord> dagRemover = new DataUpdater<ZNRecord>() {
      @Override
      public ZNRecord update(ZNRecord currentData) {
        if (currentData == null) {
          LOG.error("Could not update DAG for queue: " + queueName + " ZNRecord is null.");
          return null;
        }
        // Add the node to the existing DAG
        JobDag jobDag = JobDag.fromJson(
            currentData.getSimpleField(WorkflowConfig.WorkflowConfigProperty.Dag.name()));
        Set<String> allNodes = jobDag.getAllNodes();
        if (!allNodes.contains(namespacedJobName)) {
          LOG.warn(
              "Could not delete job from queue " + queueName + ", job " + jobName + " not exists");
          return currentData;
        }
        String parent = null;
        String child = null;
        // remove the node from the queue
        for (String node : allNodes) {
          if (jobDag.getDirectChildren(node).contains(namespacedJobName)) {
            parent = node;
            jobDag.removeParentToChild(parent, namespacedJobName);
          } else if (jobDag.getDirectParents(node).contains(namespacedJobName)) {
            child = node;
            jobDag.removeParentToChild(namespacedJobName, child);
          }
        }
        if (parent != null && child != null) {
          jobDag.addParentToChild(parent, child);
        }
        jobDag.removeNode(namespacedJobName);

        // Save the updated DAG
        try {
          currentData
              .setSimpleField(WorkflowConfig.WorkflowConfigProperty.Dag.name(), jobDag.toJson());
        } catch (Exception e) {
          throw new IllegalStateException(
              "Could not remove job " + jobName + " from DAG of queue " + queueName, e);
        }
        return currentData;
      }
    };

    String path = _accessor.keyBuilder().resourceConfig(queueName).getPath();
    if (!_accessor.getBaseDataAccessor().update(path, dagRemover, AccessOption.PERSISTENT)) {
      throw new IllegalArgumentException(
          "Could not remove job " + jobName + " from DAG of queue " + queueName);
    }
  }

  /** update queue's property to remove job from JOB_STATES if it is already started. */
  private void removeJobStateFromQueue(final String queueName, final String jobName) {
    final String namespacedJobName = TaskUtil.getNamespacedJobName(queueName, jobName);
    String queuePropertyPath =
        Joiner.on("/")
            .join(TaskConstants.REBALANCER_CONTEXT_ROOT, queueName, TaskUtil.CONTEXT_NODE);

    DataUpdater<ZNRecord> updater = new DataUpdater<ZNRecord>() {
      @Override public ZNRecord update(ZNRecord currentData) {
        if (currentData != null) {
          Map<String, String> states =
              currentData.getMapField(WorkflowContext.WorkflowContextProperties.JOB_STATES.name());
          if (states != null && states.containsKey(namespacedJobName)) {
            states.keySet().remove(namespacedJobName);
          }
        }
        return currentData;
      }
    };
    if (!_propertyStore.update(queuePropertyPath, updater, AccessOption.PERSISTENT)) {
      LOG.warn("Fail to remove job state for job " + namespacedJobName + " from queue " + queueName);
    }
  }

  /**
   * Adds a new job to the end an existing named queue.
   *
   * @param queueName
   * @param jobName
   * @param jobBuilder
   * @throws Exception
   */
  public void enqueueJob(final String queueName, final String jobName,
      JobConfig.Builder jobBuilder) {
    // Get the job queue config and capacity
    WorkflowConfig workflowConfig = TaskUtil.getWorkflowCfg(_accessor, queueName);
    if (workflowConfig == null) {
      throw new IllegalArgumentException("Queue " + queueName + " config does not yet exist!");
    }
    boolean isTerminable = workflowConfig.isTerminable();
    if (isTerminable) {
      throw new IllegalArgumentException(queueName + " is not a queue!");
    }

    final int capacity = workflowConfig.getCapacity();

    // Create the job to ensure that it validates
    JobConfig jobConfig = jobBuilder.setWorkflow(queueName).build();

    final String namespacedJobName = TaskUtil.getNamespacedJobName(queueName, jobName);

    // add job config first.
    addJobConfig(namespacedJobName, jobConfig);
    final String jobType = jobConfig.getJobType();

    // Add the job to the end of the queue in the DAG
    DataUpdater<ZNRecord> updater = new DataUpdater<ZNRecord>() {
      @Override
      public ZNRecord update(ZNRecord currentData) {
        // Add the node to the existing DAG
        JobDag jobDag = JobDag.fromJson(
            currentData.getSimpleField(WorkflowConfig.WorkflowConfigProperty.Dag.name()));
        Set<String> allNodes = jobDag.getAllNodes();
        if (capacity > 0 && allNodes.size() >= capacity) {
          throw new IllegalStateException(
              "Queue " + queueName + " is at capacity, will not add " + jobName);
        }
        if (allNodes.contains(namespacedJobName)) {
          throw new IllegalStateException(
              "Could not add to queue " + queueName + ", job " + jobName + " already exists");
        }
        jobDag.addNode(namespacedJobName);

        // Add the node to the end of the queue
        String candidate = null;
        for (String node : allNodes) {
          if (!node.equals(namespacedJobName) && jobDag.getDirectChildren(node).isEmpty()) {
            candidate = node;
            break;
          }
        }
        if (candidate != null) {
          jobDag.addParentToChild(candidate, namespacedJobName);
        }

        // Add job type if job type is not null
        if (jobType != null) {
          Map<String, String> jobTypes =
              currentData.getMapField(WorkflowConfig.WorkflowConfigProperty.JobTypes.name());
          if (jobTypes == null) {
            jobTypes = new HashMap<String, String>();
          }
          jobTypes.put(jobName, jobType);
          currentData.setMapField(WorkflowConfig.WorkflowConfigProperty.JobTypes.name(), jobTypes);
        }

        // Save the updated DAG
        try {
          currentData
              .setSimpleField(WorkflowConfig.WorkflowConfigProperty.Dag.name(), jobDag.toJson());
        } catch (Exception e) {
          throw new IllegalStateException("Could not add job " + jobName + " to queue " + queueName,
              e);
        }
        return currentData;
      }
    };
    String path = _accessor.keyBuilder().resourceConfig(queueName).getPath();
    boolean status = _accessor.getBaseDataAccessor().update(path, updater, AccessOption.PERSISTENT);
    if (!status) {
      throw new IllegalArgumentException("Could not enqueue job");
    }

    // This is to make it back-compatible with old Helix task driver.
    addWorkflowResourceIfNecessary(queueName);

    // Schedule the job
    RebalanceScheduler.invokeRebalance(_accessor, queueName);
  }

  /**
   * Remove all jobs that are in final states (ABORTED, FAILED, COMPLETED) from the job queue.
   * The job config, job context will be removed from Zookeeper.
   *
   * @param queueName The name of job queue
   */
  public void cleanupJobQueue(String queueName) {
    WorkflowConfig workflowCfg =
        TaskUtil.getWorkflowCfg(_accessor, queueName);

    if (workflowCfg == null) {
      throw new IllegalArgumentException("Queue " + queueName + " does not yet exist!");
    }

    WorkflowContext wCtx = TaskUtil.getWorkflowContext(_propertyStore, queueName);
    if (wCtx != null && wCtx.getWorkflowState() == null) {
      throw new IllegalStateException("Queue " + queueName + " does not have a valid work state!");
    }

    for (String jobNode : workflowCfg.getJobDag().getAllNodes()) {
      TaskState curState = wCtx.getJobState(jobNode);
      if (curState != null && (curState == TaskState.ABORTED || curState == TaskState.COMPLETED
          || curState == TaskState.FAILED)) {
        removeJob(queueName, TaskUtil.getDenamespacedJobName(queueName, jobNode));
      }
    }
  }

  /** Posts new workflow resource to cluster */
  private void addWorkflowResource(String workflow) {
    // Add workflow resource
    _admin.addResource(_clusterName, workflow, 1, TaskConstants.STATE_MODEL_NAME);

    IdealState is = buildWorkflowIdealState(workflow);
    TaskUtil
        .createUserContent(_propertyStore, workflow, new ZNRecord(TaskUtil.USER_CONTENT_NODE));

    _admin.setResourceIdealState(_clusterName, workflow, is);

  }

  /**
   * Posts new workflow resource to cluster if it does not exist
   */
  private void addWorkflowResourceIfNecessary(String workflow) {
    IdealState is = _admin.getResourceIdealState(_clusterName, workflow);
    if (is == null) {
      addWorkflowResource(workflow);
    }
  }

  private IdealState buildWorkflowIdealState(String workflow) {
    CustomModeISBuilder IsBuilder = new CustomModeISBuilder(workflow);
    IsBuilder.setRebalancerMode(IdealState.RebalanceMode.TASK).setNumReplica(1).setNumPartitions(1)
        .setStateModel(TaskConstants.STATE_MODEL_NAME).setDisableExternalView(true);
    IdealState is = IsBuilder.build();
    is.getRecord().setListField(workflow, new ArrayList<String>());
    is.getRecord().setMapField(workflow, new HashMap<String, String>());
    is.setRebalancerClassName(WorkflowRebalancer.class.getName());

    return is;
  }

  /**
   * Add new job config to cluster
   */
  private void addJobConfig(String jobName, JobConfig jobConfig) {
    LOG.info("Add job configuration " + jobName);

    // Set the job configuration
    JobConfig newJobCfg = new JobConfig(jobName, jobConfig);
    if (!TaskUtil.setResourceConfig(_accessor, jobName, newJobCfg)) {
      LOG.error("Failed to add job configuration for job " + jobName);
    }
  }

  /**
   * Public method to resume a workflow/queue.
   *
   * @param workflow
   */
  public void resume(String workflow) {
    setWorkflowTargetState(workflow, TargetState.START);
  }

  /**
   * Public async method to stop a workflow/queue.
   *
   * This call only send STOP command to Helix, it does not check
   * whether the workflow (all jobs) has been stopped yet.
   *
   * @param workflow
   */
  public void stop(String workflow) throws InterruptedException {
    setWorkflowTargetState(workflow, TargetState.STOP);
  }

  /**
   * Public sync method to stop a workflow/queue with timeout
   *
   * Basically the workflow and all of its jobs has been stopped if this method return success.
   *
   * @param workflow  The workflow name
   * @param timeout   The timeout for stopping workflow/queue in milisecond
   */
  public void waitToStop(String workflow, long timeout) throws InterruptedException {
    setWorkflowTargetState(workflow, TargetState.STOP);
    long endTime = System.currentTimeMillis() + timeout;

    while (System.currentTimeMillis() <= endTime) {
      WorkflowContext workflowContext = getWorkflowContext(workflow);

      if (workflowContext == null || !workflowContext.getWorkflowState()
          .equals(TaskState.STOPPED)) {
        Thread.sleep(1000);
      } else {
        // Successfully stopped
        return;
      }
    }

    // Failed to stop with timeout
    throw new HelixException(String
        .format("Fail to stop the workflow/queue %s with in %d milliseconds.", workflow, timeout));
  }

  /**
   * Public method to delete a workflow/queue.
   *
   * @param workflow
   */
  public void delete(String workflow) {
    // After set DELETE state, rebalancer may remove the workflow instantly.
    // So record context before set DELETE state.
    WorkflowContext wCtx = TaskUtil.getWorkflowContext(_propertyStore, workflow);

    setWorkflowTargetState(workflow, TargetState.DELETE);

    // Delete all finished scheduled workflows.
    if (wCtx != null && wCtx.getScheduledWorkflows() != null) {
      for (String scheduledWorkflow : wCtx.getScheduledWorkflows()) {
        WorkflowContext scheduledWorkflowCtx = TaskUtil.getWorkflowContext(_propertyStore, scheduledWorkflow);
        if (scheduledWorkflowCtx != null && scheduledWorkflowCtx.getFinishTime() != WorkflowContext.UNFINISHED) {
          setWorkflowTargetState(scheduledWorkflow, TargetState.DELETE);
        }
      }
    }
  }

  /**
   * Helper function to change target state for a given workflow
   */
  private void setWorkflowTargetState(String workflowName, TargetState state) {
    setSingleWorkflowTargetState(workflowName, state);

    // TODO: just need to change the lastScheduledWorkflow.
    List<String> resources = _accessor.getChildNames(_accessor.keyBuilder().resourceConfigs());
    for (String resource : resources) {
      if (resource.startsWith(workflowName)) {
        setSingleWorkflowTargetState(resource, state);
      }
    }
  }

  /**
   * Helper function to change target state for a given workflow
   */
  private void setSingleWorkflowTargetState(String workflowName, final TargetState state) {
    LOG.info("Set " + workflowName + " to target state " + state);
    DataUpdater<ZNRecord> updater = new DataUpdater<ZNRecord>() {
      @Override public ZNRecord update(ZNRecord currentData) {
        if (currentData != null) {
          // Only update target state for non-completed workflows
          String finishTime = currentData
              .getSimpleField(WorkflowContext.WorkflowContextProperties.FINISH_TIME.name());
          if (finishTime == null || finishTime.equals(WorkflowContext.UNFINISHED)) {
            currentData.setSimpleField(WorkflowConfig.WorkflowConfigProperty.TargetState.name(),
                state.name());
          } else {
            LOG.info("TargetState DataUpdater: ignore to update target state " + finishTime);
          }
        } else {
          LOG.error("TargetState DataUpdater: Fails to update target state " + currentData);
        }
        return currentData;
      }
    };
    List<DataUpdater<ZNRecord>> updaters = Lists.newArrayList();
    List<String> paths = Lists.newArrayList();

    PropertyKey cfgKey = TaskUtil.getWorkflowConfigKey(_accessor, workflowName);
    if (_accessor.getProperty(cfgKey) != null) {
      paths.add(_accessor.keyBuilder().resourceConfig(workflowName).getPath());
      updaters.add(updater);
      _accessor.updateChildren(paths, updaters, AccessOption.PERSISTENT);
      RebalanceScheduler.invokeRebalance(_accessor, workflowName);
    } else {
      LOG.error("Configuration path " + cfgKey + " not found!");
    }
  }

  public WorkflowConfig getWorkflowConfig(String workflow) {
    return TaskUtil.getWorkflowCfg(_accessor, workflow);
  }

  public WorkflowContext getWorkflowContext(String workflow) {
    return TaskUtil.getWorkflowContext(_propertyStore, workflow);
  }

  public JobConfig getJobConfig(String job) {
    return TaskUtil.getJobCfg(_accessor, job);
  }

  public JobContext getJobContext(String job) {
    return TaskUtil.getJobContext(_propertyStore, job);
  }

  public static JobContext getJobContext(HelixManager manager, String job) {
    return TaskUtil.getJobContext(manager, job);
  }

  public static WorkflowConfig getWorkflowConfig(HelixManager manager, String workflow) {
    return TaskUtil.getWorkflowCfg(manager, workflow);
  }

  public static WorkflowContext getWorkflowContext(HelixManager manager, String workflow) {
    return TaskUtil.getWorkflowContext(manager, workflow);
  }

  public static JobConfig getJobConfig(HelixManager manager, String job) {
    return TaskUtil.getJobCfg(manager, job);
  }

  /**
   * Batch get the configurations of all workflows in this cluster.
   *
   * @return
   */
  public Map<String, WorkflowConfig> getWorkflows() {
    Map<String, WorkflowConfig> workflowConfigMap = new HashMap<String, WorkflowConfig>();
    Map<String, ResourceConfig> resourceConfigMap =
        _accessor.getChildValuesMap(_accessor.keyBuilder().resourceConfigs());

    for (Map.Entry<String, ResourceConfig> resource : resourceConfigMap.entrySet()) {
      try {
        WorkflowConfig config = WorkflowConfig.fromHelixProperty(resource.getValue());
        workflowConfigMap.put(resource.getKey(), config);
      } catch (IllegalArgumentException ex) {
        // ignore if it is not a workflow config.
      }
    }

    return workflowConfigMap;
  }

  /**
   * This call will be blocked until either workflow reaches to one of the state specified
   * in the arguments, or timeout happens. If timeout happens, then it will throw a HelixException
   * Otherwise, it will return current workflow state
   *
   * @param workflowName The workflow to be monitored
   * @param timeout A long integer presents the time out, in milliseconds
   * @param targetStates Specified states that user would like to stop monitoring
   * @return A TaskState, which is current workflow state
   * @throws InterruptedException
   */
  public TaskState pollForWorkflowState(String workflowName, long timeout,
      TaskState... targetStates) throws InterruptedException {
    // Wait for completion.
    long st = System.currentTimeMillis();
    WorkflowContext ctx;
    Set<TaskState> allowedStates = new HashSet<TaskState>(Arrays.asList(targetStates));

    long timeToSleep = timeout > 100L ? 100L : timeout;
    do {
      Thread.sleep(timeToSleep);
      ctx = getWorkflowContext(workflowName);
    } while ((ctx == null || ctx.getWorkflowState() == null || !allowedStates
        .contains(ctx.getWorkflowState())) && System.currentTimeMillis() < st + timeout);

    if (ctx == null || !allowedStates.contains(ctx.getWorkflowState())) {
      throw new HelixException(String
          .format("Workflow \"%s\" context is empty or not in states: \"%s\"", workflowName,
              targetStates));
    }

    return ctx.getWorkflowState();
  }

  /**
   * This is a wrapper function that set default time out for monitoring workflow in 2 MINUTES.
   * If timeout happens, then it will throw a HelixException, Otherwise, it will return
   * current job state.
   *
   * @param workflowName The workflow to be monitored
   * @param targetStates Specified states that user would like to stop monitoring
   * @return A TaskState, which is current workflow state
   * @throws InterruptedException
   */
  public TaskState pollForWorkflowState(String workflowName, TaskState... targetStates)
      throws InterruptedException {
    return pollForWorkflowState(workflowName, _defaultTimeout, targetStates);
  }

  /**
   * This call will be blocked until either specified job reaches to one of the state
   * in the arguments, or timeout happens. If timeout happens, then it will throw a HelixException
   * Otherwise, it will return current job state
   *
   * @param workflowName The workflow that contains the job to monitor
   * @param jobName The specified job to monitor
   * @param timeout A long integer presents the time out, in milliseconds
   * @param states Specified states that user would like to stop monitoring
   * @return A TaskState, which is current job state
   * @throws Exception
   */
  public TaskState pollForJobState(String workflowName, String jobName, long timeout,
      TaskState... states) throws InterruptedException {
    // Get workflow config
    WorkflowConfig workflowConfig = getWorkflowConfig(workflowName);

    if (workflowConfig == null) {
      throw new HelixException(String.format("Workflow \"%s\" does not exists!", workflowName));
    }

    long timeToSleep = timeout > 100L ? 100L : timeout;

    WorkflowContext ctx;
    if (workflowConfig.isRecurring()) {
      // if it's recurring, need to reconstruct workflow and job name
      do {
        Thread.sleep(timeToSleep);
        ctx = getWorkflowContext(workflowName);
      } while ((ctx == null || ctx.getLastScheduledSingleWorkflow() == null));

      jobName = jobName.substring(workflowName.length() + 1);
      workflowName = ctx.getLastScheduledSingleWorkflow();
      jobName = TaskUtil.getNamespacedJobName(workflowName, jobName);
    }

    Set<TaskState> allowedStates = new HashSet<TaskState>(Arrays.asList(states));
    // Wait for state
    long st = System.currentTimeMillis();
    do {
      Thread.sleep(timeToSleep);
      ctx = getWorkflowContext(workflowName);
    } while ((ctx == null || ctx.getJobState(jobName) == null || !allowedStates
        .contains(ctx.getJobState(jobName))) && System.currentTimeMillis() < st + timeout);

    if (ctx == null || !allowedStates.contains(ctx.getJobState(jobName))) {
      throw new HelixException(
          String.format("Job \"%s\" context is null or not in states: \"%s\"", jobName, states));
    }

    return ctx.getJobState(jobName);
  }

  /**
   * This is a wrapper function for monitoring job state with default timeout 2 MINUTES.
   * If timeout happens, then it will throw a HelixException, Otherwise, it will return
   * current job state
   *
   * @param workflowName The workflow that contains the job to monitor
   * @param jobName The specified job to monitor
   * @param states Specified states that user would like to stop monitoring
   * @return A TaskState, which is current job state
   * @throws Exception
   */
  public TaskState pollForJobState(String workflowName, String jobName, TaskState... states)
      throws InterruptedException {
    return pollForJobState(workflowName, jobName, _defaultTimeout, states);
  }
}
