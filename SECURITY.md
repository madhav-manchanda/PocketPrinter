# PocketPrint Security Policy

We take reports about security vulnerabilities seriously. This policy covers
the latest code on the `main` branch. No separate long-term support branches
are currently promised.

## Report a vulnerability privately

Please do not disclose exploitable vulnerabilities in public issues,
discussions, or pull requests.

Use GitHub's private vulnerability reporting for this repository, if enabled:
[Report a vulnerability](https://github.com/madhav-manchanda/PocketPrinter/security/advisories/new).

If private reporting is unavailable, contact the repository owner through a
private GitHub channel and ask for a secure way to share the report. Do not
include secrets or personal data in an initial public message.

Include, where possible:
- The affected component and commit or release.
- A clear description of the security impact and realistic attack scenario.
- Reproduction steps or a proof of concept using synthetic data and systems
  you own or are authorised to test.
- Suggested mitigations, if known.

## What to expect

The maintainer will make a reasonable effort to acknowledge and investigate
reports, assess severity, and coordinate a fix and disclosure timeline.
Response and remediation times are not guaranteed. Please allow reasonable
time to investigate and release a fix before public disclosure.

## Security-sensitive areas

Reports are especially welcome for:
- Authentication, device pairing, session and token handling.
- Authorization boundaries between users, devices, and print jobs.
- Document upload, storage, download, and retention.
- WebSocket tickets, device leases, and job state transitions.
- USB handling, parsing, and document rendering.
- Deployment defaults, CORS, trusted hosts, and secret management.

## Safe testing and data handling

Only test systems, devices, accounts, and documents that you own or have
explicit permission to assess. Avoid destructive testing, denial of service,
data exfiltration, and privacy violations.

Never include real user documents, passwords, tokens, private keys, production
logs, or other personal data in reports. Redact sensitive values and use
synthetic examples.

If a secret is exposed, revoke or rotate it immediately. Deleting a file from
the latest commit does not remove it from Git history.
