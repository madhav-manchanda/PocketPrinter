import React, { useMemo, useState } from 'react';
import { Job, JobStatus } from '../types';

interface QueueTableProps {
  jobs: Job[];
  onInspect: (jobId: string) => void;
  onCancel: (jobId: string) => void;
  onRetry: (jobId: string) => void;
  onReconcile: (jobId: string, outcome: 'printed' | 'not_printed' | 'cancel') => void;
}

export const QueueTable: React.FC<QueueTableProps> = ({
  jobs,
  onInspect,
  onCancel,
  onRetry,
  onReconcile,
}) => {
  const [filter, setFilter] = useState<'ALL' | 'ACTIVE' | 'COMPLETED' | 'FAILED'>('ALL');
  const [search, setSearch] = useState('');

  const filteredJobs = useMemo(() => {
    return jobs.filter((job) => {
      // Status filter
      if (filter === 'ACTIVE') {
        const inFlight: JobStatus[] = [
          'QUEUED',
          'UPLOADED',
          'ASSIGNED',
          'DOWNLOADING',
          'READY',
          'PRINTING',
          'TRANSMISSION_COMPLETED',
          'CANCEL_REQUESTED',
        ];
        if (!inFlight.includes(job.status)) return false;
      } else if (filter === 'COMPLETED') {
        if (job.status !== 'COMPLETED') return false;
      } else if (filter === 'FAILED') {
        if (!['FAILED', 'CANCELLED', 'UNCERTAIN', 'NEEDS_RECONCILIATION'].includes(job.status)) {
          return false;
        }
      }

      // Search query
      if (search.trim()) {
        const q = search.toLowerCase();
        const matchName = job.filename.toLowerCase().includes(q);
        const matchId = job.id.toLowerCase().includes(q);
        return matchName || matchId;
      }

      return true;
    });
  }, [jobs, filter, search]);

  const getStatusBadge = (status: JobStatus) => {
    switch (status) {
      case 'COMPLETED':
        return <span className="badge badge-completed">COMPLETED</span>;
      case 'QUEUED':
      case 'UPLOADED':
        return <span className="badge badge-queued">QUEUED</span>;
      case 'ASSIGNED':
      case 'DOWNLOADING':
      case 'READY':
      case 'PRINTING':
      case 'TRANSMISSION_COMPLETED':
        return <span className="badge badge-in-flight">{status.replace('_', ' ')}</span>;
      case 'CANCEL_REQUESTED':
        return <span className="badge badge-warning">CANCELLING</span>;
      case 'UNCERTAIN':
      case 'NEEDS_RECONCILIATION':
        return <span className="badge badge-warning">UNCERTAIN</span>;
      case 'FAILED':
      case 'CANCELLED':
        return <span className="badge badge-failed">{status}</span>;
      default:
        return <span className="badge badge-queued">{status}</span>;
    }
  };

  const formatTimestamp = (isoStr: string) => {
    try {
      const d = new Date(isoStr);
      return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    } catch {
      return isoStr.slice(11, 19);
    }
  };

  return (
    <div className="card table-card">
      {/* Table Toolbar */}
      <div className="table-toolbar">
        <div className="filter-tabs">
          <button
            type="button"
            className={`filter-tab ${filter === 'ALL' ? 'active' : ''}`}
            onClick={() => setFilter('ALL')}
          >
            All ({jobs.length})
          </button>
          <button
            type="button"
            className={`filter-tab ${filter === 'ACTIVE' ? 'active' : ''}`}
            onClick={() => setFilter('ACTIVE')}
          >
            In-Flight (
            {
              jobs.filter((j) =>
                ['QUEUED', 'ASSIGNED', 'DOWNLOADING', 'READY', 'PRINTING', 'TRANSMISSION_COMPLETED'].includes(
                  j.status
                )
              ).length
            }
            )
          </button>
          <button
            type="button"
            className={`filter-tab ${filter === 'COMPLETED' ? 'active' : ''}`}
            onClick={() => setFilter('COMPLETED')}
          >
            Completed ({jobs.filter((j) => j.status === 'COMPLETED').length})
          </button>
          <button
            type="button"
            className={`filter-tab ${filter === 'FAILED' ? 'active' : ''}`}
            onClick={() => setFilter('FAILED')}
          >
            Failed ({jobs.filter((j) => ['FAILED', 'CANCELLED', 'UNCERTAIN'].includes(j.status)).length})
          </button>
        </div>

        <div className="search-input-wrap">
          <svg className="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <input
            type="text"
            placeholder="Search documents or IDs..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>

      {/* Table Content */}
      <div className="table-responsive">
        <table>
          <thead>
            <tr>
              <th>Document</th>
              <th>Status</th>
              <th>Progress</th>
              <th>Options</th>
              <th>Time</th>
              <th style={{ textAlign: 'right' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {filteredJobs.length === 0 ? (
              <tr>
                <td colSpan={6} style={{ textAlign: 'center', padding: '36px 16px', color: 'var(--text-muted)' }}>
                  No print jobs match the selected criteria.
                </td>
              </tr>
            ) : (
              filteredJobs.map((job) => {
                const isInFlight = [
                  'QUEUED',
                  'ASSIGNED',
                  'DOWNLOADING',
                  'READY',
                  'PRINTING',
                  'TRANSMISSION_COMPLETED',
                ].includes(job.status);
                const isRetryable = ['FAILED', 'CANCELLED'].includes(job.status);
                const isUncertain = ['UNCERTAIN', 'NEEDS_RECONCILIATION'].includes(job.status);

                return (
                  <tr key={job.id}>
                    <td>
                      <div style={{ fontWeight: 600 }}>{job.filename}</div>
                      <div style={{ fontSize: '11px', color: 'var(--text-dim)', fontFamily: 'var(--font-mono)' }}>
                        #{job.id.slice(0, 8)}
                        {job.pageCount ? ` · ${job.pageCount} pg` : ''}
                      </div>
                    </td>

                    <td>{getStatusBadge(job.status)}</td>

                    <td style={{ minWidth: '130px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <div className="progress-track" style={{ flex: 1 }}>
                          <div
                            className={`progress-fill ${
                              job.status === 'COMPLETED'
                                ? 'success'
                                : job.status === 'FAILED'
                                ? 'failed'
                                : ''
                            }`}
                            style={{ width: `${job.progressPercent}%` }}
                          />
                        </div>
                        <span style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', minWidth: '32px' }}>
                          {job.progressPercent}%
                        </span>
                      </div>
                    </td>

                    <td style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                      {job.colorMode === 'MONOCHROME_1BIT' ? 'Mono' : 'Color'} · {job.paperSize} · {job.copies}x
                    </td>

                    <td style={{ fontSize: '12px', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                      {formatTimestamp(job.createdAt)}
                    </td>

                    <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        style={{ marginRight: '6px' }}
                        onClick={() => onInspect(job.id)}
                        title="View diagnostic telemetry"
                      >
                        Telemetry
                      </button>

                      {isInFlight && (
                        <button
                          type="button"
                          className="btn btn-danger btn-sm"
                          onClick={() => onCancel(job.id)}
                          title="Cancel print job"
                        >
                          Cancel
                        </button>
                      )}

                      {isRetryable && (
                        <button
                          type="button"
                          className="btn btn-primary btn-sm"
                          onClick={() => onRetry(job.id)}
                          title="Retry print job"
                        >
                          Retry
                        </button>
                      )}

                      {isUncertain && (
                        <div style={{ display: 'inline-flex', gap: '4px' }}>
                          <button
                            type="button"
                            className="btn btn-primary btn-sm"
                            onClick={() => onReconcile(job.id, 'printed')}
                            title="Confirm physical print finished"
                          >
                            Confirmed
                          </button>
                          <button
                            type="button"
                            className="btn btn-secondary btn-sm"
                            onClick={() => onReconcile(job.id, 'not_printed')}
                            title="Requeue print"
                          >
                            Requeue
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};
