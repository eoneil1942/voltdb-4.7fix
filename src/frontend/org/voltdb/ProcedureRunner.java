/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import org.apache.hadoop_voltpatches.util.PureJavaCrc32C;
import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.CoreUtils;
import org.voltdb.CatalogContext.ProcedurePartitionInfo;
import org.voltdb.VoltProcedure.VoltAbortException;
import org.voltdb.catalog.PlanFragment;
import org.voltdb.catalog.ProcParameter;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;
import org.voltdb.catalog.StmtParameter;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcedureInvocationType;
import org.voltdb.compiler.AdHocPlannedStatement;
import org.voltdb.compiler.AdHocPlannedStmtBatch;
import org.voltdb.compiler.Language;
import org.voltdb.compiler.ProcedureCompiler;
import org.voltdb.dtxn.DtxnConstants;
import org.voltdb.dtxn.TransactionState;
import org.voltdb.exceptions.EEException;
import org.voltdb.exceptions.SerializableException;
import org.voltdb.groovy.GroovyScriptProcedureDelegate;
import org.voltdb.iv2.UniqueIdGenerator;
import org.voltdb.messaging.FragmentTaskMessage;
import org.voltdb.planner.ActivePlanRepository;
import org.voltdb.sysprocs.AdHocBase;
import org.voltdb.types.TimestampType;
import org.voltdb.utils.Encoder;
import org.voltdb.utils.MiscUtils;

public class ProcedureRunner {

    private static final VoltLogger log = new VoltLogger("HOST");
    private static final boolean HOST_TRACE_ENABLED;
    static {
        HOST_TRACE_ENABLED = log.isTraceEnabled();
    }

    // SQL statement queue info
    //
    // This must be less than or equal to MAX_BATCH_COUNT in src/ee/execution/VoltDBEngine.h
    final static int MAX_BATCH_SIZE = 200;
    static class QueuedSQL {
        SQLStmt stmt;
        ParameterSet params;
        Expectation expectation = null;
        ByteBuffer serialization = null;
    }
    protected final ArrayList<QueuedSQL> m_batch = new ArrayList<QueuedSQL>(100);
    // cached fake SQLStmt array for single statement non-java procs
    QueuedSQL m_cachedSingleStmt = new QueuedSQL(); // never null
    boolean m_seenFinalBatch = false;

    // reflected info
    //
    protected final String m_procedureName;
    protected final VoltProcedure m_procedure;
    protected Method m_procMethod;
    protected Class<?>[] m_paramTypes;

    // per txn state (are reset after call)
    //
    protected TransactionState m_txnState; // used for sysprocs only
    protected byte m_statusCode = ClientResponse.SUCCESS;
    protected String m_statusString = null;
    // Status code that can be set by stored procedure upon invocation that will be returned with the response.
    protected byte m_appStatusCode = ClientResponse.UNINITIALIZED_APP_STATUS_CODE;
    protected String m_appStatusString = null;
    // cached txnid-seeded RNG so all calls to getSeededRandomNumberGenerator() for
    // a given call don't re-seed and generate the same number over and over
    private Random m_cachedRNG = null;

    // hooks into other parts of voltdb
    //
    protected final SiteProcedureConnection m_site;
    protected final SystemProcedureExecutionContext m_systemProcedureContext;
    protected final CatalogSpecificPlanner m_csp;

    // per procedure state and catalog info
    //
    protected ProcedureStatsCollector m_statsCollector;
    protected final Procedure m_catProc;
    protected final boolean m_isSysProc;
    protected final boolean m_isSinglePartition;
    protected final boolean m_hasJava;
    protected final boolean m_isReadOnly;
    protected final boolean m_isEverySite;
    protected final int m_partitionColumn;
    protected final VoltType m_partitionColumnType;
    protected final Language m_language;

    // dependency ids for ad hoc
    protected final static int AGG_DEPID = 1;

    // current hash of sql and params
    protected final PureJavaCrc32C m_inputCRC = new PureJavaCrc32C();

    // running procedure info
    //  - track the current call to voltExecuteSQL for logging progress
    protected int m_batchIndex;

    // Used to get around the "abstract" for StmtProcedures.
    // Path of least resistance?
    static class StmtProcedure extends VoltProcedure {
        public final SQLStmt sql = new SQLStmt("TBD");
    }

    private final static Language.Visitor<String, VoltProcedure> procedureNameRetriever =
            new Language.Visitor<String, VoltProcedure>() {
                @Override
                public String visitJava(VoltProcedure p) {
                    return p.getClass().getSimpleName();
                }
                @Override
                public String visitGroovy(VoltProcedure p) {
                    return ((GroovyScriptProcedureDelegate)p).getProcedureName();
                }
            };

    ProcedureRunner(VoltProcedure procedure,
                    SiteProcedureConnection site,
                    SystemProcedureExecutionContext sysprocContext,
                    Procedure catProc,
                    CatalogSpecificPlanner csp) {
        assert(m_inputCRC.getValue() == 0L);

        String language = catProc.getLanguage();

        if (language != null && !language.trim().isEmpty()) {
            m_language = Language.valueOf(language.trim().toUpperCase());
        } else if (procedure instanceof StmtProcedure){
            m_language = null;
        } else {
            m_language = Language.JAVA;
        }

        if (procedure instanceof StmtProcedure) {
            m_procedureName = catProc.getTypeName().intern();
        }
        else {
            m_procedureName = m_language.accept(procedureNameRetriever, procedure);
        }
        m_procedure = procedure;
        m_isSysProc = procedure instanceof VoltSystemProcedure;
        m_catProc = catProc;
        m_hasJava = catProc.getHasjava();
        m_isReadOnly = catProc.getReadonly();
        m_isSinglePartition = m_catProc.getSinglepartition();
        boolean isEverySite = false;
        if (isSystemProcedure()) {
            isEverySite = m_catProc.getEverysite();
        }
        m_isEverySite = isEverySite();
        if (m_isSinglePartition) {
            ProcedurePartitionInfo ppi = (ProcedurePartitionInfo)m_catProc.getAttachment();
            m_partitionColumn = ppi.index;
            m_partitionColumnType = ppi.type;
        } else {
            m_partitionColumn = 0;
            m_partitionColumnType = null;
        }
        m_site = site;
        m_systemProcedureContext = sysprocContext;
        m_csp = csp;

        m_procedure.init(this);

        m_statsCollector = new ProcedureStatsCollector(
                m_site.getCorrespondingSiteId(),
                m_site.getCorrespondingPartitionId(),
                m_catProc);
        VoltDB.instance().getStatsAgent().registerStatsSource(
                StatsSelector.PROCEDURE,
                site.getCorrespondingSiteId(),
                m_statsCollector);

        reflect();
    }

