# Phase 5 — USB Streaming Reliability (Complete)

## Status

Implemented and unit-verified in the Android application module. No physical
printer validation was possible in this session; hardware A/B confirmation
remains pending.

## Root cause

The previous transport attempted a large fixed chunk on the first `bulkTransfer`
and treated every non-positive result as an unrecoverable exception:

- `chunkSize = 8192`
- `timeoutMs = 5000`
- any `rc < 0` → immediate failure
- `rc == 0` → broken continuation handling
- partial writes did not always update the confirmed offset safely

The observed `bulkTransfer() failed (rc=-1) at offset 24576` occurred at an old
8 KiB chunk boundary region. With the small receive FIFO of the HP Ink Tank 310,
the printer can stop accepting within the 5 s timeout window, producing an
ambiguous timeout.

Android USB bulk transfers do not guarantee whether a timed-out or erroring
request committed data to the device. Therefore:

- `rc > 0` is safe and becomes the new confirmed offset.
- `rc == 0` is safe to retry (no bytes were committed).
- `rc < 0` is treated as byte-commit uncertain and is not automatically replayed.

## Implemented design

### `UsbStreamEngine`

- Single in-flight bulk writer enforced by the state machine.
- Confirmed offset advances only by the returned positive byte count.
- Partial transfers are accounted for.
- Bounded retry policy with exponential backoff.
- Cancellation supported between chunks and during backoff.
- Snapshot output includes offset, confirmed bytes, total bytes, remaining,
  percent, chunk index/size, retry count, last result, chunk time, elapsed time,
  and speed.

### `StreamConfig`

- `VERIFIED_CHUNK_SIZE = 16384`, `RELIABLE_CHUNK_SIZE = 4096`.
- `verified()` preserves diagnostic profile: 16 KiB / 15,000 ms.
- `reliable()` uses production-safe 4 KiB / 15,000 ms.
- `diagnostic(chunkSize, timeoutMs)` preserves per-candidate values.

### `UsbPrintTransport`

- Uses `StreamConfig.reliable()` by default for production jobs.
- Returns typed results instead of throwing for expected stream failure.
- Finalizes only after a successful `StreamResult.Completed`.
- Does not issue ZLP or soft reset after failure.
- Reports `PROCESSING` only after successful delivery.
- Cancellation via `cancelActiveTransfer()`.

### Connection and job state

- `PrinterConnectionManager` is authoritative for physical/authorization state.
- Transfer errors may yield `TRANSFER_ERROR` or `RECOVERY_REQUIRED` without
  marking the physical device disconnected.
- `PrintJobState` now includes `FINALIZING`, `TRANSFER_ERROR`, and
  `DISCONNECTED`.
- Ambiguous failures map to `NEEDS_RECOVERY`.

### Crash recovery

- `PrintJobJournal` records in-flight jobs.
- `RemoteJobQueueStore.recoverInterruptedJobs()` marks interrupted jobs
  `NEEDS_RECOVERY`.
- Interrupted jobs are not automatically replayed.

## Verification

- `UsbStreamingEngineTest` now exercises the real pure engine with a fake bulk
  channel.
- Coverage includes: complete stream, partial writes, zero-byte returns, `rc=-1`
  ambiguity, explicit opt-in retry, bounded retries, disconnect handling, invalid
  return, cancellation, snapshots, and configuration validation.
- `PrintProfileRegressionTest` locks verified candidate chunks and compressor
  output behavior.
- Current unit suite: `BUILD SUCCESSFUL` for `testDebugUnitTest`.

## Remaining physical validation

- Re-run the HP Ink Tank 310 A/B matrix using the verified 16 KiB diagnostic
  profile and production 4 KiB streaming profile.
- Confirm that multi-page documents, color mode, and end-of-job ZLP behavior are
  stable.
- Validate that the UI no longer shows a spurious disconnected state during
  transient transfer errors.

## Acceptance

- No confirmed byte is retransmitted automatically.
- No job finalization is attempted after ambiguous failure.
- Progress UI exposes byte/percent/chunk/retry/connection state.
- Jobs paused mid-transfer require operator recovery rather than silent replay.
