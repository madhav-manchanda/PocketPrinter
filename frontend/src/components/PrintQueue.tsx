import React, { useEffect, useState } from 'react';
import { api } from '../api';
import { Job } from '../types';

interface PrintQueueProps {
  onRefresh: () => void;
}

export const PrintQueue: React.FC<PrintQueueProps> = ({ onRefresh }) => {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [filter, setFilter] = useState<'all' | 'active' | 'queued' | 'failed'>('all');

  useEffect(() => {
    const loadJobs = async () => {
      try {
        const res = await api.listJobs(50, 0, filter);
        setJobs(res.items || []);
      } catch (err) {
        console.error('Failed to load jobs:', err);
      }
    };
    loadJobs();
    const interval = setInterval(loadJobs, 10000);
    return () => clearInterval(interval);
  }, [filter]);

  const statusFilterOptions = [
    { label: 'All', value: 'all' },
    { label: 'Active', value: 'active' },
    { label: 'Queued', value: 'queued' },
    { label: 'Failed', value: 'failed' },
  ];

  const statusFilterOptionsMap: Record<string, string> = {
    all: 'ALL',
    active: 'PRINTING',
    queued: 'QUEUED',
    failed: 'FAILED',
  };

  const handleFilterChange = (value: string) => {
    setFilter(value);
    onRefresh();
  };

  return (
    <div className="space-y-4">
      {/* Filter Bar */}
      <div className="bg-surface-container-low rounded-lg p-4 flex flex-col sm:flex-row gap-2">
        <span className="font-label-xs text-label-xs text-on-surface-variant uppercase tracking-wider">
          View
        </span>
        <div className="flex gap-1 sm:w-48">
          {filterOptions.map((opt) => (
            <button
              key={opt.value}
              className={`px-2 py-1 rounded bg-${
                filter === opt.value
                  ? 'surface-container-high'
                  : 'surface-container-low'
                } text-${
                  filter === opt.value
                    ? 'primary'
                    : 'on-surface-variant'
                } transition-colors ${filter === opt.value ? 'font-medium' : ''}`
                onClick={() => handleFilterChange(opt.value)}
            >
              <span className="font-label-xs text-label-xs">
                {opt.label}
              </span>
              {filter === opt.value && (
                <span className="bg-primary/20 text-primary px-1 py-0.5 rounded-full font-mono-code text-[9px]">
                  {jobs.length}
                </span>
              )}
            </button>
          ))}
        </div>
      </div>

      {/* Job Table */}
      {jobs.length === 0 && (
        <div className="text-center py-8 text-on-surface-variant">
          No jobs found. <button className="underline text-primary" onClick={onRefresh}>
            Refresh
          </button>
        </div>
      )}

      <div className="overflow-x-auto">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-surface-container-low text-on-surface-variant text-xs uppercase tracking-wider">
              <th className="py-2 px-3">Job ID</th>
              <th className="py-2 px-3">Document</th>
              <th className="py-2 px-3">Size & Media</th>
              <th className="py-2 px-3">Target Device</th>
              <th className="py-2 px-3">Submission Source</th>
              <th className="py-2 px-3">Job State</th>
              <th className="py-2 px-3 min-w-[180px]">Pipeline Progress</th>
              <th className="py-2 px-3 text-right">Actions</th>
            </thead>
            <tbody>
              {jobs.map((job) => {
                const statusBadge =
                  job.status === 'PRINTING'
                    ? 'Printing'
                    : job.status === 'QUEUED'
                    ? 'Queued'
                    : job.status === 'FAILED'
                    ? 'Failed'
                    : job.status;

                const statusClass =
                  job.status === 'PRINTING'
                    ? 'bg-green-500/20 text-green-400'
                    : job.status === 'FAILED'
                    ? 'bg-error-container/20 text-error'
                    : job.status === 'QUEUED'
                    ? 'bg-surface-container-high/20 text-on-surface'
                    : 'bg-surface-container-high/20 text-on-surface';

                return (
                  <tr key={job.id} className="hover:bg-surface-container/40 transition-colors">
                    <td className="py-2 px-3">
                      <div className="flex items-center gap-2">
                        <span
                          className="w-1.5 h-1.5 rounded-full bg-secondary animate-ping"
                        />
                        <span className="font-mono-code font-bold text-primary">
                          {job.id}
                        </span>
                      </div>
                    </td>
                    <td className="py-2 px-3">
                      <div className="flex items-center gap-2">
                        <span
                          className="material-symbols-outlined text-[18px] text-on-surface-variant"
                        >
                          picture_as_pdf
                        </span>
                        <span className="font-body-md text-body-md text-on-surface font-medium truncate max-w-xs">
                          {job.filename || 'Unknown'}
                        </span>
                      </div>
                    </td>
                    <td className="py-2 px-3">
                      <div className="flex flex-col">
                        <span className="font-mono-code text-mono-code text-on-surface">
                          {job.fileSize ? `${job.fileSize / 1024} KB` : 'Unknown'}
                        </span>
                        <span className="font-label-xs text-label-xs text-on-surface-variant">
                          {job.documentId ? 'ESC/POS Raw' : 'PDF'}
                        </span>
                      </div>
                    </td>
                    <td className="py-2 px-3">
                      <div className="flex items-center gap-2">
                        <span
                          className="w-1.5 h-1.5 rounded-full bg-tertiary"
                        />
                        <span className="font-mono-code text-mono-code text-on-surface">
                          {device?.name || 'ZJ-5805DD'}
                        </span>
                      </div>
                    </td>
                    <td className="py-2 px-3">
                      <div className="flex flex-col text-xs">
                        <span className="text-on-surface">12s ago</span>
                        <span className="font-label-xs text-label-xs text-on-surface-variant">
                          via Web UI (Client REST)
                        </span>
                      </div>
                    </td>
                    <td className="py-2 px-3">
                      <div className="inline-flex items-center gap-2 px-2 py-0.5 rounded-full bg-secondary-container/30 text-secondary">
                        <span className="w-1.5 h-1.5 rounded-full bg-secondary animate-pulse" />
                        <span className="font-label-xs font-medium tracking-wide uppercase">
                          {statusBadge || 'Queued'}
                        </span>
                      </div>
                    </td>
                    <td className="py-2 px-3">
                      <div className="flex flex-col gap-1">
                        <div className="flex justify-between items-center text-label-xs font-label-xs">
                          <span className="text-secondary font-mono-code">
                            {(Math.random() * 100).toFixed(0)}% Transferred
                          </span>
                          <span className="text-on-surface-variant font-mono-code">13/20 Pkts</span>
                        </div>
                        <div>
                          <div
                            className="w-full bg-surface-container-highest rounded-full overflow-hidden"
                            style={{ width: `${Math.random() * 100}%` }}
                          ></div>
                        </div>
                        <span className="font-mono-code text-[10px] text-tertiary">
                          ACK latency {Math.floor(Math.random() * 50) + 10}ms
                        </span>
                      </div>
                    </td>
                    <td className="py-2 px-3 text-right">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          className="p-1 rounded hover:bg-surface-container-highest text-on-surface hover:text-on-surface transition-colors"
                          title="Pause Stream"
                        >
                          <span className="material-symbols-outlined text-[16px]">pause</span>
                        </button>
                        <button
                          className="p-1 rounded hover:bg-error-container/20 text-on-surface-variant hover:text-error transition-colors"
                          title="Cancel Transmission"
                        >
                          <span className="material-symbols-outlined text-[16px]">cancel</span>
                        </button>
                        <button
                          className="p-1 rounded bg-surface-container-high hover:bg-primary hover:text-on-primary text-secondary transition-colors"
                          title="Telemetry Inspector"
                          onclick="window.open('/diagnostics', '_blank')
                        >
                          <span className="material-symbols-outlined text-[16px]">analytics</span>
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );