export type JobStatus =
  | 'UPLOADED'
  | 'QUEUED'
  | 'ASSIGNED'
  | 'DOWNLOADING'
  | 'READY'
  | 'PRINTING'
  | 'TRANSMISSION_COMPLETED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'CANCEL_REQUESTED'
  | 'UNCERTAIN'
  | 'NEEDS_RECONCILIATION'
  | 'RETRY_PENDING';

export type ColorMode = 'MONOCHROME_1BIT' | 'GRAYSCALE_8BIT' | 'COLOR_RGB' | 'AUTO';
export type PaperSize = 'A4' | 'A5' | 'A3' | 'LETTER' | 'LEGAL' | 'CUSTOM';
export type Orientation = 'PORTRAIT' | 'LANDSCAPE';
export type Scaling = 'FIT_TO_PAGE' | 'FIT_TO_PRINTABLE' | 'ACTUAL_SIZE' | 'CUSTOM';
export type PrintQuality = 'DRAFT' | 'NORMAL' | 'HIGH' | 'BEST';
export type OptimizationTarget = 'text' | 'images' | 'mixed' | 'economy' | 'fine_detail';
export type MarginPreset = 'normal' | 'narrow' | 'moderate' | 'wide' | 'custom' | 'borderless';
export type PaperType = 'plain' | 'photo' | 'matte' | 'glossy';

export interface Margins {
  top: number;
  bottom: number;
  left: number;
  right: number;
  unit: 'mm' | 'in';
}

export interface AdvancedOptions {
  skipBlankPages?: boolean;
  backgroundGraphics?: boolean;
  imageInterpolation?: boolean;
  reverseOrder?: boolean;
  duplex?: 'NONE' | 'LONG_EDGE' | 'SHORT_EDGE';
  renderingMode?: 'vector' | 'raster_hybrid' | 'pure_raster';
  imageScaling?: 'fit' | 'fill' | 'stretch';
  centerHorizontal?: boolean;
  centerVertical?: boolean;
  colorIntensity?: number;
  blackOnly?: boolean;
}

export interface Device {
  id: string;
  name: string;
  printerModel: string | null;
  vidPid: string | null;
  status: 'ONLINE' | 'OFFLINE' | 'DISABLED';
  isOnline: boolean;
  lastHeartbeat: string | null;
  pendingJobsCount?: number;
  activeProfile?: string | null;
  appVersion?: string | null;
  isRevoked?: boolean;
  currentJobId?: string | null;
  createdAt?: string;
}

export interface Job {
  id: string;
  filename: string;
  documentId: string;
  deviceId: string | null;
  deviceName: string | null;
  fileSize: number | null;
  sha256: string | null;
  mimeType: string | null;
  pageCount: number | null;
  pageRange: string | null;
  colorMode: ColorMode | string;
  paperSize: PaperSize | string;
  orientation: Orientation | string;
  copies: number;
  scaling: Scaling | string;
  dpi?: number;
  quality?: string;
  margins?: string;
  collate?: boolean;
  pagesPerSheet?: number;
  printOptions?: string;
  status: JobStatus;
  progressPercent: number;
  scheduledItems?: number;
  completedItems?: number;
  attempt?: number;
  bytesTransferred?: number;
  physicalOutputConfirmed?: boolean | null;
  failureReason?: string | null;
  createdAt: string;
  assignedAt?: string | null;
  transmissionCompletedAt?: string | null;
  completedAt?: string | null;
}

export interface JobEvent {
  id: string;
  type: string;
  status: string;
  message: string;
  data: Record<string, unknown> | null;
  timestamp: string;
}

export interface JobPage {
  id: string;
  documentPage: number;
  scheduledPage: number;
  copy: number;
  state: string;
  attempt: number;
  updatedAt: string;
  error?: string | null;
}

export interface JobDetail extends Job {
  events: JobEvent[];
  pages: JobPage[];
}

export interface ConnectResponse {
  status: string;
  clientToken: string;
  expiresAt: string;
  device: Device;
}

export interface HealthResponse {
  status: string;
  service: string;
  version: string;
  apiVersion: number;
}

export interface UploadOptions {
  colorMode: ColorMode;
  paperSize: PaperSize | string;
  orientation: Orientation;
  copies: number;
  pageRange?: string;
  scaling: Scaling;
  dpi?: number;
  quality?: PrintQuality | string;
  margins?: Margins | string;
  collate?: boolean;
  pagesPerSheet?: number;
  printOptions?: AdvancedOptions | Record<string, unknown> | string;
}

export interface ToastMessage {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  title?: string;
  message: string;
  duration?: number;
}
