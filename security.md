# PocketPrint Security Policy

## Supported versions

Security fixes are currently considered for the latest version on the
repository's default branch. No separate long-term support branches are
currently promised.

## Reporting a vulnerability

Please do not disclose exploitable vulnerabilities in public GitHub issues,
discussions, or pull requests.

Use GitHub's **Report a vulnerability** feature on this repository, if enabled,
to submit a private vulnerability report. Include:

- A clear description of the affected component and version or commit.
- The security impact and realistic attack scenario.
- Reproduction steps or a proof of concept that does not access another
  person's data or disrupt a service.
- Any suggested mitigation, if known.

Do not include real user documents, passwords, tokens, private keys, or other
personal data in a report. Redact sensitive values and use synthetic examples.

If private vulnerability reporting is unavailable, contact the repository
owner through a private GitHub channel. Do not publish exploit details while
waiting for a response.

## What to expect

The maintainer will make a reasonable effort to acknowledge a report, validate
the issue, assess its severity, and coordinate a fix and disclosure timeline.
Response and remediation times are not guaranteed.

Please allow the maintainer a reasonable opportunity to investigate and release
a fix before public disclosure. Coordinated disclosure is appreciated.

## Security-sensitive areas

Reports are especially welcome for:

- Authentication, device pairing, session and token handling.
- Authorization boundaries between users, devices, and print jobs.
- Document upload, storage, download, and retention.
- WebSocket tickets, device leases, and job state transitions.
- USB handling, parsing, and document rendering.
- Deployment defaults, CORS, trusted hosts, and secret management.

## Safe testing

Do not test against systems, devices, accounts, or documents that you do not
own or have explicit permission to assess. Avoid destructive testing, denial
of service, data exfiltration, and privacy violations.

## Secrets and personal data

Never commit environment files, credentials, signing keys, database files,
uploaded documents, production logs, or private printer diagnostics. If a
secret is exposed, revoke or rotate it immediately; deleting the file from the
latest commit does not remove it from Git history.