    public boolean isSystemProcedure() {
        return m_isSysProc;
    }

    public boolean isEverySite() {
        return m_isEverySite;
    }
    /**
     * Note this fails for Sysprocs that use it in non-coordinating fragment work. Don't.
     * @return The transaction id for determinism, not for ordering.
     */
    long getTransactionId() {
        StoredProcedureInvocation invocation = m_txnState.getInvocation();
        if (invocation != null && invocation.getType() == ProcedureInvocationType.REPLICATED) {
            return invocation.getOriginalTxnId();
        } else {
            return m_txnState.txnId;
        }
    }

    Random getSeededRandomNumberGenerator() {
        // this value is memoized here and reset at the beginning of call(...).
        if (m_cachedRNG == null) {
            m_cachedRNG = new Random(getUniqueId());
        }
        return m_cachedRNG;
    }

    @SuppressWarnings("finally")
    public ClientResponseImpl call(Object... paramListIn) {
        // verify per-txn state has been reset
        assert(m_statusCode == ClientResponse.SUCCESS);
        assert(m_statusString == null);
        assert(m_appStatusCode == ClientResponse.UNINITIALIZED_APP_STATUS_CODE);
        assert(m_appStatusString == null);
        assert(m_cachedRNG == null);

        // reset the hash of results
        m_inputCRC.reset();

        // reset batch context info
        m_batchIndex = -1;

        // set procedure name in the site/ee
        m_site.setProcedureName(m_procedureName);

        // use local var to avoid warnings about reassigning method argument
        Object[] paramList = paramListIn;

        ClientResponseImpl retval = null;
        // assert no sql is queued
        assert(m_batch.size() == 0);

        try {
            m_statsCollector.beginProcedure();

            VoltTable[] results = null;

            // inject sysproc execution context as the first parameter.
            if (isSystemProcedure()) {
                final Object[] combinedParams = new Object[paramList.length + 1];
                combinedParams[0] = m_systemProcedureContext;
                for (int i=0; i < paramList.length; ++i) {
                    combinedParams[i+1] = paramList[i];
                }
                // swap the lists.
                paramList = combinedParams;
            }

            if (paramList.length != m_paramTypes.length) {
                m_statsCollector.endProcedure(false, true, null, null);
                String msg = "PROCEDURE " + m_procedureName + " EXPECTS " + String.valueOf(m_paramTypes.length) +
                    " PARAMS, BUT RECEIVED " + String.valueOf(paramList.length);
                m_statusCode = ClientResponse.GRACEFUL_FAILURE;
                return getErrorResponse(m_statusCode, msg, null);
            }

            for (int i = 0; i < m_paramTypes.length; i++) {
                try {
                    paramList[i] = ParameterConverter.tryToMakeCompatible(m_paramTypes[i], paramList[i]);
                    // check the result type in an assert
                    assert(ParameterConverter.verifyParameterConversion(paramList[i], m_paramTypes[i]));
                } catch (Exception e) {
                    m_statsCollector.endProcedure(false, true, null, null);
                    String msg = "PROCEDURE " + m_procedureName + " TYPE ERROR FOR PARAMETER " + i +
                            ": " + e.toString();
                    m_statusCode = ClientResponse.GRACEFUL_FAILURE;
                    return getErrorResponse(m_statusCode, msg, null);
                }
            }

            boolean error = false;
            boolean abort = false;
            // run a regular java class
            if (m_hasJava) {
                try {
                    if (m_language == Language.JAVA) {
                        if (HOST_TRACE_ENABLED) {
                            log.trace("invoking... procMethod=" + m_procMethod.getName() + ", class=" + m_procMethod.getDeclaringClass().getName());
                        }
                        try {
                            Object rawResult = m_procMethod.invoke(m_procedure, paramList);
                            results = getResultsFromRawResults(rawResult);
                        } catch (IllegalAccessException e) {
                            // If reflection fails, invoke the same error handling that other exceptions do
                            throw new InvocationTargetException(e);
                        }
                    }
                    else if (m_language == Language.GROOVY) {
                        if (HOST_TRACE_ENABLED) {
                            log.trace("invoking... groovy closure on class=" + getClass().getName());
                        }
                        GroovyScriptProcedureDelegate proc = (GroovyScriptProcedureDelegate)m_procedure;
                        Object rawResult = proc.invoke(paramList);
                        results = getResultsFromRawResults(rawResult);
                    }
                    log.trace("invoked");
                }
                catch (InvocationTargetException itex) {
                    //itex.printStackTrace();
                    Throwable ex = itex.getCause();
                    if (ex instanceof VoltAbortException &&
                            !(ex instanceof EEException)) {
                        abort = true;
                    } else {
                        error = true;
                    }
                    if (CoreUtils.isStoredProcThrowableFatalToServer(ex)) {
                        // If the stored procedure attempted to do something other than linklibraray or instantiate
                        // a missing object that results in an error, throw the error and let the server deal with
                        // the condition as best as it can (usually a crashLocalVoltDB).
                        try {
                            m_statsCollector.endProcedure(false, true, null, null);
                        }
                        finally {
                            // Ensure that ex is always re-thrown even if endProcedure throws an exception.
                            throw (Error)ex;
                        }
                    }
                    retval = getErrorResponse(ex);
                }
            }
            // single statement only work
            // (this could be made faster, but with less code re-use)
            else {
                assert(m_catProc.getStatements().size() == 1);
                try {
                    m_cachedSingleStmt.params = getCleanParams(m_cachedSingleStmt.stmt, paramList);
                    if (getHsqlBackendIfExists() != null) {
                        // HSQL handling
                        VoltTable table =
                            getHsqlBackendIfExists().runSQLWithSubstitutions(
                                m_cachedSingleStmt.stmt,
                                m_cachedSingleStmt.params,
                                m_cachedSingleStmt.stmt.statementParamJavaTypes);
                        results = new VoltTable[] { table };
                    }
                    else {
                        m_batch.add(m_cachedSingleStmt);
                        results = voltExecuteSQL(true);
                    }
                }
                catch (SerializableException ex) {
                    retval = getErrorResponse(ex);
                }
            }

            // Record statistics for procedure call.
            StoredProcedureInvocation invoc = (m_txnState != null ? m_txnState.getInvocation() : null);
            ParameterSet paramSet = (invoc != null ? invoc.getParams() : null);
            m_statsCollector.endProcedure(abort, error, results, paramSet);

            // don't leave empty handed
            if (results == null) {
                results = new VoltTable[0];
            }

            if (retval == null) {
                retval = new ClientResponseImpl(
                        m_statusCode,
                        m_appStatusCode,
                        m_appStatusString,
                        results,
                        m_statusString);
            }

            int hash = (int) m_inputCRC.getValue();
            if (ClientResponseImpl.isTransactionallySuccessful(retval.getStatus()) && (hash != 0)) {
                retval.setHash(hash);
            }
            if ((m_txnState != null) && // may be null for tests
                (m_txnState.getInvocation() != null) &&
                (m_txnState.getInvocation().getType() == ProcedureInvocationType.REPLICATED))
            {
                retval.convertResultsToHashForDeterminism();
            }
        }
        finally {
            // finally at the call(..) scope to ensure params can be
            // garbage collected and that the queue will be empty for
            // the next call
            m_batch.clear();

            // reset other per-txn state
            m_txnState = null;
            m_statusCode = ClientResponse.SUCCESS;
            m_statusString = null;
            m_appStatusCode = ClientResponse.UNINITIALIZED_APP_STATUS_CODE;
            m_appStatusString = null;
            m_cachedRNG = null;
            m_cachedSingleStmt.params = null;
            m_cachedSingleStmt.expectation = null;
            m_seenFinalBatch = false;

            m_site.setProcedureName(null);
        }

        return retval;
    }

