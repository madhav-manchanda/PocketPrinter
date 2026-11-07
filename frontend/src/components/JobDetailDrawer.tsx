import React from 'react';
import { JobDetail } from '../types';

interface JobDetailDrawerProps {
  job: JobDetail | null;
  onClose: () => void;
  onRetry: (id: string) => void;
  onCancel: (id: string) => void;
  onReconcile: (id: string, outcome: 'printed' | 'not_printed' | 'cancel') => void;
}

export const JobDetailDrawer: React.FC<JobDetailDrawerProps> = ({
  job,
  onClose,
  onRetry,
  onCancel,
  onReconcile,
}) => {
  if (!job) return null;

  const interpretFailure = (reason?: string | null) => {
    if (!reason) return null;
    const r = reason.toLowerCase();
    if (r.includes('usb') && r.includes('permission')) {
      return 'Android USB permission was revoked or denied by the system dialog.';
    }
    if (r.includes('usb') && (r.includes('detached') || r.includes('connection'))) {
      return 'Printer hardware disconnected during active USB transmission.';
    }
    if (r.includes('pcl') || r.includes('raster') || r.includes('encoding')) {
      return 'Document rasterization error. Page conversion failed to generate printable raster stream.';
    }
    if (r.includes('sha256') || r.includes('checksum')) {
      return 'Document download checksum mismatch between VPS and Android device.';
    }
    if (r.includes('lease') || r.includes('expired')) {
      return 'Worker lease expired before job transmission completed.';
    }
    return reason;
  };

  const copySha256 = () => {
    if (job.sha256) {
      navigator.clipboard.writeText(job.sha256);
    }
  };

  const isRetryable = ['FAILED', 'CANCELLED'].includes(job.status);
  const isInFlight = [
    'QUEUED',
    'ASSIGNED',
    'DOWNLOADING',
    'READY',
    'PRINTING',
    'TRANSMISSION_COMPLETED',
  ].includes(job.status);
  const isUncertain = ['UNCERTAIN', 'NEEDS_RECONCILIATION'].includes(job.status);

  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <div className="drawer-panel" onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className="drawer-header">
          <div>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', fontFamily: 'var(--font-mono)' }}>
              DIAGNOSTIC TELEMETRY · JOB #{job.id.slice(0, 8)}
            </div>
            <h3 style={{ fontSize: '16px', fontWeight: 700, marginTop: '2px' }}>
              {job.filename}
            </h3>
          </div>
          <button type="button" className="btn-icon" onClick={onClose} title="Close drawer">
            ✕
          </button>
        </div>

        {/* Body */}
        <div className="drawer-body">
          {/* Status & Failure Banner */}
          <div
            className={`card ${
              job.status === 'FAILED'
                ? 'toast-error'
                : job.status === 'COMPLETED'
                ? 'toast-success'
                : 'toast-info'
            }`}
            style={{ padding: '14px 16px', borderRadius: 'var(--radius-md)' }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <span style={{ fontWeight: 600 }}>State: {job.status.replace('_', ' ')}</span>
              <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px' }}>
                Progress: {job.progressPercent}%
              </span>
            </div>
            {job.failureReason && (
              <div style={{ marginTop: '8px', fontSize: '12px', color: 'var(--danger-text)' }}>
                <b>Root Cause:</b> {interpretFailure(job.failureReason)}
                <div className="code-block" style={{ marginTop: '6px' }}>
                  Raw Error: {job.failureReason}
                </div>
              </div>
            )}
          </div>

          {/* Quick Actions */}
          <div style={{ display: 'flex', gap: '8px' }}>
            {isRetryable && (
              <button
                type="button"
                className="btn btn-primary"
                style={{ flex: 1 }}
                onClick={() => {
                  onRetry(job.id);
                  onClose();
                }}
              >
                Retry Print Job
              </button>
            )}

            {isInFlight && (
              <button
                type="button"
                className="btn btn-danger"
                style={{ flex: 1 }}
                onClick={() => {
                  onCancel(job.id);
                  onClose();
                }}
              >
                Cancel Transmission
              </button>
            )}

            {isUncertain && (
              <>
                <button
                  type="button"
                  className="btn btn-primary"
                  style={{ flex: 1 }}
                  onClick={() => {
                    onReconcile(job.id, 'printed');
                    onClose();
                  }}
                >
                  Confirm Printed
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  style={{ flex: 1 }}
                  onClick={() => {
                    onReconcile(job.id, 'not_printed');
                    onClose();
                  }}
                >
                  Requeue Job
                </button>
              </>
            )}
          </div>

          {/* Document Properties */}
          <div>
            <div className="card-title" style={{ marginBottom: '8px' }}>
              Document Properties
            </div>
            <div className="code-block" style={{ display: 'grid', gridTemplateColumns: '120px 1fr', gap: '6px' }}>
              <span style={{ color: 'var(--text-dim)' }}>Document ID:</span>
              <span>{job.documentId}</span>

              <span style={{ color: 'var(--text-dim)' }}>File Size:</span>
              <span>{job.fileSize ? `${job.fileSize.toLocaleString()} bytes` : 'N/A'}</span>

              <span style={{ color: 'var(--text-dim)' }}>MIME Type:</span>
              <span>{job.mimeType || 'application/octet-stream'}</span>

              <span style={{ color: 'var(--text-dim)' }}>Page Count:</span>
              <span>{job.pageCount || 1} pages</span>

              <span style={{ color: 'var(--text-dim)' }}>Color & Size:</span>
              <span>{job.colorMode} · {job.paperSize} ({job.orientation})</span>

              <span style={{ color: 'var(--text-dim)' }}>SHA256 Hash:</span>
              <span
                style={{ cursor: 'pointer', textDecoration: 'underline' }}
                onClick={copySha256}
                title="Click to copy hash"
              >
                {job.sha256 || 'N/A'} (copy)
              </span>
            </div>
          </div>

          {/* Timing Lifecycle */}
          <div>
            <div className="card-title" style={{ marginBottom: '8px' }}>
              Execution Lifecycle
            </div>
            <div className="code-block" style={{ display: 'grid', gridTemplateColumns: '140px 1fr', gap: '6px' }}>
              <span style={{ color: 'var(--text-dim)' }}>Created:</span>
              <span>{job.createdAt.replace('T', ' ').slice(0, 19)}</span>

              <span style={{ color: 'var(--text-dim)' }}>Leased to Agent:</span>
              <span>{job.assignedAt ? job.assignedAt.replace('T', ' ').slice(0, 19) : 'Waiting'}</span>

              <span style={{ color: 'var(--text-dim)' }}>Stream Completed:</span>
              <span>{job.transmissionCompletedAt ? job.transmissionCompletedAt.replace('T', ' ').slice(0, 19) : 'In-flight'}</span>

              <span style={{ color: 'var(--text-dim)' }}>Finalized:</span>
              <span>{job.completedAt ? job.completedAt.replace('T', ' ').slice(0, 19) : 'Pending'}</span>
            </div>
          </div>

          {/* Per-Page Progress breakdown */}
          {job.pages && job.pages.length > 0 && (
            <div>
              <div className="card-title" style={{ marginBottom: '8px' }}>
                Page Execution Matrix ({job.pages.length} recorded)
              </div>
              <div className="table-responsive" style={{ border: '1px solid var(--border-subtle)', borderRadius: 'var(--radius-md)' }}>
                <table>
                  <thead>
                    <tr>
                      <th>Page</th>
                      <th>Copy</th>
                      <th>State</th>
                      <th>Attempt</th>
                      <th>Updated</th>
                    </tr>
                  </thead>
                  <tbody>
                    {job.pages.map((p) => (
                      <tr key={p.id}>
                        <td>Doc #{p.documentPage}</td>
                        <td>Copy #{p.copy}</td>
                        <td>
                          <span
                            className={`badge ${
                              p.state === 'PRINTED'
                                ? 'badge-completed'
                                : p.state === 'FAILED'
                                ? 'badge-failed'
                                : 'badge-queued'
                            }`}
                          >
                            {p.state}
                          </span>
                        </td>
                        <td>{p.attempt}</td>
                        <td style={{ fontSize: '11px', fontFamily: 'var(--font-mono)' }}>
                          {p.updatedAt.slice(11, 19)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* Event Stream Timeline */}
          <div>
            <div className="card-title" style={{ marginBottom: '12px' }}>
              Event Trace Stream ({job.events?.length || 0} events)
            </div>
            <div className="diag-timeline">
              {job.events && job.events.length > 0 ? (
                job.events.map((ev) => (
                  <div key={ev.id} className="diag-event">
                    <div className="diag-event-title">{ev.type.replace(/_/g, ' ')}</div>
                    <div className="diag-event-time">
                      {ev.timestamp.replace('T', ' ').slice(0, 19)} · {ev.status}
                    </div>
                    <div className="diag-event-msg">{ev.message}</div>
                  </div>
                ))
              ) : (
                <div style={{ color: 'var(--text-dim)', fontSize: '12px' }}>
                  No historical event frames logged for this job.
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
