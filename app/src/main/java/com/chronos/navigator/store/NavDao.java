package com.chronos.navigator.store;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.chronos.navigator.store.NavEntities.*;

import java.util.List;

/** Blocking, like Chronos's own DAOs: the store runs on the IO executor. */
@Dao
public interface NavDao {

    // ------------------------------------------------------------------ graph

    @Query("SELECT * FROM nav_graph WHERE id = 1") GraphRow graph();
    @Query("SELECT * FROM nav_nodes ORDER BY position") List<NodeRow> nodes();
    @Query("SELECT * FROM nav_edges") List<EdgeRow> edges();
    @Query("SELECT * FROM nav_regimes ORDER BY position") List<RegimeRow> regimes();

    @Insert(onConflict = OnConflictStrategy.REPLACE) void putGraph(GraphRow row);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putNodes(List<NodeRow> rows);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putNode(NodeRow row);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putEdges(List<EdgeRow> rows);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putRegimes(List<RegimeRow> rows);
    @Query("DELETE FROM nav_nodes") void clearNodes();
    @Query("DELETE FROM nav_edges") void clearEdges();
    @Query("DELETE FROM nav_regimes") void clearRegimes();

    /** A new file replaces the structure whole, in one transaction: never half of each. */
    @Transaction
    default void replaceGraph(GraphRow graph, List<NodeRow> nodes, List<EdgeRow> edges,
                              List<RegimeRow> regimes, List<ParamRow> params) {
        putGraph(graph);
        clearNodes(); clearEdges(); clearRegimes();
        putNodes(nodes); putEdges(edges); putRegimes(regimes);
        putParams(params);
    }

    @Transaction
    default void replaceNodes(List<NodeRow> nodes, List<EdgeRow> edges) {
        clearNodes(); clearEdges();
        putNodes(nodes); putEdges(edges);
    }

    // ----------------------------------------------------------------- params

    @Query("SELECT * FROM nav_params ORDER BY `key`") List<ParamRow> params();
    @Query("SELECT * FROM nav_params WHERE `key` = :key") ParamRow param(String key);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putParams(List<ParamRow> rows);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putParam(ParamRow row);
    @Query("DELETE FROM nav_params WHERE `key` = :key") void dropParam(String key);
    @Query("DELETE FROM nav_params WHERE `key` LIKE :prefix || '%'") void dropParamsLike(String prefix);
    @Query("SELECT * FROM nav_params WHERE `key` LIKE :prefix || '%'") List<ParamRow> paramsLike(String prefix);

    // ------------------------------------------------------------------ links

    @Query("SELECT * FROM nav_links") List<LinkRow> links();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putLink(LinkRow row);
    @Query("DELETE FROM nav_links WHERE linkKey = :key") void dropLink(String key);

    // -------------------------------------------------------------- day types

    @Query("SELECT * FROM nav_day_types ORDER BY date") List<DayTypeRow> dayTypes();
    @Query("SELECT * FROM nav_day_types WHERE date = :iso") DayTypeRow dayType(String iso);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putDayType(DayTypeRow row);
    @Query("DELETE FROM nav_day_types WHERE date = :iso") void dropDayType(String iso);

    // --------------------------------------------------------------- forecast

    @Insert long putForecast(ForecastRow row);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putForecastNodes(List<ForecastNodeRow> rows);
    @Query("SELECT * FROM nav_forecasts ORDER BY runAt DESC LIMIT 1") ForecastRow latestForecast();
    /** The newest forecast made at or before a moment: what the trend is measured against. */
    @Query("SELECT * FROM nav_forecasts WHERE runAt <= :beforeMs ORDER BY runAt DESC LIMIT 1") ForecastRow forecastBefore(long beforeMs);
    @Query("DELETE FROM nav_forecast_nodes WHERE forecastId IN (SELECT id FROM nav_forecasts WHERE runAt < :beforeMs)")
    void pruneForecastNodes(long beforeMs);
    @Query("DELETE FROM nav_forecasts WHERE runAt < :beforeMs") void pruneForecasts(long beforeMs);
    @Query("SELECT * FROM nav_forecasts WHERE runAt >= :sinceMs ORDER BY runAt") List<ForecastRow> forecastsSince(long sinceMs);
    @Query("SELECT * FROM nav_forecast_nodes WHERE forecastId = :id") List<ForecastNodeRow> forecastNodes(long id);
    @Query("SELECT * FROM nav_forecast_nodes WHERE nodeId = :nodeId AND forecastId IN "
         + "(SELECT id FROM nav_forecasts WHERE runAt >= :sinceMs) ORDER BY forecastId")
    List<ForecastNodeRow> nodeHistory(String nodeId, long sinceMs);

    // ------------------------------------------------------------- allocation

    @Query("SELECT * FROM nav_allocations WHERE week = :week") List<AllocationRow> allocation(String week);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putAllocation(List<AllocationRow> rows);
    @Query("DELETE FROM nav_allocations WHERE week = :week") void dropAllocation(String week);
    @Query("SELECT * FROM nav_allocations WHERE week = (SELECT MAX(week) FROM nav_allocations)") List<AllocationRow> latestAllocation();

    // -------------------------------------------------------------- triggers

    @Query("SELECT * FROM nav_triggers") List<TriggerRow> triggers();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putTrigger(TriggerRow row);

    // ------------------------------------------------------------- proposals

    @Query("SELECT * FROM nav_proposals ORDER BY createdAt DESC") List<ProposalRow> proposals();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putProposal(ProposalRow row);
    @Query("SELECT * FROM nav_proposals WHERE id = :id") ProposalRow proposal(String id);

    @Query("SELECT * FROM nav_llm_calls ORDER BY createdAt DESC") List<LlmCallRow> llmCalls();
    @Query("SELECT * FROM nav_llm_calls WHERE id = :id") LlmCallRow llmCall(String id);
    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM nav_llm_calls WHERE createdAt >= :sinceMs") double costSince(long sinceMs);
    /** Answered calls of one provider; the trigger column ends in "@" + the provider's id. */
    @Query("SELECT COUNT(*) FROM nav_llm_calls WHERE createdAt >= :sinceMs AND status = 'DONE' "
         + "AND `trigger` LIKE '%@' || :profileId")
    int answeredSince(long sinceMs, String profileId);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void putLlmCall(LlmCallRow row);
    @Query("SELECT * FROM nav_llm_calls WHERE status = 'QUEUED' ORDER BY createdAt") List<LlmCallRow> queuedCalls();
}
