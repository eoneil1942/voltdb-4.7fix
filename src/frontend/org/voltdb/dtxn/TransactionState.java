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

package org.voltdb.dtxn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.voltcore.messaging.Mailbox;
import org.voltcore.messaging.TransactionInfoBaseMessage;
import org.voltdb.ClientResponseImpl;
import org.voltdb.SiteProcedureConnection;
import org.voltdb.StoredProcedureInvocation;
import org.voltdb.VoltTable;
import org.voltdb.iv2.Site;
import org.voltdb.messaging.FragmentTaskMessage;

/**
 * Controls the state of a transaction. Encapsulates from the SimpleDTXNConnection
 * all the logic about what needs to happen next in a transaction. The DTXNConn just
 * pumps events into the TransactionState and it takes the appropriate actions,
 * ultimately it will return true from finished().
 *
 */
public abstract class TransactionState extends OrderableTransaction  {

    protected final boolean m_isReadOnly;
    protected final TransactionInfoBaseMessage m_notice;
    protected int m_nextDepId = 1;
    protected final Mailbox m_mbox;
    volatile protected boolean m_done = false;
    protected long m_beginUndoToken;
    volatile protected boolean m_needsRollback = false;
    protected ClientResponseImpl m_response = null;
    protected final boolean m_isForReplay;
    protected int m_hash = -1; // -1 shows where the value comes from (they only have to match)
    // IZZY: make me protected/private
    public final long m_spHandle;
    private ArrayList<UndoAction> m_undoLog;

    /**
     * Set up the final member variables from the parameters. This will
     * be called exclusively by subclasses.
     *
     * @param mbox The mailbox for the site.
     * @param notice The information about the new transaction.
     */
    protected TransactionState(Mailbox mbox,
                               TransactionInfoBaseMessage notice)
    {
        this(mbox, notice, notice.isReadOnly());
    }

    /**
     * This constructor is only reserved for BorrowTransactionState, which is read only now.
     * @param mbox
     * @param notice
     * @param readOnly
     */
    protected TransactionState(Mailbox mbox,
                               TransactionInfoBaseMessage notice,
                               boolean readOnly)
    {
        super(notice.getTxnId(), notice.getUniqueId(), notice.getInitiatorHSId());
        m_spHandle = notice.getSpHandle();
        m_mbox = mbox;
        m_notice = notice;
        m_isReadOnly = readOnly;
        m_beginUndoToken = Site.kInvalidUndoToken;
        m_isForReplay = notice.isForReplay();
    }


    final public TransactionInfoBaseMessage getNotice() {
        return m_notice;
    }

    // Assume that done-ness is a latch.
    public void setDone() {
        m_done = true;
    }

    final public boolean isDone() {
        return m_done;
    }

    public boolean isReadOnly()
    {
        return m_isReadOnly;
    }

    public boolean isForReplay() {
        return m_isForReplay;
    }

    public int getHash() {
        return m_hash;
    }

    /**
     * Indicate whether or not the transaction represented by this
     * TransactionState is single-partition.  Should be overridden to provide
     * sane results by subclasses.
     */
    public abstract boolean isSinglePartition();

    public void setHash(Integer hash) {
        m_hash = hash == null ? 0 : hash; // don't allow null
    }

    public void storeResults(ClientResponseImpl response) {
        m_response = response;
    }

    public ClientResponseImpl getResults() {
        return m_response;
    }

    public void setBeginUndoToken(long undoToken)
    {
        m_beginUndoToken = undoToken;
    }

    public long getBeginUndoToken()
    {
        return m_beginUndoToken;
    }

    // Assume that rollback-ness is a latch.
    public void setNeedsRollback()
    {
        m_needsRollback = true;
    }

    public boolean needsRollback()
    {
        return m_needsRollback;
    }

    public abstract StoredProcedureInvocation getInvocation();

    public void createFragmentWork(long[] partitions, FragmentTaskMessage task) {
        String msg = "The current transaction context of type " + this.getClass().getName();
        msg += " doesn't support creating fragment tasks.";
        throw new UnsupportedOperationException(msg);
    }

    public void createAllParticipatingFragmentWork(FragmentTaskMessage task, boolean[] isReplicatedRead) {
        String msg = "The current transaction context of type " + this.getClass().getName();
        msg += " doesn't support creating fragment tasks.";
        throw new UnsupportedOperationException(msg);
    }

    public void createLocalFragmentWork(FragmentTaskMessage task, boolean nonTransactional) {
        String msg = "The current transaction context of type " + this.getClass().getName();
        msg += " doesn't support accepting fragment tasks.";
        throw new UnsupportedOperationException(msg);
    }

    public void setupProcedureResume(boolean isFinal, int[] dependencies) {
        String msg = "The current transaction context of type " + this.getClass().getName();
        msg += " doesn't support receiving dependencies.";
        throw new UnsupportedOperationException(msg);
    }

    public int getNextDependencyId() {
        return m_nextDepId++;
    }

    /**
     * IV2 implementation: in iv2, recursable run is a function on the
     * transaction state; we block in the transaction state recording
     * until all dependencies / workunits are received.
     * IV2's SiteProcedureConnection.recursableRun(TransactionState) delegates
     * to this recursableRun method.
     *
     * The IV2 initiator mailbox knows how to offer() incoming fragment
     * responses to the waiting transaction state.
     * @return
     */
    public Map<Integer, List<VoltTable>> recursableRun(SiteProcedureConnection siteConnection)
    {
        return null;
    }

    public void registerUndoAction(UndoAction action) {
        if (m_undoLog == null) {
            m_undoLog = new ArrayList<UndoAction>();
        }
        m_undoLog.add(action);
    }

    public List<UndoAction> getUndoLog() {
        return m_undoLog;
    }
}
