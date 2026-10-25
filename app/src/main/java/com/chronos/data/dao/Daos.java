package com.chronos.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.chronos.data.entity.Entities.*;

import java.util.List;

/**
 * All queries are blocking. The repository owns the executor; making the DAO
 * asynchronous as well would put two threading models on top of each other.
 */
public final class Daos {
    private Daos() {}

    @Dao
    public interface ConfigDao {
        @Query("SELECT * FROM params WHERE id = 1") ParamsRow params();
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putParams(ParamsRow row);

        @Query("SELECT * FROM goals ORDER BY position") List<GoalRow> goals();
        @Query("SELECT * FROM activities ORDER BY position") List<ActivityRow> activities();
        @Query("SELECT * FROM commitments ORDER BY position") List<CommitmentRow> commitments();

        @Insert(onConflict = OnConflictStrategy.REPLACE) void putGoals(List<GoalRow> rows);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putActivities(List<ActivityRow> rows);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putCommitments(List<CommitmentRow> rows);

        @Query("DELETE FROM goals") void clearGoals();
        @Query("DELETE FROM activities") void clearActivities();
        @Query("DELETE FROM commitments") void clearCommitments();

        @Query("SELECT * FROM comparisons") List<ComparisonRow> comparisons();
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putComparison(ComparisonRow row);
        @Query("DELETE FROM comparisons WHERE pairKey LIKE :pattern") void dropComparisons(String pattern);

        /**
         * Replacing the whole configuration in one transaction. A half-written
         * configuration would be read by Readiness as a set of missing answers
         * and would lock the solver for no reason.
         */
        @Transaction
        default void replaceAll(ParamsRow params, List<GoalRow> goals,
                                List<ActivityRow> activities, List<CommitmentRow> commitments) {
            putParams(params);
            clearGoals(); clearActivities(); clearCommitments();
            putGoals(goals); putActivities(activities); putCommitments(commitments);
        }
    }

    @Dao
    public interface LogDao {
        @Query("SELECT * FROM logs ORDER BY ts") List<LogRow> all();
        @Query("SELECT * FROM logs WHERE date = :isoDate ORDER BY at") List<LogRow> onDate(String isoDate);
        @Query("SELECT * FROM logs WHERE date >= :fromIso ORDER BY ts") List<LogRow> since(String fromIso);
        @Query("SELECT COUNT(*) FROM logs WHERE kind = 'CHECKIN'") int checkinCount();
        @Insert(onConflict = OnConflictStrategy.REPLACE) void put(LogRow row);
        @Query("SELECT * FROM logs WHERE id = :id") LogRow byId(String id);
        @Query("UPDATE logs SET countedUnits = :units WHERE id = :id") void setCounted(String id, Double units);
        @Delete void remove(LogRow row);
        @Query("DELETE FROM logs WHERE id = :id") void removeById(String id);
    }

    @Dao
    public interface PlanDao {
        @Query("SELECT * FROM snapshots ORDER BY createdAt DESC LIMIT 1") SnapshotRow latest();
        @Insert(onConflict = OnConflictStrategy.REPLACE) long put(SnapshotRow row);
        @Query("DELETE FROM snapshots WHERE id NOT IN "
             + "(SELECT id FROM snapshots ORDER BY createdAt DESC LIMIT :keep)")
        void prune(int keep);

        @Query("SELECT * FROM day_plans WHERE date = :isoDate") DayPlanRow day(String isoDate);
        @Query("SELECT * FROM day_plans WHERE date BETWEEN :fromIso AND :toIso ORDER BY date")
        List<DayPlanRow> range(String fromIso, String toIso);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putDay(DayPlanRow row);
        @Query("DELETE FROM day_plans WHERE snapshotId != :snapshotId") void dropStaleDays(long snapshotId);
        /** Days that have begun keep the plan they were given; only the future is replaced. */
        @Query("DELETE FROM day_plans WHERE snapshotId != :snapshotId AND date >= :fromIso")
        void dropStaleDaysFrom(long snapshotId, String fromIso);
        @Query("DELETE FROM day_plans WHERE date < :isoDate") void dropBefore(String isoDate);
        @Query("UPDATE day_plans SET synced_to_calendar = 1 WHERE date = :isoDate") void markSynced(String isoDate);
    }

    @Dao
    public interface StateDao {
        @Query("SELECT * FROM app_state WHERE id = 1") AppStateRow get();
        @Insert(onConflict = OnConflictStrategy.REPLACE) void put(AppStateRow row);
    }
}
