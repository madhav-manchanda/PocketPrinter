import React from 'react';
import type { Device, Job } from '../types';

interface StatusOverviewProps {
  device: Device | null;
  jobs: Job[];
}

const formatSeen = (value: string | null | undefined) => {
  if (!value) return 'No heartbeat received';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Heartbeat time unavailable';
  return date.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
};

const statusLabel = (device: Device | null) => {
  if (!device) return 'Not paired';
  if (device.isOnline) return 'Online';
  if (device.status === 'DISABLED') return 'Disabled';
  return 'Offline';
};

const jobTone = (status: string) => {
  if (status === 'COMPLETED') return 'complete';
  if (['FAILED', 'CANCELLED', 'UNCERTAIN', 'NEEDS_RECONCILIATION'].includes(status)) return 'attention';
  return 'running';
};

export const StatusOverview: React.FC<StatusOverviewProps> = ({ device, jobs }) => {
  const recentJobs = [...jobs]
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
    .slice(0, 4);

  return (
    <div className="pp-hardware-grid">
      <section className="pp-hardware-card">
        <div className="pp-hardware-card-head">
          <span className="pp-hardware-icon material-symbols-outlined" aria-hidden="true">print</span>
          <span className={`pp-state-pill ${device?.isOnline ? 'online' : 'offline'}`}>
            <i />{statusLabel(device)}
          </span>
        </div>
        <p className="pp-card-kicker">Printer</p>
        <h4>{device?.name || 'No printer paired'}</h4>
        <p className="pp-hardware-detail">{device?.printerModel || 'Pair the Android relay to identify your printer.'}</p>
        <dl className="pp-fact-list">
          <div><dt>USB identifier</dt><dd>{device?.vidPid || 'Not reported'}</dd></div>
          <div><dt>Last heartbeat</dt><dd>{formatSeen(device?.lastHeartbeat)}</dd></div>
        </dl>
      </section>

      <section className="pp-hardware-card pp-activity-card">
        <div className="pp-hardware-card-head">
          <span className="pp-hardware-icon material-symbols-outlined" aria-hidden="true">smartphone</span>
          <span className={`pp-state-pill ${device?.isOnline ? 'online' : 'offline'}`}>
            <i />{device?.isOnline ? 'Reporting' : 'No signal'}
          </span>
        </div>
        <p className="pp-card-kicker">Android relay</p>
        <h4>{device?.name || 'Relay not connected'}</h4>
        <p className="pp-hardware-detail">
          {device?.appVersion ? `Agent version ${device.appVersion}` : 'The phone forwards print jobs to the USB printer.'}
        </p>
        <dl className="pp-fact-list">
          <div><dt>Device ID</dt><dd>{device?.id ? `${device.id.slice(0, 8)}…` : 'Not assigned'}</dd></div>
          <div><dt>Current job</dt><dd>{device?.currentJobId ? `${device.currentJobId.slice(0, 8)}…` : 'No active job reported'}</dd></div>
        </dl>
      </section>

      <section className="pp-hardware-card pp-recent-card">
        <div className="pp-hardware-card-head">
          <span className="pp-hardware-icon material-symbols-outlined" aria-hidden="true">history</span>
          <span className="pp-card-count">{jobs.length} total</span>
        </div>
        <p className="pp-card-kicker">Recent activity</p>
        <h4>{recentJobs.length ? 'Latest print jobs' : 'No print history yet'}</h4>
        {recentJobs.length ? (
          <ul className="pp-recent-jobs">
            {recentJobs.map((job) => (
              <li key={job.id}>
                <span className={`pp-job-dot ${jobTone(job.status)}`} />
                <span className="pp-recent-job-name" title={job.filename}>{job.filename}</span>
                <span className={`pp-recent-job-status ${jobTone(job.status)}`}>{job.status.replace(/_/g, ' ')}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="pp-hardware-detail pp-empty-activity">Your submitted documents will appear here.</p>
        )}
      </section>
    </div>
  );
};