    /**
     * Check if the txn hashes to this partition. If not, it should be restarted.
     * @param txnState
     * @return true if the txn hashes to the current partition, false otherwise
     */
    public boolean checkPartition(TransactionState txnState, TheHashinator hashinator) {
        if (m_isSinglePartition) {
            TheHashinator.HashinatorType hashinatorType = hashinator.getConfigurationType();
            if (hashinatorType == TheHashinator.HashinatorType.LEGACY) {
                // Legacy hashinator is not used for elastic, no need to check partitioning. In fact,
                // since SP sysprocs all pass partitioning parameters as bytes,
                // they will hash to different partitions using the legacy hashinator. So don't do it.
                return true;
            }

            StoredProcedureInvocation invocation = txnState.getInvocation();
            VoltType parameterType;
            Object parameterAtIndex;

            // check if AdHoc_RO_SP or AdHoc_RW_SP
            if (m_procedure instanceof AdHocBase) {
                // ClientInterface should pre-validate this param is valid
                parameterAtIndex = invocation.getParameterAtIndex(0);
                parameterType = VoltType.get((Byte) invocation.getParameterAtIndex(1));
            } else {
                parameterType = m_partitionColumnType;
                parameterAtIndex = invocation.getParameterAtIndex(m_partitionColumn);
            }

            // Note that @LoadSinglepartitionTable has problems if the parititoning param
            // uses integers as bytes and isn't padded to 8b or using the right byte order.
            // Since this is not exposed to users, we're ok for now. The right fix is to probably
            // accept the right partitioning type from the user, then rewrite the params internally
            // before we initiate the proc (like adhocs).

            try {
                int partition = hashinator.getHashedPartitionForParameter(parameterType, parameterAtIndex);
                if (partition == m_site.getCorrespondingPartitionId()) {
                    return true;
                } else {
                    // Wrong partition, should restart the txn
                    if (HOST_TRACE_ENABLED) {
                        log.trace("Txn " + txnState.getInvocation().getProcName() +
                                " will be restarted");
                    }
                }
            } catch (Exception e) {
                log.warn("Unable to check partitioning of transaction " + txnState.m_spHandle, e);
            }
            return false;
        } else {
            // For n-partition transactions, we need to rehash the partitioning values and check
            // if they still hash to the assigned partitions.
            //
            // Note that when n-partition transaction runs, it's run on the MPI site, so calling
            // m_site.getCorrespondingPartitionId() will return the MPI's partition ID. We need
            // another way of getting what partitions were assigned to this transaction.
            return true;
        }
    }

    public void setupTransaction(TransactionState txnState) {
        m_txnState = txnState;
    }

    public TransactionState getTxnState() {
        assert(m_isSysProc);
        return m_txnState;
    }

    /**
     * If returns non-null, then using hsql backend
     */
    public HsqlBackend getHsqlBackendIfExists() {
        return m_site.getHsqlBackendIfExists();
    }

    public void setAppStatusCode(byte statusCode) {
        m_appStatusCode = statusCode;
    }

    public void setAppStatusString(String statusString) {
        m_appStatusString = statusString;
    }

    /*
     * Extract the timestamp from the timestamp field we have been passing around
     * that is now a unique id with a timestamp encoded in the most significant bits ala
     * a pre-IV2 transaction id.
     */
    public Date getTransactionTime() {
        StoredProcedureInvocation invocation = m_txnState.getInvocation();
        if (invocation != null && invocation.getType() == ProcedureInvocationType.REPLICATED) {
            return new Date(UniqueIdGenerator.getTimestampFromUniqueId(invocation.getOriginalUniqueId()));
        } else {
            return new Date(UniqueIdGenerator.getTimestampFromUniqueId(m_txnState.uniqueId));
        }
    }

    /*
     * The timestamp field is no longer really a timestamp, it is a unique id that is time based
     * and is similar to a pre-IV2 transaction ID except the less significant bits are set
     * to allow matching partition counts with IV2. It's OK, still can do 512k txns/second per
     * partition so plenty of headroom.
     */
    public long getUniqueId() {
        StoredProcedureInvocation invocation = m_txnState.getInvocation();
        if (invocation != null && invocation.getType() == ProcedureInvocationType.REPLICATED) {
            return invocation.getOriginalUniqueId();
        } else {
            return m_txnState.uniqueId;
        }
    }

