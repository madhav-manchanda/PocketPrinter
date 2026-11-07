import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';
import { Device, HealthResponse, Job, JobDetail, ToastMessage, UploadOptions } from './types';

export function usePocketPrint() {
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(() => Boolean(api.getToken()));
  const [device, setDevice] = useState<Device | null>(null);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [latency, setLatency] = useState<number | null>(null);
  const [wsConnected, setWsConnected] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const [selectedJob, setSelectedJob] = useState<JobDetail | null>(null);
  const [theme, setTheme] = useState<'dark' | 'light'>(() => {
    return (localStorage.getItem('pp_theme') as 'dark' | 'light') || 'dark';
  });

  const wsRef = useRef<WebSocket | null>(null);
  const pollingTimerRef = useRef<number | null>(null);

  // Apply theme
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('pp_theme', theme);
  }, [theme]);

  const toggleTheme = useCallback(() => {
    setTheme((t) => (t === 'dark' ? 'light' : 'dark'));
  }, []);

  const addToast = useCallback(
    (message: string, type: ToastMessage['type'] = 'info', title?: string, duration = 5000) => {
      const id = `${Date.now()}-${Math.random().toString(36).substring(2, 7)}`;
      const newToast: ToastMessage = { id, type, title, message, duration };
      setToasts((prev) => [...prev, newToast]);
      if (duration > 0) {
        setTimeout(() => {
          setToasts((prev) => prev.filter((t) => t.id !== id));
        }, duration);
      }
    },
    []
  );

  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  // Fetch jobs
  const refreshJobs = useCallback(async () => {
    if (!api.getToken()) return;
    try {
      const res = await api.listJobs(100, 0);
      setJobs(res.items || []);
    } catch (err: any) {
      if (err.status !== 401) {
        // Silently tolerate transient listing errors during reconnect
      }
    }
  }, []);

  // Fetch device
  const refreshDevice = useCallback(async () => {
    if (!api.getToken()) return;
    try {
      const dev = await api.getDeviceStatus();
      setDevice(dev);
    } catch (err: any) {
      if (err.status === 401) {
        setIsAuthenticated(false);
      }
    }
  }, []);

  // Health and latency check
  const pingServer = useCallback(async () => {
    try {
      const h = await api.checkHealth();
      setHealth(h);
      const lat = await api.measureLatency();
      setLatency(lat);
    } catch {
      setHealth(null);
      setLatency(null);
    }
  }, []);

  // Full refresh
  const refreshAll = useCallback(async () => {
    setIsLoading(true);
    try {
      await Promise.all([refreshDevice(), refreshJobs(), pingServer()]);
    } finally {
      setIsLoading(false);
    }
  }, [refreshDevice, refreshJobs, pingServer]);

  // WebSocket lifecycle
  const setupWebSocket = useCallback(async () => {
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }

    if (!api.getToken()) return;

    try {
      const ticketRes = await api.getWsTicket();
      if (!ticketRes.ticket || !ticketRes.deviceId) return;

      const base = api.getBaseUrl();
      const wsProtocol = base.startsWith('https') ? 'wss' : 'ws';
      const cleanHost = base.replace(/^https?:\/\//, '').replace(/\/+$/, '');
      const wsUrl = `${wsProtocol}://${cleanHost}/ws/device/${ticketRes.deviceId}?ticket=${ticketRes.ticket}`;

      const socket = new WebSocket(wsUrl);

      socket.onopen = () => {
        setWsConnected(true);
      };

      socket.onmessage = (event) => {
        if (event.data === 'PONG') return;
        try {
          const parsed = JSON.parse(event.data);
          // Backend notifies: 'new_job', 'job_status', 'job_progress', etc.
          if (parsed && typeof parsed === 'object') {
            refreshJobs();
            refreshDevice();
          }
        } catch {
          // Plain notification text, trigger refresh
          refreshJobs();
          refreshDevice();
        }
      };

      socket.onclose = () => {
        setWsConnected(false);
        wsRef.current = null;
      };

      socket.onerror = () => {
        setWsConnected(false);
      };

      wsRef.current = socket;
    } catch {
      setWsConnected(false);
    }
  }, [refreshJobs, refreshDevice]);

  // Auth state management
  const connectWithPassword = useCallback(
    async (password: string, customVpsUrl?: string) => {
      setIsLoading(true);
      if (customVpsUrl) {
        api.setBaseUrl(customVpsUrl);
      }
      try {
        const res = await api.connect(password);
        setIsAuthenticated(true);
        setDevice(res.device);
        addToast(`Connected to printer node: ${res.device.name}`, 'success');
        await refreshAll();
        setupWebSocket();
        return res;
      } catch (err: any) {
        const msg = err.message || 'Connection failed';
        addToast(msg, 'error', 'Connection Error');
        throw err;
      } finally {
        setIsLoading(false);
      }
    },
    [addToast, refreshAll, setupWebSocket]
  );

  const disconnectSession = useCallback(async () => {
    setIsLoading(true);
    try {
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
      await api.disconnect();
      setIsAuthenticated(false);
      setDevice(null);
      setJobs([]);
      setSelectedJob(null);
      addToast('Disconnected from printer node', 'info');
    } finally {
      setIsLoading(false);
    }
  }, [addToast]);

  // Document upload handler
  const uploadAndPrint = useCallback(
    async (file: File, options: UploadOptions, onProgress?: (p: number) => void) => {
      try {
        const result = await api.createPrintJob(file, options, onProgress);
        addToast(`Document queued: ${file.name} (Job #${result.jobId.slice(0, 8)})`, 'success');
        await refreshJobs();
        await refreshDevice();
        return result;
      } catch (err: any) {
        const msg = err.message || 'Upload failed';
        addToast(msg, 'error', 'Upload Rejected');
        throw err;
      }
    },
    [addToast, refreshJobs, refreshDevice]
  );

  // Job Actions
  const cancelJob = useCallback(
    async (jobId: string) => {
      try {
        await api.cancelJob(jobId);
        addToast(`Cancelled job #${jobId.slice(0, 8)}`, 'info');
        await refreshJobs();
      } catch (err: any) {
        addToast(err.message || 'Could not cancel job', 'error');
      }
    },
    [addToast, refreshJobs]
  );

  const retryJob = useCallback(
    async (jobId: string) => {
      try {
        await api.retryJob(jobId);
        addToast(`Job #${jobId.slice(0, 8)} requeued for printing`, 'success');
        await refreshJobs();
      } catch (err: any) {
        addToast(err.message || 'Could not retry job', 'error');
      }
    },
    [addToast, refreshJobs]
  );

  const reconcileJob = useCallback(
    async (jobId: string, outcome: 'printed' | 'not_printed' | 'cancel') => {
      try {
        await api.reconcileJob(jobId, outcome);
        addToast(`Job reconciled as ${outcome.replace('_', ' ')}`, 'info');
        await refreshJobs();
      } catch (err: any) {
        addToast(err.message || 'Could not reconcile job', 'error');
      }
    },
    [addToast, refreshJobs]
  );

  const inspectJob = useCallback(
    async (jobId: string) => {
      try {
        const detail = await api.getJobDetail(jobId);
        setSelectedJob(detail);
      } catch (err: any) {
        addToast(err.message || 'Failed to load job details', 'error');
      }
    },
    [addToast]
  );

  // Setup loop
  useEffect(() => {
    const handleAuthExpired = () => {
      setIsAuthenticated(false);
      setDevice(null);
      addToast('Session expired. Please reconnect with your printer password.', 'warning');
    };

    window.addEventListener('pp_auth_expired', handleAuthExpired);
    return () => window.removeEventListener('pp_auth_expired', handleAuthExpired);
  }, [addToast]);

  useEffect(() => {
    if (isAuthenticated) {
      refreshAll();
      setupWebSocket();

      // Adaptive polling fallback
      pollingTimerRef.current = window.setInterval(() => {
        if (!document.hidden) {
          refreshDevice();
          refreshJobs();
          pingServer();
        }
      }, 5000);
    } else {
      pingServer();
    }

    return () => {
      if (pollingTimerRef.current) {
        clearInterval(pollingTimerRef.current);
        pollingTimerRef.current = null;
      }
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, [isAuthenticated, refreshAll, setupWebSocket, refreshDevice, refreshJobs, pingServer]);

  return {
    isAuthenticated,
    device,
    jobs,
    health,
    latency,
    wsConnected,
    isLoading,
    toasts,
    selectedJob,
    theme,
    toggleTheme,
    setSelectedJob,
    connectWithPassword,
    disconnectSession,
    uploadAndPrint,
    cancelJob,
    retryJob,
    reconcileJob,
    inspectJob,
    refreshAll,
    addToast,
    removeToast,
  };
}
