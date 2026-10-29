package com.chronos.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.chronos.data.dao.Daos;
import com.chronos.data.entity.Entities;
import com.chronos.navigator.store.NavDao;
import com.chronos.navigator.store.NavEntities;

/**
 * One offline, single-user database. No converters are registered because the
 * entities store only primitives and strings; the nested shapes are encoded by
 * {@link Codec} on the way in, which keeps the schema legible in a database
 * browser and keeps migrations boring.
 */
@Database(
    entities = {
        Entities.ParamsRow.class,
        Entities.GoalRow.class,
        Entities.ActivityRow.class,
        Entities.CommitmentRow.class,
        Entities.ComparisonRow.class,
        Entities.LogRow.class,
        Entities.SnapshotRow.class,
        Entities.DayPlanRow.class,
        Entities.AppStateRow.class,
        // Navigator (goal graph and its layers); Chronos's tables are only read by it.
        NavEntities.GraphRow.class,
        NavEntities.NodeRow.class,
        NavEntities.EdgeRow.class,
        NavEntities.RegimeRow.class,
        NavEntities.ParamRow.class,
        NavEntities.LinkRow.class,
        NavEntities.DayTypeRow.class,
        NavEntities.ForecastRow.class,
        NavEntities.ForecastNodeRow.class,
        NavEntities.AllocationRow.class,
        NavEntities.TriggerRow.class,
        NavEntities.ProposalRow.class,
        NavEntities.LlmCallRow.class
    },
    version = 17,
    exportSchema = true
)
public abstract class ChronosDb extends RoomDatabase {
    public abstract Daos.ConfigDao config();
    public abstract Daos.LogDao logs();
    public abstract Daos.PlanDao plans();
    public abstract Daos.StateDao state();
    public abstract NavDao nav();

    public static final String NAME = "chronos.db";
}