    private void updateCRC(QueuedSQL queuedSQL) {
        if (!queuedSQL.stmt.isReadOnly) {
            m_inputCRC.update(queuedSQL.stmt.sqlCRC);
            try {
                ByteBuffer buf = ByteBuffer.allocate(queuedSQL.params.getSerializedSize());
                queuedSQL.params.flattenToBuffer(buf);
                buf.flip();
                m_inputCRC.update(buf.array());
                queuedSQL.serialization = buf;
            } catch (IOException e) {
                log.error("Unable to compute CRC of parameters to " +
                        "a SQL statement in procedure: " + m_procedureName, e);
                // don't crash
                // presumably, this will fail deterministically at all replicas
                // just log the error and hope people report it
            }
        }
    }

    public void voltQueueSQL(final SQLStmt stmt, Expectation expectation, Object... args) {
        if (stmt == null) {
            throw new IllegalArgumentException("SQLStmt parameter to voltQueueSQL(..) was null.");
        }
        QueuedSQL queuedSQL = new QueuedSQL();
        queuedSQL.expectation = expectation;
        queuedSQL.params = getCleanParams(stmt, args);
        queuedSQL.stmt = stmt;

        updateCRC(queuedSQL);
        m_batch.add(queuedSQL);
    }

    public void voltQueueSQL(final SQLStmt stmt, Object... args) {
        voltQueueSQL(stmt, (Expectation) null, args);
    }

    public void voltQueueSQL(final String sql, Object... args) {
        if (sql == null || sql.isEmpty()) {
            throw new IllegalArgumentException("SQL statement '" + sql + "' is null or the empty string");
        }

        try {
            AdHocPlannedStmtBatch batch = m_csp.plan(sql, args,m_isSinglePartition).get();
            if (batch.errorMsg != null) {
                throw new VoltAbortException("Failed to plan sql '" + sql + "' error: " + batch.errorMsg);
            }

            if (m_isReadOnly && !batch.isReadOnly()) {
                throw new VoltAbortException("Attempted to queue DML adhoc sql '" + sql + "' from read only procedure");
            }

            assert(1 == batch.plannedStatements.size());

            QueuedSQL queuedSQL = new QueuedSQL();
            AdHocPlannedStatement plannedStatement = batch.plannedStatements.get(0);

            long aggFragId = ActivePlanRepository.loadOrAddRefPlanFragment(
                    plannedStatement.core.aggregatorHash, plannedStatement.core.aggregatorFragment);
            long collectorFragId = 0;
            if (plannedStatement.core.collectorFragment != null) {
                collectorFragId = ActivePlanRepository.loadOrAddRefPlanFragment(
                        plannedStatement.core.collectorHash, plannedStatement.core.collectorFragment);
            }

            queuedSQL.stmt = SQLStmtAdHocHelper.createWithPlan(
                    plannedStatement.sql,
                    aggFragId,
                    plannedStatement.core.aggregatorHash,
                    true,
                    collectorFragId,
                    plannedStatement.core.collectorHash,
                    true,
                    plannedStatement.core.isReplicatedTableDML,
                    plannedStatement.core.readOnly,
                    plannedStatement.core.parameterTypes,
                    m_site);
            Object[] argumentParams = args;
            // case handles if there were parameters OR
            // if there were no constants to pull out
            // In the current scheme, which does not support combining user-provided parameters
            // with planner-extracted parameters in the same statement, an original sql statement
            // containing parameters to match its provided arguments should bypass the parameterizer,
            // so there should be no extracted params.
            // The presence of extracted paremeters AND user-provided arguments can only arise when
            // the arguments have no user-specified parameters in the sql statement
            // to put these arguments to use -- these are invalid, so flag an error.
            // Even the special "partitioning argument" provided to @AdHocSpForTest with no
            // corresponding parameter in the sql should have been noted and discarded by the
            // dispatcher before reaching this ProcedureRunner. This opens the possibility of
            // supporting @AdHocSpForTest with queries that contain '?' parameters.
            if (plannedStatement.hasExtractedParams()) {
                if (args.length > 0) {
                    throw new VoltAbortException(
                            "Number of arguments provided was " + args.length  +
                            " where 0 were expected for statement: " + sql);
                }
                argumentParams = plannedStatement.extractedParamArray();
                if (argumentParams.length != queuedSQL.stmt.statementParamJavaTypes.length) {
                    String msg = String.format(
                            "The wrong number of arguments (" + argumentParams.length +
                            " vs. the " + queuedSQL.stmt.statementParamJavaTypes.length +
                            " expected) were passed for the parameterized statement: %s", sql);
                    throw new VoltAbortException(msg);
                }
            }
            queuedSQL.params = getCleanParams(queuedSQL.stmt, argumentParams);

            updateCRC(queuedSQL);
            m_batch.add(queuedSQL);
        }
        catch (Exception e) {
            if (e instanceof ExecutionException) {
                throw new VoltAbortException(e.getCause());
            }
            if (e instanceof VoltAbortException) {
                throw (VoltAbortException) e;
            }
            throw new VoltAbortException(e);
        }
    }

    public VoltTable[] voltExecuteSQL(boolean isFinalSQL) {
        try {
            if (m_seenFinalBatch) {
                throw new RuntimeException("Procedure " + m_procedureName +
                                           " attempted to execute a batch " +
                                           "after claiming a previous batch was final " +
                                           "and will be aborted.\n  Examine calls to " +
                                           "voltExecuteSQL() and verify that the call " +
                                           "with the argument value 'true' is actually " +
                                           "the final one");
            }
            m_seenFinalBatch = isFinalSQL;

            // memo-ize the original batch size here
            int batchSize = m_batch.size();
            // increment the number of voltExecuteSQL calls for this proc
            m_batchIndex++;
            m_site.setBatch(m_batchIndex);

            // if batch is small (or reasonable size), do it in one go
            if (batchSize <= MAX_BATCH_SIZE) {
                return executeQueriesInABatch(m_batch, isFinalSQL);
            }
            // otherwise, break it into sub-batches
            else {
                List<VoltTable[]> results = new ArrayList<VoltTable[]>();

                while (m_batch.size() > 0) {
                    int subSize = Math.min(MAX_BATCH_SIZE, m_batch.size());

                    // get the beginning of the batch (or all if small enough)
                    // note: this is a view into the larger list and changes to it
                    //  will mutate the larger m_batch.
                    List<QueuedSQL> subBatch = m_batch.subList(0, subSize);

                    // decide if this sub-batch should be marked final
                    boolean finalSubBatch = isFinalSQL && (subSize == m_batch.size());

                    // run the sub-batch and copy the sub-results into the list of lists of results
                    // note: executeQueriesInABatch removes items from the batch as it runs.
                    //  this means subBatch will be empty after running and since subBatch is a
                    //  view on the larger batch, it removes subBatch.size() elements from m_batch.
                    results.add(executeQueriesInABatch(subBatch, finalSubBatch));
                }

                // merge the list of lists into something returnable
                VoltTable[] retval = MiscUtils.concatAll(new VoltTable[0], results);
                assert(retval.length == batchSize);

                return retval;
            }
        }
        finally {
            m_batch.clear();
        }
    }

