import {
  ConnectResponse,
  Device,
  HealthResponse,
  Job,
  JobDetail,
  UploadOptions,
} from './types';

class ApiClient {
  private customBaseUrl: string | null = null;

  public getBaseUrl(): string {
    if (this.customBaseUrl) return this.customBaseUrl;
    const envUrl = (import.meta.env.VITE_API_BASE_URL as string | undefined)?.trim();
    if (envUrl) return envUrl.replace(/\/+$/, '');
    const stored = sessionStorage.getItem('pp_vps_url');
    if (stored) return stored.replace(/\/+$/, '');
    if (typeof window !== 'undefined' && window.location.hostname.endsWith('vercel.app')) {
      return window.location.origin;
    }
    return window.location.origin;
  }

  public setBaseUrl(url: string): void {
    const clean = url.trim().replace(/\/+$/, '');
    this.customBaseUrl = clean;
    sessionStorage.setItem('pp_vps_url', clean);
  }

  public getToken(): string {
    return sessionStorage.getItem('pp_client_token') || '';
  }

  public setToken(token: string): void {
    sessionStorage.setItem('pp_client_token', token);
  }

  public clearToken(): void {
    sessionStorage.removeItem('pp_client_token');
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const base = this.getBaseUrl();
    const url = `${base}${path.startsWith('/') ? path : `/${path}`}`;
    const headers = new Headers(init.headers || {});

    if (!headers.has('Accept')) {
      headers.set('Accept', 'application/json');
    }

    const token = this.getToken();
    if (token && !headers.has('Authorization')) {
      headers.set('Authorization', `Bearer ${token}`);
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 30000);

    try {
      const response = await fetch(url, {
        ...init,
        headers,
        signal: init.signal || controller.signal,
      });

      if (response.status === 401) {
        if (!path.includes('/api/connect')) {
          this.clearToken();
          window.dispatchEvent(new CustomEvent('pp_auth_expired'));
        }
      }

      const contentType = response.headers.get('content-type') || '';
      let data: any = null;
      if (contentType.includes('application/json')) {
        data = await response.json().catch(() => null);
      } else {
        data = await response.text().catch(() => null);
      }

      if (!response.ok) {
        const detail =
          (data && typeof data === 'object' && (data.detail || data.message)) ||
          `Request failed with status ${response.status}`;
        const error = new Error(detail);
        (error as any).status = response.status;
        (error as any).data = data;
        throw error;
      }

      return data as T;
    } finally {
      clearTimeout(timeout);
    }
  }

  // --- Health & Diagnostic Endpoints ---
  public async checkHealth(): Promise<HealthResponse> {
    return this.request<HealthResponse>('/health');
  }

  public async checkReadiness(): Promise<{ status: string }> {
    return this.request<{ status: string }>('/ready');
  }

  public async measureLatency(): Promise<number> {
    const start = performance.now();
    await this.request<{ status: string }>('/health');
    return Math.round(performance.now() - start);
  }

  // --- Authentication / Client Connection ---
  public async connect(password: string, deviceId?: string): Promise<ConnectResponse> {
    const body: Record<string, string> = { password };
    if (deviceId) body.deviceId = deviceId;

    const data = await this.request<ConnectResponse>('/api/connect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });

    if (data.clientToken) {
      this.setToken(data.clientToken);
    }
    return data;
  }

  public async disconnect(): Promise<void> {
    try {
      if (this.getToken()) {
        await this.request('/api/disconnect', { method: 'POST' });
      }
    } catch {
      // Ignored on logout
    } finally {
      this.clearToken();
    }
  }

  public async getDeviceStatus(): Promise<Device> {
    return this.request<Device>('/api/connect/device');
  }

  public async getWsTicket(): Promise<{ ticket: string; deviceId: string; expiresIn: number }> {
    return this.request<{ ticket: string; deviceId: string; expiresIn: number }>(
      '/api/connect/ws-ticket',
      { method: 'POST' }
    );
  }

  // --- Print Jobs ---
  public async listJobs(limit = 50, offset = 0, statusFilter?: string): Promise<{ items: Job[] }> {
    let path = `/api/jobs?limit=${limit}&offset=${offset}`;
    if (statusFilter && statusFilter !== 'ALL') {
      path += `&status_filter=${encodeURIComponent(statusFilter)}`;
    }
    return this.request<{ items: Job[] }>(path);
  }

  public async getJobDetail(jobId: string): Promise<JobDetail> {
    return this.request<JobDetail>(`/api/jobs/${encodeURIComponent(jobId)}`);
  }

  public async createPrintJob(
    file: File,
    options: UploadOptions,
    onProgress?: (percent: number) => void
  ): Promise<{ jobId: string; status: string; filename: string }> {
    const base = this.getBaseUrl();
    const url = `${base}/api/jobs`;

    const formData = new FormData();
    formData.append('file', file);
    formData.append('colorMode', options.colorMode);
    formData.append('paperSize', options.paperSize);
    formData.append('orientation', options.orientation);
    formData.append('copies', String(options.copies));
    formData.append('scaling', options.scaling);
    if (options.pageRange) {
      formData.append('pageRange', options.pageRange);
    }
    if (options.dpi) {
      formData.append('dpi', String(options.dpi));
    }
    if (options.quality) {
      formData.append('quality', options.quality);
    }
    if (options.margins) {
      formData.append(
        'margins',
        typeof options.margins === 'string' ? options.margins : JSON.stringify(options.margins)
      );
    }
    if (options.collate !== undefined) {
      formData.append('collate', String(options.collate));
    }
    if (options.pagesPerSheet) {
      formData.append('pagesPerSheet', String(options.pagesPerSheet));
    }
    if (options.printOptions) {
      formData.append(
        'printOptions',
        typeof options.printOptions === 'string'
          ? options.printOptions
          : JSON.stringify(options.printOptions)
      );
    }

    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', url);

      const token = this.getToken();
      if (token) {
        xhr.setRequestHeader('Authorization', `Bearer ${token}`);
      }
      xhr.setRequestHeader('Accept', 'application/json');

      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && onProgress) {
          const percent = Math.round((event.loaded / event.total) * 100);
          onProgress(percent);
        }
      };

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            const parsed = JSON.parse(xhr.responseText);
            resolve(parsed);
          } catch {
            resolve({ jobId: '', status: 'QUEUED', filename: file.name });
          }
        } else {
          let errorDetail = `Upload failed: HTTP ${xhr.status}`;
          try {
            const errObj = JSON.parse(xhr.responseText);
            if (errObj && errObj.detail) errorDetail = errObj.detail;
          } catch {
            // Ignored
          }
          reject(new Error(errorDetail));
        }
      };

      xhr.onerror = () => reject(new Error('Network connection failed during document upload'));
      xhr.ontimeout = () => reject(new Error('Upload request timed out'));
      xhr.timeout = 120000; // 2 minutes

      xhr.send(formData);
    });
  }

  public async cancelJob(jobId: string): Promise<void> {
    await this.request(`/api/jobs/${encodeURIComponent(jobId)}/cancel`, { method: 'POST' });
  }

  public async retryJob(jobId: string): Promise<void> {
    await this.request(`/api/jobs/${encodeURIComponent(jobId)}/retry`, { method: 'POST' });
  }

  public async reconcileJob(
    jobId: string,
    outcome: 'printed' | 'not_printed' | 'cancel',
    note?: string
  ): Promise<void> {
    await this.request(`/api/jobs/${encodeURIComponent(jobId)}/reconcile`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ outcome, note: note || 'Reconciled via PocketPrint dashboard' }),
    });
  }
}

export const api = new ApiClient();
