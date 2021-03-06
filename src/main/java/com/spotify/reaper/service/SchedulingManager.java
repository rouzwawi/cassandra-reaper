package com.spotify.reaper.service;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import com.spotify.reaper.AppContext;
import com.spotify.reaper.ReaperException;
import com.spotify.reaper.core.Cluster;
import com.spotify.reaper.core.RepairRun;
import com.spotify.reaper.core.RepairSchedule;
import com.spotify.reaper.core.RepairUnit;
import com.spotify.reaper.resources.CommonTools;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.Nullable;

public class SchedulingManager extends TimerTask {

  private static final Logger LOG = LoggerFactory.getLogger(SchedulingManager.class);

  private static TimerTask schedulingManager;

  public static void start(AppContext context) {
    if (null == schedulingManager) {
      LOG.info("Starting new SchedulingManager instance");
      schedulingManager = new SchedulingManager(context);
      Timer timer = new Timer("SchedulingManagerTimer");
      timer.schedule(schedulingManager, 1000, 1000 * 60); // activate once per minute
    } else {
      LOG.warn("there is already one instance of SchedulingManager running, not starting new");
    }
  }

  public static void pauseRepairSchedule(AppContext context, RepairSchedule schedule) {
    RepairSchedule updatedSchedule = schedule.with()
        .state(RepairSchedule.State.PAUSED)
        .pauseTime(DateTime.now())
        .build(schedule.getId());
    if (!context.storage.updateRepairSchedule(updatedSchedule)) {
      throw new RuntimeException("failed updating repair schedule " + updatedSchedule.getId());
    }
  }

  public static void resumeRepairSchedule(AppContext context, RepairSchedule schedule) {
    RepairSchedule updatedSchedule = schedule.with()
        .state(RepairSchedule.State.RUNNING)
        .pauseTime(null)
        .build(schedule.getId());
    if (!context.storage.updateRepairSchedule(updatedSchedule)) {
      throw new RuntimeException("failed updating repair schedule " + updatedSchedule.getId());
    }
  }

  private AppContext context;

  /* nextActivatedSchedule used for nicer logging only */
  @Nullable
  private RepairSchedule nextActivatedSchedule;

  private SchedulingManager(AppContext context) {
    this.context = context;
  }

  /**
   * Called regularly, do not block!
   */
  @Override
  public void run() {
    LOG.debug("Checking for repair schedules...");
    long lastId = -1;
    try {
      Collection<RepairSchedule> schedules = context.storage.getAllRepairSchedules();
      boolean anyRunStarted = false;
      for (RepairSchedule schedule : schedules) {
        lastId = schedule.getId();
        boolean runStarted = manageSchedule(schedule);
        if (runStarted) {
          anyRunStarted = true;
        }
      }
      if (!anyRunStarted && nextActivatedSchedule != null) {
        LOG.debug("not scheduling new repairs yet, next activation is '{}' for schedule id '{}'",
                  nextActivatedSchedule.getNextActivation(), nextActivatedSchedule.getId());
      }
    } catch (Exception ex) {
      LOG.error("failed managing schedule for run with id: {}", lastId);
      LOG.error("catch exception", ex);
    }
  }

  /**
   * Manage, i.e. check whether a new repair run should be started with this schedule.
   * @param schedule The schedule to be checked for activation.
   * @return boolean indicating whether a new RepairRun instance was created and started.
   * @throws ReaperException
   */
  private boolean manageSchedule(RepairSchedule schedule) throws ReaperException {
    boolean startNewRun = false;
    if (schedule.getNextActivation().isBeforeNow()) {
      startNewRun = true;
      LOG.info("repair unit '{}' should be repaired based on RepairSchedule with id '{}'",
               schedule.getRepairUnitId(), schedule.getId());

      RepairUnit repairUnit = null;
      if (schedule.getState() == RepairSchedule.State.PAUSED) {
        LOG.info("Repair schedule '{}' is paused", schedule.getId());
        startNewRun = false;
      } else {
        repairUnit = context.storage.getRepairUnit(schedule.getRepairUnitId()).get();
        Collection<RepairRun> repairRuns = context.storage.getRepairRunsForUnit(repairUnit);
        for (RepairRun repairRun : repairRuns) {
          RepairRun.RunState state = repairRun.getRunState();
          if (state != RepairRun.RunState.DONE && state != RepairRun.RunState.NOT_STARTED) {
            LOG.info("there is repair (id {}) in state '{}' for repair unit '{}', "
                     + "postponing current schedule trigger until next scheduling",
                     repairRun.getId(), repairRun.getRunState(), repairUnit.getId());
            startNewRun = false;
          }
        }
      }

      if (startNewRun) {
        RepairRun startedRun = startNewRunForUnit(schedule, repairUnit);
        ImmutableList<Long> newRunHistory = new ImmutableList.Builder<Long>()
            .addAll(schedule.getRunHistory()).add(startedRun.getId()).build();
        context.storage.updateRepairSchedule(schedule.with()
                                                 .runHistory(newRunHistory)
                                                 .nextActivation(schedule.getFollowingActivation())
                                                 .build(schedule.getId()));
      } else {
        LOG.warn("skip scheduling, next activation for repair schedule '{}' will be: {}",
                 schedule.getId(), schedule.getFollowingActivation());
        context.storage.updateRepairSchedule(schedule.with()
                                                 .nextActivation(schedule.getFollowingActivation())
                                                 .build(schedule.getId()));
      }
    } else {
      if (nextActivatedSchedule == null) {
        nextActivatedSchedule = schedule;
      } else if (nextActivatedSchedule.getNextActivation().isAfter(schedule.getNextActivation())) {
        nextActivatedSchedule = schedule;
      }
    }
    return startNewRun;
  }

  private RepairRun startNewRunForUnit(RepairSchedule schedule, RepairUnit repairUnit)
      throws ReaperException {
    Cluster cluster = context.storage.getCluster(repairUnit.getClusterName()).get();
    RepairRun newRepairRun = CommonTools.registerRepairRun(
        context, cluster, repairUnit, Optional.of("scheduled run"),
        schedule.getOwner(), schedule.getSegmentCount(), schedule.getRepairParallelism(),
        schedule.getIntensity());
    context.repairManager.startRepairRun(context, newRepairRun);
    return newRepairRun;
  }

}