    protected VoltTable[] executeQueriesInABatch(List<QueuedSQL> batch, boolean isFinalSQL) {
        final int batchSize = batch.size();

        VoltTable[] results = null;

        if (batchSize == 0) {
            return new VoltTable[] {};
        }

        // IF THIS IS HSQL, RUN THE QUERIES DIRECTLY IN HSQL
        if (getHsqlBackendIfExists() != null) {
            results = new VoltTable[batchSize];
            int i = 0;
            for (QueuedSQL qs : batch) {
                results[i++] = getHsqlBackendIfExists().runSQLWithSubstitutions(
                        qs.stmt, qs.params, qs.stmt.statementParamJavaTypes);
            }
        }
        else if (m_isSinglePartition) {
            results = fastPath(batch);
        }
        else {
            results = slowPath(batch, isFinalSQL);
        }

        // check expectations
        int i = 0; for (QueuedSQL qs : batch) {
            Expectation.check(m_procedureName, qs.stmt,
                    i, qs.expectation, results[i]);
            i++;
        }

        // clear the queued sql list for the next call
        batch.clear();

        return results;
    }

    public byte[] voltLoadTable(String clusterName, String databaseName,
                              String tableName, VoltTable data, boolean returnUniqueViolations, boolean shouldDRStream)
    throws VoltAbortException
    {
        if (data == null || data.getRowCount() == 0) {
            return null;
        }
        try {
            return m_site.loadTable(m_txnState.txnId, m_txnState.m_spHandle,
                             clusterName, databaseName,
                             tableName, data, returnUniqueViolations, shouldDRStream, false);
        }
        catch (EEException e) {
            throw new VoltAbortException("Failed to load table: " + tableName);
        }
    }

    public DependencyPair executeSysProcPlanFragment(
            TransactionState txnState,
            Map<Integer, List<VoltTable>> dependencies, long fragmentId,
            ParameterSet params) {
        setupTransaction(txnState);
        assert (m_procedure instanceof VoltSystemProcedure);
        VoltSystemProcedure sysproc = (VoltSystemProcedure) m_procedure;
        return sysproc.executePlanFragment(dependencies, fragmentId, params, m_systemProcedureContext);
    }

    private final ParameterSet getCleanParams(SQLStmt stmt, Object... inArgs) {
        final int numParamTypes = stmt.statementParamJavaTypes.length;
        final byte stmtParamTypes[] = stmt.statementParamJavaTypes;
        final Object[] args = new Object[numParamTypes];
        if (inArgs.length != numParamTypes) {
            throw new VoltAbortException(
                    "Number of arguments provided was " + inArgs.length  +
                    " where " + numParamTypes + " was expected for statement " + stmt.getText());
        }
        for (int ii = 0; ii < numParamTypes; ii++) {
            // this handles non-null values
            if (inArgs[ii] != null) {
                args[ii] = inArgs[ii];
                continue;
            }
            // this handles null values
            VoltType type = VoltType.get(stmtParamTypes[ii]);
            if (type == VoltType.TINYINT) {
                args[ii] = Byte.MIN_VALUE;
            } else if (type == VoltType.SMALLINT) {
                args[ii] = Short.MIN_VALUE;
            } else if (type == VoltType.INTEGER) {
                args[ii] = Integer.MIN_VALUE;
            } else if (type == VoltType.BIGINT) {
                args[ii] = Long.MIN_VALUE;
            } else if (type == VoltType.FLOAT) {
                args[ii] = VoltType.NULL_FLOAT;
            } else if (type == VoltType.TIMESTAMP) {
                args[ii] = new TimestampType(Long.MIN_VALUE);
            } else if (type == VoltType.STRING) {
                args[ii] = VoltType.NULL_STRING_OR_VARBINARY;
            } else if (type == VoltType.VARBINARY) {
                args[ii] = VoltType.NULL_STRING_OR_VARBINARY;
            } else if (type == VoltType.DECIMAL) {
                args[ii] = VoltType.NULL_DECIMAL;
            } else {
                throw new VoltAbortException("Unknown type " + type +
                        " can not be converted to NULL representation for arg " + ii +
                        " for SQL stmt: " + stmt.getText());
            }
        }

        return ParameterSet.fromArrayNoCopy(args);
    }

    public void initSQLStmt(SQLStmt stmt, Statement catStmt) {

        int fragCount = catStmt.getFragments().size();

        for (PlanFragment frag : catStmt.getFragments()) {
            byte[] planHash = Encoder.hexDecode(frag.getPlanhash());
            byte[] plan = Encoder.decodeBase64AndDecompressToBytes(frag.getPlannodetree());
            long id = ActivePlanRepository.loadOrAddRefPlanFragment(planHash, plan);
            boolean transactional = frag.getNontransactional() == false;

            SQLStmt.Frag stmtFrag = new SQLStmt.Frag(id, planHash, transactional);

            if (fragCount == 1 || frag.getHasdependencies()) {
                stmt.aggregator = stmtFrag;
            }
            else {
                stmt.collector = stmtFrag;
            }
        }

        stmt.isReadOnly = catStmt.getReadonly();
        stmt.isReplicatedTableDML = catStmt.getReplicatedtabledml();

        stmt.site = m_site;

        int numStatementParamJavaTypes = catStmt.getParameters().size();
        stmt.statementParamJavaTypes = new byte[numStatementParamJavaTypes];
        for (StmtParameter param : catStmt.getParameters()) {
            stmt.statementParamJavaTypes[param.getIndex()] = (byte)param.getJavatype();
            // ??? How does the SQLStmt successfully handle IN LIST queries without
            // caching the value of param.getIsarray() here?
            // Is the array-ness also reflected in the javatype? --paul
        }
    }


    protected void reflect() {
        // fill in the sql for single statement procs
        if (m_catProc.getHasjava() == false) {
            try {
                Map<String, SQLStmt> stmtMap = ProcedureCompiler.getValidSQLStmts(null, m_procedureName, m_procedure.getClass(), m_procedure, true);
                SQLStmt stmt = stmtMap.get(VoltDB.ANON_STMT_NAME);
                assert(stmt != null);
                Statement statement = m_catProc.getStatements().get(VoltDB.ANON_STMT_NAME);
                String s = statement.getSqltext();
                SQLStmtAdHocHelper.setSQLStr(stmt, s);
                m_cachedSingleStmt.stmt = stmt;

                int numParams = m_catProc.getParameters().size();
                m_paramTypes = new Class<?>[numParams];

                for (ProcParameter param : m_catProc.getParameters()) {
                    VoltType type = VoltType.get((byte) param.getType());
                    if (param.getIsarray()) {
                        m_paramTypes[param.getIndex()] = type.vectorClassFromType();
                        continue;
                    }
                    // Paul doesn't understand why single-statement procedures
                    // need to have their input parameter types widened here.
                    // Is it not better to catch too-wide values in the ProcedureRunner
                    // (ParameterConverter.tryToMakeCompatible) before falling through to the EE?
                    if (type == VoltType.INTEGER) {
                        type = VoltType.BIGINT;
                    } else if (type == VoltType.SMALLINT) {
                        type = VoltType.BIGINT;
                    } else if (type == VoltType.TINYINT) {
                        type = VoltType.BIGINT;
                    } else if (type == VoltType.NUMERIC) {
                        type = VoltType.FLOAT;
                    }

                    m_paramTypes[param.getIndex()] = type.classFromType();
                }
            } catch (Exception e) {
                // shouldn't throw anything outside of the compiler
                e.printStackTrace();
            }
        }
        else {
            // this is where, in the case of java procedures, m_method is set
            m_paramTypes = m_language.accept(parametersTypeRetriever, this);

            if (m_procMethod == null && m_language == Language.JAVA) {
                throw new RuntimeException("No \"run\" method found in: " + m_procedure.getClass().getName());
            }
        }

        // iterate through the fields and deal with sql statements
        Map<String, SQLStmt> stmtMap = null;
        if (m_catProc.getHasjava() == false) {
            try {
                stmtMap = ProcedureCompiler.getValidSQLStmts(null, m_procedureName, m_procedure.getClass(), m_procedure, true);
            } catch (Exception e1) {
                // shouldn't throw anything outside of the compiler
                e1.printStackTrace();
                return;
            }
        }
        else {
            stmtMap = m_language.accept(sqlStatementsRetriever, this);
        }

        for (final Entry<String, SQLStmt> entry : stmtMap.entrySet()) {
            String name = entry.getKey();
            Statement s = m_catProc.getStatements().get(name);
            if (s != null) {
                /*
                 * Cache all the information we need about the statements in this stored
                 * procedure locally instead of pulling them from the catalog on
                 * a regular basis.
                 */
                SQLStmt stmt = entry.getValue();

                // done in a static method in an abstract class so users don't call it
                initSQLStmt(stmt, s);
                //LOG.fine("Found statement " + name);
            }
        }
    }

    private final static Language.Visitor<Class<?>[], ProcedureRunner> parametersTypeRetriever =
            new Language.Visitor<Class<?>[], ProcedureRunner>() {
                @Override
                public Class<?>[] visitJava(ProcedureRunner p) {
                    Method[] methods = p.m_procedure.getClass().getDeclaredMethods();

                    for (final Method m : methods) {
                        String name = m.getName();
                        if (name.equals("run")) {
                            if (Modifier.isPublic(m.getModifiers()) == false) {
                                continue;
                            }
                            p.m_procMethod = m;
                            return m.getParameterTypes();
                        }
                    }
                    return null;
                }
                @Override
                public Class<?>[] visitGroovy(ProcedureRunner p) {
                    return ((GroovyScriptProcedureDelegate)p.m_procedure).getParameterTypes();
                }
            };

   private final static Language.Visitor<Map<String,SQLStmt>, ProcedureRunner> sqlStatementsRetriever =
           new Language.Visitor<Map<String,SQLStmt>, ProcedureRunner>() {
            @Override
            public Map<String, SQLStmt> visitJava(ProcedureRunner p) {
                Map<String, SQLStmt> stmtMap = null;
                try {
                    stmtMap = ProcedureCompiler.getValidSQLStmts(null, p.m_procedureName, p.m_procedure.getClass(), p.m_procedure, true);
                } catch (Exception e1) {
                    // shouldn't throw anything outside of the compiler
                    e1.printStackTrace();
                }
                return stmtMap;
            }
            @Override
            public Map<String, SQLStmt> visitGroovy(ProcedureRunner p) {
                return ((GroovyScriptProcedureDelegate)p.m_procedure).getStatementMap();
            }
        };

   /**
    * Test whether or not the given stack frame is within a procedure invocation
    * @param stel a stack trace element
    * @return true if it is, false it is not
    */
   protected boolean isProcedureStackTraceElement(StackTraceElement stel) {
       int lastPeriodPos = stel.getClassName().lastIndexOf('.');

       if (lastPeriodPos == -1) {
           lastPeriodPos = 0;
       } else {
           ++lastPeriodPos;
       }

       // Account for inner classes too. Inner classes names comprise of the parent
       // class path followed by a dollar sign
       String simpleName = stel.getClassName().substring(lastPeriodPos);
       return simpleName.equals(m_procedureName)
           || (simpleName.startsWith(m_procedureName) && simpleName.charAt(m_procedureName.length()) == '$');
   }

   /**
    *
    * @param e
    * @return A ClientResponse containing error information
    */
   protected ClientResponseImpl getErrorResponse(Throwable eIn) {
       // use local var to avoid warnings about reassigning method argument
       Throwable e = eIn;
       boolean expected_failure = true;
       StackTraceElement[] stack = e.getStackTrace();
       ArrayList<StackTraceElement> matches = new ArrayList<StackTraceElement>();
       for (StackTraceElement ste : stack) {
           if (isProcedureStackTraceElement(ste)) {
               matches.add(ste);
           }
       }

       byte status = ClientResponse.UNEXPECTED_FAILURE;
       StringBuilder msg = new StringBuilder();

       if (e.getClass() == VoltAbortException.class) {
           status = ClientResponse.USER_ABORT;
           msg.append("USER ABORT\n");
       }
       else if (e.getClass() == org.voltdb.exceptions.ConstraintFailureException.class) {
           status = ClientResponse.GRACEFUL_FAILURE;
           msg.append("CONSTRAINT VIOLATION\n");
       }
       else if (e.getClass() == org.voltdb.exceptions.SQLException.class) {
           status = ClientResponse.GRACEFUL_FAILURE;
           msg.append("SQL ERROR\n");
       }
       // Interrupt exception will be thrown when the procedure is killed by a user
       // or by a timeout in the middle of executing.
       else if (e.getClass() == org.voltdb.exceptions.InterruptException.class) {
           status = ClientResponse.GRACEFUL_FAILURE;
           msg.append("Transaction Interrupted\n");
       }
       else if (e.getClass() == org.voltdb.ExpectedProcedureException.class) {
           msg.append("HSQL-BACKEND ERROR\n");
           if (e.getCause() != null) {
               e = e.getCause();
           }
       }
       else if (e.getClass() == org.voltdb.exceptions.TransactionRestartException.class) {
           status = ClientResponse.TXN_RESTART;
           msg.append("TRANSACTION RESTART\n");
       }
       else {
           msg.append("UNEXPECTED FAILURE:\n");
           expected_failure = false;
       }

       // If the error is something we know can happen as part of normal operation,
       // reduce the verbosity.
       // Otherwise, generate more output for debuggability
       if (expected_failure) {
           msg.append("  ").append(e.getMessage());
           for (StackTraceElement ste : matches) {
               msg.append("\n    at ");
               msg.append(ste.getClassName()).append(".").append(ste.getMethodName());
               msg.append("(").append(ste.getFileName()).append(":");
               msg.append(ste.getLineNumber()).append(")");
           }
       }
       else {
           Writer result = new StringWriter();
           PrintWriter pw = new PrintWriter(result);
           e.printStackTrace(pw);
           msg.append("  ").append(result.toString());
       }

       return getErrorResponse(
               status, msg.toString(),
               e instanceof SerializableException ? (SerializableException)e : null);
   }

   protected ClientResponseImpl getErrorResponse(byte status, String msg, SerializableException e) {
       return new ClientResponseImpl(
               status,
               m_appStatusCode,
               m_appStatusString,
               new VoltTable[0],
               "VOLTDB ERROR: " + msg);
   }

   /**
    * Given the results of a procedure, convert it into a sensible array of VoltTables.
    * @throws InvocationTargetException
    */
   final private VoltTable[] getResultsFromRawResults(Object result) throws InvocationTargetException {
       if (result == null) {
           return new VoltTable[0];
       }
       if (result instanceof VoltTable[]) {
           VoltTable[] retval = (VoltTable[]) result;
           for (VoltTable table : retval) {
            if (table == null) {
                   Exception e = new RuntimeException("VoltTable arrays with non-zero length cannot contain null values.");
                   throw new InvocationTargetException(e);
               }
        }

           return retval;
       }
       if (result instanceof VoltTable) {
           return new VoltTable[] { (VoltTable) result };
       }
       if (result instanceof Long) {
           VoltTable t = new VoltTable(new VoltTable.ColumnInfo("", VoltType.BIGINT));
           t.addRow(result);
           return new VoltTable[] { t };
       }
       throw new RuntimeException("Procedure didn't return acceptable type.");
   }

   /**
    * Used by executeSlowHomogeneousBatch() to build messages and keep track
    * of other information as the batch is processed.
    */
   private static class BatchState {

       // needed to size arrays and check index arguments
       final int m_batchSize;

       // needed to get various IDs
       private final TransactionState m_txnState;

       // the set of dependency ids for the expected results of the batch
       // one per sql statment
       final int[] m_depsToResume;

       // these dependencies need to be received before the local stuff can run
       // Since replicated reads are not handled in the local task, 
       // this list has variable length, so assemble it first in an ArrayList
       // and later convert it to the array
       private List<Integer> m_depsForLocalTaskList = new ArrayList<Integer>();
       int[] m_depsForLocalTask = null;

       // check if all local fragment work is non-transactional
       boolean m_localFragsAreNonTransactional = false;

       // the data and message for locally processed fragments
       final FragmentTaskMessage m_localTask;
              
       // the data and message for all sites in the transaction
       final FragmentTaskMessage m_distributedTask;
       // for each of m_distributedTask's fragments: which ones are replicated reads
       boolean[] m_isReplicatedRead = null; 

       // holds query results
       final VoltTable[] m_results;

       BatchState(int batchSize, TransactionState txnState, long siteId, boolean finalTask, String procedureName) {
           m_batchSize = batchSize;
           m_txnState = txnState;

           m_depsToResume = new int[batchSize];
           m_results = new VoltTable[batchSize];

           // the data and message for locally processed fragments
           m_localTask = new FragmentTaskMessage(m_txnState.initiatorHSId,
                                                 siteId,
                                                 m_txnState.txnId,
                                                 m_txnState.uniqueId,
                                                 m_txnState.isReadOnly(),
                                                 false,
                                                 txnState.isForReplay());
           m_localTask.setProcedureName(procedureName);

           // the data and message for all sites in the transaction
           // except that the replicated reads are done on only one site
           m_distributedTask = new FragmentTaskMessage(m_txnState.initiatorHSId,
                                                       siteId,
                                                       m_txnState.txnId,
                                                       m_txnState.uniqueId,
                                                       m_txnState.isReadOnly(),
                                                       finalTask,
                                                       txnState.isForReplay());
           m_distributedTask.setProcedureName(procedureName);
           // which fragments of m_distributedTask are replicated reads
           m_isReplicatedRead = new boolean[batchSize];
       }

       /*
        * Replicated fragment.
        */
       void addStatement(int index, SQLStmt stmt, ByteBuffer params, SiteProcedureConnection site) {
           assert(index >= 0);
           assert(index < m_batchSize);
           assert(stmt != null);

           // if any frag is transactional, update this check
           if (stmt.aggregator.transactional == true) {
               m_localFragsAreNonTransactional = false;
           }

           // single replicated read fragment
           if (stmt.collector == null) {
               // Replicated read: will be done only on one site in distributed work phase
        	   //   in its proper place with respect to other reads and writes
        	   //   Arrange to deliver its results directly to the stored procedure
        	   m_isReplicatedRead[index] = true;  // mark position in m_distributedTask as replicated read
               if (stmt.inCatalog) {
                   m_distributedTask.addFragment(stmt.aggregator.planHash, m_depsToResume[index], params);
               }
               else {
                   byte[] planBytes = ActivePlanRepository.planForFragmentId(stmt.aggregator.id);
                   m_distributedTask.addCustomFragment(stmt.aggregator.planHash, m_depsToResume[index], params, planBytes);
               }
              
           }
           // two fragments
           else {
               int outputDepId =
                       m_txnState.getNextDependencyId() | DtxnConstants.MULTIPARTITION_DEPENDENCY;
               m_depsForLocalTaskList.add(outputDepId);
               m_isReplicatedRead[index] = false;  // mark position as not replicated read
               // Add fragment to local and distributed fragments.
               if (stmt.inCatalog) {
                   m_localTask.addFragment(stmt.aggregator.planHash, m_depsToResume[index], params);
                   // add distributed fragment to work for all sites, special or not
                   m_distributedTask.addFragment(stmt.collector.planHash, outputDepId, params);
               }
               else {
                   byte[] planBytes = ActivePlanRepository.planForFragmentId(stmt.aggregator.id);
                   m_localTask.addCustomFragment(stmt.aggregator.planHash, m_depsToResume[index], params, planBytes);
                   planBytes = ActivePlanRepository.planForFragmentId(stmt.collector.id);
                   m_distributedTask.addCustomFragment(stmt.collector.planHash, outputDepId, params, planBytes);
               }
           }
           if (index == m_batchSize - 1) {
	    	   // end of adding statements, now convert list to array
	    	   m_depsForLocalTask = new int[m_depsForLocalTaskList.size()];
	    	   for (int i=0; i< m_depsForLocalTaskList.size(); i++)
	    		   m_depsForLocalTask[i] = m_depsForLocalTaskList.get(i); 
	       }
       }
   }
   
   /*
    * Execute a batch of queries, possibly a mix of reads and writes.
    * Execution of reads vs. writes in the correct order is ensured by
    * executing the original sequence on one site, the buddy site.
    * The rest of the sites execute a batch with the replicated read
    * fragments deleted. The buddy site has two special fragment batches
    * to execute, first the original read/write sequence and then the
    * "localTask" data-accumulation work.
    * This is a faster approach than that of ENG-1232, which chopped
    * up the full batch into smaller batches of homogeneous reads or writes.
    */
   VoltTable[] slowPath(final List<QueuedSQL> batch, final boolean finalTask) {

       BatchState state = new BatchState(batch.size(),
                                         m_txnState,
                                         m_site.getCorrespondingSiteId(),
                                         finalTask,
                                         m_procedureName);

       // iterate over all sql in the batch, filling out the above data structures
       for (int i = 0; i < batch.size(); ++i) {
           QueuedSQL queuedSQL = batch.get(i);

           assert(queuedSQL.stmt != null);

           // Figure out what is needed to resume the proc
           int collectorOutputDepId = m_txnState.getNextDependencyId();
           state.m_depsToResume[i] = collectorOutputDepId;

           // Build the set of params for the frags
           ByteBuffer paramBuf = null;
           try {
               if (queuedSQL.serialization != null) {
                   paramBuf = ByteBuffer.allocate(queuedSQL.serialization.capacity());
                   paramBuf.put(queuedSQL.serialization);
               }
               else {
                   paramBuf = ByteBuffer.allocate(queuedSQL.params.getSerializedSize());
                   queuedSQL.params.flattenToBuffer(paramBuf);
               }
           } catch (IOException e) {
               throw new RuntimeException("Error serializing parameters for SQL statement: " +
                                          queuedSQL.stmt.getText() + " with params: " +
                                          queuedSQL.params.toJSONString(), e);
           }
           assert(paramBuf != null);
           paramBuf.flip();

           state.addStatement(i, queuedSQL.stmt, paramBuf, m_site);
       }

       // instruct the dtxn what's needed to resume the proc
       m_txnState.setupProcedureResume(finalTask, state.m_depsToResume);

       // create all the local work for the transaction
       for (int i = 0; i < state.m_depsForLocalTask.length; i++) {
           if (state.m_depsForLocalTask[i] < 0) {
            continue;
        }
           state.m_localTask.addInputDepId(i, state.m_depsForLocalTask[i]);
       }

       // note: non-transactional work only helps us if it's final work
       m_txnState.createLocalFragmentWork(state.m_localTask,
                                          state.m_localFragsAreNonTransactional && finalTask);
        
       if(!state.m_distributedTask.isEmpty()){
    	   state.m_distributedTask.setBatch(m_batchIndex);
    	   m_txnState.createAllParticipatingFragmentWork
    	   				(state.m_distributedTask, state.m_isReplicatedRead);
       }
       // recursively call recursableRun and don't allow it to shutdown
       Map<Integer,List<VoltTable>> mapResults =
           m_site.recursableRun(m_txnState);

       assert(mapResults != null);
       assert(state.m_depsToResume != null);
       assert(state.m_depsToResume.length == batch.size());
       // build an array of answers, assuming one result per expected id
       for (int i = 0; i < batch.size(); i++) {
           List<VoltTable> matchingTablesForId = mapResults.get(state.m_depsToResume[i]);
           assert(matchingTablesForId != null);
           assert(matchingTablesForId.size() == 1);
           state.m_results[i] = matchingTablesForId.get(0);
       }

       return state.m_results;
   }

   // Batch up pre-planned fragments, but handle ad hoc independently.
   private VoltTable[] fastPath(List<QueuedSQL> batch) {
       final int batchSize = batch.size();
       Object[] params = new Object[batchSize];
       long[] fragmentIds = new long[batchSize];

       int i = 0;
       for (final QueuedSQL qs : batch) {
           assert(qs.stmt.collector == null);
           fragmentIds[i] = qs.stmt.aggregator.id;
           // use the pre-serialized params if it exists
           if (qs.serialization != null) {
               params[i] = qs.serialization;
           }
           else {
               params[i] = qs.params;
           }
           i++;
       }
       return m_site.executePlanFragments(
           batchSize,
           fragmentIds,
           null,
           params,
           m_txnState.txnId,
           m_txnState.m_spHandle,
           m_txnState.uniqueId,
           m_isReadOnly);
    }
}
